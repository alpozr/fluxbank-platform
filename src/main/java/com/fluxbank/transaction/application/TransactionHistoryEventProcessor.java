package com.fluxbank.transaction.application;

import com.fluxbank.processed.repository.ProcessedEventRepository;
import com.fluxbank.transfer.application.TransferCompletedEvent;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionHistoryEventProcessor {
    private final ProcessedEventRepository processedEvents;
    private final TransactionHistoryService history;

    public TransactionHistoryEventProcessor(ProcessedEventRepository processedEvents, TransactionHistoryService history) {
        this.processedEvents = processedEvents;
        this.history = history;
    }

    @Transactional
    public void process(TransferCompletedKafkaEvent event) {
        if (event == null || event.transferType() == null) {
            throw new IllegalArgumentException("A complete transfer-completed snapshot is required");
        }
        var snapshot = new TransferCompletedEvent(event.transferPublicId(), event.transferType(),
                event.sourceAccountPublicId(), event.destinationAccountPublicId(), event.amount(), event.currency());
        if (processedEvents.insertIfAbsent(event.eventId(), event.eventType()) == 0) {
            return;
        }
        history.createForSnapshot(snapshot);
    }
}
