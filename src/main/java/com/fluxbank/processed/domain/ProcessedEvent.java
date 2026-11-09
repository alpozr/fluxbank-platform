package com.fluxbank.processed.domain;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_event")
@EntityListeners(AuditingEntityListener.class)
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 255)
    private String eventType;

    @CreatedDate
    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
    }

    public static ProcessedEvent create(UUID eventId, String eventType) {
        if (eventId == null) {
            throw new IllegalArgumentException("Event ID is required");
        }
        if (eventType == null || eventType.isBlank() || eventType.length() > 255) {
            throw new IllegalArgumentException("Event type must contain between 1 and 255 characters");
        }
        ProcessedEvent event = new ProcessedEvent();
        event.eventId = eventId;
        event.eventType = eventType;
        return event;
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    @Override
    @Transient
    public boolean isNew() {
        // The externally assigned UUID alone cannot distinguish a new marker.
        return processedAt == null;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
