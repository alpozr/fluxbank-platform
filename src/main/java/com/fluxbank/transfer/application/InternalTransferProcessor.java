package com.fluxbank.transfer.application;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.AccountBalance;
import com.fluxbank.account.repository.AccountBalanceRepository;
import com.fluxbank.transfer.application.exception.CurrencyMismatchException;
import com.fluxbank.transfer.domain.Transfer;
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

    private final AccountBalanceRepository accountBalanceRepository;
    private final TransferRepository transferRepository;

    public InternalTransferProcessor(
            AccountBalanceRepository accountBalanceRepository,
            TransferRepository transferRepository
    ) {
        this.accountBalanceRepository = accountBalanceRepository;
        this.transferRepository = transferRepository;
    }

    @Transactional
    public void process(
            UUID transferPublicId,
            UUID sourceAccountPublicId,
            UUID destinationAccountPublicId
    ) {

        // 1. Lock the transfer so the same pending transfer
        // cannot be processed concurrently.
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

        // 2. Lock both balance rows.
        // Repository query orders them by Account ID
        // to provide deterministic lock ordering.
        List<AccountBalance> lockedBalances =
                accountBalanceRepository
                        .findAllByAccountPublicIdInForUpdate(
                                List.of(
                                        sourceAccountPublicId,
                                        destinationAccountPublicId
                                )
                        );

        if (lockedBalances.size() != 2) {
            throw new IllegalStateException(
                    "Source or destination account balance not found"
            );
        }

        // 3. Identify balances by publicId.
        // Never depend on list position.
        AccountBalance sourceBalance =
                findBalance(
                        lockedBalances,
                        sourceAccountPublicId
                );

        AccountBalance destinationBalance =
                findBalance(
                        lockedBalances,
                        destinationAccountPublicId
                );

        Account sourceAccount =
                sourceBalance.getAccount();

        Account destinationAccount =
                destinationBalance.getAccount();

        // 4. Internal transfers currently do not support FX.
        if (sourceAccount.getCurrency()
                != destinationAccount.getCurrency()) {

            throw new CurrencyMismatchException(
                    "Source and destination account currencies must match"
            );
        }

        sourceAccount.validateAvailableForDebit();
        destinationAccount.validateAvailableForCredit();

        BigDecimal amount = transfer.getAmount();

        // 5. Financial state belongs to AccountBalance.
        sourceBalance.debit(amount);
        destinationBalance.credit(amount);

        // 6. Balance changes and transfer completion
        // are committed atomically.
        transfer.complete();
    }

    private AccountBalance findBalance(
            List<AccountBalance> balances,
            UUID accountPublicId
    ) {
        return balances.stream()
                .filter(balance ->
                        balance.getAccount()
                                .getPublicId()
                                .equals(accountPublicId)
                )
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Account balance not found"
                        )
                );
    }
}