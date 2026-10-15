package com.fluxbank.transfer.api;

import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.transfer.domain.TransferFailureReason;
import com.fluxbank.transfer.domain.TransferStatus;
import com.fluxbank.transfer.domain.TransferType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID publicId,
        String referenceNumber,

        UUID sourceAccountPublicId,

        String beneficiaryName,
        String destinationIban,
        String destinationBankName,

        BigDecimal amount,
        CurrencyCode currency,

        TransferType type,
        TransferStatus status,
        TransferFailureReason failureReason,

        Instant createdAt,
        Instant completedAt
) {
}