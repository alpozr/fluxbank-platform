package com.fluxbank.account.application;

import org.springframework.stereotype.Component;

import java.math.BigInteger;

@Component
public class IbanGenerator {

    private static final String COUNTRY_CODE = "BG";
    private static final String BANK_CODE = "FLUX";
    private static final String BRANCH_CODE = "0611";
    private static final String ACCOUNT_TYPE = "00";

    private static final long MIN_ACCOUNT_IDENTIFIER = 00_000_001L;
    private static final long MAX_ACCOUNT_IDENTIFIER = 99_999_999L;

    public String generate(Long accountIdentifier) {
        if (accountIdentifier == null
                || accountIdentifier < MIN_ACCOUNT_IDENTIFIER
                || accountIdentifier > MAX_ACCOUNT_IDENTIFIER) {
            throw new IllegalArgumentException(
                    "IBAN account identifier must be an 8-digit number"
            );
        }
        String formattedIdentifier = "%08d".formatted(accountIdentifier);

        String bban = BANK_CODE
                + BRANCH_CODE
                + ACCOUNT_TYPE
                + formattedIdentifier;

        String checkDigits = calculateCheckDigits(bban);

        return COUNTRY_CODE + checkDigits + bban;
    }

    private String calculateCheckDigits(String bban) {
        String rearranged = bban + COUNTRY_CODE + "00";
        String numericRepresentation = convertLettersToNumbers(rearranged);

        BigInteger numericValue = new BigInteger(numericRepresentation);
        int remainder = numericValue.mod(BigInteger.valueOf(97)).intValue();

        int checkDigits = 98 - remainder;

        return "%02d".formatted(checkDigits);
    }

    private String convertLettersToNumbers(String value) {
        StringBuilder result = new StringBuilder();

        for (char character : value.toCharArray()) {
            if (Character.isLetter(character)) {
                result.append(Character.getNumericValue(character));
            } else {
                result.append(character);
            }
        }

        return result.toString();
    }
}