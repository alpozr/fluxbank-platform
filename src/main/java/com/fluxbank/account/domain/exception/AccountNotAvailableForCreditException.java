package com.fluxbank.account.domain.exception;

public class AccountNotAvailableForCreditException extends RuntimeException {

    public AccountNotAvailableForCreditException(String message) {
        super(message);
    }
}