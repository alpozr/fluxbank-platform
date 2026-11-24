package com.fluxbank.transaction.application;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.account.repository.AccountRepository;
import com.fluxbank.transfer.application.TransferCompletedEvent;
import com.fluxbank.transaction.application.exception.TransactionHistoryInconsistentException;
import com.fluxbank.transaction.domain.Transaction;
import com.fluxbank.transaction.domain.TransactionDirection;
import com.fluxbank.transaction.domain.TransactionType;
import com.fluxbank.transaction.repository.TransactionRepository;
import com.fluxbank.transfer.domain.Transfer;
import com.fluxbank.transfer.domain.TransferStatus;
import com.fluxbank.transfer.domain.TransferType;
import com.fluxbank.transfer.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.math.BigDecimal;
import java.util.UUID;

@Service
public class TransactionHistoryService {

    private static final Logger log = LoggerFactory.getLogger(TransactionHistoryService.class);

    private final TransferRepository transferRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionReferenceGenerator referenceGenerator;
    private final AccountRepository accountRepository;

    public TransactionHistoryService(
            TransferRepository transferRepository,
            TransactionRepository transactionRepository,
            TransactionReferenceGenerator referenceGenerator,
            AccountRepository accountRepository) {
        this.transferRepository = transferRepository;
        this.transactionRepository = transactionRepository;
        this.referenceGenerator = referenceGenerator;
        this.accountRepository = accountRepository;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void createForTransfer(UUID transferPublicId) {
        Transfer transfer = completedTransfer(transferPublicId);
        createMovements(transfer, transfer.getSourceAccount(), transfer.getDestinationAccount(),
                transfer.getType(), transfer.getAmount(), transfer.getCurrency());
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void createForSnapshot(TransferCompletedEvent snapshot) {
        if (snapshot == null || snapshot.transferType() == null) {
            throw new IllegalArgumentException("A complete transfer snapshot is required");
        }
        Transfer transfer = completedTransfer(snapshot.transferPublicId());
        Account source = accountRepository.findByPublicId(snapshot.sourceAccountPublicId())
                .orElseThrow(() -> new IllegalStateException("Snapshot source account not found"));
        Account destination = snapshot.destinationAccountPublicId() == null ? null
                : accountRepository.findByPublicId(snapshot.destinationAccountPublicId())
                .orElseThrow(() -> new IllegalStateException("Snapshot destination account not found"));
        createMovements(transfer, source, destination, snapshot.transferType(), snapshot.amount(), snapshot.currency());
    }

    private Transfer completedTransfer(UUID transferPublicId) {
        Transfer transfer = transferRepository.findByPublicId(transferPublicId)
                .orElseThrow(() -> new IllegalStateException("Transfer not found"));

        if (transfer.getStatus() != TransferStatus.COMPLETED) {
            throw new IllegalStateException("Transaction history requires a completed transfer");
        }
        return transfer;
    }

    private void createMovements(Transfer transfer, Account source, Account destination,
                                 TransferType type, BigDecimal amount, CurrencyCode currency) {
        boolean debitExists = transactionRepository.existsByTransferAndAccountAndTypeAndDirection(
                transfer, source, TransactionType.TRANSFER, TransactionDirection.DEBIT);

        if (type == TransferType.INTERNAL) {
            boolean creditExists = transactionRepository.existsByTransferAndAccountAndTypeAndDirection(
                    transfer, destination, TransactionType.TRANSFER, TransactionDirection.CREDIT);

            if (debitExists != creditExists) {
                log.error("Inconsistent transaction history for transfer {}: debitExists={}, creditExists={}",
                        transfer.getPublicId(), debitExists, creditExists);
                throw new TransactionHistoryInconsistentException(
                        "Internal transfer history must contain both debit and credit movements"
                );
            }

            if (!debitExists) {
                transactionRepository.saveAll(List.of(
                        createMovement(transfer, source, TransactionDirection.DEBIT, amount, currency),
                        createMovement(transfer, destination, TransactionDirection.CREDIT, amount, currency)
                ));
            }
        } else if (type == TransferType.EXTERNAL) {
            if (!debitExists) {
                transactionRepository.save(createMovement(
                        transfer, source, TransactionDirection.DEBIT, amount, currency
                ));
            }
        } else {
            throw new IllegalStateException("Unsupported transfer type for transaction history");
        }
    }

    private Transaction createMovement(Transfer transfer, Account account, TransactionDirection direction,
                                        BigDecimal amount, CurrencyCode currency) {
        return Transaction.createTransferMovement(
                referenceGenerator.nextReference(),
                account,
                transfer,
                direction,
                amount,
                currency
        );
    }
}
