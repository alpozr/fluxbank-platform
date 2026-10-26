package com.fluxbank.integration;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fluxbank.outbox.application.*;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OutboxRetryIntegrationTest extends DatabaseIntegrationTestSupport {
    @Autowired private OutboxProcessor processor;
    @Autowired private OutboxClaimService claims;
    @Autowired private OutboxRecoveryService recovery;
    @Autowired private OutboxRetryPolicy policy;
    @Autowired private OutboxEventRepository events;
    private ExecutorService workers;

    @BeforeEach
    void startWorkers() {
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void finishWorkersAndRemoveTestFailureTrigger() throws InterruptedException {
        workers.shutdown();
        if (!workers.awaitTermination(20, TimeUnit.SECONDS)) {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
        }
        jdbc.execute("DROP TRIGGER IF EXISTS test_outbox_transient ON fluxbank.transactions");
        jdbc.execute("DROP FUNCTION IF EXISTS fluxbank.test_outbox_transient()");
    }

    @Test
    void transientFailureRollsBackHistorySchedulesBackoffAndLaterRetrySucceedsIdempotently() {
        Fixture f = fixture("1000", "500");
        UUID transfer = pending(f, "200");
        process(f, transfer);
        UUID id = claims.claimBatch(1).get(0);
        installTransientCreditFailure();
        Instant before = Instant.now();

        assertThrows(TransientDataAccessException.class, () -> processor.process(id));

        OutboxEvent failedAttempt = event(id);
        assertEquals(OutboxStatus.PENDING, failedAttempt.getStatus());
        assertEquals(1, failedAttempt.getAttempts());
        assertNull(failedAttempt.getClaimedAt());
        assertNull(failedAttempt.getProcessedAt());
        assertFalse(failedAttempt.getNextAttemptAt().isBefore(before.plus(policy.delay(1)).minusMillis(1)));
        assertFalse(failedAttempt.getNextAttemptAt().isAfter(Instant.now().plus(policy.delay(1))));
        assertNotNull(failedAttempt.getLastError());
        assertFalse(failedAttempt.getLastError().contains("sensitive"));
        assertTrue(history(transfer).isEmpty(), "The debit must roll back when the credit fails");
        assertBalance(f.source(), "800", "0", "800");
        assertBalance(f.destination(), "700", "0", "700");
        assertEquals("COMPLETED", state(transfer).get("status"));

        // Control database timestamps explicitly; never sleep for backoff expiration.
        jdbc.update("UPDATE fluxbank.outbox_event SET next_attempt_at=CURRENT_TIMESTAMP + INTERVAL '1 hour' WHERE public_id=?", id);
        assertTrue(claims.claimBatch(1).isEmpty());
        processor.process(id); // Direct processing must also respect nextAttemptAt.
        assertEquals(1, event(id).getAttempts());
        jdbc.execute("DROP TRIGGER test_outbox_transient ON fluxbank.transactions");
        makeDue(id);
        assertEquals(List.of(id), claims.claimBatch(1));
        processor.process(id);

        assertEquals(OutboxStatus.PROCESSED, event(id).getStatus());
        assertEquals(1, event(id).getAttempts());
        assertNull(event(id).getNextAttemptAt());
        assertNull(event(id).getLastError());
        assertNotNull(event(id).getProcessedAt());
        assertEquals(2, history(transfer).size());
        for (var movement : history(transfer)) {
            assertEquals(outbox(transfer).get(0).get("transaction_id"), movement.get("transaction_id"));
        }
        processor.process(id);
        assertEquals(2, history(transfer).size());
    }

    @Test
    void exhaustingRetryBudgetLeavesFailedEventStoredAndUnclaimable() {
        Fixture f = fixture("1000", "500");
        UUID transfer = pending(f, "200");
        process(f, transfer);
        UUID id = (UUID) outbox(transfer).get(0).get("public_id");
        installTransientCreditFailure();
        for (int attempt = 1; attempt <= policy.getMaxAttempts(); attempt++) {
            makeDue(id);
            assertEquals(List.of(id), claims.claimBatch(1));
            assertThrows(TransientDataAccessException.class, () -> processor.process(id));
            assertEquals(attempt, event(id).getAttempts());
            assertEquals(attempt < policy.getMaxAttempts() ? OutboxStatus.PENDING : OutboxStatus.FAILED,
                    event(id).getStatus());
        }
        assertTrue(claims.claimBatch(10).isEmpty());
        processor.process(id);
        assertEquals(policy.getMaxAttempts(), event(id).getAttempts());
        assertNull(event(id).getNextAttemptAt());
        assertEquals(1L, events.count());
        assertTrue(history(transfer).isEmpty());
    }

    @Test
    void malformedAndUnsupportedEventsFailPermanentlyWithoutRawPayloadInError() {
        for (String type : List.of("TRANSFER_COMPLETED", "UNKNOWN")) {
            UUID id = tx(() -> events.saveAndFlush(OutboxEvent.create(type, UUID.randomUUID(),
                    JsonNodeFactory.instance.objectNode().put("transferPublicId", "sensitive-invalid-uuid"))).getPublicId());
            assertThrows(RuntimeException.class, () -> processor.process(id));
            assertEquals(OutboxStatus.FAILED, event(id).getStatus());
            assertEquals(1, event(id).getAttempts());
            assertNull(event(id).getNextAttemptAt());
            assertFalse(event(id).getLastError().contains("sensitive"));
            processor.process(id);
            assertEquals(1, event(id).getAttempts());
        }
        assertTrue(claims.claimBatch(10).isEmpty());
        assertEquals(2L, events.count());
        assertEquals(0L, transactions.count());
    }

    @Test
    void recoverySkipsRecentClaimsAndConsumesTheSameRetryBudget() {
        UUID stale = seed();
        UUID recent = seed();
        claims.claimBatch(10);
        expire(stale);

        assertEquals(List.of(stale), recovery.recoverStaleBatch(1));

        assertEquals(OutboxStatus.PENDING, event(stale).getStatus());
        assertEquals(1, event(stale).getAttempts());
        assertNotNull(event(stale).getNextAttemptAt());
        assertEquals("Stale processing claim expired", event(stale).getLastError());
        assertEquals(OutboxStatus.PROCESSING, event(recent).getStatus());
        assertEquals(0, event(recent).getAttempts());
        assertTrue(recovery.recoverStaleBatch(10).isEmpty());
        for (int attempt = 2; attempt <= policy.getMaxAttempts(); attempt++) {
            makeDue(stale);
            assertEquals(List.of(stale), claims.claimBatch(1));
            expire(stale);
            assertEquals(List.of(stale), recovery.recoverStaleBatch(1));
            assertEquals(attempt, event(stale).getAttempts());
        }
        assertEquals(OutboxStatus.FAILED, event(stale).getStatus());
        assertTrue(claims.claimBatch(10).isEmpty());
        assertEquals(2L, events.count());
    }

    @Test
    void concurrentRecoveryServicesConsumeOneAttemptOnly() throws Exception {
        UUID id = seed();
        claims.claimBatch(1);
        expire(id);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<List<UUID>> recover = () -> {
            ready.countDown();
            await(start);
            return recovery.recoverStaleBatch(1);
        };
        Future<List<UUID>> first = workers.submit(recover);
        Future<List<UUID>> second = workers.submit(recover);
        try {
            await(ready);
        } finally {
            start.countDown();
        }
        var a = first.get(10, TimeUnit.SECONDS);
        var b = second.get(10, TimeUnit.SECONDS);
        assertEquals(1, a.size() + b.size());
        assertEquals(1, event(id).getAttempts());
        assertEquals(OutboxStatus.PENDING, event(id).getStatus());
    }

    @Test
    void recoverySkipsLockedStaleRowsWithoutWaiting() throws Exception {
        UUID id = seed();
        claims.claimBatch(1);
        expire(id);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = workers.submit(() -> tx(() -> {
            assertEquals(1, events.findStaleForRecovery(Instant.now(), 1).size());
            locked.countDown();
            await(release);
            return null;
        }));
        try {
            await(locked);
            assertTrue(workers.submit(() -> recovery.recoverStaleBatch(1)).get(5, TimeUnit.SECONDS).isEmpty());
            assertFalse(holder.isDone());
        } finally {
            release.countDown();
        }
        holder.get(10, TimeUnit.SECONDS);
        assertEquals(List.of(id), recovery.recoverStaleBatch(1));
        assertEquals(1, event(id).getAttempts());
    }

    private void installTransientCreditFailure() {
        jdbc.execute("""
                CREATE FUNCTION fluxbank.test_outbox_transient() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'sensitive transient database details' USING ERRCODE='40001'; END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER test_outbox_transient BEFORE INSERT ON fluxbank.transactions
                FOR EACH ROW WHEN (NEW.direction='CREDIT') EXECUTE FUNCTION fluxbank.test_outbox_transient()
                """);
    }

    private UUID seed() {
        UUID aggregate = UUID.randomUUID();
        return tx(() -> events.saveAndFlush(OutboxEvent.create("TRANSFER_COMPLETED", aggregate,
                JsonNodeFactory.instance.objectNode().put("transferPublicId", aggregate.toString()))).getPublicId());
    }

    private OutboxEvent event(UUID id) {
        return events.findByPublicId(id).orElseThrow();
    }

    private void makeDue(UUID id) {
        jdbc.update("UPDATE fluxbank.outbox_event SET next_attempt_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE public_id=?", id);
    }

    private void expire(UUID id) {
        jdbc.update("UPDATE fluxbank.outbox_event SET claimed_at=? WHERE public_id=?",
                Timestamp.from(Instant.now().minus(policy.getProcessingTimeout()).minusSeconds(60)), id);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
