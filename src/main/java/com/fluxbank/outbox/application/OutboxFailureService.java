package com.fluxbank.outbox.application;

import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
public class OutboxFailureService {
    private final OutboxEventRepository events;
    private final OutboxRetryPolicy policy;

    public OutboxFailureService(OutboxEventRepository events, OutboxRetryPolicy policy) {
        this.events = events;
        this.policy = policy;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(OutboxAttempt attempt, RuntimeException failure) {
        events.findByPublicIdForUpdate(attempt.publicId()).ifPresent(event -> {
            if (OutboxAttempt.from(event).equals(attempt) && event.isEligibleForProcessing(Instant.now())) {
                event.recordFailure(policy.isRetryable(failure), policy.getMaxAttempts(),
                        Instant.now().plus(policy.delay(event.getAttempts() + 1)), policy.sanitizedError(failure));
            }
        });
    }
}
