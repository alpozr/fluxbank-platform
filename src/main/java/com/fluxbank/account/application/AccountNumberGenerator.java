package com.fluxbank.account.application;

import org.springframework.stereotype.Component;

@Component
public class AccountNumberGenerator {

    private static final String DEFAULT_BRANCH_CODE = "611";

    public String generate(Long customerNumber, int suffix) {
        return "%s-%d-%03d".formatted(
                DEFAULT_BRANCH_CODE,
                customerNumber,
                suffix
        );
    }
}
