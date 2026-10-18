package com.bank.risk.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataOutboxRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    /**
     * Takes the cluster-wide relay lock for the current transaction. Only one
     * replica relays at a time, which keeps each aggregate's events in order.
     */
    @Query(value = "select pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryRelayLock(@Param("key") long key);

    @Query(value = """
        select * from outbox_event
        where published_at is null and parked_at is null
        order by created_seq
        limit :batchSize
        """, nativeQuery = true)
    List<OutboxEventJpaEntity> findUnpublishedBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("delete from OutboxEventJpaEntity e where e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    long countByPublishedAtIsNull();

    /**
     * Marks rows an operator parked by hand (runbook "Manual park") as counted
     * and returns how many there were. Relay parks are written counted, so only
     * operator parks match; the update makes sure no row is counted twice.
     */
    @Modifying
    @Query(value = """
        update outbox_event set park_counted = true
        where parked_at is not null and published_at is null and not park_counted
        """, nativeQuery = true)
    int markOperatorParksCounted();

    /** Age in seconds of the oldest row waiting for the relay (not published, not parked); 0 when none waits. */
    @Query(value = """
        select coalesce(extract(epoch from (now() - min(created_at))), 0)::float8 from outbox_event
        where published_at is null and parked_at is null
        """, nativeQuery = true)
    double oldestPendingAgeSeconds();

    /** Rows waiting for the relay (not published, not parked). */
    long countByPublishedAtIsNullAndParkedAtIsNull();

    /** Rows the relay gave up on; they wait for a manual replay. */
    long countByPublishedAtIsNullAndParkedAtIsNotNull();
}
