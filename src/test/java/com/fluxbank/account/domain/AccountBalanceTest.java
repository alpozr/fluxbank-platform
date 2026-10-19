package com.fluxbank.account.domain;

import com.fluxbank.account.domain.exception.AccountCannotBeClosedException;
import com.fluxbank.account.domain.exception.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class AccountBalanceTest {
    private Account account() {
        return Account.create(null, "number", "iban", 1L, 1, CurrencyCode.EUR);
    }

    @Test
    void creationSynchronizesBothSidesAndPreventsDuplicateBalance() {
        Account account = account();
        AccountBalance balance = AccountBalance.create(account);
        assertSame(account, balance.getAccount());
        assertSame(balance, account.getBalance());
        assertEquals(BigDecimal.ZERO, balance.getCurrentBalance());
        assertEquals(BigDecimal.ZERO, balance.getReservedBalance());
        assertEquals(BigDecimal.ZERO, balance.getAvailableBalance());
        assertThrows(IllegalStateException.class, () -> AccountBalance.create(account));
        assertSame(balance, account.getBalance());
    }

    @Test
    void debitCannotSpendReservedFunds() {
        AccountBalance balance = AccountBalance.create(account());
        balance.credit(new BigDecimal("100.0000"));
        ReflectionTestUtils.setField(balance, "reservedBalance", new BigDecimal("60.0000"));
        assertEquals(new BigDecimal("40.0000"), balance.getAvailableBalance());
        assertThrows(InsufficientFundsException.class, () -> balance.debit(new BigDecimal("40.0001")));
        assertEquals(new BigDecimal("100.0000"), balance.getCurrentBalance());
        balance.debit(new BigDecimal("40.0000"));
        assertEquals(new BigDecimal("60.0000"), balance.getCurrentBalance());
        assertEquals(BigDecimal.ZERO.setScale(4), balance.getAvailableBalance());
    }

    @Test
    void mutationsRejectNonPositiveAndNullAmounts() {
        AccountBalance balance = AccountBalance.create(account());
        for (BigDecimal amount : new BigDecimal[]{null, BigDecimal.ZERO, BigDecimal.ONE.negate()}) {
            assertThrows(IllegalArgumentException.class, () -> balance.debit(amount));
            assertThrows(IllegalArgumentException.class, () -> balance.credit(amount));
        }
        assertEquals(BigDecimal.ZERO, balance.getCurrentBalance());
    }

    @Test
    void closingRequiresZeroCurrentBalanceAndPreservesDomainException() {
        Account account = account();
        AccountBalance balance = AccountBalance.create(account);
        balance.credit(BigDecimal.ONE);
        assertThrows(AccountCannotBeClosedException.class, account::close);
        balance.debit(BigDecimal.ONE);
        account.close();
        assertTrue(account.isClosed());
    }
}
