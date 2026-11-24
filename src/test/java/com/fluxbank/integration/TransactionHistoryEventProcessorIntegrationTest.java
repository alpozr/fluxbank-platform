package com.fluxbank.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.processed.repository.ProcessedEventRepository;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import com.fluxbank.transaction.application.TransactionHistoryEventProcessor;
import com.fluxbank.transaction.application.exception.TransactionHistoryInconsistentException;
import com.fluxbank.transaction.domain.Transaction;
import com.fluxbank.transaction.domain.TransactionDirection;
import com.fluxbank.transfer.application.TransferCompletedEvent;
import com.fluxbank.transfer.domain.TransferFailureReason;
import com.fluxbank.transfer.domain.TransferStatus;
import com.fluxbank.transfer.domain.TransferType;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class TransactionHistoryEventProcessorIntegrationTest extends DatabaseIntegrationTestSupport {
    @Autowired private TransactionHistoryEventProcessor processor;
    @Autowired private ProcessedEventRepository processedEvents;
    @Autowired private OutboxEventRepository outboxEvents;
    @Autowired private ObjectMapper objectMapper;
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

    @ParameterizedTest
    @EnumSource(TransferType.class)
    void markerAndExpectedSnapshotMovementsCommitInOneTransaction(TransferType type) throws Exception {
        Fixture f = fixture("1000", "500");
        var event = completedEvent(f, type);
        // The event remains the business source of truth, even if later stored
        // transfer metadata differs. No History is derived from current balances.
        jdbc.update("UPDATE fluxbank.transfers SET amount=999, currency='USD' WHERE public_id=?", event.transferPublicId());

        processor.process(event);

        var rows = history(event.transferPublicId());
        assertEquals(type == TransferType.INTERNAL ? 2 : 1, rows.size());
        var marker = processedEvents.findById(event.eventId()).orElseThrow();
        assertEquals("TRANSFER_COMPLETED", marker.getEventType());
        assertNotNull(marker.getProcessedAt());
        Long markerXid = jdbc.queryForObject("SELECT xmin::text::bigint FROM fluxbank.processed_event WHERE event_id=?", Long.class, event.eventId());
        for (var row : rows) {
            assertEquals(markerXid, row.get("transaction_id"));
            assertEquals("TRANSFER", row.get("type"));
            assertEquals("EUR", row.get("currency"));
            assertEquals(0, event.amount().compareTo((BigDecimal) row.get("amount")));
            boolean debit = "DEBIT".equals(row.get("direction"));
            assertEquals(debit ? event.sourceAccountPublicId() : event.destinationAccountPublicId(), row.get("account_public_id"));
            assertTrue(((String) row.get("reference_number")).matches("TXN-\\d{8}-\\d+"));
        }
        assertEquals(1, rows.stream().filter(row -> "DEBIT".equals(row.get("direction"))).count());
        assertBalance(f.source(), "800", "0", "800");
        assertBalance(f.destination(), type == TransferType.INTERNAL ? "700" : "500", "0", type == TransferType.INTERNAL ? "700" : "500");
    }

    @Test
    void duplicateEventIsANoOpAndDifferentEventIdPreservesBusinessIdempotency() throws Exception {
        Fixture f = fixture("1000", "500");
        var event = completedEvent(f, TransferType.INTERNAL);
        processor.process(event);
        var references = history(event.transferPublicId()).stream().map(r -> r.get("reference_number")).toList();
        var processedAt = processedEvents.findById(event.eventId()).orElseThrow().getProcessedAt();

        processor.process(event);

        assertEquals(1L, processedEvents.count());
        assertEquals(processedAt, processedEvents.findById(event.eventId()).orElseThrow().getProcessedAt());
        processor.process(copyWithEventId(event, UUID.randomUUID()));
        assertEquals(2L, processedEvents.count());
        assertEquals(references, history(event.transferPublicId()).stream().map(r -> r.get("reference_number")).toList());
    }

    @Test
    void incompleteLegacySnapshotIsRejectedBeforeMarkerInsertion() {
        UUID transferId = UUID.randomUUID();
        var legacy = new TransferCompletedKafkaEvent(UUID.randomUUID(), "TRANSFER_COMPLETED", transferId, Instant.now(), transferId);
        assertThrows(IllegalArgumentException.class, () -> processor.process(legacy));
        assertThrows(IllegalArgumentException.class, () -> processor.process(null));
        assertEquals(0L, processedEvents.count());
        assertEquals(0L, transactions.count());
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = {"PENDING", "FAILED"})
    void nonCompletedTransferRollsBackMarker(TransferStatus status) {
        Fixture f = fixture("1000", "500");
        UUID transferId = pending(f, "200");
        if (status == TransferStatus.FAILED) lifecycle.markFailed(transferId, TransferFailureReason.INSUFFICIENT_FUNDS);
        var event = new TransferCompletedKafkaEvent(UUID.randomUUID(), "TRANSFER_COMPLETED", transferId, Instant.now(), transferId,
                TransferType.INTERNAL, f.source().getPublicId(), f.destination().getPublicId(), new BigDecimal("200"), CurrencyCode.EUR);

        assertThrows(IllegalStateException.class, () -> processor.process(event));

        assertFalse(processedEvents.existsById(event.eventId()));
        assertTrue(history(transferId).isEmpty());
    }

    @Test
    void failedCreditInsertRollsBackDebitAndMarkerThenSameEventCanSucceed() throws Exception {
        Fixture f = fixture("1000", "500");
        var event = completedEvent(f, TransferType.INTERNAL);
        jdbc.execute("ALTER TABLE fluxbank.transactions ADD CONSTRAINT test_history_event_credit CHECK (direction <> 'CREDIT')");
        try {
            var failure = assertThrows(DataIntegrityViolationException.class, () -> processor.process(event));
            assertSqlState("23514", failure);
            assertTrue(history(event.transferPublicId()).isEmpty());
            assertFalse(processedEvents.existsById(event.eventId()));
            assertEquals("COMPLETED", state(event.transferPublicId()).get("status"));
            assertBalance(f.source(), "800", "0", "800");
            assertBalance(f.destination(), "700", "0", "700");
        } finally {
            jdbc.execute("ALTER TABLE fluxbank.transactions DROP CONSTRAINT test_history_event_credit");
        }
        processor.process(event);
        assertEquals(2, history(event.transferPublicId()).size());
        assertTrue(processedEvents.existsById(event.eventId()));
    }

    @ParameterizedTest
    @EnumSource(TransactionDirection.class)
    void partialInternalHistoryRemainsAnInconsistencyAndDoesNotCommitMarker(TransactionDirection direction) throws Exception {
        Fixture f = fixture("1000", "500");
        var event = completedEvent(f, TransferType.INTERNAL);
        tx(() -> transactions.saveAndFlush(Transaction.createTransferMovement(transactionReferences.nextReference(),
                direction == TransactionDirection.DEBIT ? f.source() : f.destination(),
                transfers.findByPublicId(event.transferPublicId()).orElseThrow(), direction, event.amount(), event.currency())));

        assertThrows(TransactionHistoryInconsistentException.class, () -> processor.process(event));

        assertEquals(1, history(event.transferPublicId()).size());
        assertFalse(processedEvents.existsById(event.eventId()));
    }

    @Test
    void concurrentDuplicateEventsPersistOneMarkerAndOneMovementPair() throws Exception {
        Fixture f = fixture("1000", "500");
        var event = completedEvent(f, TransferType.INTERNAL);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> process = () -> {
            ready.countDown();
            awaitLatch(start);
            processor.process(event);
            return null;
        };
        Future<Void> first = workers.submit(process);
        Future<Void> second = workers.submit(process);
        try {
            awaitLatch(ready);
        } finally {
            start.countDown();
        }
        first.get(15, TimeUnit.SECONDS);
        second.get(15, TimeUnit.SECONDS);
        assertEquals(1L, processedEvents.count());
        assertEquals(2, history(event.transferPublicId()).size());
    }

    @Test
    void duplicateWaiterProcessesEventWhenFirstTransactionRollsBack() throws Exception {
        Fixture f = fixture("1000", "500");
        var event = completedEvent(f, TransferType.INTERNAL);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger waitingPid = new AtomicInteger();
        Future<?> first = workers.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            processor.process(event);
            entityManager.flush();
            inserted.countDown();
            awaitLatch(release);
            status.setRollbackOnly();
        }));
        Future<?> second;
        try {
            awaitLatch(inserted);
            second = workers.submit(() -> tx(() -> {
                waitingPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                processor.process(event);
                return null;
            }));
            await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock'", Integer.class, waitingPid.get()) == 1);
            assertFalse(second.isDone());
        } finally {
            release.countDown();
        }
        first.get(15, TimeUnit.SECONDS);
        second.get(15, TimeUnit.SECONDS);
        assertEquals(1L, processedEvents.count());
        assertEquals(2, history(event.transferPublicId()).size());
    }

    private TransferCompletedKafkaEvent completedEvent(Fixture f, TransferType type) throws Exception {
        UUID transferId = type == TransferType.INTERNAL ? pending(f, "200") : externalPending(f, "200");
        if (type == TransferType.INTERNAL) process(f, transferId);
        else finalization.complete(transferId);
        var outbox = outboxEvents.findByPublicId((UUID) outbox(transferId).get(0).get("public_id")).orElseThrow();
        var snapshot = objectMapper.treeToValue(outbox.getPayload(), TransferCompletedEvent.class);
        return new TransferCompletedKafkaEvent(outbox.getPublicId(), outbox.getEventType(), outbox.getAggregateId(),
                outbox.getCreatedAt(), snapshot.transferPublicId(), snapshot.transferType(), snapshot.sourceAccountPublicId(),
                snapshot.destinationAccountPublicId(), snapshot.amount(), snapshot.currency());
    }

    private TransferCompletedKafkaEvent copyWithEventId(TransferCompletedKafkaEvent event, UUID id) {
        return new TransferCompletedKafkaEvent(id, event.eventType(), event.aggregateId(), event.occurredAt(), event.transferPublicId(),
                event.transferType(), event.sourceAccountPublicId(), event.destinationAccountPublicId(), event.amount(), event.currency());
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
