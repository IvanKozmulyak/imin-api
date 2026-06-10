package com.imin.iminapi.repository;

import com.imin.iminapi.model.TicketTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Collection;
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

    /** Total confirmed sales across all tiers of an event (refunds decrement this). */
    @Query("SELECT COALESCE(SUM(t.sold), 0) FROM TicketTier t WHERE t.eventId = :eventId")
    int sumSoldByEventId(@Param("eventId") UUID eventId);

    @Query("""
        SELECT t.eventId, MIN(t.priceMinor)
          FROM TicketTier t
         WHERE t.enabled = true AND t.eventId IN :eventIds
         GROUP BY t.eventId
    """)
    List<Object[]> findMinEnabledPriceByEventIds(
            @Param("eventIds") Collection<UUID> eventIds);

    /**
     * Per-event inventory rollup across ENABLED tiers only, for the public listing's
     * soldOut / lowStock flags. One batch round-trip per page (no N+1) — same pattern
     * as {@link #findMinEnabledPriceByEventIds}.
     *
     * <p>Returns {@code (eventId, SUM(remaining), MAX(remaining))} where per-tier
     * {@code remaining = quantity - reserved - sold}. {@code MAX(remaining) <= 0}
     * means every enabled tier is empty ⇒ soldOut; {@code SUM} (clamped to >=0 in the
     * service, matching {@code PublicTierDto.from}'s {@code Math.max(0, ...)}) feeds the
     * lowStock total. Events with no enabled tiers simply do not appear in the result and
     * the service treats that as "not sold out".
     */
    @Query("""
        SELECT t.eventId,
               SUM(t.quantity - t.reserved - t.sold),
               MAX(t.quantity - t.reserved - t.sold)
          FROM TicketTier t
         WHERE t.enabled = true AND t.eventId IN :eventIds
         GROUP BY t.eventId
    """)
    List<Object[]> findEnabledRemainingByEventIds(
            @Param("eventIds") Collection<UUID> eventIds);

    /**
     * Pessimistic row lock for inventory updates. Used by {@code InventoryService} to
     * serialize concurrent reserve / release / confirm flows so two buyers can't both
     * see the same {@code available} count and both succeed past the capacity check.
     *
     * <p>Uses a native {@code SELECT ... FOR UPDATE} (instead of a JPA
     * {@code PESSIMISTIC_WRITE} lock hint) because the modern PostgreSQL Hibernate
     * dialect translates {@code PESSIMISTIC_WRITE} to {@code FOR NO KEY UPDATE},
     * which H2's PG-compat mode does not understand. The plain {@code FOR UPDATE}
     * clause works on both Postgres and H2.
     */
    @Query(value = "SELECT * FROM ticket_tiers WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<TicketTier> findByIdForUpdate(@Param("id") UUID id);
}
