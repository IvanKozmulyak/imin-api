package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.PredictionLedger;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

/**
 * The prediction ledger store (spec §5, §7.2). Append-only; the only mutation is the
 * scoring job filling the outcome-join columns.
 */
@RepositoryRestResource(exported = false)
public interface PredictionLedgerRepository extends JpaRepository<PredictionLedger, UUID> {

    /** All renders for an event, newest first — the audit trail behind the calibration view. */
    List<PredictionLedger> findByEventIdOrderByCreatedAtDesc(UUID eventId);

    /** Renders not yet joined to their event's outcome. Drives the monthly scoring job. */
    List<PredictionLedger> findByOutcomeJoinedAtIsNull(Pageable pageable);

    /**
     * Renders that can ACTUALLY be joined right now: not yet joined AND belonging to an event
     * whose outcome is already finalized. The join precondition lives in the query rather than
     * in a Java skip after the page is read, because a live event accrues one un-joinable
     * REFORECAST row per day and rows for events that never finalize never leave the set — so a
     * Java-side filter lets them fill the page permanently and scoring silently stops. Ordered
     * (created, then id) so paging is total and repeatable rather than a heap-order slice.
     */
    @Query("""
            select l from PredictionLedger l
             where l.outcomeJoinedAt is null
               and exists (select 1 from EventOutcome o
                            where o.eventId = l.eventId and o.finalizedAt is not null)
             order by l.createdAt asc, l.id asc
            """)
    List<PredictionLedger> findJoinable(Pageable pageable);

    /**
     * All outcome-joined renders — the scored evaluation set behind segment aggregation and
     * the calibration view. Unpaged on purpose: early-platform volumes are small and the
     * aggregation needs the whole set; revisit with a streaming scan if this ever grows hot.
     */
    List<PredictionLedger> findByOutcomeJoinedAtIsNotNull();

    long countByEventId(UUID eventId);
}
