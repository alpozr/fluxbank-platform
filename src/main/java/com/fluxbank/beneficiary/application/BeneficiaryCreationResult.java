package com.fluxbank.beneficiary.application;

import com.fluxbank.beneficiary.api.BeneficiaryResponse;

public record BeneficiaryCreationResult(
        BeneficiaryResponse beneficiary,
        boolean created
) {
}