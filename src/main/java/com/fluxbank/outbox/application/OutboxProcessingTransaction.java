package com.fluxbank.outbox.application;

import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class OutboxProcessingTransaction {
    private final OutboxEventRepository events;

    public OutboxProcessingTransaction(OutboxEventRepository events) {
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(OutboxAttempt attempt) {
        OutboxEvent event = events.findByPublicIdForUpdate(attempt.publicId())
                .orElseThrow(() -> new IllegalStateException("Outbox event not found"));
        if (event.getStatus() == OutboxStatus.PROCESSING && OutboxAttempt.from(event).equals(attempt)) {
            event.markProcessed();
        }
    }
}
