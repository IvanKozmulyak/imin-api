package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.Season;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads/writes the event outcome record (spec §6.1) and serves the comparable
 * corpus segment queries (§6.4).
 *
 * <p>The three segment queries below are deliberately split by relaxation rung
 * rather than expressed as one query with nullable filters: every bind param is
 * non-null and compared by equality, which sidesteps the H2-vs-Postgres
 * {@code lower(bytea)} trap that a nullable {@code String} threaded through a SQL
 * function triggers (see reference: H2 vs PG null-String bytea). Own/foreign split,
 * density counting, privacy aggregation and rounding all happen in Java in
 * {@code ComparableCorpusService} over the small per-segment result.
 *
 * <p>{@code @RepositoryRestResource(exported = false)} keeps spring-data-rest from
 * auto-exposing this repo, matching every other repo in the codebase.
 */
@RepositoryRestResource(exported = false)
public interface EventOutcomeRepository extends JpaRepository<EventOutcome, UUID> {

    /**
     * Outcomes still awaiting the post-event finalize pass, oldest freeze first. Drives the
     * finalize job.
     *
     * <p>The ORDER BY is not cosmetic. This is a capped scan whose caller does NOT finalize
     * everything it fetches — {@code EventOutcomeFinalizeJob} skips every outcome whose event has
     * not yet ended past the grace window. A frozen row is written at publish and stays
     * unfinalized until well after the event, so the unfinalized set is dominated by future
     * events and grows with the published-event count; once it exceeds the page size, an
     * unordered page can be filled entirely with not-yet-due rows while a genuinely due one is
     * never selected, every tick. Oldest-first is the order that drains, and the eventId (the
     * @Id) is the deterministic tiebreaker that keeps the page stable when frozenAt ties.
     * {@code EventRepository.findPayoutCandidates} and {@code OrderRepository.findDue24hReminder}
     * carry an explicit ORDER BY for exactly this reason.
     */
    List<EventOutcome> findByFinalizedAtIsNullOrderByFrozenAtAscEventIdAsc(Pageable pageable);

    /**
     * Outcomes that are actually DUE for the post-event finalize pass: not yet finalized AND
     * belonging to an event that ended before {@code cutoff}. The due predicate lives in the
     * query rather than in a Java skip after the page is read, because every published event
     * gets a {@code finalizedAt = null} row at publish: live events, future events and events
     * with no end time can never satisfy it, and would otherwise occupy the single page forever
     * and starve the rows that can. Ordered (event date, then id) so paging is total and
     * repeatable rather than a heap-order slice.
     *
     * <p><b>Soft-deleted and CANCELLED events are excluded</b> (predictor-edge-10), matching
     * {@code EventRepository.findActive}/{@code findAllPublished} — every other Event query
     * carries the soft-delete filter. A cancelled event's tickets are refunded, so finalizing it
     * would stamp {@code sold_total ≈ 0}, {@code sell_out = false} and {@code attendance ≈ 0};
     * because {@code finalizedAt is not null} is the ONLY membership test the three corpus
     * segment queries below apply, that row would then become a cross-org comparable for every
     * other organizer in its city × genre × band × season and drag the aggregates — and
     * {@code PacingCurveService}'s median/P25/P75 shapes — toward a result that never happened.
     * It would also write fresh derived data for an event the org asked to have deleted.
     */
    @Query("""
            select o from EventOutcome o
             where o.finalizedAt is null
               and exists (select 1 from Event e
                            where e.id = o.eventId
                              and e.deletedAt is null
                              and e.status <> com.imin.iminapi.model.EventStatus.CANCELLED
                              and e.endsAt is not null
                              and e.endsAt < :cutoff)
             order by o.eventDate asc, o.eventId asc
            """)
    List<EventOutcome> findDueForFinalize(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Number of an org's events already snapshotted at publish. Used at freeze time to
     * compute {@code prior_event_count} for the NEXT freeze (excludes the row being written).
     */
    long countByOrgId(UUID orgId);

    // ---------------------------------------------------------------------------
    // Comparable corpus segment queries — FINALIZED outcomes only (a comparable is
    // an event whose actual result is known). All params non-null (trap-free).
    // ---------------------------------------------------------------------------

    /** RelaxationLevel.NONE: city × genre family × capacity band × season. */
    @Query("""
            select o from EventOutcome o
             where o.finalizedAt is not null
               and o.city = :city
               and o.genreFamily = :genreFamily
               and o.capacityBand = :capacityBand
               and o.season = :season
            """)
    List<EventOutcome> findFinalizedByCitySegment(@Param("city") String city,
                                                  @Param("genreFamily") String genreFamily,
                                                  @Param("capacityBand") CapacityBand capacityBand,
                                                  @Param("season") Season season);

    /** RelaxationLevel.CITY_TO_COUNTRY / GENRE_TO_FAMILY: country × genre family × capacity band × season. */
    @Query("""
            select o from EventOutcome o
             where o.finalizedAt is not null
               and o.country = :country
               and o.genreFamily = :genreFamily
               and o.capacityBand = :capacityBand
               and o.season = :season
            """)
    List<EventOutcome> findFinalizedByCountrySegment(@Param("country") String country,
                                                     @Param("genreFamily") String genreFamily,
                                                     @Param("capacityBand") CapacityBand capacityBand,
                                                     @Param("season") Season season);

    /** RelaxationLevel.DROP_SEASON: country × genre family × capacity band (season dropped). */
    @Query("""
            select o from EventOutcome o
             where o.finalizedAt is not null
               and o.country = :country
               and o.genreFamily = :genreFamily
               and o.capacityBand = :capacityBand
            """)
    List<EventOutcome> findFinalizedByCountrySegmentNoSeason(@Param("country") String country,
                                                             @Param("genreFamily") String genreFamily,
                                                             @Param("capacityBand") CapacityBand capacityBand);
}
