package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

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
}
