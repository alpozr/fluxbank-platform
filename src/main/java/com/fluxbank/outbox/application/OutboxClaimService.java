package com.fluxbank.outbox.application;

import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OutboxClaimService {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxClaimService(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> claimBatch(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be greater than zero");
        }
        List<OutboxEvent> events = outboxEventRepository.findPendingForClaim(batchSize);
        events.forEach(OutboxEvent::claim);
        return events.stream().map(OutboxEvent::getPublicId).toList();
    }
}
