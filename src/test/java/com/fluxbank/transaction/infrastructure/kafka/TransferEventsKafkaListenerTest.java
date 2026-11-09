package com.fluxbank.transaction.infrastructure.kafka;

import com.fluxbank.transaction.application.TransactionHistoryEventProcessor;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaEvent;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferEventsKafkaListenerTest {
    private final TransactionHistoryEventProcessor processor = mock(TransactionHistoryEventProcessor.class);
    private final TransferEventsKafkaListener listener = new TransferEventsKafkaListener(processor);

    @Test
    void delegatesEventWithoutHistoryLogic() {
        var event = event();
        listener.handle(event);
        verify(processor).process(event);
        verifyNoMoreInteractions(processor);
    }

    @Test
    void propagatesProcessingFailureToContainerErrorHandler() {
        var event = event();
        var failure = new IllegalArgumentException("Invalid snapshot");
        doThrow(failure).when(processor).process(event);
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> listener.handle(event)));
    }

    private TransferCompletedKafkaEvent event() {
        UUID id = UUID.randomUUID();
        return new TransferCompletedKafkaEvent(UUID.randomUUID(), "TRANSFER_COMPLETED", id, Instant.now(), id);
    }
}
