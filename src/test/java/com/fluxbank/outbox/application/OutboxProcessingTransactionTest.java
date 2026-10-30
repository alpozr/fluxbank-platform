package com.fluxbank.outbox.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxProcessingTransactionTest {
    private final OutboxEventRepository events = mock(OutboxEventRepository.class);
    private final OutboxProcessingTransaction completion = new OutboxProcessingTransaction(events);

    @Test
    void marksMatchingProcessingAttemptProcessed() {
        OutboxEvent event = event();
        event.claim();
        completion.markProcessed(OutboxAttempt.from(event));
        assertEquals(OutboxStatus.PROCESSED, event.getStatus());
        assertNotNull(event.getProcessedAt());
    }

    @Test
    void staleAcknowledgementCannotCompleteNewerClaim() {
        OutboxEvent event = event();
        event.claim();
        var oldAttempt = OutboxAttempt.from(event);
        event.recordFailure(true, 3, Instant.now(), "Stale claim expired");
        event.claim();
        var newClaim = event.getClaimedAt();
        completion.markProcessed(oldAttempt);
        assertEquals(OutboxStatus.PROCESSING, event.getStatus());
        assertEquals(newClaim, event.getClaimedAt());
        assertEquals(1, event.getAttempts());
        assertNull(event.getProcessedAt());
    }

    @Test
    void pendingEventCannotBeMarkedProcessedWithoutClaim() {
        OutboxEvent event = event();
        completion.markProcessed(OutboxAttempt.from(event));
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertNull(event.getProcessedAt());
    }

    @Test
    void processedEventKeepsItsOriginalTimestamp() {
        OutboxEvent event = event();
        event.claim();
        var attempt = OutboxAttempt.from(event);
        completion.markProcessed(attempt);
        var timestamp = event.getProcessedAt();
        completion.markProcessed(attempt);
        assertEquals(timestamp, event.getProcessedAt());
    }

    private OutboxEvent event() {
        var event = OutboxEvent.create("TRANSFER_COMPLETED", UUID.randomUUID(), JsonNodeFactory.instance.objectNode());
        when(events.findByPublicIdForUpdate(event.getPublicId())).thenReturn(Optional.of(event));
        return event;
    }
}
