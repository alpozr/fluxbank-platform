package com.fluxbank.transfer.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.transfer.domain.TransferType;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransferCompletedKafkaEventTest {
    private final UUID eventId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();
    private final Instant occurredAt = Instant.parse("2026-10-28T12:34:56Z");

    @ParameterizedTest
    @EnumSource(TransferType.class)
    void serializesImmutableBusinessSnapshotWithPositiveAmount(TransferType type) throws Exception {
        UUID source = UUID.randomUUID();
        UUID destination = type == TransferType.INTERNAL ? UUID.randomUUID() : null;
        var event = new TransferCompletedKafkaEvent(eventId, "TRANSFER_COMPLETED", transferId, occurredAt,
                transferId, type, source, destination, new BigDecimal("12.3456"), CurrencyCode.EUR);
        byte[] bytes;
        try (var serializer = new JsonSerializer<TransferCompletedKafkaEvent>()) {
            bytes = serializer.serialize("topic", event);
        }
        var mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.readTree(bytes);
        assertEquals(type.name(), json.get("transferType").asText());
        assertEquals(source.toString(), json.get("sourceAccountPublicId").asText());
        if (destination == null) assertFalse(json.has("destinationAccountPublicId"));
        else assertEquals(destination.toString(), json.get("destinationAccountPublicId").asText());
        assertEquals(0, event.amount().compareTo(json.get("amount").decimalValue()));
        assertEquals("EUR", json.get("currency").asText());
        assertEquals(event, mapper.readValue(bytes, TransferCompletedKafkaEvent.class));
    }

    @Test
    void acceptsLegacyJsonAndIgnoresUnknownAdditiveFields() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var legacy = new TransferCompletedKafkaEvent(eventId, "TRANSFER_COMPLETED", transferId, occurredAt, transferId);
        var json = mapper.valueToTree(legacy);
        assertEquals(5, json.size());
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).put("futureField", "ignored");
        assertEquals(legacy, mapper.treeToValue(json, TransferCompletedKafkaEvent.class));
    }

    @Test
    void rejectsInvalidNewSnapshotAccountSemanticsAndAmounts() {
        UUID source = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new TransferCompletedKafkaEvent(eventId, "TRANSFER_COMPLETED", transferId,
                occurredAt, transferId, TransferType.INTERNAL, source, null, BigDecimal.ONE, CurrencyCode.EUR));
        assertThrows(IllegalArgumentException.class, () -> new TransferCompletedKafkaEvent(eventId, "TRANSFER_COMPLETED", transferId,
                occurredAt, transferId, TransferType.EXTERNAL, source, UUID.randomUUID(), BigDecimal.ONE, CurrencyCode.EUR));
        assertThrows(IllegalArgumentException.class, () -> new TransferCompletedKafkaEvent(eventId, "TRANSFER_COMPLETED", transferId,
                occurredAt, transferId, TransferType.EXTERNAL, source, null, BigDecimal.ZERO, CurrencyCode.EUR));
    }

    @Test
    void kafkaSerializerProducesOnlyTheFiveContractFieldsWithoutJavaTypeHeaders() throws Exception {
        var event = new TransferCompletedKafkaEvent(eventId, "TRANSFER_COMPLETED", transferId, occurredAt, transferId);
        var headers = new RecordHeaders();
        byte[] bytes;
        try (var serializer = new JsonSerializer<TransferCompletedKafkaEvent>()) {
            serializer.configure(Map.of(JsonSerializer.ADD_TYPE_INFO_HEADERS, false), false);
            bytes = serializer.serialize("fluxbank.transfer-events.v1", headers, event);
        }

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.readTree(bytes);
        assertEquals(5, json.size());
        assertEquals(eventId.toString(), json.get("eventId").asText());
        assertEquals("TRANSFER_COMPLETED", json.get("eventType").asText());
        assertEquals(transferId.toString(), json.get("aggregateId").asText());
        assertEquals(occurredAt.toString(), json.get("occurredAt").asText());
        assertEquals(transferId.toString(), json.get("transferPublicId").asText());
        assertEquals(0, headers.toArray().length);
        assertEquals(event, mapper.readValue(bytes, TransferCompletedKafkaEvent.class));
    }

    @Test
    void rejectsMissingIdentityAndTimestampFields() {
        assertThrows(IllegalArgumentException.class, () -> new TransferCompletedKafkaEvent(null, "TRANSFER_COMPLETED", transferId, occurredAt, transferId));
        assertThrows(IllegalArgumentException.class, () -> new TransferCompletedKafkaEvent(eventId, "TRANSFER_COMPLETED", null, occurredAt, transferId));
        assertThrows(IllegalArgumentException.class, () -> new TransferCompletedKafkaEvent(eventId, "TRANSFER_COMPLETED", transferId, null, transferId));
        assertThrows(IllegalArgumentException.class, () -> new TransferCompletedKafkaEvent(eventId, "TRANSFER_COMPLETED", transferId, occurredAt, null));
    }

    @Test
    void rejectsOtherEventTypesAndMismatchedAggregate() {
        assertThrows(IllegalArgumentException.class, () -> new TransferCompletedKafkaEvent(eventId, null, transferId, occurredAt, transferId));
        assertThrows(IllegalArgumentException.class, () -> new TransferCompletedKafkaEvent(eventId, "UNKNOWN", transferId, occurredAt, transferId));
        assertThrows(IllegalArgumentException.class, () -> new TransferCompletedKafkaEvent(eventId, "TRANSFER_COMPLETED", UUID.randomUUID(), occurredAt, transferId));
    }
}
