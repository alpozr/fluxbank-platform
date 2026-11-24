package com.fluxbank.transfer.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import com.fluxbank.transfer.application.TransferCompletedEvent;
import com.fluxbank.transfer.application.TransferEventPublisher;
import com.fluxbank.transfer.domain.TransferStatus;
import com.fluxbank.transfer.repository.TransferRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class OutboxTransferEventPublisher implements TransferEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final TransferRepository transferRepository;

    public OutboxTransferEventPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper,
                                       TransferRepository transferRepository) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.transferRepository = transferRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCompleted(UUID transferPublicId) {
        if (transferPublicId == null) {
            throw new IllegalArgumentException("Transfer public ID is required");
        }
        var transfer = transferRepository.findByPublicId(transferPublicId)
                .orElseThrow(() -> new IllegalStateException("Transfer not found"));
        if (transfer.getStatus() != TransferStatus.COMPLETED) {
            throw new IllegalStateException("Only completed transfers can publish a completed event");
        }
        var snapshot = new TransferCompletedEvent(transfer.getPublicId(), transfer.getType(),
                transfer.getSourceAccount().getPublicId(),
                transfer.getDestinationAccount() == null ? null : transfer.getDestinationAccount().getPublicId(),
                transfer.getAmount(), transfer.getCurrency());
        outboxEventRepository.save(OutboxEvent.create(
                "TRANSFER_COMPLETED",
                transferPublicId,
                objectMapper.valueToTree(snapshot)));
    }
}
