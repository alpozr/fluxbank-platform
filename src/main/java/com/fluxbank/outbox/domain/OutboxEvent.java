package com.fluxbank.outbox.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
@EntityListeners(AuditingEntityListener.class)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 255)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    protected OutboxEvent() {
    }

    public static OutboxEvent create(String eventType, UUID aggregateId, JsonNode payload) {
        if (eventType == null || eventType.isBlank() || eventType.length() > 255) {
            throw new IllegalArgumentException("Event type must contain between 1 and 255 characters");
        }
        if (aggregateId == null) {
            throw new IllegalArgumentException("Aggregate ID is required");
        }
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            throw new IllegalArgumentException("Payload is required");
        }

        OutboxEvent event = new OutboxEvent();
        event.publicId = UUID.randomUUID();
        event.eventType = eventType;
        event.aggregateId = aggregateId;
        event.payload = payload.deepCopy();
        event.status = OutboxStatus.PENDING;
        event.attempts = 0;
        return event;
    }

    public void claim() {
        if (status != OutboxStatus.PENDING) {
            throw new IllegalStateException("Only pending outbox events can be claimed");
        }
        status = OutboxStatus.PROCESSING;
        claimedAt = Instant.now();
    }

    public void markProcessed() {
        if (status != OutboxStatus.PENDING && status != OutboxStatus.PROCESSING) {
            throw new IllegalStateException("Only pending or processing outbox events can be processed");
        }
        status = OutboxStatus.PROCESSED;
        processedAt = Instant.now();
        nextAttemptAt = null;
        lastError = null;
    }

    public boolean isEligibleForProcessing(Instant now) {
        return status == OutboxStatus.PROCESSING || (status == OutboxStatus.PENDING
                && (nextAttemptAt == null || !nextAttemptAt.isAfter(now)));
    }

    public void recordFailure(boolean retryable, int maxAttempts, Instant retryAt, String error) {
        if (status != OutboxStatus.PENDING && status != OutboxStatus.PROCESSING) {
            throw new IllegalStateException("Only pending or processing events can record a failure");
        }
        if (maxAttempts <= 0 || retryAt == null || error == null || error.isBlank()) {
            throw new IllegalArgumentException("Retry policy and sanitized failure information are required");
        }
        attempts++;
        lastError = error;
        if (retryable && attempts < maxAttempts) {
            status = OutboxStatus.PENDING;
            nextAttemptAt = retryAt;
            claimedAt = null;
        } else {
            status = OutboxStatus.FAILED;
            nextAttemptAt = null;
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public JsonNode getPayload() {
        return payload.deepCopy();
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}
