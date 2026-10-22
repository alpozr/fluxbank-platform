package com.fluxbank.transaction.application;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.transaction.application.exception.TransactionHistoryInconsistentException;
import com.fluxbank.transaction.domain.*;
import com.fluxbank.transaction.repository.TransactionRepository;
import com.fluxbank.transfer.domain.*;
import com.fluxbank.transfer.repository.TransferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionHistoryServiceTest {
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final TransactionRepository transactions = mock(TransactionRepository.class);
    private final TransactionReferenceGenerator references = mock(TransactionReferenceGenerator.class);
    private final TransactionHistoryService service = new TransactionHistoryService(transfers, transactions, references);
    private final Account source = Account.create(null, "source", "source-iban", 1L, 1, CurrencyCode.BGN);
    private final Account destination = Account.create(null, "destination", "destination-iban", 2L, 2, CurrencyCode.BGN);

    private Transfer transfer(TransferType type, boolean completed) {
        Transfer transfer = Transfer.create("TRX-reference", null, source, null,
                type == TransferType.INTERNAL ? destination : null,
                "beneficiary", "destination-iban", "bank", new BigDecimal("12.3456"),
                CurrencyCode.EUR, type);
        if (completed) {
            transfer.complete();
        }
        when(transfers.findByPublicId(transfer.getPublicId())).thenReturn(Optional.of(transfer));
        when(references.nextReference()).thenReturn("TXN-1", "TXN-2");
        return transfer;
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void internalCreatesBothMovementsUsingTransferAmountAndCurrency() {
        Transfer transfer = transfer(TransferType.INTERNAL, true);
        service.createForTransfer(transfer.getPublicId());

        ArgumentCaptor<List<Transaction>> rows = ArgumentCaptor.forClass((Class) List.class);
        verify(transactions).saveAll(rows.capture());
        assertEquals(2, rows.getValue().size());
        assertMovement(rows.getValue().get(0), transfer, source, TransactionDirection.DEBIT, "TXN-1");
        assertMovement(rows.getValue().get(1), transfer, destination, TransactionDirection.CREDIT, "TXN-2");
        verify(transactions, never()).save(any());
        verify(references, times(2)).nextReference();
    }

    @Test
    void externalCreatesOnlySourceDebit() {
        Transfer transfer = transfer(TransferType.EXTERNAL, true);
        service.createForTransfer(transfer.getPublicId());

        var row = ArgumentCaptor.forClass(Transaction.class);
        verify(transactions).save(row.capture());
        assertMovement(row.getValue(), transfer, source, TransactionDirection.DEBIT, "TXN-1");
        verify(transactions, never()).saveAll(any());
        verify(transactions, times(1)).existsByTransferAndAccountAndTypeAndDirection(
                transfer, source, TransactionType.TRANSFER, TransactionDirection.DEBIT);
        verify(references).nextReference();
    }

    @Test
    void completeInternalHistoryIsNoOp() {
        Transfer transfer = transfer(TransferType.INTERNAL, true);
        existing(transfer, source, TransactionDirection.DEBIT);
        existing(transfer, destination, TransactionDirection.CREDIT);
        service.createForTransfer(transfer.getPublicId());
        assertNoWrites();
    }

    @Test
    void existingExternalDebitIsNoOp() {
        Transfer transfer = transfer(TransferType.EXTERNAL, true);
        existing(transfer, source, TransactionDirection.DEBIT);
        service.createForTransfer(transfer.getPublicId());
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void partialInternalHistoryIsRejectedWithoutRepair(boolean debitExists) {
        Transfer transfer = transfer(TransferType.INTERNAL, true);
        existing(transfer, debitExists ? source : destination,
                debitExists ? TransactionDirection.DEBIT : TransactionDirection.CREDIT);

        assertThrows(TransactionHistoryInconsistentException.class,
                () -> service.createForTransfer(transfer.getPublicId()));
        verify(transactions).existsByTransferAndAccountAndTypeAndDirection(
                transfer, source, TransactionType.TRANSFER, TransactionDirection.DEBIT);
        verify(transactions).existsByTransferAndAccountAndTypeAndDirection(
                transfer, destination, TransactionType.TRANSFER, TransactionDirection.CREDIT);
        assertNoWrites();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void pendingAndFailedTransfersCannotGenerateHistory(boolean failed) {
        Transfer transfer = transfer(TransferType.INTERNAL, false);
        if (failed) {
            transfer.fail(TransferFailureReason.INSUFFICIENT_FUNDS);
        }
        assertThrows(IllegalStateException.class, () -> service.createForTransfer(transfer.getPublicId()));
        verifyNoInteractions(transactions);
        verifyNoInteractions(references);
    }

    @Test
    void missingTransferFailsExplicitly() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> service.createForTransfer(id));
        verifyNoInteractions(transactions, references);
    }

    @Test
    void persistenceFailureIsNotSwallowedAsDuplicate() {
        Transfer transfer = transfer(TransferType.EXTERNAL, true);
        var failure = new org.springframework.dao.DataIntegrityViolationException("reference collision");
        when(transactions.save(any())).thenThrow(failure);
        assertSame(failure, assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.createForTransfer(transfer.getPublicId())));
    }

    @Test
    void internalPersistenceFailureIsNotSwallowedAsDuplicate() {
        Transfer transfer = transfer(TransferType.INTERNAL, true);
        var failure = new org.springframework.dao.DataIntegrityViolationException("reference collision");
        when(transactions.saveAll(any())).thenThrow(failure);

        assertSame(failure, assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> service.createForTransfer(transfer.getPublicId())));

        assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
        verify(transactions, never()).save(any());
    }

    @Test
    void failureGeneratingSecondReferenceDoesNotSubmitPartialInternalHistory() {
        Transfer transfer = transfer(TransferType.INTERNAL, true);
        var failure = new IllegalStateException("Reference allocation failed");
        when(references.nextReference()).thenReturn("TXN-first").thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.createForTransfer(transfer.getPublicId())));

        verify(references, times(2)).nextReference();
        verify(transactions, never()).save(any());
        verify(transactions, never()).saveAll(any());
        assertEquals(TransferStatus.COMPLETED, transfer.getStatus());
    }

    private void existing(Transfer transfer, Account account, TransactionDirection direction) {
        when(transactions.existsByTransferAndAccountAndTypeAndDirection(
                transfer, account, TransactionType.TRANSFER, direction)).thenReturn(true);
    }

    private void assertNoWrites() {
        verify(transactions, never()).save(any());
        verify(transactions, never()).saveAll(any());
        verifyNoInteractions(references);
    }

    private void assertMovement(Transaction row, Transfer transfer, Account account,
                                TransactionDirection direction, String reference) {
        assertSame(transfer, row.getTransfer());
        assertSame(account, row.getAccount());
        assertEquals(TransactionType.TRANSFER, row.getType());
        assertEquals(direction, row.getDirection());
        assertEquals(transfer.getAmount(), row.getAmount());
        assertTrue(row.getAmount().signum() > 0);
        assertEquals(CurrencyCode.EUR, row.getCurrency()); // Accounts intentionally use BGN.
        assertEquals(reference, row.getReferenceNumber());
    }
}
