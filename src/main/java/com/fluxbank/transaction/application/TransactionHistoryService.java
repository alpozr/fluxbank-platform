package com.fluxbank.transaction.application;

import com.fluxbank.account.domain.Account;
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
import java.util.UUID;

@Service
public class TransactionHistoryService {

    private static final Logger log = LoggerFactory.getLogger(TransactionHistoryService.class);

    private final TransferRepository transferRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionReferenceGenerator referenceGenerator;

    public TransactionHistoryService(
            TransferRepository transferRepository,
            TransactionRepository transactionRepository,
            TransactionReferenceGenerator referenceGenerator) {
        this.transferRepository = transferRepository;
        this.transactionRepository = transactionRepository;
        this.referenceGenerator = referenceGenerator;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void createForTransfer(UUID transferPublicId) {
        Transfer transfer = transferRepository.findByPublicId(transferPublicId)
                .orElseThrow(() -> new IllegalStateException("Transfer not found"));

        if (transfer.getStatus() != TransferStatus.COMPLETED) {
            throw new IllegalStateException("Transaction history requires a completed transfer");
        }

        boolean debitExists = transactionRepository.existsByTransferAndAccountAndTypeAndDirection(
                transfer, transfer.getSourceAccount(), TransactionType.TRANSFER, TransactionDirection.DEBIT);

        if (transfer.getType() == TransferType.INTERNAL) {
            boolean creditExists = transactionRepository.existsByTransferAndAccountAndTypeAndDirection(
                    transfer, transfer.getDestinationAccount(), TransactionType.TRANSFER, TransactionDirection.CREDIT);

            if (debitExists != creditExists) {
                log.error("Inconsistent transaction history for transfer {}: debitExists={}, creditExists={}",
                        transferPublicId, debitExists, creditExists);
                throw new TransactionHistoryInconsistentException(
                        "Internal transfer history must contain both debit and credit movements"
                );
            }

            if (!debitExists) {
                transactionRepository.saveAll(List.of(
                        createMovement(transfer, transfer.getSourceAccount(), TransactionDirection.DEBIT),
                        createMovement(transfer, transfer.getDestinationAccount(), TransactionDirection.CREDIT)
                ));
            }
        } else if (transfer.getType() == TransferType.EXTERNAL) {
            if (!debitExists) {
                transactionRepository.save(createMovement(
                        transfer, transfer.getSourceAccount(), TransactionDirection.DEBIT
                ));
            }
        } else {
            throw new IllegalStateException("Unsupported transfer type for transaction history");
        }
    }

    private Transaction createMovement(Transfer transfer, Account account, TransactionDirection direction) {
        return Transaction.createTransferMovement(
                referenceGenerator.nextReference(),
                account,
                transfer,
                direction,
                transfer.getAmount(),
                transfer.getCurrency()
        );
    }
}
