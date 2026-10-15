package com.fluxbank.account.repository;

import com.fluxbank.account.domain.Account;
import com.fluxbank.customer.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
       select a
       from Account a
       where a.publicId in :publicIds
       order by a.id asc
       """)
    List<Account> findAllByPublicIdInForUpdate(
            @Param("publicIds") Collection<UUID> publicIds
    );
    Optional<Account> findByPublicIdAndUser(
            UUID publicId,
            User user
    );

    Optional<Account> findByIban(String iban);
}
