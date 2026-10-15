package com.fluxbank.transfer.application.exception;

public class SameAccountTransferException extends RuntimeException {

    public SameAccountTransferException() {
        super("Source and destination accounts must be different");
    }
}
