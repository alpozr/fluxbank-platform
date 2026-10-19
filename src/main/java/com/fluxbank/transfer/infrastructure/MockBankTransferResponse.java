package com.fluxbank.transfer.infrastructure;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MockBankTransferResponse(

        @JsonProperty("transaction_reference")
        String transactionReference,

        @JsonProperty("status")
        String status
) {}