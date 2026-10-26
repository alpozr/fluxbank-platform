package com.fluxbank.outbox.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxFailureServiceTest {
    private final OutboxEventRepository events = mock(OutboxEventRepository.class);
    private final OutboxRetryPolicy policy = new OutboxRetryPolicy(3, Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofMinutes(5));
    private final OutboxFailureService failures = new OutboxFailureService(events, policy);
    private final RuntimeException transientFailure = new TransientDataAccessResourceException("private data");

    @Test
    void recordsTransientFailureOnceEvenIfSameAttemptIsReportedTwice() {
        OutboxEvent event = event();
        var attempt = OutboxAttempt.from(event);

        failures.recordFailure(attempt, transientFailure);
        var nextAttempt = event.getNextAttemptAt();
        failures.recordFailure(attempt, transientFailure);

        assertEquals(1, event.getAttempts());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(nextAttempt, event.getNextAttemptAt());
        assertFalse(event.getLastError().contains("private data"));
    }

    @Test
    void staleBookkeepingCannotOverwriteNewClaim() {
        OutboxEvent event = event();
        var oldAttempt = OutboxAttempt.from(event);
        event.recordFailure(true, 3, Instant.now(), "Stale claim expired");
        event.claim();
        var newClaim = event.getClaimedAt();

        failures.recordFailure(oldAttempt, transientFailure);

        assertEquals(1, event.getAttempts());
        assertEquals(OutboxStatus.PROCESSING, event.getStatus());
        assertEquals(newClaim, event.getClaimedAt());
    }

    @Test
    void staleBookkeepingCannotOverwriteProcessedEvent() {
        OutboxEvent event = event();
        var attempt = OutboxAttempt.from(event);
        event.markProcessed();

        failures.recordFailure(attempt, transientFailure);

        assertEquals(OutboxStatus.PROCESSED, event.getStatus());
        assertEquals(0, event.getAttempts());
        assertNull(event.getLastError());
    }

    @Test
    void permanentFailureDoesNotScheduleRetry() {
        OutboxEvent event = event();
        failures.recordFailure(OutboxAttempt.from(event), new IllegalArgumentException("sensitive payload"));
        assertEquals(OutboxStatus.FAILED, event.getStatus());
        assertEquals(1, event.getAttempts());
        assertNull(event.getNextAttemptAt());
        assertEquals("Invalid outbox event data", event.getLastError());
    }

    private OutboxEvent event() {
        OutboxEvent event = OutboxEvent.create("Example", UUID.randomUUID(), JsonNodeFactory.instance.objectNode());
        event.claim();
        when(events.findByPublicIdForUpdate(event.getPublicId())).thenReturn(Optional.of(event));
        return event;
    }
}
