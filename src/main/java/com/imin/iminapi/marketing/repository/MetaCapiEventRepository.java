package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.MetaCapiEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MetaCapiEventRepository extends JpaRepository<MetaCapiEvent, UUID> {

    /** Due outbox rows (pending + next_attempt_at reached), oldest first. */
    @Query("""
            SELECT e FROM MetaCapiEvent e
            WHERE e.status = 'pending' AND e.nextAttemptAt <= :now
            ORDER BY e.nextAttemptAt ASC
            """)
    List<MetaCapiEvent> findDue(@Param("now") Instant now, Pageable pageable);

    boolean existsByOrderId(UUID orderId);

    @Query("SELECT COUNT(e) FROM MetaCapiEvent e WHERE e.orgId = :orgId AND e.status = 'sent' AND e.sentAt >= :since")
    long countSentSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    /**
     * Purchase CAPI events successfully delivered to Meta whose row was CREATED
     * (i.e. whose order was fulfilled) since the cutoff. Keyed on {@code createdAt}
     * — NOT {@code sentAt} — so it lines up with the org-wide order cohort that
     * drives the funnel's PAYMENTS_COMPLETED / Purchase stage: of the orders in the
     * window, how many got a Purchase event Meta actually received. The gap between
     * this and the order count is the silent signal loss the card surfaces (spec §8).
     * Every outbox row is a {@code Purchase} ({@code MetaCapiOutboxWriter} hardcodes
     * {@code event_name}), so no event-name filter is needed.
     */
    @Query("SELECT COUNT(e) FROM MetaCapiEvent e WHERE e.orgId = :orgId AND e.status = 'sent' AND e.createdAt >= :since")
    long countSentByCreatedAtSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Query("SELECT COUNT(e) FROM MetaCapiEvent e WHERE e.orgId = :orgId AND e.attempts > 0 AND e.status <> 'sent' AND e.createdAt >= :since")
    long countFailingSince(@Param("orgId") UUID orgId, @Param("since") Instant since);

    @Query("SELECT COUNT(e) FROM MetaCapiEvent e WHERE e.orgId = :orgId AND e.status = 'dead'")
    long countDead(@Param("orgId") UUID orgId);

    @Query("""
            SELECT e.lastError FROM MetaCapiEvent e
            WHERE e.orgId = :orgId AND e.lastError IS NOT NULL
            ORDER BY e.createdAt DESC
            """)
    List<String> recentErrors(@Param("orgId") UUID orgId, Pageable pageable);
}
