package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.Season;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

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

    /** Outcomes still awaiting the post-event finalize pass. Drives the finalize job. */
    List<EventOutcome> findByFinalizedAtIsNull(Pageable pageable);

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
