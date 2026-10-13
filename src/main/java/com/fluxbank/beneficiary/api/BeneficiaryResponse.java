package com.fluxbank.beneficiary.api;

import java.util.UUID;

public record BeneficiaryResponse(
        UUID publicId,
        String firstName,
        String lastName,
        String iban,
        String bankName
) {
}