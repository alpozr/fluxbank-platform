package com.fluxbank.integration;

import com.fluxbank.account.application.AccountNumberGenerator;
import com.fluxbank.account.application.IbanGenerator;
import com.fluxbank.account.domain.*;
import com.fluxbank.account.repository.*;
import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.beneficiary.repository.BeneficiaryRepository;
import com.fluxbank.customer.domain.User;
import com.fluxbank.customer.repository.UserRepository;
import com.fluxbank.transaction.application.*;
import com.fluxbank.transaction.repository.TransactionRepository;
import com.fluxbank.transfer.application.*;
import com.fluxbank.transfer.domain.TransferType;
import com.fluxbank.transfer.repository.TransferRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("fluxbank-postgres-data")
abstract class DatabaseIntegrationTestSupport extends PostgresIntegrationTest {
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager manager;
    @Autowired protected EntityManager entityManager;
    @Autowired protected UserRepository users;
    @Autowired protected AccountRepository accounts;
    @Autowired protected AccountBalanceRepository balances;
    @Autowired protected BeneficiaryRepository beneficiaries;
    @Autowired protected TransferRepository transfers;
    @Autowired protected TransactionRepository transactions;
    @Autowired protected TransferLifecycleService lifecycle;
    @Autowired protected InternalTransferProcessor internal;
    @Autowired protected ExternalTransferReservationService reservation;
    @Autowired protected ExternalTransferFinalizationService finalization;
    @Autowired protected TransferService transferService;
    @Autowired protected TransactionHistoryService historyService;
    @Autowired protected TransferReferenceGenerator transferReferences;
    @Autowired protected TransactionReferenceGenerator transactionReferences;
    @Autowired protected IbanGenerator ibans;
    @Autowired protected AccountNumberGenerator accountNumbers;

    record Fixture(User owner, Account source, Account destination,
                   Beneficiary internalBeneficiary, Beneficiary externalBeneficiary) {}

    // JdbcTemplate runs this outside any test transaction, so cleanup is committed.
    // Subclass @AfterEach methods must finish worker threads before this superclass cleanup.
    @BeforeEach
    @AfterEach
    void cleanCommittedData() {
        jdbc.execute("""
                TRUNCATE TABLE fluxbank.transactions, fluxbank.transfers, fluxbank.account_balances,
                fluxbank.beneficiaries, fluxbank.accounts, fluxbank.users RESTART IDENTITY
                """);
    }

    protected <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(manager).execute(status -> work.get());
    }

    protected Fixture fixture(String sourceAmount, String destinationAmount) {
        return tx(() -> {
            User owner = users.save(User.createCustomer("Test", "Owner", UUID.randomUUID() + "@example.com",
                    "unused", users.getNextCustomerNumber()));
            Account source = createAccount(owner, 1, sourceAmount);
            Account destination = createAccount(owner, 2, destinationAmount);
            Beneficiary local = beneficiaries.save(Beneficiary.create(
                    owner, "Local", "Recipient", destination.getIban(), "FluxBank"));
            Beneficiary external = beneficiaries.save(Beneficiary.create(
                    owner, "External", "Recipient", "BG80BNBG96611020345678", "External Bank"));
            return new Fixture(owner, source, destination, local, external);
        });
    }

    private Account createAccount(User owner, int suffix, String amount) {
        Long identifier = accounts.getNextIbanAccountIdentifier();
        Account account = accounts.save(Account.create(owner,
                accountNumbers.generate(owner.getCustomerNumber(), suffix), ibans.generate(identifier),
                identifier, suffix, CurrencyCode.EUR));
        AccountBalance balance = AccountBalance.create(account);
        if (new BigDecimal(amount).signum() > 0) {
            balance.credit(new BigDecimal(amount));
        }
        balances.save(balance);
        return account;
    }

    protected UUID pending(Fixture f, String amount) {
        return lifecycle.createPending(f.owner(), f.source(), f.internalBeneficiary(), f.destination(),
                new BigDecimal(amount), TransferType.INTERNAL);
    }

    protected UUID externalPending(Fixture f, String amount) {
        return reservation.createPendingAndReserve(f.owner(), f.source().getPublicId(),
                f.externalBeneficiary(), new BigDecimal(amount)).publicId();
    }

    protected void reserveOtherFunds(Fixture f, String amount) {
        tx(() -> {
            balances.findByAccountPublicIdForUpdate(f.source().getPublicId()).orElseThrow()
                    .reserve(new BigDecimal(amount));
            return null;
        });
    }

    protected void process(Fixture f, UUID id) {
        internal.process(id, f.source().getPublicId(), f.destination().getPublicId());
    }

    protected void assertBalance(Account account, String current, String reserved, String available) {
        var row = jdbc.queryForMap("""
                SELECT b.current_balance, b.reserved_balance FROM fluxbank.account_balances b
                JOIN fluxbank.accounts a ON a.id=b.account_id WHERE a.public_id=?
                """, account.getPublicId());
        BigDecimal actualCurrent = (BigDecimal) row.get("current_balance");
        BigDecimal actualReserved = (BigDecimal) row.get("reserved_balance");
        assertEquals(0, new BigDecimal(current).compareTo(actualCurrent));
        assertEquals(0, new BigDecimal(reserved).compareTo(actualReserved));
        assertEquals(0, new BigDecimal(available).compareTo(actualCurrent.subtract(actualReserved)));
    }

    protected Map<String, Object> state(UUID transferId) {
        return jdbc.queryForMap("SELECT status, failure_reason, completed_at FROM fluxbank.transfers WHERE public_id=?",
                transferId);
    }

    protected List<Map<String, Object>> history(UUID transferId) {
        return jdbc.queryForList("""
                SELECT a.public_id AS account_public_id, h.direction, h.type, h.amount, h.currency,
                       h.reference_number, h.xmin::text::bigint AS transaction_id
                FROM fluxbank.transactions h JOIN fluxbank.accounts a ON a.id=h.account_id
                JOIN fluxbank.transfers t ON t.id=h.transfer_id WHERE t.public_id=? ORDER BY h.direction
                """, transferId);
    }

    protected void assertSqlState(String expected, Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && expected.equals(sql.getSQLState())) {
                return;
            }
        }
        fail("Expected PostgreSQL SQLSTATE " + expected, failure);
    }

    // Deliberately rewind only the isolated test database sequence to reproduce a reference collision.
    protected void rewindReference(String sequence, String nextReference) {
        long number = Long.parseLong(nextReference.substring(nextReference.lastIndexOf('-') + 1));
        jdbc.queryForObject("SELECT setval('fluxbank." + sequence + "', ?, true)", Long.class, number - 1);
    }
}
