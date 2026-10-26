package com.fluxbank.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OutboxEventPersistenceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private OutboxEventRepository events;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Test
    @Transactional
    void persistsAndReloadsJsonbPayloadAndInitialState() throws Exception {
        UUID aggregateId = UUID.randomUUID();
        var payload = objectMapper.readTree("{\"amount\":12.34,\"metadata\":{\"confirmed\":true},\"tags\":[\"test\"]}");
        OutboxEvent event = events.saveAndFlush(OutboxEvent.create("Example", aggregateId, payload));
        Long id = event.getId();
        UUID publicId = event.getPublicId();
        entityManager.clear();

        OutboxEvent reloaded = events.findById(id).orElseThrow();
        assertEquals(publicId, reloaded.getPublicId());
        assertEquals("Example", reloaded.getEventType());
        assertEquals(aggregateId, reloaded.getAggregateId());
        assertEquals(payload, reloaded.getPayload());
        assertEquals(OutboxStatus.PENDING, reloaded.getStatus());
        assertEquals(0, reloaded.getAttempts());
        assertNotNull(reloaded.getCreatedAt());
        assertNull(reloaded.getProcessedAt());
        assertNull(reloaded.getLastError());
        assertEquals("jsonb", jdbc.queryForObject(
                "SELECT pg_typeof(payload)::text FROM fluxbank.outbox_event WHERE id = ?", String.class, id));
    }
}
