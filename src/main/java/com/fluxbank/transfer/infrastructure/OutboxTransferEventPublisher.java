package com.fluxbank.transfer.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import com.fluxbank.transfer.application.TransferCompletedEvent;
import com.fluxbank.transfer.application.TransferEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class OutboxTransferEventPublisher implements TransferEventPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxTransferEventPublisher(OutboxEventRepository outboxEventRepository, ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCompleted(UUID transferPublicId) {
        outboxEventRepository.save(OutboxEvent.create(
                "TRANSFER_COMPLETED",
                transferPublicId,
                objectMapper.valueToTree(new TransferCompletedEvent(transferPublicId))));
    }
}
