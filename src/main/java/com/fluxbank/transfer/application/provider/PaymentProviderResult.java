package com.fluxbank.transfer.application.provider;

public record PaymentProviderResult(
        PaymentProviderStatus status,
        String providerReference
) {
}
