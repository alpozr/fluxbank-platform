package com.fluxbank.outbox.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import com.fluxbank.transaction.application.TransactionHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxProcessingTransactionTest {
    private final OutboxEventRepository events = mock(OutboxEventRepository.class);
    private final TransactionHistoryService history = mock(TransactionHistoryService.class);
    private final OutboxProcessingTransaction processor = new OutboxProcessingTransaction(events, history);

    @Test
    void staleAttemptCannotProcessANewerClaim() {
        OutboxEvent event = event("TRANSFER_COMPLETED", UUID.randomUUID());
        event.claim();
        var oldAttempt = OutboxAttempt.from(event);
        event.recordFailure(true, 3, java.time.Instant.now(), "Stale claim expired");
        event.claim();

        processor.process(oldAttempt);

        verifyNoInteractions(history);
        assertEquals(OutboxStatus.PROCESSING, event.getStatus());
        assertEquals(1, event.getAttempts());
    }

    @Test
    void createsHistoryBeforeMarkingEventProcessed() {
        OutboxEvent event = event("TRANSFER_COMPLETED", UUID.randomUUID());
        doAnswer(invocation -> {
            assertEquals(OutboxStatus.PENDING, event.getStatus());
            assertNull(event.getProcessedAt());
            return null;
        }).when(history).createForTransfer(event.getAggregateId());

        processor.process(OutboxAttempt.from(event));

        verify(history).createForTransfer(event.getAggregateId());
        assertEquals(OutboxStatus.PROCESSED, event.getStatus());
        assertNotNull(event.getProcessedAt());
    }

    @Test
    void historyFailureLeavesEventPendingAndPropagates() {
        OutboxEvent event = event("TRANSFER_COMPLETED", UUID.randomUUID());
        var failure = new IllegalStateException("History failure");
        doThrow(failure).when(history).createForTransfer(event.getAggregateId());

        assertSame(failure, assertThrows(IllegalStateException.class, () -> processor.process(OutboxAttempt.from(event))));

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertNull(event.getProcessedAt());
    }

    @Test
    void processedEventIsANoOp() {
        OutboxEvent event = event("TRANSFER_COMPLETED", UUID.randomUUID());
        event.markProcessed();
        var processedAt = event.getProcessedAt();

        processor.process(OutboxAttempt.from(event));

        verifyNoInteractions(history);
        assertEquals(processedAt, event.getProcessedAt());
    }

    @Test
    void rejectsMissingEvent() {
        UUID id = UUID.randomUUID();
        when(events.findByPublicIdForUpdate(id)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> processor.process(new OutboxAttempt(id, OutboxStatus.PENDING, 0, null)));
        verifyNoInteractions(history);
    }

    @Test
    void rejectsUnsupportedEventType() {
        OutboxEvent event = event("UNSUPPORTED", UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> processor.process(OutboxAttempt.from(event)));
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        verifyNoInteractions(history);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"transferPublicId\":null}", "{\"transferPublicId\":42}", "{\"transferPublicId\":\"invalid\"}"})
    void rejectsInvalidPayloadWithoutCreatingHistory(String json) throws Exception {
        OutboxEvent event = OutboxEvent.create("TRANSFER_COMPLETED", UUID.randomUUID(),
                new ObjectMapper().readTree(json));
        when(events.findByPublicIdForUpdate(event.getPublicId())).thenReturn(Optional.of(event));

        assertThrows(IllegalArgumentException.class, () -> processor.process(OutboxAttempt.from(event)));

        assertEquals(OutboxStatus.PENDING, event.getStatus());
        verifyNoInteractions(history);
    }

    @Test
    void rejectsPayloadForAnotherAggregate() {
        UUID aggregate = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.create("TRANSFER_COMPLETED", aggregate,
                JsonNodeFactory.instance.objectNode().put("transferPublicId", UUID.randomUUID().toString()));
        when(events.findByPublicIdForUpdate(event.getPublicId())).thenReturn(Optional.of(event));

        assertThrows(IllegalArgumentException.class, () -> processor.process(OutboxAttempt.from(event)));
        verifyNoInteractions(history);
    }

    private OutboxEvent event(String type, UUID transferId) {
        OutboxEvent event = OutboxEvent.create(type, transferId,
                JsonNodeFactory.instance.objectNode().put("transferPublicId", transferId.toString()));
        when(events.findByPublicIdForUpdate(event.getPublicId())).thenReturn(Optional.of(event));
        return event;
    }
}
