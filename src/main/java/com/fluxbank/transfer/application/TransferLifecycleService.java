package com.fluxbank.transfer.application;

import com.fluxbank.account.domain.Account;
import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.customer.domain.User;
import com.fluxbank.transfer.api.TransferResponse;
import com.fluxbank.transfer.domain.Transfer;
import com.fluxbank.transfer.domain.TransferFailureReason;
import com.fluxbank.transfer.domain.TransferType;
import com.fluxbank.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class TransferLifecycleService {

    private final TransferRepository transferRepository;
    private final TransferReferenceGenerator referenceGenerator;

    public TransferLifecycleService(
            TransferRepository transferRepository,
            TransferReferenceGenerator referenceGenerator) {

        this.transferRepository = transferRepository;
        this.referenceGenerator = referenceGenerator;
    }

    @Transactional
    public UUID createPending(
            User owner,
            Account sourceAccount,
            Beneficiary beneficiary,
            Account destinationAccount,
            BigDecimal amount,
            TransferType type) {

        String referenceNumber = referenceGenerator.nextReference();

        String beneficiaryName =
                beneficiary.getFirstName() + " " + beneficiary.getLastName();

        Transfer transfer = Transfer.create(
                referenceNumber,
                owner,
                sourceAccount,
                beneficiary,
                destinationAccount,
                beneficiaryName,
                beneficiary.getIban(),
                beneficiary.getBankName(),
                amount,
                sourceAccount.getCurrency(),
                type
        );

        Transfer savedTransfer = transferRepository.save(transfer);

        return savedTransfer.getPublicId();
    }

    @Transactional
    public void markFailed(
            UUID transferPublicId,
            TransferFailureReason reason) {

        Transfer transfer = transferRepository
                .findByPublicId(transferPublicId)
                .orElseThrow(() ->
                        new IllegalStateException("Transfer not found")
                );

        transfer.fail(reason);
    }

    @Transactional(readOnly = true)
    public TransferResponse getResponse(UUID transferPublicId) {

        Transfer transfer = transferRepository
                .findByPublicId(transferPublicId)
                .orElseThrow(() ->
                        new IllegalStateException("Transfer not found")
                );

        return new TransferResponse(
                transfer.getPublicId(),
                transfer.getReferenceNumber(),
                transfer.getSourceAccount().getPublicId(),
                transfer.getBeneficiaryName(),
                transfer.getDestinationIban(),
                transfer.getDestinationBankName(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getType(),
                transfer.getStatus(),
                transfer.getFailureReason(),
                transfer.getCreatedAt(),
                transfer.getCompletedAt()
        );
    }
}