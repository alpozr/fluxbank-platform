package com.fluxbank.transfer.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.transfer.domain.TransferType;
import com.fluxbank.transfer.application.TransferCompletedEvent;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferCompletedKafkaPublisherTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, TransferCompletedKafkaEvent> template = mock(KafkaTemplate.class);
    private final TransferCompletedKafkaPublisher publisher = new TransferCompletedKafkaPublisher(template, "configured.transfer-events.v1", new ObjectMapper());
    private final UUID transferId = UUID.randomUUID();
    private final Instant createdAt = Instant.parse("2026-10-28T12:34:56Z");

    @ParameterizedTest
    @EnumSource(TransferType.class)
    void publishesStoredBusinessSnapshotAndPreservesIdentityAcrossAttempts(TransferType type) {
        UUID source = UUID.randomUUID();
        UUID destination = type == TransferType.INTERNAL ? UUID.randomUUID() : null;
        var snapshot = new TransferCompletedEvent(transferId, type, source, destination,
                new BigDecimal("987.6543"), CurrencyCode.EUR);
        OutboxEvent event = timestamped(OutboxEvent.create("TRANSFER_COMPLETED", transferId,
                new ObjectMapper().valueToTree(snapshot)));
        when(template.send(anyString(), anyString(), any(TransferCompletedKafkaEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(event);
        publisher.publish(event);

        var captor = ArgumentCaptor.forClass(TransferCompletedKafkaEvent.class);
        verify(template, times(2)).send(eq("configured.transfer-events.v1"), eq(transferId.toString()), captor.capture());
        var sent = captor.getAllValues().get(0);
        assertEquals(event.getPublicId(), sent.eventId());
        assertEquals(createdAt, sent.occurredAt());
        assertEquals(type, sent.transferType());
        assertEquals(source, sent.sourceAccountPublicId());
        assertEquals(destination, sent.destinationAccountPublicId());
        assertEquals(new BigDecimal("987.6543"), sent.amount());
        assertEquals(CurrencyCode.EUR, sent.currency());
        assertEquals(sent, captor.getAllValues().get(1));
        assertUnchanged(event);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"transferType\":\"INTERNAL\"}",
            "{\"transferType\":null}",
            "{\"amount\":-1}",
            "{\"currency\":\"UNKNOWN\"}",
            "{\"transferType\":\"UNKNOWN\"}"})
    void rejectsIncompleteOrInvalidSnapshotsBeforeSending(String fields) throws Exception {
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(fields);
        payload.put("transferPublicId", transferId.toString());
        OutboxEvent event = timestamped(OutboxEvent.create("TRANSFER_COMPLETED", transferId, payload));
        assertThrows(IllegalArgumentException.class, () -> publisher.publish(event));
        verifyNoInteractions(template);
    }

    @Test
    void mapsStableOutboxIdentityAndFieldsToConfiguredTopicWithTransferKey() {
        OutboxEvent event = event();
        when(template.send(anyString(), anyString(), any(TransferCompletedKafkaEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(event);
        publisher.publish(event);

        var captor = ArgumentCaptor.forClass(TransferCompletedKafkaEvent.class);
        verify(template, times(2)).send(eq("configured.transfer-events.v1"), eq(transferId.toString()), captor.capture());
        TransferCompletedKafkaEvent sent = captor.getAllValues().get(0);
        assertEquals(event.getPublicId(), sent.eventId());
        assertEquals("TRANSFER_COMPLETED", sent.eventType());
        assertEquals(transferId, sent.aggregateId());
        assertEquals(createdAt, sent.occurredAt());
        assertEquals(transferId, sent.transferPublicId());
        assertEquals(sent, captor.getAllValues().get(1), "Publishing again must preserve event identity and occurrence time");
        assertUnchanged(event);
    }

    @Test
    @SuppressWarnings("unchecked")
    void waitsOnTheKafkaSendResultBeforeReturning() {
        OutboxEvent event = event();
        CompletableFuture<SendResult<String, TransferCompletedKafkaEvent>> result = mock(CompletableFuture.class);
        when(template.send(anyString(), anyString(), any(TransferCompletedKafkaEvent.class))).thenReturn(result);

        publisher.publish(event);

        verify(result).join();
        assertUnchanged(event);
    }

    @Test
    void asynchronousSendFailurePropagatesWithoutRetryOrStateMutation() {
        OutboxEvent event = event();
        var failure = new KafkaException("Broker rejected send");
        when(template.send(anyString(), anyString(), any(TransferCompletedKafkaEvent.class)))
                .thenReturn(CompletableFuture.failedFuture(failure));

        var thrown = assertThrows(CompletionException.class, () -> publisher.publish(event));

        assertSame(failure, thrown.getCause());
        verify(template).send(anyString(), anyString(), any(TransferCompletedKafkaEvent.class));
        assertUnchanged(event);
    }

    @Test
    void synchronousSendFailurePropagatesWithoutRetry() {
        OutboxEvent event = event();
        var failure = new KafkaException("Send failed");
        when(template.send(anyString(), anyString(), any(TransferCompletedKafkaEvent.class))).thenThrow(failure);

        assertSame(failure, assertThrows(KafkaException.class, () -> publisher.publish(event)));

        verify(template).send(anyString(), anyString(), any(TransferCompletedKafkaEvent.class));
        assertUnchanged(event);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"transferPublicId\":null}", "{\"transferPublicId\":42}", "{\"transferPublicId\":\"invalid\"}"})
    void invalidPayloadIsRejectedBeforeSending(String json) throws Exception {
        OutboxEvent event = timestamped(OutboxEvent.create("TRANSFER_COMPLETED", transferId, new ObjectMapper().readTree(json)));

        assertThrows(IllegalArgumentException.class, () -> publisher.publish(event));

        verifyNoInteractions(template);
        assertUnchanged(event);
    }

    @Test
    void unsupportedEventIsRejectedBeforeSending() {
        OutboxEvent event = timestamped(OutboxEvent.create("UNKNOWN", transferId,
                JsonNodeFactory.instance.objectNode().put("transferPublicId", transferId.toString())));

        assertThrows(IllegalArgumentException.class, () -> publisher.publish(event));
        verifyNoInteractions(template);
    }

    private OutboxEvent event() {
        return timestamped(OutboxEvent.create("TRANSFER_COMPLETED", transferId,
                JsonNodeFactory.instance.objectNode().put("transferPublicId", transferId.toString())));
    }

    private OutboxEvent timestamped(OutboxEvent event) {
        // createdAt is normally assigned by JPA auditing when the outbox row is persisted.
        OutboxEvent timestamped = spy(event);
        doReturn(createdAt).when(timestamped).getCreatedAt();
        return timestamped;
    }

    private void assertUnchanged(OutboxEvent event) {
        assertEquals(OutboxStatus.PENDING, event.getStatus());
        assertEquals(0, event.getAttempts());
        assertNull(event.getProcessedAt());
        assertNull(event.getLastError());
        assertNull(event.getNextAttemptAt());
    }
}
