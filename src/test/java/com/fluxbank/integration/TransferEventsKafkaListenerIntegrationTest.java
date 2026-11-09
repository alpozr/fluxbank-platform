package com.fluxbank.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxbank.processed.repository.ProcessedEventRepository;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import com.fluxbank.transaction.application.TransactionHistoryEventProcessor;
import com.fluxbank.transfer.application.TransferCompletedEvent;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaEvent;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.TransactionSystemException;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@TestPropertySource(properties = {
        "kafka.consumer.enabled=true", "kafka.consumer.max-retries=2", "kafka.consumer.retry-interval-ms=10",
        "spring.kafka.listener.auto-startup=false", "spring.kafka.listener.poll-timeout=100ms",
        "spring.kafka.producer.properties[max.block.ms]=1000",
        "spring.kafka.producer.properties[delivery.timeout.ms]=2000",
        "spring.kafka.producer.properties[request.timeout.ms]=1000"})
class TransferEventsKafkaListenerIntegrationTest extends DatabaseIntegrationTestSupport {
    private static final String TOPIC = "fluxbank.transfer-events.consumer-test";
    private static final String DLQ = TOPIC + ".DLT";
    private static final String GROUP = "transaction-history-test-" + UUID.randomUUID();
    private static final TopicPartition SOURCE = new TopicPartition(TOPIC, 0);
    @Container
    private static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> GROUP);
        registry.add("kafka.topics.transfer-events", () -> TOPIC);
        registry.add("kafka.topics.transfer-events-dlq", () -> DLQ);
    }

    @Autowired private KafkaTemplate<String, TransferCompletedKafkaEvent> template;
    @Autowired private KafkaListenerEndpointRegistry registry;
    @Autowired private DefaultErrorHandler errorHandler;
    @Autowired private ProcessedEventRepository markers;
    @Autowired private OutboxEventRepository outboxEvents;
    @Autowired private ObjectMapper mapper;
    @MockitoSpyBean private TransactionHistoryEventProcessor processor;
    private AdminClient admin;
    private MessageListenerContainer listener;

    @BeforeEach
    void prepareBrokerAndStoppedListener() throws Exception {
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
        listener = registry.getListenerContainer("transaction-history-transfer-events");
        assertNotNull(listener);
        assertFalse(listener.isRunning());
        ensureTopic(TOPIC);
        ensureTopic(DLQ);
        long end = admin.listOffsets(Map.of(SOURCE, OffsetSpec.latest())).all().get(20, TimeUnit.SECONDS).get(SOURCE).offset();
        admin.alterConsumerGroupOffsets(GROUP, Map.of(SOURCE, new OffsetAndMetadata(end))).all().get(20, TimeUnit.SECONDS);
        errorHandler.setRetryListeners((record, failure, attempt) -> { });
    }

    @AfterEach
    void stopListenerBeforeDatabaseCleanup() {
        if (listener != null) listener.stop();
        if (admin != null) admin.close(Duration.ofSeconds(5));
    }

    @Test
    void successAndDuplicateAdvanceOffsetsWithOneMarkerAndHistoryPair() throws Exception {
        var event = completedEvent();
        startListener();
        long first = send(event);
        awaitCommitted(first);
        assertEquals(1L, markers.count());
        assertEquals(2, history(event.transferPublicId()).size());
        long duplicate = send(event);
        awaitCommitted(duplicate);
        assertEquals(1L, markers.count());
        assertEquals(2, history(event.transferPublicId()).size());
        verify(processor, times(2)).process(any(TransferCompletedKafkaEvent.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void directOrNestedTemporaryFailureIsRetriedAndEventuallyCommitted(int wrapperDepth) throws Exception {
        var event = completedEvent();
        RuntimeException failure = new TransientDataAccessResourceException("temporary database outage");
        if (wrapperDepth >= 1) failure = new TransactionSystemException("transaction wrapper", failure);
        if (wrapperDepth >= 2) failure = new IllegalStateException("application wrapper", failure);
        doThrow(failure)
                .doCallRealMethod().when(processor).process(any(TransferCompletedKafkaEvent.class));
        startListener();
        long offset = send(event);
        awaitCommitted(offset);
        assertTrue(markers.existsById(event.eventId()));
        assertEquals(2, history(event.transferPublicId()).size());
        verify(processor, times(2)).process(any(TransferCompletedKafkaEvent.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invalidJsonOrIncompleteSnapshotGoesDirectlyToDlq(boolean malformed) throws Exception {
        UUID transfer = UUID.randomUUID();
        var legacy = new TransferCompletedKafkaEvent(UUID.randomUUID(), "TRANSFER_COMPLETED", transfer, Instant.now(), transfer);
        String key = transfer.toString();
        byte[] raw = "{broken-json".getBytes(StandardCharsets.UTF_8);
        AtomicInteger deliveries = new AtomicInteger();
        errorHandler.setRetryListeners((failed, error, attempt) -> deliveries.incrementAndGet());
        startListener();
        long offset = malformed ? sendRaw(key, raw) : send(legacy);
        var dead = receiveDlq(key);
        if (malformed) {
            assertArrayEquals(raw, dead.value());
            verifyNoInteractions(processor);
        } else {
            assertEquals(legacy.eventId().toString(), mapper.readTree(dead.value()).get("eventId").asText());
            verify(processor).process(any(TransferCompletedKafkaEvent.class));
        }
        assertEquals(TOPIC, new String(dead.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(), StandardCharsets.UTF_8));
        awaitCommitted(offset);
        assertEquals(1, deliveries.get(), "Malformed and legacy records must go directly to DLT without retries");
        assertEquals(0L, markers.count());
        assertEquals(0L, transactions.count());
    }

    @Test
    void exhaustedTemporaryFailureGoesToDlqAfterExactlyThreeDeliveries() throws Exception {
        var event = completedEvent();
        doThrow(new TransientDataAccessResourceException("temporary database outage"))
                .when(processor).process(any(TransferCompletedKafkaEvent.class));
        startListener();
        long offset = send(event);
        var dead = receiveDlq(event.transferPublicId().toString());
        assertEquals(event.eventId().toString(), mapper.readTree(dead.value()).get("eventId").asText());
        awaitCommitted(offset);
        verify(processor, times(3)).process(any(TransferCompletedKafkaEvent.class));
        assertEquals(0L, markers.count());
        assertTrue(history(event.transferPublicId()).isEmpty());
    }

    @Test
    void failedDlqPublicationLeavesSourceUncommittedUntilDlqBecomesAvailable() throws Exception {
        admin.deleteTopics(List.of(DLQ)).all().get(20, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).until(() -> !admin.listTopics().names().get(5, TimeUnit.SECONDS).contains(DLQ));
        CountDownLatch recoveryFailed = new CountDownLatch(1);
        errorHandler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int attempt) { }
            @Override
            public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
                recoveryFailed.countDown();
            }
        });
        String key = UUID.randomUUID().toString();
        byte[] raw = "{unparseable".getBytes(StandardCharsets.UTF_8);
        startListener();
        long offset = sendRaw(key, raw);
        assertTrue(recoveryFailed.await(30, TimeUnit.SECONDS), "The real DLQ send must fail before checking offsets");
        assertTrue(committedOffset() <= offset, "Failed DLQ publication must not commit past the source record");
        assertEquals(0L, markers.count());

        ensureTopic(DLQ);

        assertArrayEquals(raw, receiveDlq(key).value());
        awaitCommitted(offset);
        assertEquals(0L, transactions.count());
    }

    private void startListener() {
        listener.start();
        await().atMost(Duration.ofSeconds(30)).until(() -> listener.getAssignedPartitions() != null
                && listener.getAssignedPartitions().contains(SOURCE));
    }

    private long send(TransferCompletedKafkaEvent event) throws Exception {
        return template.send(TOPIC, event.transferPublicId().toString(), event).get(20, TimeUnit.SECONDS).getRecordMetadata().offset();
    }

    private long sendRaw(String key, byte[] value) throws Exception {
        try (var producer = new KafkaProducer<String, byte[]>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            return producer.send(new ProducerRecord<>(TOPIC, key, value)).get(20, TimeUnit.SECONDS).offset();
        }
    }

    private void awaitCommitted(long recordOffset) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertTrue(committedOffset() > recordOffset));
    }

    private long committedOffset() throws Exception {
        var offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(SOURCE);
        return offset == null ? -1 : offset.offset();
    }

    private ConsumerRecord<String, byte[]> receiveDlq(String key) {
        try (var consumer = new KafkaConsumer<String, byte[]>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlq-check-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            TopicPartition partition = new TopicPartition(DLQ, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if (key.equals(record.key())) return record;
                }
            }
            return fail("Expected DLQ record was not delivered");
        }
    }

    private void ensureTopic(String topic) throws Exception {
        try {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            if (!(failure.getCause() instanceof TopicExistsException)) throw failure;
        }
    }

    private TransferCompletedKafkaEvent completedEvent() throws Exception {
        Fixture f = fixture("1000", "500");
        UUID transfer = pending(f, "200");
        process(f, transfer);
        var event = outboxEvents.findByPublicId((UUID) outbox(transfer).get(0).get("public_id")).orElseThrow();
        var snapshot = mapper.treeToValue(event.getPayload(), TransferCompletedEvent.class);
        return new TransferCompletedKafkaEvent(event.getPublicId(), event.getEventType(), event.getAggregateId(), event.getCreatedAt(),
                snapshot.transferPublicId(), snapshot.transferType(), snapshot.sourceAccountPublicId(), snapshot.destinationAccountPublicId(),
                snapshot.amount(), snapshot.currency());
    }
}
