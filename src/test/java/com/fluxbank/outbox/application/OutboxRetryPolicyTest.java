package com.fluxbank.outbox.application;

import org.junit.jupiter.api.Test;
import org.springframework.dao.*;
import org.springframework.transaction.CannotCreateTransactionException;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class OutboxRetryPolicyTest {
    private final OutboxRetryPolicy policy = new OutboxRetryPolicy(3, Duration.ofSeconds(5),
            Duration.ofSeconds(30), Duration.ofMinutes(5));

    @Test
    void classifiesKnownKafkaWrappersWithoutRetryingPermanentErrors() {
        var transientFailure = new java.util.concurrent.CompletionException(
                new org.springframework.kafka.KafkaException("send failed", new org.apache.kafka.common.errors.TimeoutException("secret")));
        assertTrue(policy.isRetryable(transientFailure));
        assertEquals("Transient Kafka publication failure", policy.sanitizedError(transientFailure));
        assertFalse(policy.isRetryable(new java.util.concurrent.CompletionException(
                new org.apache.kafka.common.errors.SerializationException("secret"))));
        assertFalse(policy.isRetryable(new java.util.concurrent.CompletionException(
                new org.apache.kafka.common.errors.TopicAuthorizationException("secret"))));
        assertFalse(policy.isRetryable(new RuntimeException(new org.apache.kafka.common.errors.TimeoutException("secret"))));
    }

    @Test
    void exponentialBackoffIsDeterministicAndCapped() {
        assertEquals(Duration.ofSeconds(5), policy.delay(1));
        assertEquals(Duration.ofSeconds(10), policy.delay(2));
        assertEquals(Duration.ofSeconds(20), policy.delay(3));
        assertEquals(Duration.ofSeconds(30), policy.delay(4));
        assertEquals(Duration.ofSeconds(30), policy.delay(Integer.MAX_VALUE));
    }

    @Test
    void retriesOnlyExplicitTransientInfrastructureFailures() {
        assertTrue(policy.isRetryable(new TransientDataAccessResourceException("secret")));
        assertTrue(policy.isRetryable(new RecoverableDataAccessException("secret")));
        assertTrue(policy.isRetryable(new CannotCreateTransactionException("secret")));
        assertFalse(policy.isRetryable(new DataIntegrityViolationException("secret")));
        assertFalse(policy.isRetryable(new IllegalArgumentException("secret")));
        assertFalse(policy.isRetryable(new IllegalStateException("secret")));
        assertFalse(policy.isRetryable(new RuntimeException("secret")));
    }

    @Test
    void storedErrorsNeverIncludeRawMessagesOrStackTraces() {
        for (RuntimeException failure : new RuntimeException[]{new TransientDataAccessResourceException("password=secret"),
                new IllegalArgumentException("payload=secret"), new IllegalStateException("secret"), new RuntimeException("secret")}) {
            assertFalse(policy.sanitizedError(failure).contains("secret"));
            assertFalse(policy.sanitizedError(failure).contains("\n"));
        }
    }

    @Test
    void rejectsInvalidConfigurationAndAttemptNumbers() {
        assertThrows(IllegalArgumentException.class, () -> policy.delay(0));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRetryPolicy(3, Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRetryPolicy(3, Duration.ofSeconds(3), Duration.ofSeconds(2), Duration.ofSeconds(3)));
        assertThrows(IllegalArgumentException.class, () -> new OutboxRetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ZERO));
    }
}
