package com.fluxbank.account.domain;

import com.fluxbank.account.domain.exception.AccountCannotBeClosedException;
import com.fluxbank.customer.domain.User;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(
        name = "accounts",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_accounts_user_suffix",
                        columnNames = {"user_id", "suffix"}
                )
        }
)
@EntityListeners(AuditingEntityListener.class)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "account_number", nullable = false, unique = true, length = 30, updatable = false)
    private String accountNumber;

    @Column(nullable = false, unique = true, length = 34, updatable = false)
    private String iban;

    @Column(nullable = false, updatable = false)
    private Integer suffix;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "is_closed", nullable = false)
    private boolean closed;

    @Column(name = "is_dormant", nullable = false)
    private boolean dormant;

    @Column(name = "is_blocked", nullable = false)
    private boolean blocked;

    @Column(name = "is_expired", nullable = false)
    private boolean expired;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(
            name = "iban_account_identifier",
            nullable = false,
            unique = true,
            updatable = false
    )
    private Long ibanAccountIdentifier;

    protected Account() {
    }

    public UUID getPublicId() {
        return publicId;
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

    public BigDecimal getBalance() {
        return balance;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
    public User getUser() {
        return user;
    }

    public static Account create(User user,
                                 String accountNumber,
                                 String iban,
                                 Long ibanAccountIdentifier,
                                 Integer suffix,
                                 CurrencyCode currency){
        Account account = new Account();

        account.publicId = UUID.randomUUID();
        account.user = user;
        account.accountNumber = accountNumber;
        account.iban = iban;
        account.ibanAccountIdentifier = ibanAccountIdentifier;
        account.suffix = suffix;
        account.currency = currency;
        account.balance = BigDecimal.ZERO;

        account.closed = false;
        account.dormant = false;
        account.blocked = false;
        account.expired = false;

        return account;
    }

    public void close() {
        if (this.closed) {
            return;
        }

        if (this.balance.compareTo(BigDecimal.ZERO) != 0) {
            throw new AccountCannotBeClosedException(
                    "Account with a non-zero balance cannot be closed"
            );
        }

        this.closed = true;
    }

    public void block() {
        if (this.closed) {
            throw new IllegalStateException(
                    "Closed account cannot be blocked"
            );
        }

        this.blocked = true;
    }

    public void unblock() {
        if (this.closed) {
            throw new IllegalStateException(
                    "Closed account cannot be unblocked"
            );
        }

        this.blocked = false;
    }

    public void markDormant() {
        if (this.closed) {
            throw new IllegalStateException(
                    "Closed account cannot become dormant"
            );
        }

        this.dormant = true;
    }

    public void reactivate() {
        if (this.closed) {
            throw new IllegalStateException(
                    "Closed account cannot be reactivated"
            );
        }

        this.dormant = false;
    }

    public void expire() {
        if (this.closed) {
            return;
        }

        this.expired = true;
    }
}