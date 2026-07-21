package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.EventSalesDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Reads/writes the materialized sales trajectory (spec §6.2). The materialization
 * recomputes a whole event's series and replaces it ({@link #deleteByEventId} +
 * insert) so the job is idempotent at the event grain.
 */
@RepositoryRestResource(exported = false)
public interface EventSalesDailyRepository extends JpaRepository<EventSalesDaily, UUID> {

    /** All daily points for an event, ordered by day then tier — drives the normalized read. */
    List<EventSalesDaily> findByEventIdOrderBySalesDateAscTierIdAsc(UUID eventId);

    @Modifying
    @Transactional
    @Query("delete from EventSalesDaily d where d.eventId = :eventId")
    void deleteByEventId(@Param("eventId") UUID eventId);
}
