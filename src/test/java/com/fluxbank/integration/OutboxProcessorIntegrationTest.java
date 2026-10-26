package com.fluxbank.integration;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fluxbank.outbox.application.OutboxProcessor;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OutboxProcessorIntegrationTest extends DatabaseIntegrationTestSupport {
    @Autowired
    private OutboxProcessor processor;

    @Autowired
    private OutboxEventRepository events;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void historyAndProcessedStateCommitInTheSameTransaction(boolean external) {
        Fixture f = fixture("1000", "500");
        UUID transferId = complete(f, external);
        UUID eventId = eventId(transferId);
        assertTrue(history(transferId).isEmpty());
        long financialXid = jdbc.queryForObject(
                "SELECT xmin::text::bigint FROM fluxbank.transfers WHERE public_id=?", Long.class, transferId);

        processor.process(eventId);

        var event = outbox(transferId).get(0);
        assertEquals("PROCESSED", event.get("status"));
        assertNotNull(event.get("processed_at"));
        assertEquals(0, event.get("attempts"));
        assertNull(event.get("last_error"));
        var movements = history(transferId);
        assertEquals(external ? 1 : 2, movements.size());
        for (var movement : movements) {
            assertEquals(event.get("transaction_id"), movement.get("transaction_id"),
                    "History rows and outbox status must have the same PostgreSQL transaction ID");
            assertNotEquals(financialXid, movement.get("transaction_id"));
            assertEquals("TRANSFER", movement.get("type"));
            assertEquals("EUR", movement.get("currency"));
            assertEquals(0, new BigDecimal("200").compareTo((BigDecimal) movement.get("amount")));
            boolean source = f.source().getPublicId().equals(movement.get("account_public_id"));
            assertEquals(source ? "DEBIT" : "CREDIT", movement.get("direction"));
            if (!source) {
                assertFalse(external);
                assertEquals(f.destination().getPublicId(), movement.get("account_public_id"));
            }
        }
        assertTrue(movements.stream().anyMatch(m -> f.source().getPublicId().equals(m.get("account_public_id"))));
        assertBalance(f.source(), "800", "0", "800");
        assertBalance(f.destination(), external ? "500" : "700", "0", external ? "500" : "700");
        assertEquals("COMPLETED", state(transferId).get("status"));
    }

    @Test
    void replayAndAnotherEventForSameTransferDoNotDuplicateHistory() {
        Fixture f = fixture("1000", "500");
        UUID transferId = complete(f, false);
        UUID original = eventId(transferId);
        processor.process(original);
        var references = history(transferId).stream().map(m -> m.get("reference_number")).toList();
        var processedAt = events.findByPublicId(original).orElseThrow().getProcessedAt();

        processor.process(original);
        UUID duplicate = tx(() -> events.saveAndFlush(OutboxEvent.create("TRANSFER_COMPLETED", transferId,
                JsonNodeFactory.instance.objectNode().put("transferPublicId", transferId.toString()))).getPublicId());
        processor.process(duplicate);

        assertEquals(references, history(transferId).stream().map(m -> m.get("reference_number")).toList());
        assertEquals(processedAt, events.findByPublicId(original).orElseThrow().getProcessedAt());
        assertTrue(outbox(transferId).stream().allMatch(e -> "PROCESSED".equals(e.get("status"))));
        assertEquals(2, history(transferId).size());
    }

    @Test
    void permanentCreditInsertFailureRollsBackDebitAndMarksEventFailed() {
        Fixture f = fixture("1000", "500");
        UUID transferId = complete(f, false);
        UUID eventId = eventId(transferId);
        // Reject the second history insert using real PostgreSQL, without production failure hooks.
        jdbc.execute("ALTER TABLE fluxbank.transactions ADD CONSTRAINT test_reject_credit CHECK (direction <> 'CREDIT')");
        try {
            var failure = assertThrows(DataIntegrityViolationException.class, () -> processor.process(eventId));
            assertSqlState("23514", failure);
            assertFailedWithoutHistory(transferId);
        } finally {
            jdbc.execute("ALTER TABLE fluxbank.transactions DROP CONSTRAINT test_reject_credit");
        }
        processor.process(eventId); // A non-transient integrity failure is terminal.
        assertTrue(history(transferId).isEmpty());
        assertEquals("FAILED", outbox(transferId).get(0).get("status"));
    }

    @Test
    void outboxStatusUpdateFailureRollsBackAllHistoryInserts() {
        Fixture f = fixture("1000", "500");
        UUID transferId = complete(f, false);
        UUID eventId = eventId(transferId);
        jdbc.execute("ALTER TABLE fluxbank.outbox_event ADD CONSTRAINT test_reject_processed CHECK (status <> 'PROCESSED')");
        try {
            var failure = assertThrows(DataIntegrityViolationException.class, () -> processor.process(eventId));
            assertSqlState("23514", failure);
            assertFailedWithoutHistory(transferId);
        } finally {
            jdbc.execute("ALTER TABLE fluxbank.outbox_event DROP CONSTRAINT test_reject_processed");
        }
    }

    private UUID complete(Fixture f, boolean external) {
        UUID transferId = external ? externalPending(f, "200") : pending(f, "200");
        if (external) {
            finalization.complete(transferId);
        } else {
            process(f, transferId);
        }
        return transferId;
    }

    private UUID eventId(UUID transferId) {
        return (UUID) outbox(transferId).get(0).get("public_id");
    }

    private void assertFailedWithoutHistory(UUID transferId) {
        assertTrue(history(transferId).isEmpty());
        assertEquals("FAILED", outbox(transferId).get(0).get("status"));
        assertNull(outbox(transferId).get(0).get("processed_at"));
        assertEquals("COMPLETED", state(transferId).get("status"));
        assertNotNull(state(transferId).get("completed_at"));
    }
}
