package com.fluxbank.transaction.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxbank.transaction.application.TransactionHistoryEventProcessor;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaEvent;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferEventsConsumerConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, JacksonAutoConfiguration.class))
            .withUserConfiguration(TransferEventsConsumerConfiguration.class, TransferEventsKafkaListener.class)
            .withBean(TransactionHistoryEventProcessor.class, () -> mock(TransactionHistoryEventProcessor.class))
            .withPropertyValues("spring.kafka.listener.auto-startup=false");

    @Test
    void configuresOwnGroupRecordAcknowledgementAndRawDlqSerialization() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(DefaultErrorHandler.class));
            var container = context.getBean(KafkaListenerEndpointRegistry.class)
                    .getListenerContainer("transaction-history-transfer-events");
            assertNotNull(container);
            assertEquals("fluxbank.transaction-history.v1", container.getGroupId());
            assertEquals(ContainerProperties.AckMode.RECORD, container.getContainerProperties().getAckMode());
            assertFalse(container.isRunning());
            var factory = context.getBean(DefaultKafkaProducerFactory.class);
            var serializer = factory.getValueSerializer();
            serializer.configure(factory.getConfigurationProperties(), false);
            byte[] malformed = "{invalid".getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(malformed, (byte[]) serializer.serialize("dlq", malformed));
            UUID id = UUID.randomUUID();
            var event = new TransferCompletedKafkaEvent(UUID.randomUUID(), "TRANSFER_COMPLETED", id, Instant.now(), id);
            var headers = new RecordHeaders();
            byte[] json = (byte[]) serializer.serialize("topic", headers, event);
            assertEquals(5, context.getBean(ObjectMapper.class).readTree(json).size());
            assertEquals(0, headers.toArray().length);
        });
    }

    @Test
    void listenerAndConsumerConfigurationCanBeDisabled() {
        runner.withPropertyValues("kafka.consumer.enabled=false").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(TransferEventsKafkaListener.class).isEmpty());
            assertTrue(context.getBeansOfType(DefaultErrorHandler.class).isEmpty());
        });
    }
}
