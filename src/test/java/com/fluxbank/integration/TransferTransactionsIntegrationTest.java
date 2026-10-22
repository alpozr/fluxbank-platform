package com.fluxbank.integration;

import com.fluxbank.account.domain.exception.InsufficientFundsException;
import com.fluxbank.transaction.domain.Transaction;
import com.fluxbank.transaction.domain.TransactionDirection;
import com.fluxbank.transfer.api.CreateTransferRequest;
import com.fluxbank.transfer.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransferTransactionsIntegrationTest extends DatabaseIntegrationTestSupport {
    @Test
    void internalCommitCreatesHistoryOnlyAfterCommitInAnotherTransaction() {
        Fixture f = fixture("1000", "500");
        UUID id = pending(f, "200");
        long financialTransaction = tx(() -> {
            long xid = jdbc.queryForObject("SELECT txid_current()", Long.class);
            process(f, id);
            entityManager.flush();
            assertTrue(history(id).isEmpty(), "History must not run before financial commit");
            return xid;
        });

        assertBalance(f.source(), "800", "0", "800");
        assertBalance(f.destination(), "700", "0", "700");
        assertEquals("COMPLETED", state(id).get("status"));
        assertNotNull(state(id).get("completed_at"));
        var rows = history(id);
        assertEquals(2, rows.size());
        assertMovement(rows, f.source().getPublicId(), "DEBIT", "200");
        assertMovement(rows, f.destination().getPublicId(), "CREDIT", "200");
        assertEquals(rows.get(0).get("transaction_id"), rows.get(1).get("transaction_id"));
        assertNotEquals(financialTransaction, rows.get(0).get("transaction_id"),
                "History must commit in its own transaction");
    }

    @Test
    void numericOverflowRollsBackFinancialMutationAndPreservesCommittedPendingTransfer() {
        Fixture f = fixture("1000", "999999999999999.9999");
        UUID id = pending(f, "200"); // TX #1 has returned and committed before TX #2 starts.
        assertEquals("PENDING", state(id).get("status"));

        RuntimeException failure = assertThrows(RuntimeException.class, () -> process(f, id));

        assertSqlState("22003", failure);
        assertBalance(f.source(), "1000", "0", "1000");
        assertBalance(f.destination(), "999999999999999.9999", "0", "999999999999999.9999");
        assertEquals("PENDING", state(id).get("status"));
        assertNull(state(id).get("completed_at"));
        assertTrue(history(id).isEmpty());
    }

    @Test
    void rolledBackFinancialTransactionDoesNotDeliverCompletedEvent() {
        Fixture f = fixture("1000", "500");
        UUID id = pending(f, "200");

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            process(f, id);
            entityManager.flush(); // Both balance updates and COMPLETED reached PostgreSQL, but are uncommitted.
            assertTrue(history(id).isEmpty());
            status.setRollbackOnly();
        });

        assertBalance(f.source(), "1000", "0", "1000");
        assertBalance(f.destination(), "500", "0", "500");
        assertEquals("PENDING", state(id).get("status"));
        assertTrue(history(id).isEmpty());
    }

    @Test
    void businessFailureIsRecordedInASeparateCommittedTransaction() {
        Fixture f = fixture("100", "500");
        var request = new CreateTransferRequest(f.source().getPublicId(),
                f.internalBeneficiary().getPublicId(), new BigDecimal("200"));

        assertThrows(InsufficientFundsException.class,
                () -> transferService.createTransfer(f.owner().getEmail(), request));

        UUID id = jdbc.queryForObject("SELECT public_id FROM fluxbank.transfers", UUID.class);
        assertEquals("FAILED", state(id).get("status"));
        assertEquals("INSUFFICIENT_FUNDS", state(id).get("failure_reason"));
        assertNull(state(id).get("completed_at"));
        assertBalance(f.source(), "100", "0", "100");
        assertBalance(f.destination(), "500", "0", "500");
        assertTrue(history(id).isEmpty());
    }

    @Test
    void externalReservationCommitsPendingAndAddsToExistingReservations() {
        Fixture f = fixture("1000", "500");
        reserveOtherFunds(f, "200");

        UUID id = externalPending(f, "300");

        assertBalance(f.source(), "1000", "500", "500");
        assertEquals("PENDING", state(id).get("status"));
        assertEquals("EXTERNAL", jdbc.queryForObject(
                "SELECT type FROM fluxbank.transfers WHERE public_id=?", String.class, id));
        assertTrue(history(id).isEmpty());
    }

    @Test
    void transferReferenceCollisionRollsBackReservation() {
        Fixture f = fixture("1000", "500");
        reserveOtherFunds(f, "200");
        String collision = tx(() -> transferReferences.nextReference());
        tx(() -> transfers.save(Transfer.create(collision, f.owner(), f.source(),
                f.externalBeneficiary(), null, "External Recipient", f.externalBeneficiary().getIban(),
                "External Bank", BigDecimal.ONE, f.source().getCurrency(), TransferType.EXTERNAL)));
        rewindReference("transfer_reference_seq", collision);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> externalPending(f, "300"));

        assertSqlState("23505", failure);
        assertBalance(f.source(), "1000", "200", "800");
        assertEquals(1L, transfers.count(), "Only the previously committed collision fixture may exist");
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM fluxbank.transfers", String.class));
    }

    @Test
    void externalCompletionCommitsSettlementAndOnlySourceDebitHistory() {
        Fixture f = fixture("1000", "500");
        UUID id = externalPending(f, "300");

        finalization.complete(id);

        assertBalance(f.source(), "700", "0", "700");
        assertEquals("COMPLETED", state(id).get("status"));
        assertNotNull(state(id).get("completed_at"));
        assertEquals(1, history(id).size());
        assertMovement(history(id), f.source().getPublicId(), "DEBIT", "300");
    }

    @Test
    void externalRejectionCommitsReleaseAndFailedState() {
        Fixture f = fixture("1000", "500");
        UUID id = externalPending(f, "300");

        finalization.fail(id, TransferFailureReason.EXTERNAL_PROVIDER_REJECTED);

        assertBalance(f.source(), "1000", "0", "1000");
        assertEquals("FAILED", state(id).get("status"));
        assertEquals("EXTERNAL_PROVIDER_REJECTED", state(id).get("failure_reason"));
        assertNull(state(id).get("completed_at"));
        assertTrue(history(id).isEmpty());
    }

    @Test
    void nullFailureReasonRollsBackRelease() {
        Fixture f = fixture("1000", "500");
        UUID id = externalPending(f, "300");

        assertThrows(IllegalArgumentException.class, () -> finalization.fail(id, null));

        assertBalance(f.source(), "1000", "300", "700");
        assertEquals("PENDING", state(id).get("status"));
        assertNull(state(id).get("failure_reason"));
        assertTrue(history(id).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void finalizationRollbackRestoresBalancesAndTransferState(boolean complete) {
        Fixture f = fixture("1000", "500");
        UUID id = externalPending(f, "300");

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            if (complete) {
                finalization.complete(id);
            } else {
                finalization.fail(id, TransferFailureReason.EXTERNAL_PROVIDER_REJECTED);
            }
            entityManager.flush();
            status.setRollbackOnly();
        });

        assertBalance(f.source(), "1000", "300", "700");
        assertEquals("PENDING", state(id).get("status"));
        assertTrue(history(id).isEmpty());
    }

    @Test
    void historyCreditCollisionRollsBackBothHistoryRowsButNotFinancialCommit() {
        Fixture f = fixture("1000", "500");
        UUID id = pending(f, "200");
        String first = tx(() -> transactionReferences.nextReference());
        String collision = tx(() -> transactionReferences.nextReference());
        // A valid, separate completed-transfer fixture owns the colliding reference.
        tx(() -> {
            Transfer blocker = transfers.save(Transfer.create(transferReferences.nextReference(), f.owner(),
                    f.source(), f.externalBeneficiary(), null, "External Recipient",
                    f.externalBeneficiary().getIban(), "External Bank", BigDecimal.ONE,
                    f.source().getCurrency(), TransferType.EXTERNAL));
            blocker.complete();
            transactions.saveAndFlush(Transaction.createTransferMovement(collision, f.source(), blocker,
                    TransactionDirection.DEBIT, BigDecimal.ONE, f.source().getCurrency()));
            return null;
        });
        rewindReference("transaction_reference_seq", first);

        process(f, id); // AFTER_COMMIT failure is logged by Spring after the financial commit.

        assertBalance(f.source(), "800", "0", "800");
        assertBalance(f.destination(), "700", "0", "700");
        assertEquals("COMPLETED", state(id).get("status"));
        assertTrue(history(id).isEmpty(), "The first history insert must roll back when the second fails");
        assertEquals(1L, transactions.count(), "The unrelated collision fixture remains committed");
        long lastAllocated = jdbc.queryForObject("SELECT last_value FROM fluxbank.transaction_reference_seq", Long.class);
        assertTrue(collision.endsWith("-" + lastAllocated), "Both history references were actually allocated");
    }

    private void assertMovement(List<Map<String, Object>> rows, UUID account, String direction, String amount) {
        var row = rows.stream().filter(r -> account.equals(r.get("account_public_id"))
                && direction.equals(r.get("direction"))).findFirst().orElseThrow();
        assertEquals("TRANSFER", row.get("type"));
        assertEquals("EUR", row.get("currency"));
        assertEquals(0, new BigDecimal(amount).compareTo((BigDecimal) row.get("amount")));
        assertTrue(((String) row.get("reference_number")).matches("TXN-\\d{8}-\\d+"));
    }
}
