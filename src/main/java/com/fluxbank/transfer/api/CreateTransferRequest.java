package com.fluxbank.transfer.api;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTransferRequest(

        @NotNull
        UUID sourceAccountPublicId,

        @NotNull
        UUID beneficiaryPublicId,

        @NotNull
        @Positive
        @Digits(integer = 17, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount
) {
}