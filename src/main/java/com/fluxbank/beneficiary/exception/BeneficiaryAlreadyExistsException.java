package com.fluxbank.beneficiary.exception;

public class BeneficiaryAlreadyExistsException extends RuntimeException {

    public BeneficiaryAlreadyExistsException() {
        super("Beneficiary already exists");
    }
}