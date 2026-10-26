package com.fluxbank.outbox.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboxRecoverySchedulerTest {

    @Test
    void delegatesConfiguredBatchSizeWithoutProcessingRecoveredEvents() {
        OutboxRecoveryService recovery = mock(OutboxRecoveryService.class);
        OutboxProcessor processor = mock(OutboxProcessor.class);
        when(recovery.recoverStaleBatch(7)).thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));

        new ApplicationContextRunner()
                .withUserConfiguration(OutboxRecoveryScheduler.class)
                .withBean(OutboxRecoveryService.class, () -> recovery)
                .withBean(OutboxProcessor.class, () -> processor)
                .withPropertyValues("outbox.recovery.enabled=true", "outbox.recovery.batch-size=7")
                .run(context -> {
                    assertNull(context.getStartupFailure());

                    context.getBean(OutboxRecoveryScheduler.class).recover();

                    verify(recovery).recoverStaleBatch(7);
                    verifyNoMoreInteractions(recovery);
                    verifyNoInteractions(processor);
                });
    }

    @Test
    void enabledByDefaultWithBatchSizeTwenty() {
        OutboxRecoveryService recovery = mock(OutboxRecoveryService.class);
        when(recovery.recoverStaleBatch(20)).thenReturn(List.of());

        new ApplicationContextRunner()
                .withUserConfiguration(OutboxRecoveryScheduler.class)
                .withBean(OutboxRecoveryService.class, () -> recovery)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    context.getBean(OutboxRecoveryScheduler.class).recover();
                    verify(recovery).recoverStaleBatch(20);
                    verifyNoMoreInteractions(recovery);
                });
    }

    @Test
    void disabledConfigurationPreventsBeanCreation() {
        new ApplicationContextRunner()
                .withUserConfiguration(OutboxRecoveryScheduler.class)
                .withPropertyValues("outbox.recovery.enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(OutboxRecoveryScheduler.class).isEmpty());
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveBatchSize(int batchSize) {
        assertThrows(IllegalArgumentException.class, () ->
                new OutboxRecoveryScheduler(mock(OutboxRecoveryService.class), batchSize));
    }
}
