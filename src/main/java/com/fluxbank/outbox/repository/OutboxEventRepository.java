package com.fluxbank.outbox.repository;

import com.fluxbank.outbox.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    Optional<OutboxEvent> findByPublicId(UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.publicId = :publicId")
    Optional<OutboxEvent> findByPublicIdForUpdate(@Param("publicId") UUID publicId);

    @Query(value = """
            SELECT * FROM fluxbank.outbox_event
            WHERE status = 'PENDING'
              AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
            ORDER BY created_at ASC, id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingForClaim(@Param("batchSize") int batchSize);

    @Query(value = """
            SELECT * FROM fluxbank.outbox_event
            WHERE status = 'PROCESSING' AND claimed_at < :cutoff
            ORDER BY claimed_at ASC, id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findStaleForRecovery(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
