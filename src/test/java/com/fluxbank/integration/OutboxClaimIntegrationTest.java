package com.fluxbank.integration;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fluxbank.outbox.application.OutboxClaimService;
import com.fluxbank.outbox.application.OutboxProcessor;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class OutboxClaimIntegrationTest extends DatabaseIntegrationTestSupport {
    @Autowired private OutboxClaimService claims;
    @Autowired private OutboxEventRepository events;
    @Autowired private OutboxProcessor processor;

    private ExecutorService workers;

    @BeforeEach
    void startWorkers() {
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void finishWorkersBeforeCleanup() throws InterruptedException {
        workers.shutdown();
        if (!workers.awaitTermination(20, TimeUnit.SECONDS)) {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void respectsBatchLimitAndOldestFirstWithIdAsTieBreaker() {
        List<UUID> ids = seed(3);
        jdbc.update("UPDATE fluxbank.outbox_event SET created_at=? WHERE public_id=?",
                Timestamp.from(Instant.parse("2021-01-01T00:00:00Z")), ids.get(0));

        assertEquals(List.of(ids.get(1), ids.get(2)), claims.claimBatch(2));

        assertEquals(OutboxStatus.PENDING, events.findByPublicId(ids.get(0)).orElseThrow().getStatus());
        for (UUID id : ids.subList(1, 3)) {
            OutboxEvent event = events.findByPublicId(id).orElseThrow();
            assertEquals(OutboxStatus.PROCESSING, event.getStatus());
            assertNotNull(event.getClaimedAt());
            assertNull(event.getProcessedAt());
            assertEquals(0, event.getAttempts());
        }
        assertEquals(List.of(ids.get(0)), claims.claimBatch(2));
        assertTrue(claims.claimBatch(2).isEmpty());
        assertEquals(0L, transactions.count(), "Claiming must not run history processing");
    }

    @Test
    void excludesProcessingProcessedAndFailedEvents() {
        List<UUID> ids = seed(4);
        tx(() -> {
            events.findByPublicId(ids.get(0)).orElseThrow().claim();
            events.findByPublicId(ids.get(1)).orElseThrow().markProcessed();
            return null;
        });
        jdbc.update("UPDATE fluxbank.outbox_event SET status='FAILED' WHERE public_id=?", ids.get(2));

        assertEquals(List.of(ids.get(3)), claims.claimBatch(10));

        assertTrue(claims.claimBatch(10).isEmpty());
        assertEquals(OutboxStatus.FAILED, events.findByPublicId(ids.get(2)).orElseThrow().getStatus());
    }

    @Test
    void concurrentServicesReturnDisjointBatches() throws Exception {
        List<UUID> ids = seed(4);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<List<UUID>> claim = () -> {
            ready.countDown();
            awaitLatch(start);
            return claims.claimBatch(2);
        };
        Future<List<UUID>> first = workers.submit(claim);
        Future<List<UUID>> second = workers.submit(claim);
        try {
            awaitLatch(ready);
        } finally {
            start.countDown();
        }
        List<UUID> a = first.get(10, TimeUnit.SECONDS);
        List<UUID> b = second.get(10, TimeUnit.SECONDS);
        assertEquals(2, a.size());
        assertEquals(2, b.size());
        HashSet<UUID> combined = new HashSet<>(a);
        assertTrue(combined.addAll(b));
        assertEquals(4, combined.size());
        assertEquals(new HashSet<>(ids), combined);
    }

    @Test
    void skipLockedClaimsLaterRowsWhileFirstTransactionStillHoldsLocks() throws Exception {
        List<UUID> ids = seed(4);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Hold open exactly the repository query + domain transitions used by claimBatch,
        // so the competing production service must encounter real, uncommitted row locks.
        Future<List<UUID>> first = workers.submit(() -> tx(() -> {
            var batch = events.findPendingForClaim(2);
            batch.forEach(OutboxEvent::claim);
            entityManager.flush();
            locked.countDown();
            awaitLatch(release);
            return batch.stream().map(OutboxEvent::getPublicId).toList();
        }));
        try {
            awaitLatch(locked);
            Future<List<UUID>> second = workers.submit(() -> claims.claimBatch(2));
            assertEquals(List.of(ids.get(2), ids.get(3)), second.get(5, TimeUnit.SECONDS));
            assertFalse(first.isDone(), "The competing claim completed before the first transaction released its locks");
        } finally {
            release.countDown();
        }
        assertEquals(List.of(ids.get(0), ids.get(1)), first.get(10, TimeUnit.SECONDS));
    }

    @Test
    void claimCommitsIndependentlyOfCallerRollback() {
        UUID id = seed(1).get(0);

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertEquals(List.of(id), claims.claimBatch(1));
            status.setRollbackOnly();
        });

        assertEquals(OutboxStatus.PROCESSING, events.findByPublicId(id).orElseThrow().getStatus());
        assertNotNull(events.findByPublicId(id).orElseThrow().getClaimedAt());
    }

    @Test
    void claimLocksAreReleasedBeforeProcessingInAnotherTransaction() {
        Fixture f = fixture("1000", "500");
        UUID transferId = pending(f, "200");
        process(f, transferId);
        UUID id = (UUID) outbox(transferId).get(0).get("public_id");
        assertEquals(List.of(id), claims.claimBatch(1));
        var claimed = events.findByPublicId(id).orElseThrow().getClaimedAt();
        Object claimXid = outbox(transferId).get(0).get("transaction_id");

        // An independent transaction can immediately lock the claimed row. Locks
        // acquired later by history processing are separate from the released claim lock.
        tx(() -> jdbc.queryForObject("SELECT public_id FROM fluxbank.outbox_event WHERE public_id=? FOR UPDATE NOWAIT",
                UUID.class, id));
        assertTrue(history(transferId).isEmpty());
        processor.process(id);

        assertEquals("PROCESSED", outbox(transferId).get(0).get("status"));
        assertNotEquals(claimXid, outbox(transferId).get(0).get("transaction_id"));
        assertEquals(claimed, events.findByPublicId(id).orElseThrow().getClaimedAt());
        assertEquals(2, history(transferId).size());
        for (var row : history(transferId)) {
            assertEquals(outbox(transferId).get(0).get("transaction_id"), row.get("transaction_id"));
        }
    }

    @Test
    void permanentProcessingFailureRollsBackHistoryAndRecordsTerminalFailure() {
        Fixture f = fixture("1000", "500");
        UUID transferId = pending(f, "200");
        process(f, transferId);
        UUID id = claims.claimBatch(1).get(0);
        var claimedAt = events.findByPublicId(id).orElseThrow().getClaimedAt();
        jdbc.execute("ALTER TABLE fluxbank.transactions ADD CONSTRAINT test_claim_reject_credit CHECK (direction <> 'CREDIT')");
        try {
            var failure = assertThrows(DataIntegrityViolationException.class, () -> processor.process(id));
            assertSqlState("23514", failure);
            assertTrue(history(transferId).isEmpty());
            OutboxEvent event = events.findByPublicId(id).orElseThrow();
            assertEquals(OutboxStatus.FAILED, event.getStatus());
            assertEquals(claimedAt, event.getClaimedAt());
            assertNull(event.getProcessedAt());
            assertEquals(1, event.getAttempts());
            assertNotNull(event.getLastError());
            assertTrue(claims.claimBatch(1).isEmpty(), "FAILED events are terminal");
            assertBalance(f.source(), "800", "0", "800");
            assertBalance(f.destination(), "700", "0", "700");
        } finally {
            jdbc.execute("ALTER TABLE fluxbank.transactions DROP CONSTRAINT test_claim_reject_credit");
        }
    }

    private List<UUID> seed(int count) {
        return tx(() -> {
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(events.saveAndFlush(OutboxEvent.create("TRANSFER_COMPLETED", UUID.randomUUID(),
                        JsonNodeFactory.instance.objectNode())).getPublicId());
            }
            jdbc.update("UPDATE fluxbank.outbox_event SET created_at=?", Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")));
            return ids;
        });
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for worker synchronization");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
