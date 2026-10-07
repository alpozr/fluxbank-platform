package com.fluxbank.customer.domain;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false , unique = true)
    private UUID publicId;

    @Column(nullable = false , length = 100)
    private String firstName;

    @Column(nullable = false , length = 100)
    private String lastName;

    @Column(nullable = false , length = 255)
    private String email;

    @Column(nullable = false , length = 255)
    private String passwordHash;


    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false, unique = true, updatable = false)
    private Long customerNumber;


    protected User() {
    }

    public static User createCustomer(
            String firstName,
            String lastName,
            String email,
            String passwordHash,
            Long customerNumber) {

        User user = new User();

        user.publicId = UUID.randomUUID();
        user.firstName = firstName;
        user.lastName = lastName;
        user.email = email;
        user.passwordHash = passwordHash;
        user.role = UserRole.CUSTOMER;
        user.status = UserStatus.ACTIVE;
        user.customerNumber=customerNumber;

        return user;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole(){return role;}

    public UserStatus getStatus(){return status;}

    public Long getCustomerNumber() {return customerNumber;
    }
}
