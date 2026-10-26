package com.fluxbank.outbox.application;

import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import java.time.Instant;
import java.util.UUID;

// Fences bookkeeping and processing against a newer claim or recovery.
record OutboxAttempt(UUID publicId, OutboxStatus status, int attempts, Instant claimedAt) {
    static OutboxAttempt from(OutboxEvent event) {
        return new OutboxAttempt(event.getPublicId(), event.getStatus(), event.getAttempts(), event.getClaimedAt());
    }
}
