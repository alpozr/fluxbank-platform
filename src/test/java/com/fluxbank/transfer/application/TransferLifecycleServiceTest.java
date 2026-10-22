package com.fluxbank.transfer.application;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.customer.domain.User;
import com.fluxbank.transfer.api.TransferResponse;
import com.fluxbank.transfer.domain.*;
import com.fluxbank.transfer.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferLifecycleServiceTest {
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final TransferReferenceGenerator references = mock(TransferReferenceGenerator.class);
    private final TransferLifecycleService service = new TransferLifecycleService(transfers, references);
    private final User owner = User.createCustomer("Owner", "One", "owner@example.com", "unused", 1000001L);
    private final Account source = Account.create(owner, "source", "source-iban", 1L, 1, CurrencyCode.EUR);
    private final Account destination = Account.create(owner, "destination", "destination-iban", 2L, 2, CurrencyCode.EUR);
    private final Beneficiary beneficiary = Beneficiary.create(owner, "Ada", "Lovelace", "beneficiary-iban", "Beneficiary Bank");
    private final BigDecimal amount = new BigDecimal("100.00");

    @ParameterizedTest
    @EnumSource(TransferType.class)
    void createsPendingTransferWithCorrectDataAndSnapshots(TransferType type) {
        when(references.nextReference()).thenReturn("TRX-generated-reference");
        when(transfers.save(any(Transfer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Account target = type == TransferType.INTERNAL ? destination : null;

        UUID result = service.createPending(owner, source, beneficiary, target, amount, type);

        var saved = ArgumentCaptor.forClass(Transfer.class);
        verify(transfers).save(saved.capture());
        Transfer transfer = saved.getValue();
        assertNotNull(result);
        assertEquals(transfer.getPublicId(), result);
        assertEquals("TRX-generated-reference", transfer.getReferenceNumber());
        assertSame(owner, transfer.getOwner());
        assertSame(source, transfer.getSourceAccount());
        assertSame(beneficiary, transfer.getBeneficiary());
        assertSame(target, transfer.getDestinationAccount());
        assertEquals(amount, transfer.getAmount());
        assertEquals(CurrencyCode.EUR, transfer.getCurrency());
        assertEquals(type, transfer.getType());
        assertEquals(TransferStatus.PENDING, transfer.getStatus());
        assertNull(transfer.getFailureReason());
        assertNull(transfer.getCompletedAt());
        assertEquals("Ada Lovelace", transfer.getBeneficiaryName());
        assertEquals("beneficiary-iban", transfer.getDestinationIban());
        assertEquals("Beneficiary Bank", transfer.getDestinationBankName());
    }

    @ParameterizedTest
    @EnumSource(TransferStatus.class)
    void mapsExistingTransferToResponse(TransferStatus status) {
        Transfer transfer = pendingTransfer();
        if (status == TransferStatus.COMPLETED) {
            transfer.complete();
        } else if (status == TransferStatus.FAILED) {
            transfer.fail(TransferFailureReason.INSUFFICIENT_FUNDS);
        }
        when(transfers.findByPublicId(transfer.getPublicId())).thenReturn(Optional.of(transfer));

        TransferResponse response = service.getResponse(transfer.getPublicId());

        assertEquals(new TransferResponse(
                transfer.getPublicId(), "TRX-reference", source.getPublicId(), "Ada Lovelace",
                "beneficiary-iban", "Beneficiary Bank", amount, CurrencyCode.EUR,
                TransferType.INTERNAL, status, transfer.getFailureReason(),
                transfer.getCreatedAt(), transfer.getCompletedAt()), response);
    }

    @Test
    void missingTransferCannotBeMappedToResponse() {
        UUID id = UUID.randomUUID();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.getResponse(id));

        assertEquals("Transfer not found", exception.getMessage());
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = {"COMPLETED", "FAILED"})
    void markFailedCannotReplaceTerminalState(TransferStatus status) {
        Transfer transfer = pendingTransfer();
        if (status == TransferStatus.COMPLETED) {
            transfer.complete();
        } else {
            transfer.fail(TransferFailureReason.INSUFFICIENT_FUNDS);
        }
        TransferFailureReason originalReason = transfer.getFailureReason();
        Instant originalCompletedAt = transfer.getCompletedAt();
        when(transfers.findByPublicId(transfer.getPublicId())).thenReturn(Optional.of(transfer));

        assertThrows(IllegalStateException.class, () -> service.markFailed(
                transfer.getPublicId(), TransferFailureReason.CURRENCY_MISMATCH));

        assertEquals(status, transfer.getStatus());
        assertEquals(originalReason, transfer.getFailureReason());
        assertEquals(originalCompletedAt, transfer.getCompletedAt());
    }

    @Test
    void missingTransferCannotBeMarkedFailed() {
        UUID id = UUID.randomUUID();

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.markFailed(id, TransferFailureReason.INSUFFICIENT_FUNDS));

        assertEquals("Transfer not found", exception.getMessage());
    }

    private Transfer pendingTransfer() {
        return Transfer.create("TRX-reference", owner, source, beneficiary, destination,
                "Ada Lovelace", "beneficiary-iban", "Beneficiary Bank",
                amount, CurrencyCode.EUR, TransferType.INTERNAL);
    }
}
