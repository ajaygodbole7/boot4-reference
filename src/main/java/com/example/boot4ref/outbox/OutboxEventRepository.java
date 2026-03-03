package com.example.boot4ref.outbox;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for outbox events with SKIP LOCKED polling support.
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Picks up unprocessed outbox entries using SKIP LOCKED.
     * Parallel pollers claim different rows without blocking.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findPendingWithLock(int limit);

    /**
     * Deletes PROCESSED entries whose {@code processedAt} is before the given timestamp.
     * Retention is anchored to when the event was processed, not when it was created.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.processedAt < :before")
    int deleteByStatusProcessedBefore(OutboxStatus status, Instant before);

    /**
     * Deletes FAILED entries whose {@code createdAt} is before the given timestamp.
     * FAILED events never have {@code processedAt} set, so {@code createdAt} is the only viable anchor.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.createdAt < :before")
    int deleteByStatusCreatedBefore(OutboxStatus status, Instant before);
}
