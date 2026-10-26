package com.fluxbank.outbox.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxClaimServiceTest {
    private final OutboxEventRepository events = mock(OutboxEventRepository.class);
    private final OutboxClaimService service = new OutboxClaimService(events);

    @Test
    void claimsSelectedEventsAndReturnsPublicIds() {
        OutboxEvent event = OutboxEvent.create("Example", UUID.randomUUID(), JsonNodeFactory.instance.objectNode());
        when(events.findPendingForClaim(5)).thenReturn(List.of(event));

        assertEquals(List.of(event.getPublicId()), service.claimBatch(5));

        assertEquals(OutboxStatus.PROCESSING, event.getStatus());
        assertNotNull(event.getClaimedAt());
        assertEquals(0, event.getAttempts());
    }

    @Test
    void emptyBatchReturnsNoIds() {
        when(events.findPendingForClaim(5)).thenReturn(List.of());
        assertTrue(service.claimBatch(5).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveBatchSize(int size) {
        assertThrows(IllegalArgumentException.class, () -> service.claimBatch(size));
        verifyNoInteractions(events);
    }
}
