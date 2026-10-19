package com.fluxbank.transfer.api;

import java.math.BigDecimal;
import java.util.UUID;

public record ReservedExternalTransfer(
        UUID publicId,
        String referenceNumber,
        String beneficiaryName,
        String destinationIban,
        String destinationBankName,
        BigDecimal amount,
        String currency
) {
}
