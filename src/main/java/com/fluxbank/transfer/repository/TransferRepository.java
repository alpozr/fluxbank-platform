package com.fluxbank.transfer.repository;

import com.fluxbank.transfer.domain.Transfer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TransferRepository
        extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByPublicId(UUID publicId);

    Optional<Transfer> findByReferenceNumber(String referenceNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
       select t
       from Transfer t
       where t.publicId = :publicId
       """)
    Optional<Transfer> findByPublicIdForUpdate(
            @Param("publicId") UUID publicId
    );
}