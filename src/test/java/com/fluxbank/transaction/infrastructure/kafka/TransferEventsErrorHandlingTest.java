package com.fluxbank.transaction.infrastructure.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransferEventsErrorHandlingTest {
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
    @SuppressWarnings("unchecked")
    private final Consumer<Object, Object> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final ConsumerRecord<Object, Object> record = new ConsumerRecord<>("transfer-events", 0, 42, "key", "payload");
    private final DefaultErrorHandler handler = new TransferEventsConsumerConfiguration()
            .transferEventsErrorHandler(template, "transfer-events.DLT", 2, 0);

    @Test
    void recoveryWaitsForDltBrokerAcknowledgementBeforeReturning() throws Exception {
        var acknowledgement = spy(new CompletableFuture<SendResult<Object, Object>>());
        CountDownLatch awaitingAcknowledgement = new CountDownLatch(1);
        doAnswer(invocation -> {
            awaitingAcknowledgement.countDown();
            return invocation.callRealMethod();
        }).when(acknowledgement).get(anyLong(), eq(TimeUnit.MILLISECONDS));
        when(template.send(any(ProducerRecord.class))).thenReturn(acknowledgement);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> recovery = worker.submit(() -> handler.handleRemaining(
                new IllegalArgumentException("invalid event"), List.of(record), consumer, container));
        try {
            assertTrue(awaitingAcknowledgement.await(5, TimeUnit.SECONDS));
            assertFalse(recovery.isDone(), "Returning a send future is not a broker acknowledgement");
            acknowledgement.complete(sendResult());
            recovery.get(5, TimeUnit.SECONDS);
            verify(consumer, never()).seek(any(TopicPartition.class), anyLong());
        } finally {
            acknowledgement.completeExceptionally(new IllegalStateException("Test finished"));
            worker.shutdown();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void asyncOrImmediateDltSendFailurePropagatesAndSeeksOriginalOffset(boolean asynchronous) {
        var sendFailure = new KafkaException("DLT broker send failed");
        if (asynchronous) {
            when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(sendFailure));
        } else {
            when(template.send(any(ProducerRecord.class))).thenThrow(sendFailure);
        }
        AtomicReference<Exception> recoveryFailure = new AtomicReference<>();
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> failed, Exception error, int attempt) { }
            @Override
            public void recoveryFailed(ConsumerRecord<?, ?> failed, Exception original, Exception failure) {
                recoveryFailure.set(failure);
            }
        });

        assertThrows(RuntimeException.class, () -> handler.handleRemaining(
                new IllegalArgumentException("invalid event"), List.of(record), consumer, container));

        assertInstanceOf(KafkaException.class, recoveryFailure.get());
        if (asynchronous) {
            assertSame(sendFailure, recoveryFailure.get().getCause().getCause());
        }
        verify(consumer).seek(new TopicPartition(record.topic(), record.partition()), record.offset());
    }

    @ParameterizedTest
    @MethodSource("nestedDatabaseFailures")
    void nestedDatabaseFailuresReceiveTwoRetriesBeforeDlt(RuntimeException failure) {
        when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(sendResult()));
        assertFalse(handler.handleOne(failure, record, consumer, container));
        assertFalse(handler.handleOne(failure, record, consumer, container));
        verify(template, never()).send(any(ProducerRecord.class));
        assertTrue(handler.handleOne(failure, record, consumer, container));
        verify(template).send(any(ProducerRecord.class));
    }

    private static Stream<RuntimeException> nestedDatabaseFailures() {
        return Stream.of(
                new ListenerExecutionFailedException("listener", new IllegalStateException("wrapper",
                        new TransientDataAccessResourceException("temporary database failure"))),
                new ListenerExecutionFailedException("listener", new CompletionException(
                        new RecoverableDataAccessException("recoverable database failure"))),
                new ListenerExecutionFailedException("listener", new TransactionSystemException("transaction wrapper",
                        new CannotCreateTransactionException("temporary connection failure"))));
    }

    private SendResult<Object, Object> sendResult() {
        return new SendResult<>(new ProducerRecord<>("transfer-events.DLT", "key", "payload"),
                new RecordMetadata(new TopicPartition("transfer-events.DLT", 0), 0, 0, 0, 1, 1));
    }
}
