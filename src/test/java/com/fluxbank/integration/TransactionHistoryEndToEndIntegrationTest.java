package com.fluxbank.integration;

import com.fluxbank.outbox.application.OutboxClaimService;
import com.fluxbank.outbox.application.OutboxProcessor;
import com.fluxbank.outbox.application.OutboxRecoveryService;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import com.fluxbank.processed.repository.ProcessedEventRepository;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaPublisher;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@TestPropertySource(properties = {
        "kafka.consumer.enabled=true", "spring.kafka.listener.auto-startup=false",
        "spring.kafka.listener.poll-timeout=100ms", "outbox.retry.initial-delay=PT0.1S"})
class TransactionHistoryEndToEndIntegrationTest extends DatabaseIntegrationTestSupport {
    private static final String TOPIC = "fluxbank.transfer-events.end-to-end";
    private static final String GROUP = "history-end-to-end-" + UUID.randomUUID();
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    @Container
    private static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        properties.add("spring.kafka.consumer.group-id", () -> GROUP);
        properties.add("kafka.topics.transfer-events", () -> TOPIC);
        properties.add("kafka.topics.transfer-events-dlq", () -> TOPIC + ".DLT");
    }

    @Autowired private OutboxClaimService claims;
    @Autowired private OutboxProcessor processor;
    @Autowired private OutboxRecoveryService recovery;
    @Autowired private OutboxEventRepository events;
    @Autowired private ProcessedEventRepository markers;
    @Autowired private TransferCompletedKafkaPublisher publisher;
    @Autowired private KafkaListenerEndpointRegistry registry;
    private AdminClient admin;
    private MessageListenerContainer listener;

    @BeforeEach
    void startConsumerAtEndOfTopic() throws Exception {
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
        listener = registry.getListenerContainer("transaction-history-transfer-events");
        assertNotNull(listener);
        assertFalse(listener.isRunning());
        for (String topic : List.of(TOPIC, TOPIC + ".DLT")) {
            try {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof TopicExistsException)) throw failure;
            }
        }
        admin.alterConsumerGroupOffsets(GROUP, Map.of(PARTITION, new OffsetAndMetadata(endOffset())))
                .all().get(20, TimeUnit.SECONDS);
        listener.start();
        await().atMost(Duration.ofSeconds(30)).until(() -> listener.getAssignedPartitions() != null
                && listener.getAssignedPartitions().contains(PARTITION));
    }

    @AfterEach
    void stopConsumerBeforeCommittedCleanup() {
        if (listener != null) listener.stop();
        if (admin != null) admin.close(Duration.ofSeconds(5));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedTransferTravelsThroughOutboxAndKafkaAndDuplicateIsHarmless(boolean external) throws Exception {
        Fixture f = fixture("1000", "500");
        UUID transfer = complete(f, external);
        UUID event = (UUID) outbox(transfer).get(0).get("public_id");
        assertEquals("COMPLETED", state(transfer).get("status"));
        assertEquals(OutboxStatus.PENDING, events.findByPublicId(event).orElseThrow().getStatus());
        assertFalse(markers.existsById(event));
        assertTrue(history(transfer).isEmpty());

        assertEquals(List.of(event), claims.claimBatch(20));
        processor.process(event);
        awaitConsumed();

        assertEquals(OutboxStatus.PROCESSED, events.findByPublicId(event).orElseThrow().getStatus());
        assertNotNull(events.findByPublicId(event).orElseThrow().getProcessedAt());
        assertHistory(f, transfer, event, external);
        var originalHistory = history(transfer);
        // Real duplicate Kafka delivery with the original durable event ID.
        publisher.publish(events.findByPublicId(event).orElseThrow());
        awaitConsumed();
        assertEquals(originalHistory, history(transfer));
        assertEquals(1, markers.count());
        assertBalance(f.source(), "800", "0", "800");
        assertBalance(f.destination(), external ? "500" : "700", "0", external ? "500" : "700");
    }

    @Test
    void acknowledgedPublicationWithoutOutboxCompletionIsRecoveredAndRedeliveredIdempotently() throws Exception {
        Fixture f = fixture("1000", "500");
        UUID transfer = complete(f, false);
        UUID event = claims.claimBatch(1).get(0);
        // Reproduce the crash window: broker acknowledges, but the worker never records PROCESSED.
        publisher.publish(events.findByPublicId(event).orElseThrow());
        awaitConsumed();
        assertHistory(f, transfer, event, false);
        var originalHistory = history(transfer);
        assertEquals(OutboxStatus.PROCESSING, events.findByPublicId(event).orElseThrow().getStatus());
        jdbc.update("UPDATE fluxbank.outbox_event SET claimed_at=CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE public_id=?", event);

        assertEquals(List.of(event), recovery.recoverStaleBatch(20));
        assertEquals(1, events.findByPublicId(event).orElseThrow().getAttempts());
        assertEquals(OutboxStatus.PENDING, events.findByPublicId(event).orElseThrow().getStatus());
        assertNotNull(events.findByPublicId(event).orElseThrow().getNextAttemptAt());
        await().atMost(Duration.ofSeconds(10)).until(() -> claims.claimBatch(20).equals(List.of(event)));
        processor.process(event);
        awaitConsumed();

        assertEquals(OutboxStatus.PROCESSED, events.findByPublicId(event).orElseThrow().getStatus());
        assertEquals(originalHistory, history(transfer));
        assertEquals(1, markers.count());
        assertBalance(f.source(), "800", "0", "800");
        assertEquals("COMPLETED", state(transfer).get("status"));
    }

    private UUID complete(Fixture f, boolean external) {
        UUID transfer = external ? externalPending(f, "200") : pending(f, "200");
        if (external) finalization.complete(transfer);
        else process(f, transfer);
        return transfer;
    }

    private void assertHistory(Fixture f, UUID transfer, UUID event, boolean external) {
        assertTrue(markers.existsById(event));
        assertEquals(1, markers.count());
        var rows = history(transfer);
        assertEquals(external ? 1 : 2, rows.size());
        for (var row : rows) {
            boolean debit = "DEBIT".equals(row.get("direction"));
            assertTrue(debit || "CREDIT".equals(row.get("direction")));
            assertEquals(debit ? f.source().getPublicId() : f.destination().getPublicId(), row.get("account_public_id"));
            assertEquals("TRANSFER", row.get("type"));
            assertEquals(0, new BigDecimal("200").compareTo((BigDecimal) row.get("amount")));
            assertEquals("EUR", row.get("currency"));
        }
        assertEquals(1, rows.stream().filter(row -> "DEBIT".equals(row.get("direction"))).count());
        assertEquals(external ? 0 : 1, rows.stream().filter(row -> "CREDIT".equals(row.get("direction"))).count());
    }

    private long endOffset() throws Exception {
        return admin.listOffsets(Map.of(PARTITION, OffsetSpec.latest())).all()
                .get(10, TimeUnit.SECONDS).get(PARTITION).offset();
    }

    private void awaitConsumed() throws Exception {
        long end = endOffset();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(PARTITION);
            assertNotNull(committed);
            assertTrue(committed.offset() >= end);
        });
    }
}
