package com.fluxbank.transfer.infrastructure;

import com.fluxbank.transfer.application.TransferCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.UUID;

import static org.mockito.Mockito.*;

class SpringTransferEventPublisherTest {
    @Test
    void publishesOnlyTheTransferPublicId() {
        ApplicationEventPublisher spring = mock(ApplicationEventPublisher.class);
        UUID id = UUID.randomUUID();
        new SpringTransferEventPublisher(spring).publishCompleted(id);
        verify(spring).publishEvent(new TransferCompletedEvent(id));
        verifyNoMoreInteractions(spring);
    }
}
