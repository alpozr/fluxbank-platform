package com.fluxbank.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.transfer.domain.TransferType;
import com.fluxbank.transfer.application.TransferCompletedEvent;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaEvent;
import java.math.BigDecimal;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaPublisher;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class KafkaPublisherIntegrationTest extends PostgresIntegrationTest {
    @Container
    private static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired private TransferCompletedKafkaPublisher publisher;
    @Autowired private OutboxEventRepository events;
    @Autowired private ObjectMapper objectMapper;
    @Value("${kafka.topics.transfer-events}") private String topic;

    @Test
    void realBrokerReceivesTheOriginalOutboxIdentityKeyAndJsonContract() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "publisher-test-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            TopicPartition partition = new TopicPartition(topic, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));

            // Verify both enriched movement types and an existing ID-only outbox row.
            for (TransferType type : new TransferType[]{null, TransferType.INTERNAL, TransferType.EXTERNAL}) {
                UUID transferId = UUID.randomUUID();
                UUID sourceId = UUID.randomUUID();
                UUID destinationId = type == TransferType.INTERNAL ? UUID.randomUUID() : null;
                BigDecimal amount = new BigDecimal("987654321012345.6789");
                JsonNode payload = type == null ? objectMapper.createObjectNode().put("transferPublicId", transferId.toString())
                        : objectMapper.valueToTree(new TransferCompletedEvent(transferId, type, sourceId, destinationId, amount, CurrencyCode.EUR));
                OutboxEvent original = events.saveAndFlush(OutboxEvent.create("TRANSFER_COMPLETED", transferId, payload));
                // Reload JSONB and timestamp using actual PostgreSQL precision.
                OutboxEvent stored = events.findByPublicId(original.getPublicId()).orElseThrow();
                try {
                    publisher.publish(stored);

                    ConsumerRecord<String, String> record = receive(consumer);
                    assertEquals(topic, record.topic());
                    assertEquals(transferId.toString(), record.key());
                    var json = objectMapper.readTree(record.value());
                    assertEquals(type == null ? 5 : type == TransferType.INTERNAL ? 10 : 9, json.size());
                    assertEquals(original.getPublicId().toString(), json.get("eventId").asText());
                    assertEquals("TRANSFER_COMPLETED", json.get("eventType").asText());
                    assertEquals(transferId.toString(), json.get("aggregateId").asText());
                    assertEquals(transferId.toString(), json.get("transferPublicId").asText());
                    assertEquals(stored.getCreatedAt(), Instant.parse(json.get("occurredAt").asText()));
                    if (type != null) {
                        var received = objectMapper.readValue(record.value(), TransferCompletedKafkaEvent.class);
                        assertEquals(type, received.transferType());
                        assertEquals(sourceId, received.sourceAccountPublicId());
                        assertEquals(destinationId, received.destinationAccountPublicId());
                        assertEquals(amount, received.amount());
                        assertEquals(CurrencyCode.EUR, received.currency());
                        assertTrue(json.get("amount").isNumber());
                    }
                    assertEquals(OutboxStatus.PENDING, events.findByPublicId(original.getPublicId()).orElseThrow().getStatus());
                } finally {
                    events.deleteById(original.getId());
                }
            }
        }
    }

    private ConsumerRecord<String, String> receive(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            var records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        return fail("Kafka did not deliver the acknowledged record within 30 seconds");
    }
}
