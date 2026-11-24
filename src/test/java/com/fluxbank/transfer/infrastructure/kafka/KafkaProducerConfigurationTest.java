package com.fluxbank.transfer.infrastructure.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KafkaProducerConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TransferCompletedKafkaPublisher.class)
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class, JacksonAutoConfiguration.class));

    @Test
    void bootCreatesJsonProducerInfrastructureWithApplicationDefaultsWithoutConnectingToKafka() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(KafkaTemplate.class));
            assertNotNull(context.getBean(TransferCompletedKafkaPublisher.class));
            var config = context.getBean(DefaultKafkaProducerFactory.class).getConfigurationProperties();
            assertEquals(List.of("localhost:9092"), config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
            assertEquals(StringSerializer.class, config.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
            assertEquals(JsonSerializer.class, config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
            assertEquals("all", config.get(ProducerConfig.ACKS_CONFIG));
            assertEquals("true", String.valueOf(config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)));
            assertEquals("false", String.valueOf(config.get(JsonSerializer.ADD_TYPE_INFO_HEADERS)));
            assertEquals("fluxbank.transfer-events.v1", context.getEnvironment().getProperty("kafka.topics.transfer-events"));
        });
    }

    @Test
    void bootstrapServersAndTopicCanBeOverriddenThroughConfiguredVariables() {
        contextRunner.withPropertyValues("KAFKA_BOOTSTRAP_SERVERS=broker-a:9092,broker-b:9092",
                        "KAFKA_TRANSFER_EVENTS_TOPIC=custom.transfer-events.v1")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var config = context.getBean(DefaultKafkaProducerFactory.class).getConfigurationProperties();
                    assertEquals(List.of("broker-a:9092", "broker-b:9092"), config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
                    assertEquals("custom.transfer-events.v1", context.getEnvironment().getProperty("kafka.topics.transfer-events"));
                });
    }
}
