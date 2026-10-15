package com.fluxbank.transfer.domain;

public enum TransferFailureReason {
    INSUFFICIENT_FUNDS,
    SOURCE_ACCOUNT_UNAVAILABLE,
    DESTINATION_ACCOUNT_UNAVAILABLE,
    CURRENCY_MISMATCH
}