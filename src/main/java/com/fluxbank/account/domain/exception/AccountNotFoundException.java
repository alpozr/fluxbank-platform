package com.fluxbank.account.domain.exception;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException() {
        super("Account not found");
    }
}