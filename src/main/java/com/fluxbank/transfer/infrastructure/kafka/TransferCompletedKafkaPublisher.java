package com.fluxbank.transfer.infrastructure.kafka;

import com.fluxbank.outbox.domain.OutboxEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class TransferCompletedKafkaPublisher {
    private final KafkaTemplate<String, TransferCompletedKafkaEvent> kafkaTemplate;
    private final String topic;

    public TransferCompletedKafkaPublisher(KafkaTemplate<String, TransferCompletedKafkaEvent> kafkaTemplate,
                                            @Value("${kafka.topics.transfer-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(OutboxEvent event) {
        var transferPublicId = event.getPayload().path("transferPublicId");
        if (!transferPublicId.isTextual()) {
            throw new IllegalArgumentException("Transfer public ID is required in the outbox payload");
        }
        UUID transferId = UUID.fromString(transferPublicId.asText());
        var kafkaEvent = new TransferCompletedKafkaEvent(event.getPublicId(), event.getEventType(),
                event.getAggregateId(), event.getCreatedAt(), transferId);

        kafkaTemplate.send(topic, transferId.toString(), kafkaEvent).join();
    }
}
