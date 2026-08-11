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

    /**
     * Every published (publishedAt set), non-deleted event, oldest publish first. Drives the
     * predictor's one-shot outcome retro-backfill (spec §6.1) — it pages through these and
     * reconstructs a frozen snapshot for any event that has no {@code event_outcomes} row yet.
     */
    @Query("SELECT e FROM Event e WHERE e.deletedAt IS NULL AND e.publishedAt IS NOT NULL " +
           "ORDER BY e.publishedAt ASC, e.id ASC")
    List<Event> findAllPublished(Pageable pageable);

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

    /**
     * Public feed listing. CANCELLED events are excluded here (and from the city/genre
     * facets) even though {@link #findPublic} still serves them: a cancelled event must
     * stay reachable by share-link so the detail page can render the cancellation banner,
     * but it has no business appearing in a browse feed.
     *
     * <p><b>{@code freeOnly}</b> keeps only events whose cheapest PURCHASABLE tier is
     * €0. Because prices are non-negative, "min purchasable price == 0" is exactly
     * "there EXISTS a purchasable tier priced 0", which is what the EXISTS below tests
     * — so the filter and the card's {@code priceFromMinor} can never disagree. An event
     * with no purchasable tier (sold out / not yet on sale / sales ended) is NOT free,
     * it is unavailable, and drops out.
     *
     * <p><b>{@code cityKey}</b> is the normalised {@code lower(collapse(trim(city)))} key, not the
     * raw string, and it is matched with {@code =} rather than the old case-insensitive
     * {@code LIKE '%…%'}. That is what makes a city chip's count reproducible: the facet
     * ({@link #findPublicCityCounts()}) groups on the same column with the same equality, so
     * "Metz (3)" returns exactly three events — where the substring match could also drag in a
     * {@code Metzingen}. Callers normalise via {@code EventNormalization.cityKey}, so
     * {@code ?city=METZ}, {@code ?city=metz} and {@code ?city=%20Metz%20} are one query.
     *
     * <p><b>{@code genreKey}</b> is the same idea for the genre: {@code lower(collapse(trim))},
     * matched with {@code =} against the derived column the genre facet
     * ({@link #findPublicGenreCounts()}) groups on. {@code ?genre=Techno} and
     * {@code ?genre=techno} are one query over one set of nights. The display column
     * {@code e.genre} keeps the organizer's casing and is never filtered on.
     *
     * <p>The EXISTS body is the SQL mirror of {@link
     * com.imin.iminapi.service.event.TierAvailability#isPurchasable} — enabled tier,
     * event neither PAST nor CANCELLED, both sale windows open, stock remaining. Keeping
     * two expressions of one rule is a drift risk taken deliberately (a Java post-filter
     * would break pagination); {@code PublicEventServiceListTest} pins them together with
     * a case matrix asserted against {@code TierAvailability} itself.
     */
    @Query("""
        SELECT e FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
           AND e.status <> com.imin.iminapi.model.EventStatus.CANCELLED
           AND (CAST(:from AS timestamp) IS NULL
                OR e.startsAt >= :from
                OR (:includeOngoing = true AND (e.endsAt IS NULL OR e.endsAt > :now)))
           AND (CAST(:to AS timestamp) IS NULL OR e.startsAt < :to)
           AND (CAST(:genreKey AS string) IS NULL OR e.genreKey = CAST(:genreKey AS string))
           AND (CAST(:type AS string) IS NULL OR e.type = :type)
           AND (CAST(:cityKey AS string) IS NULL OR e.venueCityKey = CAST(:cityKey AS string))
           AND (CAST(:country AS string) IS NULL OR e.venueCountry = :country)
           AND (CAST(:q AS string) IS NULL OR LOWER(e.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
           AND (CAST(:orgId AS java.util.UUID) IS NULL OR e.orgId = :orgId)
           AND (:onSaleOnly = false OR (
                  (e.onSaleAt IS NULL OR e.onSaleAt <= :now)
                  AND (e.saleClosesAt IS NULL OR e.saleClosesAt > :now)
                ))
           AND (:freeOnly = false OR EXISTS (
                  SELECT 1 FROM TicketTier t
                   WHERE t.eventId = e.id
                     AND t.enabled = true
                     AND t.priceMinor = 0
                     AND e.status <> com.imin.iminapi.model.EventStatus.PAST
                     AND e.status <> com.imin.iminapi.model.EventStatus.CANCELLED
                     AND (e.onSaleAt IS NULL OR e.onSaleAt <= :now)
                     AND (t.saleStartsAt IS NULL OR t.saleStartsAt <= :now)
                     AND (t.saleClosesAt IS NULL OR t.saleClosesAt > :now)
                     AND (e.saleClosesAt IS NULL OR e.saleClosesAt > :now)
                     AND (t.quantity - t.reserved - t.sold) > 0
                ))
         ORDER BY e.startsAt ASC NULLS LAST, e.id ASC
""")
    Page<Event> findPublicListing(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("genreKey") String genreKey,
            @Param("type") String type,
            @Param("cityKey") String cityKey,
            @Param("country") String country,
            @Param("q") String q,
            @Param("orgId") UUID orgId,
            @Param("onSaleOnly") boolean onSaleOnly,
            @Param("includeOngoing") boolean includeOngoing,
            @Param("freeOnly") boolean freeOnly,
            @Param("now") Instant now,
            Pageable pageable);

    /**
     * Raw material for the city facet: {@code (venueCityKey, venueCity, venueCountry, count)},
     * one row per distinct spelling/country combination within a key.
     *
     * <p>The WHERE clause is the SAME eligibility predicate {@link #findPublicListing}
     * uses (not deleted, PUBLIC, published, not DRAFT, not CANCELLED) minus the user
     * filters, so the count a city chip shows is the number of events the buyer actually
     * lands on when they tap it.
     *
     * <p>Grouping is on the derived {@code venue_city_key} (V82) — the same column
     * {@link #findPublicListing} now filters on — so {@code Metz} and {@code METZ} are one
     * group and a merged chip's count is reproducible by its own result page. The display
     * spelling and country stay in the GROUP BY because the service still needs them: it picks
     * the most common spelling as the chip label and decides whether the key's countries agree.
     * That fold is Java, not SQL, because "most common spelling" wants no window function and
     * the facet is small (one row per city, not per event).
     */
    @Query("""
        SELECT e.venueCityKey, e.venueCity, e.venueCountry, COUNT(e) FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
           AND e.status <> com.imin.iminapi.model.EventStatus.CANCELLED
           AND e.venueCityKey IS NOT NULL
           AND e.venueCityKey <> ''
         GROUP BY e.venueCityKey, e.venueCity, e.venueCountry
         ORDER BY e.venueCityKey ASC
""")
    List<Object[]> findPublicCityCounts();

    /**
     * Writes ONLY the two venue coordinate columns (V80). The geocoding listener's sole
     * write path.
     *
     * <p><b>Why not {@code save(event)}.</b> The listener is deliberately non-transactional
     * — a provider call of up to ~9.4s must not sit inside a transaction holding a pooled DB
     * connection — so the entity it read is DETACHED by the time the answer comes back, and
     * {@code save()} on a detached entity is {@code em.merge()}: it copies EVERY field of that
     * now-stale snapshot over whatever the row has become. {@code Event} has no {@code @Version}
     * to catch it. Inside that window an organizer's Publish is silently undone, a soft-delete
     * is nulled (the event reappears in the public feed), {@code EventStatusSweeper}'s LIVE→PAST
     * is reverted, and {@code sold}/{@code revenueMinor} regress. A targeted UPDATE cannot do
     * any of that: the two columns it names are the only two it can touch, and they are ones no
     * other writer sets.
     *
     * <p><b>{@code updated_at} is deliberately NOT bumped.</b> A geocode is a derived fill, not
     * an organizer edit. The organizer PATCH path uses {@code updated_at} as its If-Match ETag,
     * so bumping it here would 412 an organizer holding a perfectly good ETag for a change they
     * did not make — the same reasoning V82's backfill records. (Bulk UPDATE bypasses
     * {@code @PreUpdate} anyway, so this is a decision, not an accident.)
     *
     * @return rows updated — 0 when the event was hard-deleted during the geocode call
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        UPDATE Event e
           SET e.venueLatitude = :latitude,
               e.venueLongitude = :longitude
         WHERE e.id = :id
    """)
    int updateVenueCoordinates(@Param("id") UUID id,
                               @Param("latitude") Double latitude,
                               @Param("longitude") Double longitude);

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

    /**
     * Competing-nights signal (predictor task scope A): other PLATFORM events in the same city
     * whose start falls within [{@code from}, {@code to}] (the ±window around the subject event),
     * excluding the subject itself and soft-deleted/draft events. Returns {@code (id, genre)} rows
     * so the service can count them, flag genre overlap, and sum their capacity. Real internal
     * data only — no external event calendar.
     *
     * <p>Matches on the derived {@code venue_city_key} (V82). Besides merging the case variants
     * of one city, this removes a latent production bug: the previous {@code LOWER(:city)} on a
     * nullable String parameter passes on H2 but throws {@code function lower(bytea) does not
     * exist} on PostgreSQL the first time a null is bound. The caller normalises with
     * {@code EventNormalization.cityKey} and a blank key binds no rows.
     */
    @Query("""
        SELECT e.id, e.genre FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.id <> :selfId
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
           AND e.startsAt IS NOT NULL
           AND e.startsAt >= :from AND e.startsAt <= :to
           AND e.venueCityKey = CAST(:cityKey AS string)
    """)
    List<Object[]> findCompetingNights(@Param("selfId") UUID selfId, @Param("cityKey") String cityKey,
                                       @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Raw material for the genre facet: {@code (genreKey, genre, count)}, one row per distinct
     * spelling within a key.
     *
     * <p>Same shape and same reasoning as {@link #findPublicCityCounts()}. The WHERE clause is
     * the listing's own eligibility predicate minus the user filters, so a chip's count is the
     * number of events the buyer lands on when they tap it. Grouping is on the derived
     * {@code genre_key} (V82) — the column {@link #findPublicListing} filters on — so
     * {@code Techno} and {@code techno} are one group; the display spelling stays in the GROUP BY
     * because the service picks the most common one as the chip label. That fold is Java, not
     * SQL: "most common spelling" wants no window function and the facet is one row per genre.
     */
    @Query("""
        SELECT e.genreKey, e.genre, COUNT(e) FROM Event e
         WHERE e.deletedAt IS NULL
           AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
           AND e.publishedAt IS NOT NULL
           AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
           AND e.status <> com.imin.iminapi.model.EventStatus.CANCELLED
           AND e.genreKey IS NOT NULL
           AND e.genreKey <> ''
         GROUP BY e.genreKey, e.genre
         ORDER BY e.genreKey ASC
""")
    List<Object[]> findPublicGenreCounts();

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
