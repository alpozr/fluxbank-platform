package com.fluxbank.transfer.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxTransferEventPublisherTest {

    private final OutboxEventRepository events = mock(OutboxEventRepository.class);
    private final OutboxTransferEventPublisher publisher = new OutboxTransferEventPublisher(events, new ObjectMapper());

    @Test
    void persistsPendingTransferCompletedEventWithPublicIdPayload() {
        UUID id = UUID.randomUUID();

        publisher.publishCompleted(id);

        var captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(events).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertNotNull(event.getPublicId());
        assertEquals("TRANSFER_COMPLETED", event.getEventType());
        assertEquals(id, event.getAggregateId());
        assertEquals(1, event.getPayload().size());
        assertEquals(id.toString(), event.getPayload().get("transferPublicId").asText());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttempts());
        assertNull(event.getProcessedAt());
        assertNull(event.getLastError());
    }

    @Test
    void rejectsMissingTransferPublicIdWithoutSaving() {
        assertThrows(IllegalArgumentException.class, () -> publisher.publishCompleted(null));
        verifyNoInteractions(events);
    }
}
