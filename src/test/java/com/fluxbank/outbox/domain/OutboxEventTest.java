package com.fluxbank.outbox.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OutboxEventTest {

    @Test
    void retryConsumesBudgetAndSuccessClearsRetryMetadata() {
        OutboxEvent event = OutboxEvent.create("Example", UUID.randomUUID(), JsonNodeFactory.instance.objectNode());
        event.claim();
        var retryAt = java.time.Instant.now().plusSeconds(30);
        event.recordFailure(true, 3, retryAt, "Sanitized failure");
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(1, event.getAttempts());
        assertEquals(retryAt, event.getNextAttemptAt());
        assertNull(event.getClaimedAt());
        assertEquals("Sanitized failure", event.getLastError());
        assertFalse(event.isEligibleForProcessing(retryAt.minusNanos(1)));
        assertTrue(event.isEligibleForProcessing(retryAt));
        event.claim();
        event.markProcessed();
        assertEquals(OutboxStatus.PROCESSED, event.getStatus());
        assertEquals(1, event.getAttempts());
        assertNull(event.getNextAttemptAt());
        assertNull(event.getLastError());
    }

    @Test
    void maximumBudgetAndPermanentFailureAreTerminal() {
        var now = java.time.Instant.now();
        OutboxEvent event = OutboxEvent.create("Example", UUID.randomUUID(), JsonNodeFactory.instance.objectNode());
        event.recordFailure(true, 2, now, "Transient failure");
        event.recordFailure(true, 2, now, "Transient failure");
        assertEquals(2, event.getAttempts());
        assertEquals(OutboxStatus.FAILED, event.getStatus());
        assertNull(event.getNextAttemptAt());
        assertFalse(event.isEligibleForProcessing(now));
        assertThrows(IllegalStateException.class, event::claim);
        assertThrows(IllegalStateException.class, event::markProcessed);
        assertThrows(IllegalStateException.class, () -> event.recordFailure(true, 2, now, "Failure"));

        OutboxEvent permanent = OutboxEvent.create("Example", UUID.randomUUID(), JsonNodeFactory.instance.objectNode());
        permanent.recordFailure(false, 5, now, "Permanent failure");
        assertEquals(1, permanent.getAttempts());
        assertEquals(OutboxStatus.FAILED, permanent.getStatus());
    }

    @Test
    void claimsOnlyOnceAndThenProcessesWithoutChangingClaimTimestamp() {
        OutboxEvent event = OutboxEvent.create("Example", UUID.randomUUID(), JsonNodeFactory.instance.objectNode());
        assertNull(event.getClaimedAt());

        event.claim();

        assertEquals(OutboxStatus.PROCESSING, event.getStatus());
        assertNotNull(event.getClaimedAt());
        var claimedAt = event.getClaimedAt();
        assertThrows(IllegalStateException.class, event::claim);
        assertEquals(claimedAt, event.getClaimedAt());
        event.markProcessed();
        assertEquals(OutboxStatus.PROCESSED, event.getStatus());
        assertNotNull(event.getProcessedAt());
        assertEquals(claimedAt, event.getClaimedAt());
        assertThrows(IllegalStateException.class, event::claim);
        assertEquals(0, event.getAttempts());
    }

    @Test
    void marksPendingEventProcessedAndPreservesTimestampOnRejectedTransition() {
        OutboxEvent event = OutboxEvent.create("Example", UUID.randomUUID(), JsonNodeFactory.instance.objectNode());

        event.markProcessed();

        assertEquals(OutboxStatus.PROCESSED, event.getStatus());
        assertNotNull(event.getProcessedAt());
        var processedAt = event.getProcessedAt();
        assertThrows(IllegalStateException.class, event::markProcessed);
        assertEquals(processedAt, event.getProcessedAt());
        assertEquals(OutboxStatus.PROCESSED, event.getStatus());
        assertEquals(0, event.getAttempts());
        assertNull(event.getLastError());
    }

    @Test
    void createsPendingEventWithInitialProcessingState() {
        UUID aggregateId = UUID.randomUUID();
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("transferPublicId", aggregateId.toString());

        OutboxEvent event = OutboxEvent.create("TransferCompleted", aggregateId, payload);

        assertNotNull(event.getPublicId());
        assertEquals("TransferCompleted", event.getEventType());
        assertEquals(aggregateId, event.getAggregateId());
        assertEquals(payload, event.getPayload());
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttempts());
        assertNull(event.getProcessedAt());
        assertNull(event.getLastError());
        assertNotEquals(event.getPublicId(), OutboxEvent.create("TransferCompleted", aggregateId, payload).getPublicId());
    }

    @Test
    void retainsPayloadSnapshotWhenCallerModifiesJson() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("value", "original");
        OutboxEvent event = OutboxEvent.create("Example", UUID.randomUUID(), payload);

        payload.put("value", "changed");
        ((ObjectNode) event.getPayload()).put("value", "also changed");

        assertEquals("original", event.getPayload().get("value").asText());
    }

    @Test
    void rejectsMissingBlankAndOversizedEventTypes() {
        for (String type : new String[]{null, "", " \t\n", "x".repeat(256)}) {
            assertThrows(IllegalArgumentException.class, () ->
                    OutboxEvent.create(type, UUID.randomUUID(), JsonNodeFactory.instance.objectNode()));
        }
    }

    @Test
    void rejectsMissingAggregateId() {
        assertThrows(IllegalArgumentException.class, () ->
                OutboxEvent.create("Example", null, JsonNodeFactory.instance.objectNode()));
    }

    @Test
    void rejectsMissingPayload() {
        for (JsonNode payload : new JsonNode[]{null, NullNode.instance, MissingNode.getInstance()}) {
            assertThrows(IllegalArgumentException.class, () ->
                    OutboxEvent.create("Example", UUID.randomUUID(), payload));
        }
    }
}
