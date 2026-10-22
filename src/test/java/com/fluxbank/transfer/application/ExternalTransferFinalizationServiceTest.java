package com.fluxbank.transfer.application;

import com.fluxbank.account.domain.*;
import com.fluxbank.account.repository.AccountBalanceRepository;
import com.fluxbank.transfer.domain.*;
import com.fluxbank.transfer.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalTransferFinalizationServiceTest {
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final AccountBalanceRepository balances = mock(AccountBalanceRepository.class);
    private final TransferEventPublisher events = mock(TransferEventPublisher.class);
    private final ExternalTransferFinalizationService service =
            new ExternalTransferFinalizationService(transfers, balances, events);
    private Transfer transfer;
    private AccountBalance balance;

    @BeforeEach
    void setUp() {
        Account source = Account.create(null, "source", "iban", 1L, 1, CurrencyCode.EUR);
        balance = spy(AccountBalance.create(source));
        balance.credit(new BigDecimal("1000"));
        balance.reserve(new BigDecimal("300"));
        transfer = Transfer.create("ref", null, source, null, null, "name", "external-iban",
                "bank", new BigDecimal("300"), CurrencyCode.EUR, TransferType.EXTERNAL);
        when(transfers.findByPublicIdForUpdate(transfer.getPublicId())).thenReturn(Optional.of(transfer));
        when(balances.findByAccountPublicIdForUpdate(source.getPublicId())).thenReturn(Optional.of(balance));
    }

    @Test
    void successSettlesCompletesAndThenPublishes() {
        doAnswer(invocation -> {
            assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
            assertEquals(new BigDecimal("700"), balance.getCurrentBalance());
            assertEquals(BigDecimal.ZERO, balance.getReservedBalance());
            assertEquals(new BigDecimal("700"), balance.getAvailableBalance());
            return null;
        }).when(events).publishCompleted(transfer.getPublicId());

        service.complete(transfer.getPublicId());

        assertBalance("700", "0", "700");
        assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
        verify(events).publishCompleted(transfer.getPublicId());
    }

    @Test
    void rejectionReleasesAndFailsWithoutCompletedEvent() {
        service.fail(transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED);
        assertEquals(TransferStatus.FAILED, transfer.getStatus());
        assertBalance("1000", "0", "1000");
        assertEquals(TransferFailureReason.EXTERNAL_PROVIDER_REJECTED, transfer.getFailureReason());
        verifyNoInteractions(events);
    }

    @Test
    void failedSettlementDoesNotPublishCompletion() {
        balance.release(new BigDecimal("100"));
        assertThrows(IllegalStateException.class, () -> service.complete(transfer.getPublicId()));
        assertEquals(TransferStatus.PENDING, transfer.getStatus());
        assertBalance("1000", "200", "800");
        verifyNoInteractions(events);
    }

    @Test
    void repeatedCompleteCannotSettleOrPublishAgain() {
        service.complete(transfer.getPublicId());
        clearInvocations(balance);

        assertThrows(IllegalStateException.class, () -> service.complete(transfer.getPublicId()));

        assertBalance("700", "0", "700");
        assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
        verify(balance, never()).settle(any());
        verify(events).publishCompleted(transfer.getPublicId());
        verifyNoMoreInteractions(events);
    }

    @Test
    void repeatedFailCannotReleaseAgain() {
        service.fail(transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED);
        clearInvocations(balance);

        assertThrows(IllegalStateException.class, () -> service.fail(
                transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED));

        assertBalance("1000", "0", "1000");
        assertEquals(TransferStatus.FAILED, transfer.getStatus());
        assertEquals(TransferFailureReason.EXTERNAL_PROVIDER_REJECTED, transfer.getFailureReason());
        verify(balance, never()).release(any());
        verifyNoInteractions(events);
    }

    @Test
    void failedTransferCannotComplete() {
        service.fail(transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED);
        clearInvocations(balance);

        assertThrows(IllegalStateException.class, () -> service.complete(transfer.getPublicId()));

        assertBalance("1000", "0", "1000");
        assertEquals(TransferStatus.FAILED, transfer.getStatus());
        assertEquals(TransferFailureReason.EXTERNAL_PROVIDER_REJECTED, transfer.getFailureReason());
        verify(balance, never()).settle(any());
        verifyNoInteractions(events);
    }

    @Test
    void completedTransferCannotFailOrRelease() {
        service.complete(transfer.getPublicId());
        clearInvocations(balance, events);

        assertThrows(IllegalStateException.class, () -> service.fail(
                transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED));

        assertBalance("700", "0", "700");
        assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
        assertNull(transfer.getFailureReason());
        verify(balance, never()).release(any());
        verifyNoInteractions(events);
    }

    @Test
    void internalTransferCannotBeCompletedByExternalFinalizer() {
        useInternalTransfer();

        assertThrows(IllegalStateException.class, () -> service.complete(transfer.getPublicId()));

        assertBalance("1000", "300", "700");
        assertEquals(TransferStatus.PENDING, transfer.getStatus());
        verify(balance, never()).settle(any());
        verifyNoInteractions(events);
    }

    @Test
    void internalTransferCannotBeFailedByExternalFinalizer() {
        useInternalTransfer();

        assertThrows(IllegalStateException.class, () -> service.fail(
                transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED));

        assertBalance("1000", "300", "700");
        assertEquals(TransferStatus.PENDING, transfer.getStatus());
        verify(balance, never()).release(any());
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingTransferCannotBeFinalized(boolean complete) {
        when(transfers.findByPublicIdForUpdate(transfer.getPublicId())).thenReturn(Optional.empty());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            if (complete) {
                service.complete(transfer.getPublicId());
            } else {
                service.fail(transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED);
            }
        });

        assertEquals("Transfer not found", failure.getMessage());
        assertBalance("1000", "300", "700");
        assertEquals(TransferStatus.PENDING, transfer.getStatus());
        verify(balance, never()).settle(any());
        verify(balance, never()).release(any());
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void missingSourceBalanceCannotFinalizeTransfer(boolean complete) {
        when(balances.findByAccountPublicIdForUpdate(balance.getAccount().getPublicId()))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> {
            if (complete) {
                service.complete(transfer.getPublicId());
            } else {
                service.fail(transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED);
            }
        });

        assertBalance("1000", "300", "700");
        assertEquals(TransferStatus.PENDING, transfer.getStatus());
        assertNull(transfer.getFailureReason());
        verifyNoInteractions(events);
    }

    @Test
    void completionPreservesOtherOutstandingReservations() {
        balance.reserve(new BigDecimal("200"));

        service.complete(transfer.getPublicId());

        assertBalance("700", "200", "500");
        assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
        verify(events).publishCompleted(transfer.getPublicId());
    }

    @Test
    void rejectionPreservesOtherOutstandingReservations() {
        balance.reserve(new BigDecimal("200"));

        service.fail(transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED);

        assertBalance("1000", "200", "800");
        assertEquals(TransferStatus.FAILED, transfer.getStatus());
        assertEquals(TransferFailureReason.EXTERNAL_PROVIDER_REJECTED, transfer.getFailureReason());
        verifyNoInteractions(events);
    }

    @Test
    void repeatedFailCannotConsumeAnotherTransfersReservation() {
        balance.reserve(new BigDecimal("300"));
        service.fail(transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED);

        assertThrows(IllegalStateException.class, () -> service.fail(
                transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED));

        assertBalance("1000", "300", "700");
        assertEquals(TransferStatus.FAILED, transfer.getStatus());
        verifyNoInteractions(events);
    }

    @Test
    void completedTransferCannotReleaseAnotherTransfersReservation() {
        balance.reserve(new BigDecimal("300"));
        service.complete(transfer.getPublicId());
        clearInvocations(events);

        assertThrows(IllegalStateException.class, () -> service.fail(
                transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED));

        assertBalance("700", "300", "400");
        assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
        verifyNoInteractions(events);
    }

    private void useInternalTransfer() {
        Account destination = Account.create(null, "destination", "destination-iban", 2L, 2, CurrencyCode.EUR);
        transfer = Transfer.create("internal-ref", null, balance.getAccount(), null, destination,
                "name", destination.getIban(), "FluxBank", new BigDecimal("300"),
                CurrencyCode.EUR, TransferType.INTERNAL);
        when(transfers.findByPublicIdForUpdate(transfer.getPublicId())).thenReturn(Optional.of(transfer));
    }

    private void assertBalance(String current, String reserved, String available) {
        assertEquals(new BigDecimal(current), balance.getCurrentBalance());
        assertEquals(new BigDecimal(reserved), balance.getReservedBalance());
        assertEquals(new BigDecimal(available), balance.getAvailableBalance());
    }
}
