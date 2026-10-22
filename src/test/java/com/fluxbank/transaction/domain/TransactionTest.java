package com.fluxbank.transaction.domain;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.transfer.domain.Transfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TransactionTest {
    private final Account account = mock(Account.class);
    private final Transfer transfer = mock(Transfer.class);

    @ParameterizedTest
    @EnumSource(TransactionDirection.class)
    void createsPositiveTransferMovement(TransactionDirection direction) {
        BigDecimal amount = new BigDecimal("12.3456");
        Transaction movement = Transaction.createTransferMovement(
                "caller-supplied-reference", account, transfer, direction, amount, CurrencyCode.EUR);

        assertNotNull(movement.getPublicId());
        assertEquals("caller-supplied-reference", movement.getReferenceNumber());
        assertSame(account, movement.getAccount());
        assertSame(transfer, movement.getTransfer());
        assertEquals(TransactionType.TRANSFER, movement.getType());
        assertEquals(direction, movement.getDirection());
        assertEquals(amount, movement.getAmount());
        assertEquals(CurrencyCode.EUR, movement.getCurrency());
        assertNotEquals(movement.getPublicId(), create("another-reference", account, transfer,
                direction, amount, CurrencyCode.EUR).getPublicId());
    }

    @Test
    void rejectsMissingOrBlankReference() {
        for (String reference : new String[]{null, "", " \t\n"}) {
            assertThrows(IllegalArgumentException.class, () -> create(reference, account, transfer,
                    TransactionDirection.DEBIT, BigDecimal.ONE, CurrencyCode.EUR));
        }
    }

    @Test
    void rejectsMissingRelationshipsDirectionAndCurrency() {
        assertThrows(IllegalArgumentException.class, () -> create("ref", null, transfer,
                TransactionDirection.DEBIT, BigDecimal.ONE, CurrencyCode.EUR));
        assertThrows(IllegalArgumentException.class, () -> create("ref", account, null,
                TransactionDirection.DEBIT, BigDecimal.ONE, CurrencyCode.EUR));
        assertThrows(IllegalArgumentException.class, () -> create("ref", account, transfer,
                null, BigDecimal.ONE, CurrencyCode.EUR));
        assertThrows(IllegalArgumentException.class, () -> create("ref", account, transfer,
                TransactionDirection.DEBIT, BigDecimal.ONE, null));
    }

    @Test
    void rejectsNullZeroAndNegativeAmounts() {
        for (BigDecimal amount : new BigDecimal[]{null, BigDecimal.ZERO, new BigDecimal("-0.0001")}) {
            assertThrows(IllegalArgumentException.class, () -> create("ref", account, transfer,
                    TransactionDirection.CREDIT, amount, CurrencyCode.EUR));
        }
    }

    private Transaction create(String reference, Account account, Transfer transfer,
                               TransactionDirection direction, BigDecimal amount, CurrencyCode currency) {
        return Transaction.createTransferMovement(reference, account, transfer, direction, amount, currency);
    }
}
