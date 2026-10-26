package com.fluxbank.outbox.application;

import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OutboxRecoveryService {
    private final OutboxEventRepository events;
    private final OutboxRetryPolicy policy;

    public OutboxRecoveryService(OutboxEventRepository events, OutboxRetryPolicy policy) {
        this.events = events;
        this.policy = policy;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<UUID> recoverStaleBatch(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Batch size must be greater than zero");
        }
        Instant now = Instant.now();
        List<OutboxEvent> stale = events.findStaleForRecovery(now.minus(policy.getProcessingTimeout()), batchSize);
        stale.forEach(event -> event.recordFailure(true, policy.getMaxAttempts(),
                now.plus(policy.delay(event.getAttempts() + 1)), "Stale processing claim expired"));
        return stale.stream().map(OutboxEvent::getPublicId).toList();
    }
}
