package com.fluxbank.integration;

import com.fluxbank.outbox.application.*;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxProcessorIntegrationTest extends DatabaseIntegrationTestSupport {
    @MockitoBean private KafkaTemplate<String, TransferCompletedKafkaEvent> kafkaTemplate;
    @Autowired private OutboxProcessor processor;
    @Autowired private OutboxClaimService claims;
    @Autowired private OutboxRecoveryService recovery;
    @Autowired private OutboxEventRepository events;

    @BeforeEach
    void acknowledgeKafkaSends() {
        when(kafkaTemplate.send(anyString(), anyString(), any(TransferCompletedKafkaEvent.class)))
                .thenAnswer(invocation -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Kafka send must have no database transaction");
                    return CompletableFuture.completedFuture(null);
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acknowledgedPublishMarksClaimProcessedWithoutCreatingHistory(boolean external) {
        Fixture f = fixture("1000", "500");
        UUID transferId = complete(f, external);
        UUID eventId = claims.claimBatch(1).get(0);
        Object claimXid = outbox(transferId).get(0).get("transaction_id");

        processor.process(eventId);

        var event = outbox(transferId).get(0);
        assertEquals("PROCESSED", event.get("status"));
        assertNotNull(event.get("processed_at"));
        assertEquals(0, event.get("attempts"));
        assertNotEquals(claimXid, event.get("transaction_id"));
        assertTrue(history(transferId).isEmpty());
        assertBalance(f.source(), "800", "0", "800");
        assertBalance(f.destination(), external ? "500" : "700", "0", external ? "500" : "700");
        assertEquals("COMPLETED", state(transferId).get("status"));
        verify(kafkaTemplate).send(eq("fluxbank.transfer-events.v1"), eq(transferId.toString()),
                argThat(message -> eventId.equals(message.eventId()) && transferId.equals(message.transferPublicId())));
    }

    @Test
    void onlyClaimedEventsPublishAndProcessedReplayDoesNothing() {
        Fixture f = fixture("1000", "500");
        UUID transferId = complete(f, false);
        UUID id = (UUID) outbox(transferId).get(0).get("public_id");
        processor.process(id);
        verifyNoInteractions(kafkaTemplate);
        claims.claimBatch(1);
        processor.process(id);
        var processedAt = events.findByPublicId(id).orElseThrow().getProcessedAt();
        processor.process(id);
        verify(kafkaTemplate).send(anyString(), anyString(), any(TransferCompletedKafkaEvent.class));
        assertEquals(processedAt, events.findByPublicId(id).orElseThrow().getProcessedAt());
        assertTrue(history(transferId).isEmpty());
    }

    @Test
    void waitingForAcknowledgementHoldsNoDatabaseTransactionOrRowLock() throws Exception {
        Fixture f = fixture("1000", "500");
        UUID transferId = complete(f, false);
        UUID id = claims.claimBatch(1).get(0);
        var acknowledgement = new CompletableFuture<SendResult<String, TransferCompletedKafkaEvent>>();
        CountDownLatch sending = new CountDownLatch(1);
        when(kafkaTemplate.send(anyString(), anyString(), any(TransferCompletedKafkaEvent.class)))
                .thenAnswer(invocation -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    sending.countDown();
                    return acknowledgement;
                });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> processing = worker.submit(() -> processor.process(id));
        try {
            assertTrue(sending.await(10, TimeUnit.SECONDS));
            assertFalse(processing.isDone());
            assertEquals(OutboxStatus.PROCESSING, events.findByPublicId(id).orElseThrow().getStatus());
            tx(() -> jdbc.queryForObject("SELECT public_id FROM fluxbank.outbox_event WHERE public_id=? FOR UPDATE NOWAIT",
                    UUID.class, id));
            acknowledgement.complete(null);
            processing.get(10, TimeUnit.SECONDS);
            assertEquals(OutboxStatus.PROCESSED, events.findByPublicId(id).orElseThrow().getStatus());
            assertTrue(history(transferId).isEmpty());
        } finally {
            acknowledgement.completeExceptionally(new IllegalStateException("Test finished"));
            worker.shutdown();
            assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void recoveryDuringPublishPreventsOldAcknowledgementFromCompletingNewClaim() {
        Fixture f = fixture("1000", "500");
        UUID transferId = complete(f, false);
        UUID id = claims.claimBatch(1).get(0);
        when(kafkaTemplate.send(anyString(), anyString(), any(TransferCompletedKafkaEvent.class)))
                .thenAnswer(invocation -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    jdbc.update("UPDATE fluxbank.outbox_event SET claimed_at=CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE public_id=?", id);
                    assertEquals(List.of(id), recovery.recoverStaleBatch(1));
                    jdbc.update("UPDATE fluxbank.outbox_event SET next_attempt_at=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE public_id=?", id);
                    assertEquals(List.of(id), claims.claimBatch(1));
                    return CompletableFuture.completedFuture(null);
                }).thenReturn(CompletableFuture.completedFuture(null));

        processor.process(id);

        assertEquals(OutboxStatus.PROCESSING, events.findByPublicId(id).orElseThrow().getStatus());
        assertNull(events.findByPublicId(id).orElseThrow().getProcessedAt());
        assertEquals(1, events.findByPublicId(id).orElseThrow().getAttempts());
        processor.process(id); // Intentional at-least-once publication, same logical event ID.
        assertEquals(OutboxStatus.PROCESSED, events.findByPublicId(id).orElseThrow().getStatus());
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), argThat(message -> id.equals(message.eventId())));
        assertTrue(history(transferId).isEmpty());
    }

    @Test
    void acknowledgedSendAndDatabaseCompletionAreNotADistributedTransaction() {
        Fixture f = fixture("1000", "500");
        UUID transferId = complete(f, false);
        UUID id = claims.claimBatch(1).get(0);
        jdbc.execute("ALTER TABLE fluxbank.outbox_event ADD CONSTRAINT test_reject_processed CHECK (status <> 'PROCESSED')");
        try {
            var failure = assertThrows(DataIntegrityViolationException.class, () -> processor.process(id));
            assertSqlState("23514", failure);
            verify(kafkaTemplate).send(anyString(), anyString(), any(TransferCompletedKafkaEvent.class));
            assertEquals(OutboxStatus.FAILED, events.findByPublicId(id).orElseThrow().getStatus());
            assertNull(events.findByPublicId(id).orElseThrow().getProcessedAt());
            assertTrue(history(transferId).isEmpty());
            assertEquals("COMPLETED", state(transferId).get("status"));
        } finally {
            jdbc.execute("ALTER TABLE fluxbank.outbox_event DROP CONSTRAINT test_reject_processed");
        }
    }

    private UUID complete(Fixture f, boolean external) {
        UUID transferId = external ? externalPending(f, "200") : pending(f, "200");
        if (external) {
            finalization.complete(transferId);
        } else {
            process(f, transferId);
        }
        return transferId;
    }
}
