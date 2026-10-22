package com.fluxbank.transaction.application.exception;

public class TransactionHistoryInconsistentException extends IllegalStateException {

    public TransactionHistoryInconsistentException(String message) {
        super(message);
    }
}
