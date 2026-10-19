package com.fluxbank.transfer.application;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.AccountBalance;
import com.fluxbank.account.repository.AccountBalanceRepository;
import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.customer.domain.User;
import com.fluxbank.transfer.api.ReservedExternalTransfer;
import com.fluxbank.transfer.domain.Transfer;
import com.fluxbank.transfer.domain.TransferType;
import com.fluxbank.transfer.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class ExternalTransferReservationService {

    private final AccountBalanceRepository accountBalanceRepository;
    private final TransferRepository transferRepository;
    private final TransferReferenceGenerator transferReferenceGenerator;

    public ExternalTransferReservationService(
            AccountBalanceRepository accountBalanceRepository,
            TransferRepository transferRepository,
            TransferReferenceGenerator transferReferenceGenerator
    ) {
        this.accountBalanceRepository = accountBalanceRepository;
        this.transferRepository = transferRepository;
        this.transferReferenceGenerator = transferReferenceGenerator;
    }

    @Transactional
    public ReservedExternalTransfer createPendingAndReserve(
            User owner,
            UUID sourceAccountPublicId,
            Beneficiary beneficiary,
            BigDecimal amount
    ) {

        AccountBalance sourceBalance =
                accountBalanceRepository
                        .findByAccountPublicIdForUpdate(sourceAccountPublicId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Source account balance not found"
                                )
                        );

        Account sourceAccount = sourceBalance.getAccount();

        sourceAccount.validateAvailableForDebit();

        String referenceNumber =
                transferReferenceGenerator.nextReference();

        String beneficiaryName =
                beneficiary.getFirstName() + " " + beneficiary.getLastName();

        String destinationIban =
                beneficiary.getIban();

        String destinationBankName =
                beneficiary.getBankName();

        Transfer transfer = Transfer.create(
                referenceNumber,
                owner,
                sourceAccount,
                beneficiary,
                null,
                beneficiaryName,
                destinationIban,
                destinationBankName,
                amount,
                sourceAccount.getCurrency(),
                TransferType.EXTERNAL
        );

        sourceBalance.reserve(amount);

        Transfer savedTransfer =
                transferRepository.save(transfer);
        return new ReservedExternalTransfer(
                savedTransfer.getPublicId(),
                savedTransfer.getReferenceNumber(),
                savedTransfer.getBeneficiaryName(),
                savedTransfer.getDestinationIban(),
                savedTransfer.getDestinationBankName(),
                savedTransfer.getAmount(),
                savedTransfer.getCurrency().name()
        );
    }
}