package com.fluxbank.common.iban;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IbanValidatorTest {

    private IbanValidator ibanValidator;

    @BeforeEach
    void setUp() {
        ibanValidator = new IbanValidator();
    }

    @Test
    void shouldValidateCorrectBulgarianIban() {
        String iban = "BG08FLUX06110000000001";

        assertTrue(ibanValidator.isValid(iban));
    }

    @Test
    void shouldNormalizeIbanWithSpacesAndLowercaseLetters() {
        String iban = "bg08 flux 0611 0000 0000 01";

        String normalized = ibanValidator.normalize(iban);

        assertEquals("BG08FLUX06110000000001", normalized);
        assertTrue(ibanValidator.isValid(iban));
    }

    @Test
    void shouldRejectIbanWithInvalidCheckDigits() {
        String iban = "BG09FLUX06110000000001";

        assertFalse(ibanValidator.isValid(iban));
    }

    @Test
    void shouldRejectIbanWithInvalidLength() {
        String iban = "BG08FLUX0611";

        assertFalse(ibanValidator.isValid(iban));
    }

    @Test
    void shouldRejectNonBulgarianIban() {
        String iban = "DE89370400440532013000";

        assertFalse(ibanValidator.isValid(iban));
    }

    @Test
    void shouldRejectNullIban() {
        assertFalse(ibanValidator.isValid(null));
    }

    @Test
    void shouldRejectIbanWithInvalidCharacters() {
        String iban = "BG08FLUX0611000000000!";

        assertFalse(ibanValidator.isValid(iban));
    }
}