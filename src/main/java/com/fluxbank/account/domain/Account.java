package com.fluxbank.account.domain;

import com.fluxbank.customer.domain.User;
import com.fluxbank.account.domain.exception.AccountCannotBeClosedException;
import com.fluxbank.account.domain.exception.AccountNotAvailableForDebitException;
import com.fluxbank.account.domain.exception.AccountNotAvailableForCreditException;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@EntityListeners(AuditingEntityListener.class)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "public_id",
            nullable = false,
            unique = true,
            updatable = false
    )
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false
    )
    private User user;

    @Column(
            name = "account_number",
            nullable = false,
            unique = true,
            length = 30,
            updatable = false
    )
    private String accountNumber;

    @Column(
            name = "iban",
            nullable = false,
            unique = true,
            length = 34,
            updatable = false
    )
    private String iban;

    @Column(
            name = "iban_account_identifier",
            nullable = false,
            unique = true,
            updatable = false
    )
    private Long ibanAccountIdentifier;

    @Column(
            name = "suffix",
            nullable = false,
            updatable = false
    )
    private Integer suffix;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "currency",
            nullable = false,
            length = 3,
            updatable = false
    )
    private CurrencyCode currency;

    @Column(name = "is_closed", nullable = false)
    private boolean closed;

    @Column(name = "is_dormant", nullable = false)
    private boolean dormant;

    @Column(name = "is_blocked", nullable = false)
    private boolean blocked;

    @Column(name = "is_expired", nullable = false)
    private boolean expired;

    @OneToOne(
            mappedBy = "account",
            fetch = FetchType.LAZY
    )
    private AccountBalance balance;

    @CreatedDate
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @LastModifiedDate
    @Column(
            name = "updated_at",
            nullable = false
    )
    private Instant updatedAt;

    protected Account() {
    }

    public static Account create(
            User user,
            String accountNumber,
            String iban,
            Long ibanAccountIdentifier,
            Integer suffix,
            CurrencyCode currency
    ) {
        Account account = new Account();

        account.publicId = UUID.randomUUID();
        account.user = user;
        account.accountNumber = accountNumber;
        account.iban = iban;
        account.ibanAccountIdentifier = ibanAccountIdentifier;
        account.suffix = suffix;
        account.currency = currency;

        account.closed = false;
        account.dormant = false;
        account.blocked = false;
        account.expired = false;

        return account;
    }

    public void close() {
        if (closed) {
            return;
        }

        if (balance.getCurrentBalance().compareTo(java.math.BigDecimal.ZERO) != 0) {
            throw new AccountCannotBeClosedException(
                    "Account with a non-zero balance cannot be closed"
            );
        }

        closed = true;
    }

    public void block() {
        if (closed) {
            throw new IllegalStateException(
                    "Closed account cannot be blocked"
            );
        }

        blocked = true;
    }

    public void unblock() {
        if (closed) {
            throw new IllegalStateException(
                    "Closed account cannot be unblocked"
            );
        }

        blocked = false;
    }

    public void markDormant() {
        if (closed) {
            throw new IllegalStateException(
                    "Closed account cannot become dormant"
            );
        }

        dormant = true;
    }

    public void reactivate() {
        if (closed) {
            throw new IllegalStateException(
                    "Closed account cannot be reactivated"
            );
        }

        dormant = false;
    }

    public void expire() {
        if (closed) {
            return;
        }
        expired = true;
    }

    public void validateAvailableForDebit() {
        if (closed || dormant || blocked || expired) {
            throw new AccountNotAvailableForDebitException(
                    "Account is not available for debit"
            );
        }
    }

    public void validateAvailableForCredit() {
        if (closed || expired) {
            throw new AccountNotAvailableForCreditException(
                    "Account is not available for credit"
            );
        }
    }

    public UUID getPublicId() {
        return publicId;
    }

    public User getUser() {
        return user;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getIban() {
        return iban;
    }

    public Long getIbanAccountIdentifier() {
        return ibanAccountIdentifier;
    }

    public Integer getSuffix() {
        return suffix;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public boolean isClosed() {
        return closed;
    }

    public boolean isDormant() {
        return dormant;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public boolean isExpired() {
        return expired;
    }

    public AccountBalance getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void assignBalance(AccountBalance balance) {
        if (this.balance != null) {
            throw new IllegalStateException(
                    "Account balance is already assigned"
            );
        }

        this.balance = balance;
    }
}
