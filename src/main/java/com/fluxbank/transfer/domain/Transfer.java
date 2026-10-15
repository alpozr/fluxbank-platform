package com.fluxbank.transfer.domain;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.CurrencyCode;
import com.fluxbank.beneficiary.domain.Beneficiary;
import com.fluxbank.customer.domain.User;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transfers")
@EntityListeners(AuditingEntityListener.class)
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "reference_number", nullable = false, unique = true, updatable = false, length = 40)
    private String referenceNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false, updatable = false)
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false, updatable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "beneficiary_id", nullable = false, updatable = false)
    private Beneficiary beneficiary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id", updatable = false)
    private Account destinationAccount;

    @Column(name = "beneficiary_name", nullable = false, length = 201, updatable = false)
    private String beneficiaryName;

    @Column(name = "destination_iban", nullable = false, length = 34, updatable = false)
    private String destinationIban;

    @Column(name = "destination_bank_name", nullable = false, length = 150, updatable = false)
    private String destinationBankName;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TransferType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 50)
    private TransferFailureReason failureReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Transfer() {
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public User getOwner() {
        return owner;
    }

    public Account getSourceAccount() {
        return sourceAccount;
    }

    public Beneficiary getBeneficiary() {
        return beneficiary;
    }

    public Account getDestinationAccount() {
        return destinationAccount;
    }

    public String getBeneficiaryName() {
        return beneficiaryName;
    }

    public String getDestinationIban() {
        return destinationIban;
    }

    public String getDestinationBankName() {
        return destinationBankName;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public TransferType getType() {
        return type;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public TransferFailureReason getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public static Transfer create(
            String referenceNumber,
            User owner,
            Account sourceAccount,
            Beneficiary beneficiary,
            Account destinationAccount,
            String beneficiaryName,
            String destinationIban,
            String destinationBankName,
            BigDecimal amount,
            CurrencyCode currency,
            TransferType type) {

        if (type == TransferType.INTERNAL && destinationAccount == null) {
            throw new IllegalArgumentException(
                    "Internal transfer requires a destination account"
            );
        }

        if (type == TransferType.EXTERNAL && destinationAccount != null) {
            throw new IllegalArgumentException(
                    "External transfer cannot have a destination account"
            );
        }

        Transfer transfer = new Transfer();

        transfer.publicId = UUID.randomUUID();
        transfer.referenceNumber = referenceNumber;

        transfer.owner = owner;
        transfer.sourceAccount = sourceAccount;
        transfer.beneficiary = beneficiary;
        transfer.destinationAccount = destinationAccount;

        transfer.beneficiaryName = beneficiaryName;
        transfer.destinationIban = destinationIban;
        transfer.destinationBankName = destinationBankName;

        transfer.amount = amount;
        transfer.currency = currency;
        transfer.type = type;

        transfer.status = TransferStatus.PENDING;
        transfer.failureReason = null;
        transfer.completedAt = null;

        return transfer;
    }

    public void complete() {
        if (this.status != TransferStatus.PENDING) {
            throw new IllegalStateException(
                    "Only pending transfers can be completed"
            );
        }

        this.status = TransferStatus.COMPLETED;
        this.failureReason = null;
        this.completedAt = Instant.now();
    }

    public void fail(TransferFailureReason reason) {
        if (this.status != TransferStatus.PENDING) {
            throw new IllegalStateException(
                    "Only pending transfers can be failed"
            );
        }

        if (reason == null) {
            throw new IllegalArgumentException(
                    "Failure reason is required"
            );
        }

        this.status = TransferStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = null;
    }
}
