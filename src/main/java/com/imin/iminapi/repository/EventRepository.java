package com.imin.iminapi.repository;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface EventRepository extends JpaRepository<Event, UUID> {

    @Query("SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
           "AND (:status IS NULL OR e.status = :status) ORDER BY e.startsAt DESC NULLS LAST, e.createdAt DESC")
    Page<Event> findVisibleByOrg(@Param("orgId") UUID orgId, @Param("status") EventStatus status, Pageable page);

    @Query("SELECT e FROM Event e WHERE e.id = :id AND e.deletedAt IS NULL")
    Optional<Event> findActive(@Param("id") UUID id);

    @Query(
        "SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.status = com.imin.iminapi.model.EventStatus.LIVE " +
        "AND e.startsAt > :now ORDER BY e.startsAt ASC")
    List<Event> findUpcomingLive(
            @Param("orgId") UUID orgId,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * Momentum Engine candidates (spec §6.1): every published, future, on-sale event
     * across ALL orgs. Mirrors {@link #findUpcomingLive} (LIVE + not-deleted + future
     * start) but drops the orgId filter and adds the FULL on-sale gate — matching the
     * {@code findPublicListing} on-sale semantics (BOTH {@code onSaleAt <= now} AND
     * {@code saleClosesAt > now}), so an event whose sales window has already CLOSED
     * (but not yet started) is excluded: the organizer could not act on a suggestion
     * with sales shut. Null {@code onSaleAt}/{@code saleClosesAt} mean "no bound".
     */
    @Query("SELECT e FROM Event e WHERE e.deletedAt IS NULL " +
           "AND e.status = com.imin.iminapi.model.EventStatus.LIVE " +
           "AND e.startsAt > :now " +
           "AND (e.onSaleAt IS NULL OR e.onSaleAt <= :now) " +
           "AND (e.saleClosesAt IS NULL OR e.saleClosesAt > :now) " +
           "ORDER BY e.startsAt ASC")
    List<Event> findMomentumCandidates(@Param("now") Instant now);

    /**
     * Scannable events for the gate scanner: events for an org whose
     * {@code startsAt} is either in the future or within the last 24 hours
     * (so currently-running events remain visible until they clearly end).
     * Excludes soft-deleted events. Ordered by {@code startsAt} ascending so
     * the gate UI shows the most imminent event first. Events with a
     * {@code null} startsAt are excluded — the gate UX is built around
     * scheduled events and a null start has no meaningful "near now" answer.
     */
    @Query(
        "SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.startsAt IS NOT NULL AND e.startsAt >= :cutoff " +
        "ORDER BY e.startsAt ASC")
    List<Event> findScannableForGate(
            @Param("orgId") UUID orgId,
            @Param("cutoff") Instant cutoff);

    @Query(
        "SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.status = com.imin.iminapi.model.EventStatus.PAST " +
        "ORDER BY e.endsAt DESC")
    List<Event> findRecentPast(
            @Param("orgId") UUID orgId,
            Pageable pageable);

    @Query(
        "SELECT COUNT(e) FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.status = com.imin.iminapi.model.EventStatus.LIVE")
    long countLive(@Param("orgId") UUID orgId);

    @Query(
        "SELECT COUNT(e) FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.publishedAt IS NOT NULL")
    long countPublished(@Param("orgId") UUID orgId);

    @Query(
        "SELECT COUNT(e) FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
        "AND e.status = com.imin.iminapi.model.EventStatus.PAST")
    long countPast(@Param("orgId") UUID orgId);

    @Query(
        "SELECT COALESCE(SUM(e.revenueMinor), 0), COALESCE(SUM(e.sold), 0) " +
        "FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL")
    List<Object[]> sumRevenueAndSold(@Param("orgId") UUID orgId);

    @Query("""
        SELECT e FROM Event e
         WHERE e.id = :id
           AND e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
""")
    Optional<Event> findPublic(@Param("id") UUID id);

    @Query("""
        SELECT e FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
           AND (CAST(:from AS timestamp) IS NULL
                OR e.startsAt >= :from
                OR (:includeOngoing = true AND (e.endsAt IS NULL OR e.endsAt > :now)))
           AND (CAST(:to AS timestamp) IS NULL OR e.startsAt < :to)
           AND (CAST(:genre AS string) IS NULL OR e.genre = :genre)
           AND (CAST(:type AS string) IS NULL OR e.type = :type)
           AND (CAST(:city AS string) IS NULL OR LOWER(e.venueCity) LIKE LOWER(CONCAT('%', CAST(:city AS string), '%')))
           AND (CAST(:country AS string) IS NULL OR e.venueCountry = :country)
           AND (CAST(:q AS string) IS NULL OR LOWER(e.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
           AND (CAST(:orgId AS java.util.UUID) IS NULL OR e.orgId = :orgId)
           AND (:onSaleOnly = false OR (
                  (e.onSaleAt IS NULL OR e.onSaleAt <= :now)
                  AND (e.saleClosesAt IS NULL OR e.saleClosesAt > :now)
                ))
         ORDER BY e.startsAt ASC NULLS LAST, e.id ASC
""")
    Page<Event> findPublicListing(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("genre") String genre,
            @Param("type") String type,
            @Param("city") String city,
            @Param("country") String country,
            @Param("q") String q,
            @Param("orgId") UUID orgId,
            @Param("onSaleOnly") boolean onSaleOnly,
            @Param("includeOngoing") boolean includeOngoing,
            @Param("now") Instant now,
            Pageable pageable);

    @Query("""
        SELECT DISTINCT e.venueCity, e.venueCountry FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
           AND e.venueCity IS NOT NULL
           AND e.venueCity <> ''
         ORDER BY e.venueCity ASC, e.venueCountry ASC
""")
    List<Object[]> findDistinctPublicCities();

    /**
     * Bulk transition: LIVE events whose {@code endsAt} is in the past become PAST.
     * Events with {@code null endsAt} are excluded — no end date means the event has
     * no defined closing time and must remain LIVE indefinitely.
     *
     * <p>The JPQL explicitly sets {@code e.updatedAt = :updatedAt} because bulk UPDATE
     * bypasses JPA entity lifecycle callbacks ({@code @PreUpdate}) — without it,
     * ETag/concurrency consumers reading the event after the sweep would see a stale
     * timestamp and might incorrectly treat the record as unchanged.
     *
     * <p>{@code @Transactional} is on the repo method so the sweep tick (which has no
     * surrounding transaction) gets a clean boundary here — matching the pattern used
     * by {@code PromoCodeRepository.incrementUsedCount}.
     *
     * @param now       current instant used as the boundary for {@code endsAt < :now}
     * @param updatedAt value written to {@code updated_at} on every transitioned row
     * @return the number of rows updated
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        UPDATE Event e
           SET e.status = com.imin.iminapi.model.EventStatus.PAST,
               e.updatedAt = :updatedAt
         WHERE e.status = com.imin.iminapi.model.EventStatus.LIVE
           AND e.endsAt IS NOT NULL
           AND e.endsAt < :now
    """)
    int markLivePast(@Param("now") Instant now, @Param("updatedAt") Instant updatedAt);

    @Query("""
        SELECT DISTINCT e.genre FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
           AND e.genre IS NOT NULL
           AND e.genre <> ''
         ORDER BY e.genre ASC
""")
    List<String> findDistinctPublicGenres();

    /**
     * Track B (manual payouts) Phase 2 — candidate events for the daily
     * {@code PostEventPayoutSweeper}. An event is due for a payout when:
     * <ul>
     *   <li>it has an {@code endsAt} that is more than the buffer in the past
     *       ({@code endsAt < :cutoff}, where {@code cutoff = now − bufferDays}
     *       resolved by the caller in the configured payout zone) — open-ended
     *       events ({@code endsAt IS NULL}) never become candidates;</li>
     *   <li>its org is payout-eligible: there is an {@code Organization} matched
     *       by {@code e.orgId} with {@code stripePayoutsEnabled = true},
     *       {@code stripePayoutScheduleManual = true} (the V44 column — the
     *       auditable guarantee the account is on a MANUAL schedule), a
     *       non-blank {@code stripeAccountId}, and {@code stripeConnectState =
     *       ACTIVE} (so a just-disabled account is excluded);</li>
     *   <li>there is no {@code PLANNED}/{@code SUBMITTED}/{@code PAID} payout run
     *       for THIS event (per-event existence guard); and</li>
     *   <li><b>(double-pay HARD RULE, §4.0)</b> there is no in-flight
     *       ({@code PLANNED}/{@code SUBMITTED}) payout run for the SAME
     *       {@code stripe_account_id} — a connected balance is one shared pool, so
     *       at most one in-flight payout per org per tick.</li>
     * </ul>
     *
     * <p>{@code Organization} has no JPA back-reference from {@code Event}, so the
     * org join is expressed as a correlated {@code EXISTS} subquery on
     * {@code e.orgId}; the two payout-run guards are correlated {@code NOT EXISTS}
     * subqueries against the {@link com.imin.iminapi.payout.PayoutRun} entity. This
     * candidate query is a coarse filter — the authoritative double-pay guard is
     * re-checked INSIDE the per-event {@code REQUIRES_NEW} transaction (see
     * {@code PostEventPayoutService}). Status literals are bound lowercase because
     * {@code PayoutRunStatus} persists via its converter.
     *
     * @param cutoff {@code now − bufferDays} resolved in the payout zone; an event
     *               qualifies only when {@code endsAt < cutoff}.
     */
    @Query("""
        SELECT e FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.endsAt IS NOT NULL
           AND e.endsAt < :cutoff
           AND EXISTS (
                 SELECT 1 FROM Organization o
                  WHERE o.id = e.orgId
                    AND o.stripePayoutsEnabled = true
                    AND o.stripePayoutScheduleManual = true
                    AND o.stripeConnectState = com.imin.iminapi.stripe.StripeConnectState.ACTIVE
                    AND o.stripeAccountId IS NOT NULL
                    AND o.stripeAccountId <> ''
                    AND NOT EXISTS (
                          SELECT 1 FROM PayoutRun r2
                           WHERE r2.stripeAccountId = o.stripeAccountId
                             AND r2.status IN (com.imin.iminapi.payout.PayoutRunStatus.PLANNED,
                                               com.imin.iminapi.payout.PayoutRunStatus.SUBMITTED))
               )
           AND NOT EXISTS (
                 SELECT 1 FROM PayoutRun r1
                  WHERE r1.eventId = e.id
                    AND r1.status IN (com.imin.iminapi.payout.PayoutRunStatus.PLANNED,
                                      com.imin.iminapi.payout.PayoutRunStatus.SUBMITTED,
                                      com.imin.iminapi.payout.PayoutRunStatus.PAID))
         ORDER BY e.endsAt ASC
""")
    List<Event> findPayoutCandidates(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Track B Phase 2 retention monitor (plan §7) — events that ENDED before
     * {@code retentionCutoff} (e.g. {@code now − 75 days}, a ~15-day margin under the
     * common 90-day Stripe retention window) and still carry sold revenue
     * ({@code revenueMinor > 0}) for a manual-schedule, payout-eligible
     * {@code ACTIVE} org. These are orgs that may be holding un-disbursed funds past
     * the safe bound — the monitor cross-checks each against a PAID payout run and
     * alerts on the stragglers so ops can force a payout before Stripe's deadline.
     *
     * <p>Same org-eligibility join as {@link #findPayoutCandidates}, with a
     * far older cutoff and a NOT EXISTS guard against an already-PAID payout run for the
     * event (an event we've already disbursed is not a straggler).
     *
     * <p>// ponytail: {@code revenue_minor} is a denormalised, COARSE prefilter — it
     * stays positive after a full refund, so it over-selects. It is only here to cheaply
     * drop never-sold events; the payout job computes the authoritative net
     * ({@code gross − refunds − net app fee}) and skips when that is {@code <= 0}. This
     * monitor stays an APPROXIMATE alert by design — do not over-engineer the net into the
     * query. The PAID-run NOT EXISTS removes the common false positive (already disbursed).
     */
    @Query("""
        SELECT e FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.endsAt IS NOT NULL
           AND e.endsAt < :retentionCutoff
           AND e.revenueMinor > 0
           AND EXISTS (
                 SELECT 1 FROM Organization o
                  WHERE o.id = e.orgId
                    AND o.stripePayoutsEnabled = true
                    AND o.stripePayoutScheduleManual = true
                    AND o.stripeConnectState = com.imin.iminapi.stripe.StripeConnectState.ACTIVE
                    AND o.stripeAccountId IS NOT NULL
                    AND o.stripeAccountId <> ''
               )
           AND NOT EXISTS (
                 SELECT 1 FROM PayoutRun r
                  WHERE r.eventId = e.id
                    AND r.status = com.imin.iminapi.payout.PayoutRunStatus.PAID)
         ORDER BY e.endsAt ASC
""")
    List<Event> findRetentionMonitorCandidates(@Param("retentionCutoff") Instant retentionCutoff, Pageable pageable);
}
