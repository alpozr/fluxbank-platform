package com.fluxbank.outbox.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxPollerTest {
    private final OutboxClaimService claims = mock(OutboxClaimService.class);
    private final OutboxProcessor processor = mock(OutboxProcessor.class);
    private final OutboxPoller poller = new OutboxPoller(claims, processor, 7);

    @Test
    void claimsOneBatchUsingConfiguredSizeAndProcessesItsEvent() {
        UUID eventId = UUID.randomUUID();
        when(claims.claimBatch(7)).thenReturn(List.of(eventId));

        poller.poll();

        verify(claims).claimBatch(7);
        verify(processor).process(eventId);
    }

    @Test
    void processesAllClaimedEventsSequentially() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(claims.claimBatch(7)).thenReturn(List.of(first, second, third));

        poller.poll();

        var order = inOrder(claims, processor);
        order.verify(claims).claimBatch(7);
        order.verify(processor).process(first);
        order.verify(processor).process(second);
        order.verify(processor).process(third);
        order.verifyNoMoreInteractions();
    }

    @Test
    void failureDoesNotPreventProcessingRemainingEvents() {
        UUID first = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        when(claims.claimBatch(7)).thenReturn(List.of(first, failed, last));
        doThrow(new IllegalStateException("Processing failed")).when(processor).process(failed);

        assertDoesNotThrow(poller::poll);

        var order = inOrder(processor);
        order.verify(processor).process(first);
        order.verify(processor).process(failed);
        order.verify(processor).process(last);
        verify(claims).claimBatch(7);
    }

    @Test
    void emptyBatchDoesNothing() {
        when(claims.claimBatch(7)).thenReturn(List.of());

        assertDoesNotThrow(poller::poll);

        verify(claims).claimBatch(7);
        verifyNoInteractions(processor);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveBatchSize(int size) {
        assertThrows(IllegalArgumentException.class, () -> new OutboxPoller(claims, processor, size));
    }
}
