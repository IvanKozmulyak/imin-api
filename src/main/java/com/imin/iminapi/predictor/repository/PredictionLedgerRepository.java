package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.PredictionLedger;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * All outcome-joined renders — the scored evaluation set behind segment aggregation and
     * the calibration view. Unpaged on purpose: early-platform volumes are small and the
     * aggregation needs the whole set; revisit with a streaming scan if this ever grows hot.
     */
    List<PredictionLedger> findByOutcomeJoinedAtIsNotNull();

    long countByEventId(UUID eventId);
}
