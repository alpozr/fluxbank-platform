package com.fluxbank.transfer.infrastructure.kafka;

import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.transfer.application.TransferCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class TransferCompletedKafkaPublisher {
    private final KafkaTemplate<String, TransferCompletedKafkaEvent> kafkaTemplate;
    private final String topic;
    private final ObjectMapper objectMapper;

    public TransferCompletedKafkaPublisher(KafkaTemplate<String, TransferCompletedKafkaEvent> kafkaTemplate,
                                            @Value("${kafka.topics.transfer-events}") String topic,
                                            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.objectMapper = objectMapper;
    }

    public void publish(OutboxEvent event) {
        var payload = event.getPayload();
        var transferPublicId = payload.path("transferPublicId");
        if (!transferPublicId.isTextual()) {
            throw new IllegalArgumentException("Transfer public ID is required in the outbox payload");
        }
        TransferCompletedEvent snapshot;
        try {
            snapshot = objectMapper.treeToValue(payload, TransferCompletedEvent.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid transfer-completed snapshot", failure);
        }
        if (snapshot.transferType() == null && (payload.has("transferType") || payload.has("sourceAccountPublicId")
                || payload.has("destinationAccountPublicId") || payload.has("amount") || payload.has("currency"))) {
            throw new IllegalArgumentException("Incomplete transfer-completed snapshot");
        }
        var kafkaEvent = new TransferCompletedKafkaEvent(event.getPublicId(), event.getEventType(),
                event.getAggregateId(), event.getCreatedAt(), snapshot.transferPublicId(), snapshot.transferType(),
                snapshot.sourceAccountPublicId(), snapshot.destinationAccountPublicId(), snapshot.amount(), snapshot.currency());

        kafkaTemplate.send(topic, snapshot.transferPublicId().toString(), kafkaEvent).join();
    }
}
