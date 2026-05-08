package com.imin.iminapi.repository;

import com.imin.iminapi.model.TicketTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface TicketTierRepository extends JpaRepository<TicketTier, UUID> {
    List<TicketTier> findByEventIdOrderBySortOrderAsc(UUID eventId);

    Optional<TicketTier> findByIdAndEventId(UUID id, UUID eventId);

    List<TicketTier> findByEventIdAndEnabledTrueOrderBySortOrderAsc(UUID eventId);

    @Query("SELECT COALESCE(SUM(t.quantity), 0) FROM TicketTier t WHERE t.eventId = :eventId")
    int sumQuantityByEventId(@Param("eventId") UUID eventId);

    @Query("""
        SELECT t.eventId, MIN(t.priceMinor)
          FROM TicketTier t
         WHERE t.enabled = true AND t.eventId IN :eventIds
         GROUP BY t.eventId
    """)
    java.util.List<Object[]> findMinEnabledPriceByEventIds(
            @Param("eventIds") java.util.Collection<java.util.UUID> eventIds);
}
