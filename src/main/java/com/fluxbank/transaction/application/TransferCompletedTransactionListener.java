package com.fluxbank.transaction.application;

import com.fluxbank.transfer.application.TransferCompletedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TransferCompletedTransactionListener {

    private final TransactionHistoryService transactionHistoryService;

    public TransferCompletedTransactionListener(TransactionHistoryService transactionHistoryService) {
        this.transactionHistoryService = transactionHistoryService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(TransferCompletedEvent event) {
        transactionHistoryService.createForTransfer(event.transferPublicId());
    }
}
