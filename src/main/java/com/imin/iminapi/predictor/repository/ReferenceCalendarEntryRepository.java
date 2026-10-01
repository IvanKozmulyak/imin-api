package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Reference calendar lookups by country, region and start date. */
@RepositoryRestResource(exported = false)
public interface ReferenceCalendarEntryRepository extends JpaRepository<ReferenceCalendarEntry, UUID> {

    /** Callers pass {@code List.of("", region)} so country-wide rows come back with the region's. */
    List<ReferenceCalendarEntry> findByCountryAndRegionInAndCalendarDateBetween(
            String country, Collection<String> regions, LocalDate from, LocalDate to);

    /** Rows whose [calendar_date, end_date] range touches [from, to], so a range starting earlier is included. */
    @Query("select e from ReferenceCalendarEntry e where e.country = :country and e.region in :regions"
            + " and e.calendarDate <= :to and coalesce(e.endDate, e.calendarDate) >= :from"
            + " order by e.calendarDate, e.kind, e.name, e.region")
    List<ReferenceCalendarEntry> findOverlapping(@Param("country") String country,
                                                 @Param("regions") Collection<String> regions,
                                                 @Param("from") LocalDate from,
                                                 @Param("to") LocalDate to);

    /** The rows one sync batch owns. */
    List<ReferenceCalendarEntry> findBySourceUrlAndKindInAndCalendarDateBetween(
            String sourceUrl, Collection<String> kinds, LocalDate from, LocalDate to);

    /** Newest {@code synced_at} among rows whose source URL matches the pattern ({@code !} escapes); null when none. */
    @Query("select max(e.syncedAt) from ReferenceCalendarEntry e where e.sourceUrl like :pattern escape '!'")
    Instant findLatestSyncedAtLike(@Param("pattern") String pattern);

    /** Any row stored by a source whose URLs share this prefix. */
    boolean existsBySourceUrlStartingWith(String prefix);

    boolean existsByCountryAndKindAndCalendarDateBetween(String country, String kind, LocalDate from, LocalDate to);

    /** Latest day any row of this kind covers for the country (its end date when it has one); null when none. */
    @Query("select max(coalesce(e.endDate, e.calendarDate)) from ReferenceCalendarEntry e"
            + " where e.country = :country and e.kind = :kind")
    LocalDate findLatestCoveredDate(@Param("country") String country, @Param("kind") String kind);

    /** Newest {@code synced_at} among the country's rows of this kind; null when none. */
    @Query("select max(e.syncedAt) from ReferenceCalendarEntry e where e.country = :country and e.kind = :kind")
    Instant findLatestSyncedAt(@Param("country") String country, @Param("kind") String kind);
}
