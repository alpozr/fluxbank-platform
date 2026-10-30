package com.fluxbank.outbox.application;

import com.fluxbank.outbox.repository.OutboxEventRepository;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class OutboxProcessor {
    private final OutboxEventRepository events;
    private final OutboxProcessingTransaction processing;
    private final OutboxFailureService failures;
    private final TransferCompletedKafkaPublisher publisher;

    public OutboxProcessor(OutboxEventRepository events, OutboxProcessingTransaction processing,
                           OutboxFailureService failures, TransferCompletedKafkaPublisher publisher) {
        this.events = events;
        this.processing = processing;
        this.failures = failures;
        this.publisher = publisher;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void process(UUID eventPublicId) {
        var event = events.findByPublicId(eventPublicId)
                .orElseThrow(() -> new IllegalStateException("Outbox event not found"));
        if (event.getStatus() != OutboxStatus.PROCESSING) {
            return;
        }
        OutboxAttempt attempt = OutboxAttempt.from(event);
        try {
            publisher.publish(event);
            processing.markProcessed(attempt);
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
