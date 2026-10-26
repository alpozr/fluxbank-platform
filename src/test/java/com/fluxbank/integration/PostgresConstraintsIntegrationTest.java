package com.fluxbank.integration;

import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.transaction.domain.Transaction;
import com.fluxbank.transaction.domain.TransactionDirection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PostgresConstraintsIntegrationTest extends DatabaseIntegrationTestSupport {
    @Test
    void emailUniquenessIsCaseInsensitiveInPostgres() {
        Fixture first = fixture("1000", "500");
        Fixture second = fixture("1000", "500");

        var failure = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE fluxbank.users SET email=? WHERE public_id=?",
                first.owner().getEmail().toUpperCase(java.util.Locale.ROOT), second.owner().getPublicId()));

        assertSqlState("23505", failure);
        assertEquals(2L, users.count());
        assertEquals(second.owner().getEmail(), jdbc.queryForObject(
                "SELECT email FROM fluxbank.users WHERE public_id=?", String.class, second.owner().getPublicId()));
    }

    @Test
    void beneficiaryUniquenessIsOwnerAndIbanRatherThanGlobalIban() {
        Fixture first = fixture("1000", "500");
        Fixture second = fixture("1000", "500");
        String iban = first.internalBeneficiary().getIban();

        var failure = assertThrows(DataIntegrityViolationException.class, () -> tx(() ->
                beneficiaries.saveAndFlush(Beneficiary.create(first.owner(), "Duplicate", "Recipient", iban, "Bank"))));
        assertSqlState("23505", failure);
        tx(() -> beneficiaries.saveAndFlush(
                Beneficiary.create(second.owner(), "Other", "Recipient", iban, "Bank")));

        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM fluxbank.beneficiaries WHERE iban=?", Integer.class, iban));
    }

    @Test
    void historyBusinessKeyPreventsDuplicateMovementsAndServiceReplayIsIdempotent() {
        Fixture f = fixture("1000", "500");
        UUID id = pending(f, "200");
        process(f, id);
        historyService.createForTransfer(id); // Explicit history fixture; publication only persists the outbox.
        var originalReferences = history(id).stream().map(r -> r.get("reference_number")).toList();

        historyService.createForTransfer(id);

        assertEquals(originalReferences, history(id).stream().map(r -> r.get("reference_number")).toList());
        var failure = assertThrows(DataIntegrityViolationException.class, () -> tx(() ->
                transactions.saveAndFlush(Transaction.createTransferMovement(
                        transactionReferences.nextReference(), f.source(), transfers.findByPublicId(id).orElseThrow(),
                        TransactionDirection.DEBIT, new java.math.BigDecimal("200"), f.source().getCurrency()))));
        assertSqlState("23505", failure);
        assertEquals(2, history(id).size());
    }

    @Test
    void transferForeignKeyPreventsDeletingFinancialHistoryByDeletingTransfer() {
        Fixture f = fixture("1000", "500");
        UUID id = pending(f, "200");
        process(f, id);
        historyService.createForTransfer(id); // Explicit history fixture; publication only persists the outbox.

        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM fluxbank.transfers WHERE public_id=?", id));

        assertSqlState("23503", failure);
        assertEquals("COMPLETED", state(id).get("status"));
        assertEquals(2, history(id).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"current_balance=-1", "reserved_balance=-1", "reserved_balance=1001"})
    void balanceChecksRejectInvalidFinancialState(String assignment) {
        Fixture f = fixture("1000", "500");

        var failure = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE fluxbank.account_balances SET " + assignment
                        + " WHERE account_id=(SELECT id FROM fluxbank.accounts WHERE public_id=?)",
                f.source().getPublicId()));

        assertSqlState("23514", failure);
        assertBalance(f.source(), "1000", "0", "1000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"transfers", "transactions"})
    void financialAmountsMustBePositiveInDatabase(String table) {
        Fixture f = fixture("1000", "500");
        UUID id = pending(f, "200");
        process(f, id);
        historyService.createForTransfer(id); // Explicit history fixture; publication only persists the outbox.

        var failure = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE fluxbank." + table + " SET amount=0"));

        assertSqlState("23514", failure);
        assertEquals(2, history(id).size());
        assertEquals(0, new java.math.BigDecimal("200").compareTo(
                jdbc.queryForObject("SELECT amount FROM fluxbank.transfers WHERE public_id=?",
                        java.math.BigDecimal.class, id)));
    }

    @Test
    void failedStateRequiresFailureReasonInDatabase() {
        Fixture f = fixture("1000", "500");
        UUID id = pending(f, "200");

        var failure = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE fluxbank.transfers SET status='FAILED', failure_reason=NULL WHERE public_id=?", id));

        assertSqlState("23514", failure);
        assertEquals("PENDING", state(id).get("status"));
    }

    @Test
    void migratedSequencesBackRealNumberAndReferenceGenerators() {
        tx(() -> {
            Long customerOne = users.getNextCustomerNumber();
            Long customerTwo = users.getNextCustomerNumber();
            assertNotNull(customerOne);
            assertTrue(customerTwo > customerOne);
            Long accountOne = accounts.getNextIbanAccountIdentifier();
            Long accountTwo = accounts.getNextIbanAccountIdentifier();
            assertNotNull(accountOne);
            assertTrue(accountTwo > accountOne);
            assertNotEquals(ibans.generate(accountOne), ibans.generate(accountTwo));
            String transferOne = transferReferences.nextReference();
            String transferTwo = transferReferences.nextReference();
            assertTrue(transferOne.matches("TRX-\\d{8}-\\d+"));
            assertTrue(transferTwo.matches("TRX-\\d{8}-\\d+"));
            assertNotEquals(transferOne, transferTwo);
            String transactionOne = transactionReferences.nextReference();
            String transactionTwo = transactionReferences.nextReference();
            assertTrue(transactionOne.matches("TXN-\\d{8}-\\d+"));
            assertTrue(transactionTwo.matches("TXN-\\d{8}-\\d+"));
            assertNotEquals(transactionOne, transactionTwo);
            return null;
        });
    }
}
