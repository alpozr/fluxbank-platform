package com.fluxbank.account.domain.exception;

public class AccountNotAvailableForDebitException extends RuntimeException {

    public AccountNotAvailableForDebitException(String message) {
        super(message);
    }
}