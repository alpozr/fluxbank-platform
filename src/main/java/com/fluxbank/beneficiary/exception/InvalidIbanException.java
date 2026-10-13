package com.fluxbank.beneficiary.exception;

public class InvalidIbanException extends RuntimeException {

    public InvalidIbanException() {
        super("Invalid IBAN");
    }
}