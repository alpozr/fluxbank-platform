package com.fluxbank.beneficiary.domain;

import com.fluxbank.customer.domain.User;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "beneficiaries")
@EntityListeners(AuditingEntityListener.class)
public class Beneficiary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false, updatable = false)
    private User owner;

    @Column(nullable = false, length = 100)
    private String firstName;

    @Column(nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 34, updatable = false)
    private String iban;

    @Column(nullable = false, length = 150)
    private String bankName;

    @Column(nullable = false)
    private boolean active;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    protected Beneficiary() {
    }

    public static Beneficiary create(
            User owner,
            String firstName,
            String lastName,
            String iban,
            String bankName) {

        Beneficiary beneficiary = new Beneficiary();

        beneficiary.publicId = UUID.randomUUID();
        beneficiary.owner = owner;
        beneficiary.firstName = firstName;
        beneficiary.lastName = lastName;
        beneficiary.iban = iban;
        beneficiary.bankName = bankName;
        beneficiary.active = true;

        return beneficiary;
    }

    public void deactivate() {
        this.active = false;
    }

    public void reactivate() {
        this.active = true;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public User getOwner() {
        return owner;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getIban() {
        return iban;
    }

    public String getBankName() {
        return bankName;
    }

    public boolean isActive() {
        return active;
    }
}