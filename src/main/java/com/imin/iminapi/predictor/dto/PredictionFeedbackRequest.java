package com.imin.iminapi.predictor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/events/{eventId}/prediction/feedback.
 * {@code type}: dismissed | executed | restored | date_verdict_match.
 *
 * <p>{@code recommendationId} is required for dismissed, executed and restored (checked in the service, 400
 * {@code fields.recommendationId}) and ignored for date_verdict_match. It is bounded at the width of
 * {@code prediction_feedback.recommendation_id} (VARCHAR(128)): it is whatever the LLM emitted as its "short
 * stable slug", and unbounded it reached the INSERT as an unnamed constraint 400. {@code type} is bounded too;
 * the closed set is checked in the service, this only stops an absurd body from getting that far.
 *
 * <p>{@code answer} (yes | partly | no, required) and {@code comment} (optional, at most 1000 characters, trimmed,
 * blank stored as null) apply only to date_verdict_match, the after-event answer to the date-check verdict.
 */
public record PredictionFeedbackRequest(
        @Size(max = 128, message = "must be at most 128 characters") String recommendationId,
        @NotBlank @Size(max = 32, message = "must be at most 32 characters") String type,
        @Size(max = 8, message = "must be at most 8 characters")
        @Schema(allowableValues = {"yes", "partly", "no"}) String answer,
        @Size(max = 1000, message = "must be at most 1000 characters") String comment) {

    /** A recommendation feedback body (dismissed | executed | restored), no verdict answer. */
    public PredictionFeedbackRequest(String recommendationId, String type) {
        this(recommendationId, type, null, null);
    }
}
