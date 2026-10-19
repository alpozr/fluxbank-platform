package com.fluxbank.transfer.application;

import com.fluxbank.account.domain.AccountBalance;
import com.fluxbank.account.repository.AccountBalanceRepository;
import com.fluxbank.transfer.domain.Transfer;
import com.fluxbank.transfer.domain.TransferFailureReason;
import com.fluxbank.transfer.domain.TransferStatus;
import com.fluxbank.transfer.domain.TransferType;
import com.fluxbank.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class ExternalTransferFinalizationService {

    private final TransferRepository transferRepository;
    private final AccountBalanceRepository accountBalanceRepository;

    public ExternalTransferFinalizationService(
            TransferRepository transferRepository,
            AccountBalanceRepository accountBalanceRepository
    ) {
        this.transferRepository = transferRepository;
        this.accountBalanceRepository = accountBalanceRepository;
    }

    @Transactional
     public void complete(UUID transferUUID){

        Transfer transfer =transferRepository.findByPublicIdForUpdate(transferUUID)
                .orElseThrow(()-> new IllegalStateException("Transfer not found")
                );

        validateExternalPending(transfer);

        UUID sourceAccountPublicId=transfer.getSourceAccount()
                .getPublicId();

        AccountBalance sourceAccountBalance=accountBalanceRepository
                .findByAccountPublicIdForUpdate(sourceAccountPublicId)
                .orElseThrow(()-> new IllegalStateException("Source Account balance not found"));

        BigDecimal settledAmount = transfer.getAmount();
        sourceAccountBalance.settle(settledAmount);
        transfer.complete();
    }

    @Transactional
    public void fail(UUID transferUUID,TransferFailureReason failureReason){
        Transfer transfer = transferRepository.findByPublicIdForUpdate(transferUUID)
                .orElseThrow(()-> new IllegalStateException("Transfer not found"));

        UUID sourceAccountUUID = transfer.getSourceAccount().getPublicId();

        AccountBalance sourceAccountBalance= accountBalanceRepository
                .findByAccountPublicIdForUpdate(sourceAccountUUID)
                .orElseThrow(()-> new IllegalStateException("Source account balance not found"));

        BigDecimal releasedAmount = transfer.getAmount();
        sourceAccountBalance.release(releasedAmount);
        transfer.fail(failureReason);
    }

    private void validateExternalPending(Transfer transfer) {

        if (transfer.getType() != TransferType.EXTERNAL) {
            throw new IllegalStateException(
                    "Only external transfers can be settled"
            );
        }

        if (transfer.getStatus() != TransferStatus.PENDING) {
            throw new IllegalStateException(
                    "Only pending transfers can be settled"
            );
        }
    }
}