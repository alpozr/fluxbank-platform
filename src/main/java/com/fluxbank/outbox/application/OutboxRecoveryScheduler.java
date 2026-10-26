package com.fluxbank.outbox.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "outbox.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRecoveryScheduler {
    private final OutboxRecoveryService recovery;
    private final int batchSize;

    public OutboxRecoveryScheduler(OutboxRecoveryService recovery,
                                   @Value("${outbox.recovery.batch-size:20}") int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Outbox recovery batch size must be greater than zero");
        }
        this.recovery = recovery;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.recovery.interval-ms:60000}")
    public void recover() {
        recovery.recoverStaleBatch(batchSize);
    }
}
