package com.fluxbank.transfer.application;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.repository.AccountRepository;
import com.fluxbank.transfer.application.exception.CurrencyMismatchException;
import com.fluxbank.transfer.domain.Transfer;
import com.fluxbank.transfer.domain.TransferFailureReason;
import com.fluxbank.transfer.domain.TransferStatus;
import com.fluxbank.transfer.domain.TransferType;
import com.fluxbank.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class InternalTransferProcessor {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;

    public InternalTransferProcessor(
            AccountRepository accountRepository,
            TransferRepository transferRepository) {

        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
    }

    @Transactional
    public void process(
            UUID transferPublicId,
            UUID sourceAccountPublicId,
            UUID destinationAccountPublicId) {

        Transfer transfer = transferRepository
                .findByPublicIdForUpdate(transferPublicId)
                .orElseThrow(() ->
                        new IllegalStateException("Transfer not found")
                );

        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new IllegalStateException(
                    "Only pending transfers can be processed"
            );
        }

        if (transfer.getType() != TransferType.INTERNAL) {
            throw new IllegalStateException(
                    "Only internal transfers can be processed here"
            );
        }

        List<Account> lockedAccounts =
                accountRepository.findAllByPublicIdInForUpdate(
                        List.of(
                                sourceAccountPublicId,
                                destinationAccountPublicId
                        )
                );

            if (lockedAccounts.size() != 2) {
            throw new IllegalStateException(
                    "Source or destination account not found"
            );
        }

        Account sourceAccount = findAccount(
                lockedAccounts,
                sourceAccountPublicId
        );

        Account destinationAccount = findAccount(
                lockedAccounts,
                destinationAccountPublicId
        );

        if (sourceAccount.getCurrency()
                != destinationAccount.getCurrency()) {

            throw new CurrencyMismatchException(
                    "Source and destination account currencies must match"
            );
        }
        BigDecimal amount = transfer.getAmount();
        sourceAccount.debit(amount);
        destinationAccount.credit(amount);

        transfer.complete();
    }

    private Account findAccount(
            List<Account> accounts,
            UUID publicId) {

        return accounts.stream()
                .filter(account ->
                        account.getPublicId().equals(publicId)
                )
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("Account not found")
                );
    }
}