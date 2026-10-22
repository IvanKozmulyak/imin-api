package com.imin.iminapi.marketing.repository;

import com.imin.iminapi.marketing.model.StockAlertSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface StockAlertSettingsRepository extends JpaRepository<StockAlertSettings, UUID> {

    Optional<StockAlertSettings> findByEventId(UUID eventId);

    /** Settings with a switch on whose event is live, public and on sale now (findMomentumCandidates' predicate + PUBLIC). */
    @Query("SELECT s FROM StockAlertSettings s, Event e WHERE e.id = s.eventId "
            + "AND (s.lastTicketsOn = true OR s.priceUpOn = true) "
            + "AND e.deletedAt IS NULL "
            + "AND e.status = com.imin.iminapi.model.EventStatus.LIVE "
            + "AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC "
            + "AND e.startsAt > :now "
            + "AND (e.onSaleAt IS NULL OR e.onSaleAt <= :now) "
            + "AND (e.saleClosesAt IS NULL OR e.saleClosesAt > :now) "
            + "ORDER BY e.startsAt ASC")
    List<StockAlertSettings> findActiveForEvaluation(@Param("now") Instant now);
}
