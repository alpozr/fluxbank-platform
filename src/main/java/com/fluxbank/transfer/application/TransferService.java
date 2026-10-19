package com.fluxbank.transfer.application;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.exception.AccountNotAvailableForCreditException;
import com.fluxbank.account.domain.exception.AccountNotAvailableForDebitException;
import com.fluxbank.account.domain.exception.InsufficientFundsException;
import com.fluxbank.account.repository.AccountRepository;
import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.beneficiary.exception.BeneficiaryNotFoundException;
import com.fluxbank.beneficiary.repository.BeneficiaryRepository;
import com.fluxbank.customer.domain.User;
import com.fluxbank.customer.repository.UserRepository;
import com.fluxbank.transfer.api.CreateTransferRequest;
import com.fluxbank.transfer.api.ReservedExternalTransfer;
import com.fluxbank.transfer.api.TransferResponse;
import com.fluxbank.transfer.application.exception.CurrencyMismatchException;
import com.fluxbank.transfer.application.exception.SourceAccountNotFoundException;
import com.fluxbank.transfer.application.exception.SameAccountTransferException;
import com.fluxbank.transfer.application.provider.ExternalTransferProcessor;
import com.fluxbank.transfer.domain.TransferFailureReason;
import com.fluxbank.transfer.domain.TransferType;
import com.fluxbank.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransferService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final TransferRepository transferRepository;
    private final TransferLifecycleService lifecycleService;
    private final InternalTransferProcessor internalTransferProcessor;
    private final ExternalTransferReservationService externalTransferReservationService;
    private final ExternalTransferProcessor externalTransferProcess;

    public TransferService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            BeneficiaryRepository beneficiaryRepository,
            TransferRepository transferRepository,
            TransferLifecycleService lifecycleService,
            InternalTransferProcessor internalTransferProcessor, ExternalTransferReservationService externalTransferReservationService, ExternalTransferProcessor externalTransferProcess) {

        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.transferRepository = transferRepository;
        this.lifecycleService = lifecycleService;
        this.internalTransferProcessor = internalTransferProcessor;
        this.externalTransferReservationService = externalTransferReservationService;
        this.externalTransferProcess = externalTransferProcess;
    }

    public TransferResponse createTransfer(
            String authenticatedEmail,
            CreateTransferRequest request) {

        User user = userRepository
                .findByEmail(authenticatedEmail)
                .orElseThrow(() ->
                        new IllegalStateException("Authenticated user not found")
                );

        Account sourceAccount = accountRepository
                .findByPublicIdAndUser(
                        request.sourceAccountPublicId(),
                        user
                )
                .orElseThrow(SourceAccountNotFoundException::new);

        Beneficiary beneficiary = beneficiaryRepository
                .findByPublicIdAndOwnerAndActiveTrue(
                        request.beneficiaryPublicId(),
                        user
                )
                .orElseThrow(BeneficiaryNotFoundException::new);

        Optional<Account> destinationAccount =
                accountRepository.findByIban(
                        beneficiary.getIban()
                );

        if(destinationAccount.isPresent()){
            return processInternalTransfer(user,
                    sourceAccount,
                    beneficiary,
                    destinationAccount.get(),
                    request.amount());
        }
        return processExternalTransfer(
                user,
                sourceAccount,
                beneficiary,
                request.amount()
        );
    }


    private TransferResponse processInternalTransfer(
            User user,
            Account sourceAccount,
            Beneficiary beneficiary,
            Account destinationAccount,
            BigDecimal amount
    ) {
        if (sourceAccount.getPublicId()
                .equals(destinationAccount.getPublicId())) {

            throw new SameAccountTransferException();
        }

        UUID transferPublicId =
                lifecycleService.createPending(
                        user,
                        sourceAccount,
                        beneficiary,
                        destinationAccount,
                        amount,
                        TransferType.INTERNAL
                );

        try{
            internalTransferProcessor.process(transferPublicId,
                    sourceAccount.getPublicId(),
                    destinationAccount.getPublicId());
            return lifecycleService.getResponse(transferPublicId);
        } catch (InsufficientFundsException ex) {

            lifecycleService.markFailed(
                    transferPublicId,
                    TransferFailureReason.INSUFFICIENT_FUNDS
            );

            throw ex;

        } catch (AccountNotAvailableForDebitException ex) {

            lifecycleService.markFailed(
                    transferPublicId,
                    TransferFailureReason.SOURCE_ACCOUNT_UNAVAILABLE
            );

            throw ex;

        } catch (AccountNotAvailableForCreditException ex) {

            lifecycleService.markFailed(
                    transferPublicId,
                    TransferFailureReason.DESTINATION_ACCOUNT_UNAVAILABLE
            );

            throw ex;

        } catch (CurrencyMismatchException ex) {

            lifecycleService.markFailed(
                    transferPublicId,
                    TransferFailureReason.CURRENCY_MISMATCH
            );

            throw ex;
        }
    }
    private TransferResponse processExternalTransfer(
            User user,
            Account sourceAccount,
            Beneficiary beneficiary,
            BigDecimal amount
    ) {
        ReservedExternalTransfer externalTransfer = externalTransferReservationService.createPendingAndReserve(user,
                sourceAccount.getPublicId(), beneficiary,
                amount);
        externalTransferProcess.process(externalTransfer);
        return lifecycleService
                .getResponse(externalTransfer.publicId());
    }
}