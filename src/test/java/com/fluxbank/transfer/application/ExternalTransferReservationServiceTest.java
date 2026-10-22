package com.fluxbank.transfer.application;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.AccountBalance;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.account.domain.exception.AccountNotAvailableForDebitException;
import com.fluxbank.account.domain.exception.InsufficientFundsException;
import com.fluxbank.account.repository.AccountBalanceRepository;
import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.customer.domain.User;
import com.fluxbank.transfer.domain.Transfer;
import com.fluxbank.transfer.domain.TransferStatus;
import com.fluxbank.transfer.domain.TransferType;
import com.fluxbank.transfer.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalTransferReservationServiceTest {
    private final AccountBalanceRepository balances = mock(AccountBalanceRepository.class);
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final TransferReferenceGenerator references = mock(TransferReferenceGenerator.class);
    private final ExternalTransferReservationService service =
            new ExternalTransferReservationService(balances, transfers, references);
    private final String referenceNumber = "TRX-20261022-100001";
    private User owner;
    private Account source;
    private AccountBalance balance;
    private Beneficiary beneficiary;

    @BeforeEach
    void setUp() {
        owner = User.createCustomer("Owner", "One", "owner@example.com", "unused", 1000001L);
        source = Account.create(owner, "611-1000001-001", "source-iban", 1L, 1, CurrencyCode.EUR);
        balance = AccountBalance.create(source);
        beneficiary = Beneficiary.create(owner, "Ada", "Lovelace", "external-iban", "External Bank");
        when(balances.findByAccountPublicIdForUpdate(source.getPublicId()))
                .thenReturn(Optional.of(balance));
    }

    @Test
    void createsPendingExternalTransferAndPreservesExistingReservation() {
        fundWithExistingReservation();
        when(references.nextReference()).thenReturn(referenceNumber);
        when(transfers.save(any(Transfer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.createPendingAndReserve(
                owner, source.getPublicId(), beneficiary, new BigDecimal("300"));

        assertBalance("1000", "500", "500");
        verify(balances).findByAccountPublicIdForUpdate(source.getPublicId());
        var saved = ArgumentCaptor.forClass(Transfer.class);
        verify(transfers).save(saved.capture());
        Transfer transfer = saved.getValue();
        assertNotNull(transfer.getPublicId());
        assertEquals(referenceNumber, transfer.getReferenceNumber());
        assertSame(owner, transfer.getOwner());
        assertSame(source, transfer.getSourceAccount());
        assertSame(beneficiary, transfer.getBeneficiary());
        assertEquals("Ada Lovelace", transfer.getBeneficiaryName());
        assertEquals("external-iban", transfer.getDestinationIban());
        assertEquals("External Bank", transfer.getDestinationBankName());
        assertEquals(new BigDecimal("300"), transfer.getAmount());
        assertEquals(CurrencyCode.EUR, transfer.getCurrency());
        assertEquals(TransferType.EXTERNAL, transfer.getType());
        assertEquals(TransferStatus.PENDING, transfer.getStatus());
        assertNull(transfer.getDestinationAccount());
        assertNull(transfer.getFailureReason());
        assertNull(transfer.getCompletedAt());

        assertEquals(transfer.getPublicId(), result.publicId());
        assertEquals(referenceNumber, result.referenceNumber());
        assertEquals("Ada Lovelace", result.beneficiaryName());
        assertEquals("external-iban", result.destinationIban());
        assertEquals("External Bank", result.destinationBankName());
        assertEquals(new BigDecimal("300"), result.amount());
        assertEquals("EUR", result.currency());
    }

    @Test
    void insufficientAvailableBalanceIsRejectedWithoutMutationOrTransferSave() {
        fundWithExistingReservation();
        when(references.nextReference()).thenReturn(referenceNumber);

        assertThrows(InsufficientFundsException.class, () -> service.createPendingAndReserve(
                owner, source.getPublicId(), beneficiary, new BigDecimal("900")));

        assertBalance("1000", "200", "800");
        verify(transfers, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BLOCKED", "DORMANT", "CLOSED", "EXPIRED"})
    void outgoingUnavailableSourceCannotReserveOrCreateTransfer(String state) {
        // Apply state while the balance is zero so close() uses the normal domain API.
        switch (state) {
            case "BLOCKED" -> source.block();
            case "DORMANT" -> source.markDormant();
            case "CLOSED" -> source.close();
            case "EXPIRED" -> source.expire();
            default -> throw new IllegalArgumentException("Unexpected test state");
        }
        fundWithExistingReservation();

        assertThrows(AccountNotAvailableForDebitException.class, () -> service.createPendingAndReserve(
                owner, source.getPublicId(), beneficiary, new BigDecimal("300")));

        assertBalance("1000", "200", "800");
        verifyNoInteractions(transfers, references);
    }

    @Test
    void reservingExactlyAvailableBalanceLeavesPendingExternalTransfer() {
        fundWithExistingReservation();
        when(references.nextReference()).thenReturn(referenceNumber);
        when(transfers.save(any(Transfer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.createPendingAndReserve(
                owner, source.getPublicId(), beneficiary, new BigDecimal("800"));

        assertBalance("1000", "1000", "0");
        var saved = ArgumentCaptor.forClass(Transfer.class);
        verify(transfers).save(saved.capture());
        assertEquals(TransferStatus.PENDING, saved.getValue().getStatus());
        assertEquals(TransferType.EXTERNAL, saved.getValue().getType());
        assertEquals(new BigDecimal("800"), saved.getValue().getAmount());
        assertEquals(saved.getValue().getPublicId(), result.publicId());
        assertEquals(new BigDecimal("800"), result.amount());
    }

    @Test
    void missingSourceBalanceIsRejectedBeforeCreatingTransfer() {
        fundWithExistingReservation();
        when(balances.findByAccountPublicIdForUpdate(source.getPublicId())).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.createPendingAndReserve(
                owner, source.getPublicId(), beneficiary, new BigDecimal("300")));

        assertBalance("1000", "200", "800");
        verifyNoInteractions(transfers, references);
    }

    private void fundWithExistingReservation() {
        balance.credit(new BigDecimal("1000"));
        balance.reserve(new BigDecimal("200"));
    }

    private void assertBalance(String current, String reserved, String available) {
        assertEquals(new BigDecimal(current), balance.getCurrentBalance());
        assertEquals(new BigDecimal(reserved), balance.getReservedBalance());
        assertEquals(new BigDecimal(available), balance.getAvailableBalance());
    }
}
