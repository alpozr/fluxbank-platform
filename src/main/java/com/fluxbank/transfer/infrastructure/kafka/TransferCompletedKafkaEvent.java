package com.fluxbank.transfer.infrastructure.kafka;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

public record TransferCompletedKafkaEvent(
        UUID eventId,
        String eventType,
        UUID aggregateId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        UUID transferPublicId) {

    public TransferCompletedKafkaEvent {
        if (eventId == null || aggregateId == null || occurredAt == null || transferPublicId == null) {
            throw new IllegalArgumentException("Event ID, aggregate ID, occurrence time and transfer public ID are required");
        }
        if (!"TRANSFER_COMPLETED".equals(eventType)) {
            throw new IllegalArgumentException("Event type must be TRANSFER_COMPLETED");
        }
        if (!aggregateId.equals(transferPublicId)) {
            throw new IllegalArgumentException("Aggregate ID must match the transfer public ID");
        }
    }
}
