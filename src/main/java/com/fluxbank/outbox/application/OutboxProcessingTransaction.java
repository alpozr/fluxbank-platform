package com.fluxbank.outbox.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import com.fluxbank.transaction.application.TransactionHistoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.Instant;

import java.util.UUID;

@Service
public class OutboxProcessingTransaction {

    private final OutboxEventRepository outboxEventRepository;
    private final TransactionHistoryService transactionHistoryService;

    public OutboxProcessingTransaction(OutboxEventRepository outboxEventRepository,
                           TransactionHistoryService transactionHistoryService) {
        this.outboxEventRepository = outboxEventRepository;
        this.transactionHistoryService = transactionHistoryService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(OutboxAttempt attempt) {
        OutboxEvent event = outboxEventRepository.findByPublicIdForUpdate(attempt.publicId())
                .orElseThrow(() -> new IllegalStateException("Outbox event not found"));
        if (!OutboxAttempt.from(event).equals(attempt) || !event.isEligibleForProcessing(Instant.now())) {
            return;
        }
        if (!"TRANSFER_COMPLETED".equals(event.getEventType())) {
            throw new IllegalStateException("Unsupported outbox event type");
        }

        JsonNode transferPublicId = event.getPayload().path("transferPublicId");
        if (!transferPublicId.isTextual()) {
            throw new IllegalArgumentException("Transfer public ID is required in the outbox payload");
        }
        UUID transferId = UUID.fromString(transferPublicId.asText());
        if (!transferId.equals(event.getAggregateId())) {
            throw new IllegalArgumentException("Transfer public ID must match the outbox aggregate ID");
        }

        transactionHistoryService.createForTransfer(transferId);
        event.markProcessed();
    }
}
