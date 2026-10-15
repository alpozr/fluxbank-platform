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
import com.fluxbank.transfer.api.TransferResponse;
import com.fluxbank.transfer.application.exception.CurrencyMismatchException;
import com.fluxbank.transfer.application.exception.SourceAccountNotFoundException;
import com.fluxbank.transfer.application.exception.BeneficiaryNotFluxBankAccountException;
import com.fluxbank.transfer.application.exception.SameAccountTransferException;
import com.fluxbank.transfer.domain.Transfer;
import com.fluxbank.transfer.domain.TransferFailureReason;
import com.fluxbank.transfer.domain.TransferType;
import com.fluxbank.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TransferService {

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final BeneficiaryRepository beneficiaryRepository;
    private final TransferRepository transferRepository;
    private final TransferLifecycleService lifecycleService;
    private final InternalTransferProcessor internalTransferProcessor;

    public TransferService(
            UserRepository userRepository,
            AccountRepository accountRepository,
            BeneficiaryRepository beneficiaryRepository,
            TransferRepository transferRepository,
            TransferLifecycleService lifecycleService,
            InternalTransferProcessor internalTransferProcessor) {

        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.beneficiaryRepository = beneficiaryRepository;
        this.transferRepository = transferRepository;
        this.lifecycleService = lifecycleService;
        this.internalTransferProcessor = internalTransferProcessor;
    }

    public TransferResponse createInternalTransfer(
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

        Account destinationAccount = accountRepository
                .findByIban(beneficiary.getIban())
                .orElseThrow(BeneficiaryNotFluxBankAccountException::new);

        if (sourceAccount.getPublicId()
                .equals(destinationAccount.getPublicId())) {

            throw new SameAccountTransferException();
        }

        UUID transferPublicId = lifecycleService.createPending(
                user,
                sourceAccount,
                beneficiary,
                destinationAccount,
                request.amount(),
                TransferType.INTERNAL
        );

        try {
            internalTransferProcessor.process(
                    transferPublicId,
                    sourceAccount.getPublicId(),
                    destinationAccount.getPublicId()
            );

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
}