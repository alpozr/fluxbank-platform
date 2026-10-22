package com.fluxbank.transfer.application;

import com.fluxbank.account.domain.*;
import com.fluxbank.account.repository.AccountBalanceRepository;
import com.fluxbank.transfer.domain.*;
import com.fluxbank.transfer.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        balance = AccountBalance.create(source);
        balance.credit(new BigDecimal("100"));
        balance.reserve(new BigDecimal("25"));
        transfer = Transfer.create("ref", null, source, null, null, "name", "external-iban",
                "bank", new BigDecimal("25"), CurrencyCode.EUR, TransferType.EXTERNAL);
        when(transfers.findByPublicIdForUpdate(transfer.getPublicId())).thenReturn(Optional.of(transfer));
        when(balances.findByAccountPublicIdForUpdate(source.getPublicId())).thenReturn(Optional.of(balance));
    }

    @Test
    void successSettlesCompletesAndThenPublishes() {
        doAnswer(invocation -> {
            assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
            assertEquals(new BigDecimal("75"), balance.getCurrentBalance());
            assertEquals(BigDecimal.ZERO, balance.getReservedBalance());
            return null;
        }).when(events).publishCompleted(transfer.getPublicId());

        service.complete(transfer.getPublicId());

        verify(events).publishCompleted(transfer.getPublicId());
    }

    @Test
    void rejectionReleasesAndFailsWithoutCompletedEvent() {
        service.fail(transfer.getPublicId(), TransferFailureReason.EXTERNAL_PROVIDER_REJECTED);
        assertEquals(TransferStatus.FAILED, transfer.getStatus());
        assertEquals(new BigDecimal("100"), balance.getCurrentBalance());
        assertEquals(BigDecimal.ZERO, balance.getReservedBalance());
        verifyNoInteractions(events);
    }

    @Test
    void failedSettlementDoesNotPublishCompletion() {
        balance.release(new BigDecimal("25"));
        assertThrows(IllegalStateException.class, () -> service.complete(transfer.getPublicId()));
        assertEquals(TransferStatus.PENDING, transfer.getStatus());
        verifyNoInteractions(events);
    }
}
