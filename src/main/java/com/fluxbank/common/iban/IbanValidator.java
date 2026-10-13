package com.fluxbank.common.iban;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Locale;

@Component
public class IbanValidator {

    private static final String SUPPORTED_COUNTRY_CODE = "BG";
    private static final int BULGARIAN_IBAN_LENGTH = 22;
    private static final BigInteger MODULUS = BigInteger.valueOf(97);

    public String normalize(String iban) {
        if (iban == null) {
            return null;
        }

        return iban
                .replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
    }

    public boolean isValid(String iban) {
        if (iban == null) {
            return false;
        }

        String normalizedIban = normalize(iban);

        if (normalizedIban.length() != BULGARIAN_IBAN_LENGTH) {
            return false;
        }

        if (!normalizedIban.startsWith(SUPPORTED_COUNTRY_CODE)) {
            return false;
        }

        if (!normalizedIban.matches("[A-Z0-9]+")) {
            return false;
        }

        String rearranged =
                normalizedIban.substring(4)
                        + normalizedIban.substring(0, 4);

        String numericRepresentation =
                convertLettersToNumbers(rearranged);

        BigInteger numericValue =
                new BigInteger(numericRepresentation);

        return numericValue.mod(MODULUS)
                .equals(BigInteger.ONE);
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