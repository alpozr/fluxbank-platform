package com.fluxbank.account.domain;

import com.fluxbank.account.domain.exception.InsufficientFundsException;
import jakarta.persistence.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account_balances")
@EntityListeners(AuditingEntityListener.class)
public class AccountBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "account_id",
            nullable = false,
            unique = true
    )
    private Account account;

    @Column(
            name = "current_balance",
            nullable = false,
            precision = 19,
            scale = 4
    )
    private BigDecimal currentBalance;

    @Column(
            name = "reserved_balance",
            nullable = false,
            precision = 19,
            scale = 4
    )
    private BigDecimal reservedBalance;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountBalance() {
    }

    public static AccountBalance create(Account account) {
        if (account == null) {
            throw new IllegalArgumentException(
                    "Account is required"
            );
        }

        AccountBalance balance = new AccountBalance();

        balance.account = account;
        balance.currentBalance = BigDecimal.ZERO;
        balance.reservedBalance = BigDecimal.ZERO;

        account.assignBalance(balance);

        return balance;
    }

    public Account getAccount() {
        return account;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public BigDecimal getReservedBalance() {
        return reservedBalance;
    }

    public BigDecimal getAvailableBalance() {
        return currentBalance.subtract(reservedBalance);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void debit(BigDecimal amount) {
        validateAmount(amount);

        if (getAvailableBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient available balance"
            );
        }

        currentBalance = currentBalance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        validateAmount(amount);
        currentBalance = currentBalance.add(amount);
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Amount must be greater than zero"
            );
        }
    }
    public void reserve(BigDecimal amount) {
        validateAmount(amount);

        if (getAvailableBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient available balance for reservation"
            );
        }

        reservedBalance = reservedBalance.add(amount);
    }

    public void release(BigDecimal amount) {
        validateAmount(amount);

        if (reservedBalance.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "Reserved balance is insufficient for release"
            );
        }

        reservedBalance = reservedBalance.subtract(amount);
    }

    public void settle(BigDecimal amount) {
        validateAmount(amount);

        if (reservedBalance.compareTo(amount) < 0) {
            throw new IllegalStateException(
                    "Reserved balance is insufficient for settlement"
            );
        }

        reservedBalance = reservedBalance.subtract(amount);
        currentBalance = currentBalance.subtract(amount);
    }
}