package com.fluxbank.transaction.application;

import com.fluxbank.transfer.application.TransferCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferCompletedTransactionListenerTest {
    private final UUID id = UUID.randomUUID();

    @Configuration
    static class Config {
        @Bean
        static TransactionalEventListenerFactory transactionalEventListenerFactory() {
            return new TransactionalEventListenerFactory();
        }

        @Bean
        TransactionHistoryService history() {
            return mock(TransactionHistoryService.class);
        }

        @Bean
        TransferCompletedTransactionListener listener(TransactionHistoryService history) {
            return new TransferCompletedTransactionListener(history);
        }

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        int commits;
        int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }

    @Test
    void historyRunsOnlyAfterCommit() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var history = context.getBean(TransactionHistoryService.class);
            var manager = context.getBean(RecordingTransactionManager.class);
            doAnswer(invocation -> {
                assertEquals(1, manager.commits);
                return null;
            }).when(history).createForTransfer(id);

            new TransactionTemplate(manager).executeWithoutResult(status -> {
                context.publishEvent(new TransferCompletedEvent(id));
                verifyNoInteractions(history);
            });

            verify(history).createForTransfer(id);
        }
    }

    @Test
    void rollbackDoesNotDeliverHistory() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var manager = context.getBean(RecordingTransactionManager.class);
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                context.publishEvent(new TransferCompletedEvent(id));
                status.setRollbackOnly();
            });
            assertEquals(1, manager.rollbacks);
            verifyNoInteractions(context.getBean(TransactionHistoryService.class));
        }
    }

    @Test
    void eventOutsideTransactionIsNotDelivered() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            context.publishEvent(new TransferCompletedEvent(id));
            verifyNoInteractions(context.getBean(TransactionHistoryService.class));
        }
    }

    @Test
    void historyFailureCannotUndoCommittedTransaction() {
        try (var context = new AnnotationConfigApplicationContext(Config.class)) {
            var history = context.getBean(TransactionHistoryService.class);
            var manager = context.getBean(RecordingTransactionManager.class);
            doThrow(new IllegalStateException("history failure")).when(history).createForTransfer(id);

            assertDoesNotThrow(() -> new TransactionTemplate(manager).executeWithoutResult(
                    status -> context.publishEvent(new TransferCompletedEvent(id))));

            verify(history).createForTransfer(id);
            assertEquals(1, manager.commits);
            assertEquals(0, manager.rollbacks);
        }
    }
}
