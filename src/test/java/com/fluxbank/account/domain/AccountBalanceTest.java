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

    @Test
    void reserveIncreasesReservedBalanceWithoutChangingCurrentBalance() {
        AccountBalance balance = balanceWithReservation("200");

        balance.reserve(new BigDecimal("300"));

        assertBalance(balance, "1000", "500", "500");
    }

    @Test
    void reserveCanUseExactlyAllAvailableBalance() {
        AccountBalance balance = balanceWithReservation("200");

        balance.reserve(new BigDecimal("800"));

        assertBalance(balance, "1000", "1000", "0");
    }

    @Test
    void reserveMoreThanAvailableIsRejectedWithoutMutation() {
        AccountBalance balance = balanceWithReservation("200");

        assertThrows(InsufficientFundsException.class,
                () -> balance.reserve(new BigDecimal("900")));

        assertBalance(balance, "1000", "200", "800");
    }

    @Test
    void releaseReducesReservedBalanceWithoutChangingCurrentBalance() {
        AccountBalance balance = balanceWithReservation("500");

        balance.release(new BigDecimal("300"));

        assertBalance(balance, "1000", "200", "800");
    }

    @Test
    void releaseMoreThanReservedIsRejectedWithoutMutation() {
        AccountBalance balance = balanceWithReservation("200");

        assertThrows(IllegalStateException.class,
                () -> balance.release(new BigDecimal("300")));

        assertBalance(balance, "1000", "200", "800");
    }

    @Test
    void settleReducesBothCurrentAndReservedBalance() {
        AccountBalance balance = balanceWithReservation("500");

        balance.settle(new BigDecimal("300"));

        assertBalance(balance, "700", "200", "500");
    }

    @Test
    void settleMoreThanReservedIsRejectedWithoutMutation() {
        AccountBalance balance = balanceWithReservation("200");

        assertThrows(IllegalStateException.class,
                () -> balance.settle(new BigDecimal("300")));

        assertBalance(balance, "1000", "200", "800");
    }

    @Test
    void multipleReservationsAccumulate() {
        AccountBalance balance = AccountBalance.create(account());
        balance.credit(new BigDecimal("1000"));

        balance.reserve(new BigDecimal("200"));
        balance.reserve(new BigDecimal("300"));

        assertBalance(balance, "1000", "500", "500");
    }

    @Test
    void settlingOneReservationPreservesOtherOutstandingReservations() {
        AccountBalance balance = balanceWithReservation("200");
        balance.reserve(new BigDecimal("300"));

        balance.settle(new BigDecimal("300"));

        assertBalance(balance, "700", "200", "500");
    }

    @Test
    void releasingOneReservationPreservesOtherOutstandingReservations() {
        AccountBalance balance = balanceWithReservation("200");
        balance.reserve(new BigDecimal("300"));

        balance.release(new BigDecimal("300"));

        assertBalance(balance, "1000", "200", "800");
    }

    @Test
    void reserveRejectsNonPositiveAmountsWithoutMutation() {
        AccountBalance balance = balanceWithReservation("200");

        for (BigDecimal amount : new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ONE.negate()}) {
            assertThrows(IllegalArgumentException.class, () -> balance.reserve(amount));
            assertBalance(balance, "1000", "200", "800");
        }
    }

    @Test
    void releaseRejectsNonPositiveAmountsWithoutMutation() {
        AccountBalance balance = balanceWithReservation("200");

        for (BigDecimal amount : new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ONE.negate()}) {
            assertThrows(IllegalArgumentException.class, () -> balance.release(amount));
            assertBalance(balance, "1000", "200", "800");
        }
    }

    @Test
    void settleRejectsNonPositiveAmountsWithoutMutation() {
        AccountBalance balance = balanceWithReservation("200");

        for (BigDecimal amount : new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ONE.negate()}) {
            assertThrows(IllegalArgumentException.class, () -> balance.settle(amount));
            assertBalance(balance, "1000", "200", "800");
        }
    }

    private AccountBalance balanceWithReservation(String reserved) {
        AccountBalance balance = AccountBalance.create(account());
        balance.credit(new BigDecimal("1000"));
        balance.reserve(new BigDecimal(reserved));
        return balance;
    }

    private void assertBalance(AccountBalance balance, String current, String reserved, String available) {
        assertEquals(new BigDecimal(current), balance.getCurrentBalance());
        assertEquals(new BigDecimal(reserved), balance.getReservedBalance());
        assertEquals(new BigDecimal(available), balance.getAvailableBalance());
    }
}
