package com.fluxbank.transfer.application;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.account.domain.exception.*;
import com.fluxbank.account.repository.AccountRepository;
import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.beneficiary.exception.BeneficiaryNotFoundException;
import com.fluxbank.beneficiary.repository.BeneficiaryRepository;
import com.fluxbank.customer.domain.User;
import com.fluxbank.customer.repository.UserRepository;
import com.fluxbank.transfer.api.CreateTransferRequest;
import com.fluxbank.transfer.application.exception.*;
import com.fluxbank.transfer.domain.*;
import com.fluxbank.transfer.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {
    @Mock private UserRepository users;
    @Mock private AccountRepository accounts;
    @Mock private BeneficiaryRepository beneficiaries;
    @Mock private TransferRepository transfers;
    @Mock private TransferLifecycleService lifecycle;
    @Mock private InternalTransferProcessor processor;
    @InjectMocks private TransferService service;

    private User owner;
    private Account source;
    private Account destination;
    private Beneficiary beneficiary;
    private CreateTransferRequest request;
    private final String email = "owner@example.com";

    @BeforeEach
    void setUp() {
        owner = User.createCustomer("Owner", "One", email, "unused", 1000001L);
        source = Account.create(owner, "611-1000001-001", "source-iban", 1L, 1, CurrencyCode.EUR);
        destination = Account.create(owner, "611-1000001-002", "destination-iban", 2L, 2, CurrencyCode.EUR);
        beneficiary = Beneficiary.create(owner, "Ada", "Lovelace", destination.getIban(), "FluxBank");
        request = new CreateTransferRequest(source.getPublicId(), beneficiary.getPublicId(), new BigDecimal("10.00"));
    }

    @Test
    void shouldRejectSourceNotFoundForAuthenticatedOwnerBeforeCreatingPendingTransfer() {
        when(users.findByEmail(email)).thenReturn(Optional.of(owner));
        when(accounts.findByPublicIdAndUser(source.getPublicId(), owner)).thenReturn(Optional.empty());
        assertThrows(SourceAccountNotFoundException.class, () -> service.createInternalTransfer(email, request));
        verifyNoInteractions(lifecycle, processor);
    }

    @Test
    void shouldReuseBeneficiaryNotFoundForMissingInactiveOrUnownedBeneficiary() {
        when(users.findByEmail(email)).thenReturn(Optional.of(owner));
        when(accounts.findByPublicIdAndUser(source.getPublicId(), owner)).thenReturn(Optional.of(source));
        when(beneficiaries.findByPublicIdAndOwnerAndActiveTrue(beneficiary.getPublicId(), owner)).thenReturn(Optional.empty());
        assertThrows(BeneficiaryNotFoundException.class, () -> service.createInternalTransfer(email, request));
        verifyNoInteractions(lifecycle, processor);
    }

    @Test
    void shouldRejectDestinationOutsideFluxBankBeforeCreatingPendingTransfer() {
        when(users.findByEmail(email)).thenReturn(Optional.of(owner));
        when(accounts.findByPublicIdAndUser(source.getPublicId(), owner)).thenReturn(Optional.of(source));
        when(beneficiaries.findByPublicIdAndOwnerAndActiveTrue(beneficiary.getPublicId(), owner)).thenReturn(Optional.of(beneficiary));
        when(accounts.findByIban(beneficiary.getIban())).thenReturn(Optional.empty());
        assertThrows(BeneficiaryNotFluxBankAccountException.class, () -> service.createInternalTransfer(email, request));
        verifyNoInteractions(lifecycle, processor);
    }

    @Test
    void shouldRejectSameAccountBeforeCreatingPendingTransfer() {
        when(users.findByEmail(email)).thenReturn(Optional.of(owner));
        when(accounts.findByPublicIdAndUser(source.getPublicId(), owner)).thenReturn(Optional.of(source));
        when(beneficiaries.findByPublicIdAndOwnerAndActiveTrue(beneficiary.getPublicId(), owner)).thenReturn(Optional.of(beneficiary));
        when(accounts.findByIban(beneficiary.getIban())).thenReturn(Optional.of(source));
        assertThrows(SameAccountTransferException.class, () -> service.createInternalTransfer(email, request));
        verifyNoInteractions(lifecycle, processor);
    }

    static Stream<Arguments> processingFailures() {
        return Stream.of(
                Arguments.of(new InsufficientFundsException("Insufficient account balance"), TransferFailureReason.INSUFFICIENT_FUNDS),
                Arguments.of(new AccountNotAvailableForDebitException("Unavailable"), TransferFailureReason.SOURCE_ACCOUNT_UNAVAILABLE),
                Arguments.of(new AccountNotAvailableForCreditException("Unavailable"), TransferFailureReason.DESTINATION_ACCOUNT_UNAVAILABLE),
                Arguments.of(new CurrencyMismatchException("Currencies differ"), TransferFailureReason.CURRENCY_MISMATCH)
        );
    }

    @ParameterizedTest
    @MethodSource("processingFailures")
    void shouldMarkProcessingFailureWithCorrectReasonAndRethrowSameException(RuntimeException failure, TransferFailureReason reason) {
        when(users.findByEmail(email)).thenReturn(Optional.of(owner));
        when(accounts.findByPublicIdAndUser(source.getPublicId(), owner)).thenReturn(Optional.of(source));
        when(beneficiaries.findByPublicIdAndOwnerAndActiveTrue(beneficiary.getPublicId(), owner)).thenReturn(Optional.of(beneficiary));
        when(accounts.findByIban(beneficiary.getIban())).thenReturn(Optional.of(destination));
        UUID transferId = UUID.randomUUID();
        when(lifecycle.createPending(owner, source, beneficiary, destination, request.amount(), TransferType.INTERNAL)).thenReturn(transferId);
        doThrow(failure).when(processor).process(transferId, source.getPublicId(), destination.getPublicId());

        assertSame(failure, assertThrows(failure.getClass(), () -> service.createInternalTransfer(email, request)));

        var order = inOrder(lifecycle, processor);
        order.verify(lifecycle).createPending(owner, source, beneficiary, destination, request.amount(), TransferType.INTERNAL);
        order.verify(processor).process(transferId, source.getPublicId(), destination.getPublicId());
        order.verify(lifecycle).markFailed(transferId, reason);
        verify(lifecycle, never()).getResponse(any());
    }

    @Test
    void shouldKeepFailedTransferIncompleteWhenLifecycleRecordsInsufficientFunds() {
        var transfer = Transfer.create("TR-1", owner, source, beneficiary, destination, "Ada Lovelace",
                beneficiary.getIban(), beneficiary.getBankName(), request.amount(), CurrencyCode.EUR, TransferType.INTERNAL);
        when(transfers.findByPublicId(transfer.getPublicId())).thenReturn(Optional.of(transfer));
        var realLifecycle = new TransferLifecycleService(transfers, mock(TransferReferenceGenerator.class));

        realLifecycle.markFailed(transfer.getPublicId(), TransferFailureReason.INSUFFICIENT_FUNDS);

        assertEquals(TransferStatus.FAILED, transfer.getStatus());
        assertEquals(TransferFailureReason.INSUFFICIENT_FUNDS, transfer.getFailureReason());
        assertNull(transfer.getCompletedAt());
    }
}
