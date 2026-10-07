package com.fluxbank.account.api;

import com.fluxbank.account.domain.CurrencyCode;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountResponse(
        UUID publicId,
        Long customerNumber,
        String accountNumber,
        String iban,
        CurrencyCode currency,
        BigDecimal balance,
        boolean closed,
        boolean dormant,
        boolean blocked,
        boolean expired
) {
}
