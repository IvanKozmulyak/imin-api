package com.imin.iminapi.marketing.repository;

import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import com.imin.iminapi.marketing.model.MetaCapiEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
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

    // ── DSAR (Art.15 / Art.17) ───────────────────────────────────────────────

    /** This org's CAPI outbox rows for a set of the data subject's orders. */
    @Query("select e from MetaCapiEvent e where e.orgId = :orgId and e.orderId in :orderIds "
            + "order by e.createdAt asc")
    List<MetaCapiEvent> findByOrgAndOrderIds(@Param("orgId") UUID orgId,
                                             @Param("orderIds") java.util.Collection<UUID> orderIds);

    /**
     * Art.17: strip the identifiers, keep the send record.
     *
     * <p>Redacted rather than deleted. The row is the evidence that a hashed
     * address was transmitted to Meta for a retained order, which is the fact a
     * regulator or the data subject would ask about; deleting it would destroy
     * the proof of the disclosure while the disclosure itself has already
     * happened. What is removed is everything that identifies the person:
     * {@code email_sha256} (a hashed address is still personal data — it is a
     * pseudonym, and Meta can re-identify it), plus the {@code fbp}/{@code fbc}
     * browser cookies.
     */
    @Modifying
    @Query("update MetaCapiEvent e set e.emailSha256 = null, e.fbp = null, e.fbc = null "
            + "where e.orgId = :orgId and e.orderId in :orderIds")
    int redactIdentifiersByOrgAndOrderIds(@Param("orgId") UUID orgId,
                                          @Param("orderIds") java.util.Collection<UUID> orderIds);
}
