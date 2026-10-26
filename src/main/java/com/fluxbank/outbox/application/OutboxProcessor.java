package com.fluxbank.outbox.application;

import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
public class OutboxProcessor {
    private final OutboxEventRepository events;
    private final OutboxProcessingTransaction processing;
    private final OutboxFailureService failures;

    public OutboxProcessor(OutboxEventRepository events, OutboxProcessingTransaction processing,
                           OutboxFailureService failures) {
        this.events = events;
        this.processing = processing;
        this.failures = failures;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void process(UUID eventPublicId) {
        var event = events.findByPublicId(eventPublicId)
                .orElseThrow(() -> new IllegalStateException("Outbox event not found"));
        if (!event.isEligibleForProcessing(Instant.now())) {
            return;
        }
        OutboxAttempt attempt = OutboxAttempt.from(event);
        try {
            processing.process(attempt);
        } catch (RuntimeException failure) {
            try {
                failures.recordFailure(attempt, failure);
            } catch (RuntimeException bookkeepingFailure) {
                failure.addSuppressed(bookkeepingFailure);
            }
            throw failure;
        }
    }
}
