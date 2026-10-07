package com.fluxbank.account.api;

import com.fluxbank.account.domain.CurrencyCode;
import jakarta.validation.constraints.NotNull;

public record CreateAccountRequest(

        @NotNull
        Long customerNumber,

        @NotNull
        CurrencyCode currency

) {
}
