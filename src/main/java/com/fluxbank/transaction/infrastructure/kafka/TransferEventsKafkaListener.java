package com.fluxbank.transaction.infrastructure.kafka;

import com.fluxbank.transaction.application.TransactionHistoryEventProcessor;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "kafka.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class TransferEventsKafkaListener {
    private final TransactionHistoryEventProcessor processor;

    public TransferEventsKafkaListener(TransactionHistoryEventProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(id = "transaction-history-transfer-events", topics = "${kafka.topics.transfer-events}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void handle(TransferCompletedKafkaEvent event) {
        processor.process(event);
    }
}
