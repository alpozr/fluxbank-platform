package com.fluxbank.transfer.application.exception;

public class BeneficiaryNotFluxBankAccountException extends RuntimeException {

    public BeneficiaryNotFluxBankAccountException() {
        super("Beneficiary is not a FluxBank account");
    }
}
