package com.fluxbank.transfer.application;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.transfer.domain.TransferType;

import java.math.BigDecimal;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferCompletedEvent(UUID transferPublicId, TransferType transferType,
                                     UUID sourceAccountPublicId, UUID destinationAccountPublicId,
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, CurrencyCode currency) {
    public TransferCompletedEvent {
        if (transferPublicId == null) {
            throw new IllegalArgumentException("Transfer public ID is required");
        }
        // Legacy payloads contain only the transfer ID. Never reconstruct their
        // business snapshot from later database state.
        boolean legacy = transferType == null && sourceAccountPublicId == null
                && destinationAccountPublicId == null && amount == null && currency == null;
        if (!legacy) {
            if (transferType == null || sourceAccountPublicId == null || amount == null
                    || amount.signum() <= 0 || currency == null) {
                throw new IllegalArgumentException("A complete transfer snapshot with a positive amount is required");
            }
            if (transferType == TransferType.INTERNAL && destinationAccountPublicId == null) {
                throw new IllegalArgumentException("Internal transfer snapshot requires a destination account");
            }
            if (transferType == TransferType.EXTERNAL && destinationAccountPublicId != null) {
                throw new IllegalArgumentException("External transfer snapshot cannot contain a destination account");
            }
        }
    }

    public TransferCompletedEvent(UUID transferPublicId) {
        this(transferPublicId, null, null, null, null, null);
    }
}
