package com.fluxbank.outbox.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(name = "outbox.polling.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {
    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxClaimService claims;
    private final OutboxProcessor processor;
    private final int batchSize;

    public OutboxPoller(OutboxClaimService claims, OutboxProcessor processor,
                        @Value("${outbox.polling.batch-size:20}") int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Outbox polling batch size must be greater than zero");
        }
        this.claims = claims;
        this.processor = processor;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${outbox.polling.interval-ms:2000}")
    public void poll() {
        for (UUID eventId : claims.claimBatch(batchSize)) {
            try {
                processor.process(eventId);
            } catch (RuntimeException failure) {
                log.warn("Outbox processing failed for event {}; continuing batch", eventId);
            }
        }
    }
}
