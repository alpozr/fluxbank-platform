package com.fluxbank.integration;

import com.fluxbank.account.domain.exception.InsufficientFundsException;
import com.fluxbank.transaction.domain.Transaction;
import com.fluxbank.transaction.domain.TransactionDirection;
import com.fluxbank.transfer.api.CreateTransferRequest;
import com.fluxbank.transfer.domain.*;
import com.fluxbank.transfer.application.TransferCompletedEvent;
import com.fluxbank.transfer.application.TransferEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransferTransactionsIntegrationTest extends DatabaseIntegrationTestSupport {
    @Autowired
    private TransferEventPublisher publisher;

    @Autowired
    private ApplicationEventPublisher springEvents;

    @Test
    void publishingWithoutAFinancialTransactionIsRejected() {
        UUID id = UUID.randomUUID();

        assertThrows(IllegalTransactionStateException.class, () -> publisher.publishCompleted(id));

        assertTrue(outbox(id).isEmpty());
    }

    @Test
    void legacyInMemoryEventNoLongerCreatesHistory() {
        Fixture f = fixture("1000", "500");
        UUID id = pending(f, "200");
        process(f, id);

        tx(() -> {
            springEvents.publishEvent(new TransferCompletedEvent(id));
            return null;
        });

        assertTrue(history(id).isEmpty());
        assertPendingOutbox(id);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedOutboxInsertRollsBackFinancialCompletion(boolean external) {
        Fixture f = fixture("1000", "500");
        UUID id = external ? externalPending(f, "200") : pending(f, "200");
        // Test-only database rejection; no production failure hooks or repository mocks.
        jdbc.execute("ALTER TABLE fluxbank.outbox_event ADD CONSTRAINT test_reject_completed_event "
                + "CHECK (aggregate_id <> '" + id + "'::uuid)");
        try {
            var failure = assertThrows(DataIntegrityViolationException.class, () -> {
                if (external) {
                    finalization.complete(id);
                } else {
                    process(f, id);
                }
            });
            assertSqlState("23514", failure);
            assertBalance(f.source(), "1000", external ? "200" : "0", external ? "800" : "1000");
            assertBalance(f.destination(), "500", "0", "500");
            assertEquals("PENDING", state(id).get("status"));
            assertNull(state(id).get("completed_at"));
            assertTrue(outbox(id).isEmpty());
            assertTrue(history(id).isEmpty());
        } finally {
            jdbc.execute("ALTER TABLE fluxbank.outbox_event DROP CONSTRAINT test_reject_completed_event");
        }
    }

    @Test
    void internalCompletionAndOutboxCommitInTheSameTransactionWithoutCreatingHistory() {
        Fixture f = fixture("1000", "500");
        UUID id = pending(f, "200");
        long financialTransaction = tx(() -> {
            long xid = jdbc.queryForObject("SELECT txid_current()", Long.class);
            process(f, id);
            entityManager.flush();
            assertTrue(history(id).isEmpty(), "History must not run during durable publication");
            assertPendingOutbox(id);
            return xid;
        });

        assertBalance(f.source(), "800", "0", "800");
        assertBalance(f.destination(), "700", "0", "700");
        assertEquals("COMPLETED", state(id).get("status"));
        assertNotNull(state(id).get("completed_at"));
        assertPendingOutbox(id);
        assertTrue(history(id).isEmpty());
        assertEquals(financialTransaction, outbox(id).get(0).get("transaction_id"));
        long transferTransaction = jdbc.queryForObject(
                "SELECT xmin::text::bigint FROM fluxbank.transfers WHERE public_id=?", Long.class, id);
        assertEquals(financialTransaction, transferTransaction);
        assertEquals(financialTransaction, jdbc.queryForObject("""
                SELECT b.xmin::text::bigint FROM fluxbank.account_balances b
                JOIN fluxbank.accounts a ON a.id=b.account_id WHERE a.public_id=?
                """, Long.class, f.source().getPublicId()));

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
        assertTrue(outbox(id).isEmpty());
    }

    @Test
    void rolledBackFinancialTransactionAlsoRollsBackOutboxInsert() {
        Fixture f = fixture("1000", "500");
        UUID id = pending(f, "200");

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            process(f, id);
            entityManager.flush(); // Both balance updates and COMPLETED reached PostgreSQL, but are uncommitted.
            assertTrue(history(id).isEmpty());
            assertPendingOutbox(id);
            status.setRollbackOnly();
        });

        assertBalance(f.source(), "1000", "0", "1000");
        assertBalance(f.destination(), "500", "0", "500");
        assertEquals("PENDING", state(id).get("status"));
        assertTrue(history(id).isEmpty());
        assertTrue(outbox(id).isEmpty());
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
        assertTrue(outbox(id).isEmpty());
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
        assertTrue(outbox(id).isEmpty());
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
    void externalCompletionCommitsSettlementAndOutboxWithoutCreatingHistory() {
        Fixture f = fixture("1000", "500");
        UUID id = externalPending(f, "300");

        finalization.complete(id);

        assertBalance(f.source(), "700", "0", "700");
        assertEquals("COMPLETED", state(id).get("status"));
        assertNotNull(state(id).get("completed_at"));
        assertTrue(history(id).isEmpty());
        assertPendingOutbox(id);
        assertEquals(outbox(id).get(0).get("transaction_id"), jdbc.queryForObject(
                "SELECT xmin::text::bigint FROM fluxbank.transfers WHERE public_id=?", Long.class, id));
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
        assertTrue(outbox(id).isEmpty());
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
        assertTrue(outbox(id).isEmpty());
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
        assertTrue(outbox(id).isEmpty());
    }

    @Test
    void explicitHistoryCreationFailureRollsBackBothHistoryRowsButNotFinancialCommit() {
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

        process(f, id);
        assertPendingOutbox(id);
        assertThrows(DataIntegrityViolationException.class, () -> historyService.createForTransfer(id));

        assertBalance(f.source(), "800", "0", "800");
        assertBalance(f.destination(), "700", "0", "700");
        assertEquals("COMPLETED", state(id).get("status"));
        assertTrue(history(id).isEmpty(), "The first history insert must roll back when the second fails");
        assertEquals(1L, transactions.count(), "The unrelated collision fixture remains committed");
        long lastAllocated = jdbc.queryForObject("SELECT last_value FROM fluxbank.transaction_reference_seq", Long.class);
        assertTrue(collision.endsWith("-" + lastAllocated), "Both history references were actually allocated");
    }

    private void assertPendingOutbox(UUID id) {
        var rows = outbox(id);
        assertEquals(1, rows.size());
        var event = rows.get(0);
        assertNotNull(event.get("public_id"));
        assertEquals("TRANSFER_COMPLETED", event.get("event_type"));
        assertEquals(id, event.get("aggregate_id"));
        assertEquals(id.toString(), event.get("transfer_public_id"));
        assertEquals("PENDING", event.get("status"));
        assertEquals(0, event.get("attempts"));
        assertNull(event.get("processed_at"));
        assertNull(event.get("last_error"));
    }
}
