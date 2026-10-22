package com.fluxbank.transfer.infrastructure;

import com.fluxbank.transfer.application.TransferCompletedEvent;
import com.fluxbank.transfer.application.TransferEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SpringTransferEventPublisher implements TransferEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public SpringTransferEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void publishCompleted(UUID transferPublicId) {
        eventPublisher.publishEvent(new TransferCompletedEvent(transferPublicId));
    }
}
