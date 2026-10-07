package com.fluxbank.customer.repository;

import com.fluxbank.customer.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User,Long> {

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    @Query(value = "SELECT nextval('fluxbank.customer_number_seq')", nativeQuery = true)
    Long getNextCustomerNumber();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT u
        FROM User u
        WHERE u.customerNumber = :customerNumber
        """)
    Optional<User> findByCustomerNumberForUpdate(
            @Param("customerNumber") Long customerNumber
    );
}
