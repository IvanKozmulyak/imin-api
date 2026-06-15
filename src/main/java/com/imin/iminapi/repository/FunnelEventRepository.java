package com.imin.iminapi.repository;

import com.imin.iminapi.model.FunnelEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface FunnelEventRepository extends JpaRepository<FunnelEvent, UUID> {

    /**
     * Distinct-session counts per stage for an event. Tuple shape:
     * {@code [String stage, Long distinctAnonCount]}. Stages with zero rows are
     * simply absent from the result — the caller defaults them to 0.
     */
    @Query("""
            select e.stage, count(distinct e.anonId) from FunnelEvent e
             where e.eventId = :eventId
             group by e.stage
            """)
    List<Object[]> countDistinctAnonByStage(@Param("eventId") UUID eventId);
}
