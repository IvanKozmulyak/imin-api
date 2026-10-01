package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.OpenEventOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Open-data event nights per city (V165); written only by OpenEventsWriter. */
@RepositoryRestResource(exported = false)
public interface OpenEventOccurrenceRepository extends JpaRepository<OpenEventOccurrence, UUID> {

    List<OpenEventOccurrence> findByCityKeyAndNightDateBetween(String cityKey, LocalDate from, LocalDate to);

    @Modifying
    @Query("delete from OpenEventOccurrence o where o.source = :source and o.cityKey = :cityKey and o.nightDate >= :from")
    int deleteBySourceAndCityKeyAndNightDateGreaterThanEqual(@Param("source") String source,
                                                              @Param("cityKey") String cityKey,
                                                              @Param("from") LocalDate from);

    @Modifying
    @Query("delete from OpenEventOccurrence o where o.nightDate < :before")
    int deleteByNightDateBefore(@Param("before") LocalDate before);

    /** The source's oldest kept row in the city: its synced_at bounds the weeks that source fully covers. */
    Optional<OpenEventOccurrence> findFirstBySourceAndCityKeyOrderBySyncedAtAsc(String source, String cityKey);

    boolean existsBySourceAndCityKeyAndNightDateBefore(String source, String cityKey, LocalDate before);
}
