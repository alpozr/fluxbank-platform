package com.fluxbank.account.repository;

import com.fluxbank.account.domain.Account;
import com.fluxbank.account.domain.AccountBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountBalanceRepository
        extends JpaRepository<AccountBalance, Long> {

    Optional<AccountBalance> findByAccount(Account account);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
       select ab
       from AccountBalance ab
       where ab.account.publicId in :accountPublicIds
       order by ab.account.id asc
       """)
    List<AccountBalance> findAllByAccountPublicIdInForUpdate(
            @Param("accountPublicIds")
                    Collection<UUID> accountPublicIds
    );
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
       select ab
       from AccountBalance ab
       where ab.account.publicId = :accountPublicId
       """)
    Optional<AccountBalance> findByAccountPublicIdForUpdate(
            @Param("accountPublicId") UUID accountPublicId
    );
}