package com.fluxbank.account.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {
    private Account account() {
        Account account = Account.create(null, "number", "iban", 1L, 1, CurrencyCode.EUR);
        AccountBalance.create(account);
        return account;
    }

    @Test
    void closingAgainIsIdempotentAndPreservesBalance() {
        Account account = account();
        account.close();

        assertDoesNotThrow(account::close);

        assertTrue(account.isClosed());
        assertFalse(account.isBlocked());
        assertFalse(account.isDormant());
        assertFalse(account.isExpired());
        assertEquals(BigDecimal.ZERO, account.getBalance().getCurrentBalance());
        assertEquals(BigDecimal.ZERO, account.getBalance().getReservedBalance());
    }

    @ParameterizedTest
    @ValueSource(strings = {"block", "unblock", "markDormant", "reactivate"})
    void closedAccountRejectsOperationalStateChanges(String operation) {
        Account account = account();
        account.close();

        assertThrows(IllegalStateException.class, () -> {
            switch (operation) {
                case "block" -> account.block();
                case "unblock" -> account.unblock();
                case "markDormant" -> account.markDormant();
                case "reactivate" -> account.reactivate();
                default -> throw new IllegalArgumentException("Unexpected test operation");
            }
        });

        assertTrue(account.isClosed());
        assertFalse(account.isBlocked());
        assertFalse(account.isDormant());
        assertFalse(account.isExpired());
    }

    @Test
    void expiringClosedAccountIsNoOp() {
        Account account = account();
        account.close();

        account.expire();

        assertTrue(account.isClosed());
        assertFalse(account.isExpired());
    }

    @Test
    void unblockingDoesNotReactivateDormantAccount() {
        Account account = account();
        account.markDormant();
        account.block();

        account.unblock();

        assertFalse(account.isBlocked());
        assertTrue(account.isDormant());
        assertFalse(account.isClosed());
    }

    @Test
    void reactivatingDoesNotUnblockAccount() {
        Account account = account();
        account.markDormant();
        account.block();

        account.reactivate();

        assertFalse(account.isDormant());
        assertTrue(account.isBlocked());
        assertFalse(account.isClosed());
    }
}
