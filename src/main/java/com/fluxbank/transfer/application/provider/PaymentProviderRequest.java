package com.fluxbank.transfer.application.provider;

import java.math.BigDecimal;

public record PaymentProviderRequest(
        String referenceNumber,
        String beneficiaryName,
        String destinationIban,
        String destinationBankName,
        BigDecimal amount,
        String currency
) {
}
