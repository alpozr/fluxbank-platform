package com.fluxbank.outbox.application;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fluxbank.outbox.domain.OutboxEvent;
import com.fluxbank.outbox.domain.OutboxStatus;
import com.fluxbank.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import com.fluxbank.transfer.infrastructure.kafka.TransferCompletedKafkaPublisher;
import org.springframework.dao.TransientDataAccessResourceException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxProcessorTest {
    private final OutboxEventRepository events = mock(OutboxEventRepository.class);
    private final OutboxProcessingTransaction processing = mock(OutboxProcessingTransaction.class);
    private final OutboxFailureService failures = mock(OutboxFailureService.class);
    private final TransferCompletedKafkaPublisher publisher = mock(TransferCompletedKafkaPublisher.class);
    private final OutboxProcessor processor = new OutboxProcessor(events, processing, failures, publisher);

    @Test
    void kafkaFailureUsesExistingBookkeepingWithoutMarkingProcessed() {
        OutboxEvent event = event();
        var failure = new java.util.concurrent.CompletionException(new org.apache.kafka.common.errors.TimeoutException("private"));
        doThrow(failure).when(publisher).publish(event);

        assertSame(failure, assertThrows(RuntimeException.class, () -> processor.process(event.getPublicId())));

        verify(failures).recordFailure(OutboxAttempt.from(event), failure);
        verifyNoInteractions(processing);
    }

    @Test
    void recordsFailureAfterProcessingReturnsAnExceptionAndRethrowsIt() {
        OutboxEvent event = event();
        var attempt = OutboxAttempt.from(event);
        var failure = new TransientDataAccessResourceException("sensitive infrastructure details");
        doThrow(failure).when(processing).markProcessed(attempt);

        assertSame(failure, assertThrows(RuntimeException.class, () -> processor.process(event.getPublicId())));

        var order = inOrder(processing, failures);
        order.verify(processing).markProcessed(attempt);
        order.verify(failures).recordFailure(attempt, failure);
    }

    @Test
    void successDoesNotRecordFailure() {
        OutboxEvent event = event();
        processor.process(event.getPublicId());
        var order = inOrder(publisher, processing);
        order.verify(publisher).publish(event);
        order.verify(processing).markProcessed(OutboxAttempt.from(event));
        verifyNoInteractions(failures);
    }

    @Test
    void terminalAndNotYetDueEventsAreNotProcessed() {
        OutboxEvent event = event();
        event.recordFailure(true, 5, Instant.now().plusSeconds(300), "Sanitized failure");
        processor.process(event.getPublicId());
        event.recordFailure(false, 5, Instant.now(), "Permanent failure");
        assertEquals(OutboxStatus.FAILED, event.getStatus());
        processor.process(event.getPublicId());
        verifyNoInteractions(publisher, processing, failures);
    }

    @Test
    void missingEventFailsExplicitly() {
        UUID id = UUID.randomUUID();
        when(events.findByPublicId(id)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> processor.process(id));
        verifyNoInteractions(publisher, processing, failures);
    }

    private OutboxEvent event() {
        var event = OutboxEvent.create("TRANSFER_COMPLETED", UUID.randomUUID(), JsonNodeFactory.instance.objectNode());
        when(events.findByPublicId(event.getPublicId())).thenReturn(Optional.of(event));
        event.claim();
        return event;
    }
}
