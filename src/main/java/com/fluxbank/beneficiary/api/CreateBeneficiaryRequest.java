package com.fluxbank.beneficiary.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateBeneficiaryRequest(

        @NotBlank
        @Size(max = 100)
        String firstName,

        @NotBlank
        @Size(max = 100)
        String lastName,

        @NotBlank
        @Size(max = 34)
        String iban,

        @NotBlank
        @Size(max = 150)
        String bankName

) {
}