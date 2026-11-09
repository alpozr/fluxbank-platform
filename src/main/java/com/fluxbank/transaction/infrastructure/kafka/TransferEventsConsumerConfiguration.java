package com.fluxbank.transaction.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaEvent;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kafka.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class TransferEventsConsumerConfiguration {

    @Bean
    ProducerFactory<Object, Object> kafkaProducerFactory(KafkaProperties properties, ObjectMapper mapper,
                                                        ObjectProvider<SslBundles> sslBundles) {
        // Preserve normal JSON publication and raw bytes recovered from malformed JSON.
        var serializer = new DelegatingByTypeSerializer(Map.of(
                byte[].class, new ByteArraySerializer(),
                TransferCompletedKafkaEvent.class, new JsonSerializer<>(mapper)));
        return new DefaultKafkaProducerFactory<>(properties.buildProducerProperties(sslBundles.getIfAvailable()), null, serializer);
    }

    @Bean
    DefaultErrorHandler transferEventsErrorHandler(KafkaTemplate<Object, Object> template,
            @Value("${kafka.topics.transfer-events-dlq}") String dlqTopic,
            @Value("${kafka.consumer.max-retries:2}") long maxRetries,
            @Value("${kafka.consumer.retry-interval-ms:1000}") long retryInterval) {
        if (maxRetries < 0 || retryInterval < 0) {
            throw new IllegalArgumentException("Consumer retry settings must not be negative");
        }
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, failure) -> new TopicPartition(dlqTopic, -1));
        recoverer.setFailIfSendResultIsError(true);
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(retryInterval, maxRetries));
        handler.setResetStateOnRecoveryFailure(false);
        handler.setClassifications(Map.of(
                TransientDataAccessException.class, true,
                RecoverableDataAccessException.class, true,
                CannotCreateTransactionException.class, true), false);
        return handler;
    }
}
