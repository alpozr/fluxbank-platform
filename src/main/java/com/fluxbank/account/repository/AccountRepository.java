package com.fluxbank.account.repository;

import com.fluxbank.account.domain.Account;
import com.fluxbank.customer.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


public interface AccountRepository extends JpaRepository<Account, Long> {

    @Query("""
            SELECT MAX(a.suffix)
            FROM Account a
            WHERE a.user = :user
            """)
    Optional<Integer> findMaxSuffixByUser(@Param("user") User user);

    @Query(
            value = "SELECT nextval('fluxbank.iban_account_identifier_seq')",
            nativeQuery = true
    )
    Long getNextIbanAccountIdentifier();

    Optional<Account> findByPublicId(UUID publicId);

    List<Account> findAllByUser(User user);
}
