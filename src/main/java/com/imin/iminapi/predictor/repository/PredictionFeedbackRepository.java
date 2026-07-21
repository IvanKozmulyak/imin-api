package com.imin.iminapi.predictor.repository;

import com.imin.iminapi.predictor.model.PredictionFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

/**
 * Recommendation feedback store (spec §4.3). Child of the prediction ledger — "visible in
 * the ledger" via {@code ledgerId}. Lets later phases suppress dismissed recommendations
 * and re-forecast after an execution.
 */
@RepositoryRestResource(exported = false)
public interface PredictionFeedbackRepository extends JpaRepository<PredictionFeedback, UUID> {

    List<PredictionFeedback> findByLedgerId(UUID ledgerId);

    List<PredictionFeedback> findByEventIdAndRecommendationId(UUID eventId, String recommendationId);

    List<PredictionFeedback> findByEventId(UUID eventId);
}
