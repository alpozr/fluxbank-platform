import com.fluxbank.account.application.IbanGenerator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class IbanGeneratorTest {

    private final IbanGenerator ibanGenerator = new IbanGenerator();

    @Test
    void shouldGenerateValidBulgarianIban() {
        // given
        Long accountIdentifier = 10000000L;

        // when
        String iban = ibanGenerator.generate(accountIdentifier);

        // then
        assertEquals(22, iban.length());
        assertTrue(iban.startsWith("BG"));
        assertEquals("FLUX", iban.substring(4, 8));
        assertEquals("0611", iban.substring(8, 12));
        assertEquals("00", iban.substring(12, 14));
        assertEquals("10000000", iban.substring(14));

        assertTrue(isValidIban(iban));
    }

    private boolean isValidIban(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);

        StringBuilder numericRepresentation = new StringBuilder();

        for (char character : rearranged.toCharArray()) {
            if (Character.isLetter(character)) {
                numericRepresentation.append(Character.getNumericValue(character));
            } else {
                numericRepresentation.append(character);
            }
        }

        return new BigInteger(numericRepresentation.toString())
                .mod(BigInteger.valueOf(97))
                .intValue() == 1;
    }
    @Test
    void shouldPreserveEightDigitAccountIdentifier() {
        String iban = ibanGenerator.generate(99999999L);

        assertEquals(22, iban.length());
        assertEquals("99999999", iban.substring(14));
        assertTrue(isValidIban(iban));
    }

    @Test
    void shouldRejectInvalidAccountIdentifier() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ibanGenerator.generate(0L)
        );
    }
}
