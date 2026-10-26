package com.fluxbank.outbox.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import java.time.Duration;

@Component
public class OutboxRetryPolicy {
    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final Duration processingTimeout;

    public OutboxRetryPolicy(@Value("${outbox.retry.max-attempts:5}") int maxAttempts,
                             @Value("${outbox.retry.initial-delay:PT5S}") Duration initialDelay,
                             @Value("${outbox.retry.max-delay:PT5M}") Duration maxDelay,
                             @Value("${outbox.processing-timeout:PT5M}") Duration processingTimeout) {
        if (maxAttempts <= 0 || initialDelay.isZero() || initialDelay.isNegative()
                || maxDelay.compareTo(initialDelay) < 0 || processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("Outbox retry settings must be positive and max delay must cover initial delay");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.processingTimeout = processingTimeout;
    }

    public Duration delay(int attempt) {
        if (attempt <= 0) {
            throw new IllegalArgumentException("Attempt must be positive");
        }
        Duration delay = initialDelay;
        for (int i = 1; i < attempt && delay.compareTo(maxDelay) < 0; i++) {
            if (delay.compareTo(maxDelay.dividedBy(2)) > 0) {
                return maxDelay;
            }
            delay = delay.multipliedBy(2);
        }
        return delay;
    }

    public boolean isRetryable(RuntimeException failure) {
        return failure instanceof TransientDataAccessException
                || failure instanceof RecoverableDataAccessException
                || failure instanceof CannotCreateTransactionException;
    }

    public String sanitizedError(RuntimeException failure) {
        if (isRetryable(failure)) {
            return "Transient database or transaction infrastructure failure";
        }
        if (failure instanceof IllegalArgumentException) {
            return "Invalid outbox event data";
        }
        if (failure instanceof IllegalStateException) {
            return "Unsupported event or invalid transaction history state";
        }
        return "Non-retryable processing failure";
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Duration getProcessingTimeout() {
        return processingTimeout;
    }
}
