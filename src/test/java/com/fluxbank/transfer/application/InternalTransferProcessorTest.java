package com.fluxbank.transfer.application;

import com.fluxbank.account.domain.*;
import com.fluxbank.account.domain.exception.*;
import com.fluxbank.account.repository.AccountBalanceRepository;
import com.fluxbank.transfer.domain.*;
import com.fluxbank.transfer.repository.TransferRepository;
import com.fluxbank.transfer.application.exception.CurrencyMismatchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalTransferProcessorTest {
    private final AccountBalanceRepository balances = mock(AccountBalanceRepository.class);
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final InternalTransferProcessor processor = new InternalTransferProcessor(balances, transfers);
    private Account source;
    private Account destination;
    private Transfer transfer;

    @BeforeEach
    void setUp() {
        source = Account.create(null, "source", "source-iban", 1L, 1, CurrencyCode.EUR);
        destination = Account.create(null, "destination", "destination-iban", 2L, 2, CurrencyCode.EUR);
        AccountBalance.create(source).credit(new BigDecimal("100"));
        AccountBalance.create(destination);
        transfer = Transfer.create("reference", null, source, null, destination,
                "name", destination.getIban(), "FluxBank", new BigDecimal("25"),
                CurrencyCode.EUR, TransferType.INTERNAL);
        when(transfers.findByPublicIdForUpdate(transfer.getPublicId())).thenReturn(Optional.of(transfer));
        // Reverse order ensures the processor resolves balances by UUID.
        when(balances.findAllByAccountPublicIdInForUpdate(
                List.of(source.getPublicId(), destination.getPublicId())))
                .thenReturn(List.of(destination.getBalance(), source.getBalance()));
    }

    private void process() {
        processor.process(transfer.getPublicId(), source.getPublicId(), destination.getPublicId());
    }

    @Test
    void completesTransferUsingBalancesResolvedByUuid() {
        process();
        assertEquals(new BigDecimal("75"), source.getBalance().getCurrentBalance());
        assertEquals(new BigDecimal("25"), destination.getBalance().getCurrentBalance());
        assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
        var order = inOrder(transfers, balances);
        order.verify(transfers).findByPublicIdForUpdate(transfer.getPublicId());
        order.verify(balances).findAllByAccountPublicIdInForUpdate(
                List.of(source.getPublicId(), destination.getPublicId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"blocked", "dormant", "closed", "expired"})
    void unavailableSourceIsRejected(String state) {
        ReflectionTestUtils.setField(source, state, true);
        assertThrows(AccountNotAvailableForDebitException.class, this::process);
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"blocked", "dormant"})
    void blockedAndDormantDestinationsAcceptTransfers(String state) {
        ReflectionTestUtils.setField(destination, state, true);
        process();
        assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
        assertEquals(new BigDecimal("25"), destination.getBalance().getCurrentBalance());
    }

    @ParameterizedTest
    @ValueSource(strings = {"closed", "expired"})
    void unavailableDestinationIsRejected(String state) {
        ReflectionTestUtils.setField(destination, state, true);
        assertThrows(AccountNotAvailableForCreditException.class, this::process);
        assertUnchanged();
    }

    @Test
    void reservedFundsCannotBeTransferred() {
        ReflectionTestUtils.setField(source.getBalance(), "reservedBalance", new BigDecimal("80"));
        assertThrows(InsufficientFundsException.class, this::process);
        assertUnchanged();
    }

    @Test
    void currencyMismatchIsRejected() {
        ReflectionTestUtils.setField(destination, "currency", CurrencyCode.USD);
        assertThrows(CurrencyMismatchException.class, this::process);
        assertUnchanged();
    }

    @Test
    void missingBalanceIsRejected() {
        when(balances.findAllByAccountPublicIdInForUpdate(any())).thenReturn(List.of(source.getBalance()));
        assertThrows(IllegalStateException.class, this::process);
        assertUnchanged();
    }

    @Test
    void completedTransferIsRejectedBeforeLockingBalances() {
        transfer.complete();
        assertThrows(IllegalStateException.class, this::process);
        verifyNoInteractions(balances);
    }

    @Test
    void externalTransferIsRejectedBeforeLockingBalances() {
        ReflectionTestUtils.setField(transfer, "type", TransferType.EXTERNAL);
        assertThrows(IllegalStateException.class, this::process);
        verifyNoInteractions(balances);
        assertUnchanged();
    }

    private void assertUnchanged() {
        assertEquals(new BigDecimal("100"), source.getBalance().getCurrentBalance());
        assertEquals(BigDecimal.ZERO, destination.getBalance().getCurrentBalance());
        assertEquals(TransferStatus.PENDING, transfer.getStatus());
    }
}
