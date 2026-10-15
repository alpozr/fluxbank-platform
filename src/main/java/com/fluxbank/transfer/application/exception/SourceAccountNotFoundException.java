package com.fluxbank.transfer.application.exception;

public class SourceAccountNotFoundException extends RuntimeException {

    public SourceAccountNotFoundException() {
        super("Source account not found");
    }
}
