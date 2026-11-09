package com.fluxbank.transfer.infrastructure.kafka;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.transfer.application.TransferCompletedEvent;
import com.fluxbank.transfer.domain.TransferType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferCompletedKafkaEvent(
        UUID eventId,
        String eventType,
        UUID aggregateId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt,
        UUID transferPublicId,
        @JsonInclude(JsonInclude.Include.NON_NULL) TransferType transferType,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID sourceAccountPublicId,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID destinationAccountPublicId,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal amount,
        @JsonInclude(JsonInclude.Include.NON_NULL) CurrencyCode currency) {

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
        new TransferCompletedEvent(transferPublicId, transferType, sourceAccountPublicId,
                destinationAccountPublicId, amount, currency);
    }

    public TransferCompletedKafkaEvent(UUID eventId, String eventType, UUID aggregateId,
                                       Instant occurredAt, UUID transferPublicId) {
        this(eventId, eventType, aggregateId, occurredAt, transferPublicId, null, null, null, null, null);
    }
}
