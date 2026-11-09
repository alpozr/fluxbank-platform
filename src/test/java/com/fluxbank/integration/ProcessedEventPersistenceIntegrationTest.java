package com.fluxbank.integration;

import com.fluxbank.processed.domain.ProcessedEvent;
import com.fluxbank.processed.repository.ProcessedEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessedEventPersistenceIntegrationTest extends PostgresIntegrationTest {
    @Autowired private ProcessedEventRepository events;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @Transactional
    void persistsAndReloadsOriginalEventIdentityTypeAndProcessingTimestamp() {
        UUID eventId = UUID.randomUUID();
        ProcessedEvent marker = ProcessedEvent.create(eventId, "TRANSFER_COMPLETED");
        assertTrue(marker.isNew());

        events.saveAndFlush(marker);
        entityManager.clear();
        ProcessedEvent stored = events.findById(eventId).orElseThrow();

        assertEquals(eventId, stored.getEventId());
        assertEquals("TRANSFER_COMPLETED", stored.getEventType());
        assertNotNull(stored.getProcessedAt());
        assertFalse(stored.isNew());
    }

    @Test
    void duplicateEventIdIsRejectedRatherThanMergedIntoExistingMarker() {
        UUID eventId = UUID.randomUUID();
        events.saveAndFlush(ProcessedEvent.create(eventId, "TRANSFER_COMPLETED"));
        var originalTimestamp = events.findById(eventId).orElseThrow().getProcessedAt();
        try {
            // Each repository call has its own transaction. A rejected insert must
            // not poison the transaction used to verify the original committed row.
            var failure = assertThrows(DataIntegrityViolationException.class, () ->
                    events.saveAndFlush(ProcessedEvent.create(eventId, "OTHER_EVENT")));
            boolean uniqueViolation = false;
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    uniqueViolation = true;
                }
            }
            assertTrue(uniqueViolation, "PostgreSQL must reject the duplicate primary key");
            ProcessedEvent original = events.findById(eventId).orElseThrow();
            assertEquals("TRANSFER_COMPLETED", original.getEventType());
            assertEquals(originalTimestamp, original.getProcessedAt());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM fluxbank.processed_event WHERE event_id=?", Integer.class, eventId));
        } finally {
            events.deleteById(eventId);
        }
    }

    @Test
    void flywayCreatesPrimaryKeyOnEventId() {
        assertEquals("PRIMARY KEY (event_id)", jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conrelid='fluxbank.processed_event'::regclass
                  AND conname='pk_processed_event' AND contype='p'
                """, String.class));
    }
}
