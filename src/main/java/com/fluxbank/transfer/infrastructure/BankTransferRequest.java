package com.fluxbank.transfer.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record BankTransferRequest(

        @JsonProperty("idempotency_key")
        String idempotencyKey,

        @JsonProperty("from_account")
        String fromAccount,

        @JsonProperty("to_account")
        String toAccount,

        BigDecimal amount,

        String currency,

        String description
) {
}