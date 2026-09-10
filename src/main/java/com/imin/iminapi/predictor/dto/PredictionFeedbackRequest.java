package com.imin.iminapi.predictor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/v1/events/{eventId}/prediction/feedback (frozen contract, task 86cav4766).
 * {@code type}: dismissed | executed | restored.
 *
 * <p>{@code recommendationId} is bounded at the width of
 * {@code prediction_feedback.recommendation_id} (VARCHAR(128)) — predictor-edge-12. It is
 * whatever the LLM emitted as its "short stable slug", and unbounded it reached the INSERT and
 * came back as an unnamed 400 "Request violates a data constraint", i.e. a served recommendation
 * whose Dismiss button could never work. {@code type} is bounded too; the closed set is checked
 * in the service, this only stops an absurd body from getting that far.
 */
public record PredictionFeedbackRequest(
        @NotBlank @Size(max = 128, message = "must be at most 128 characters") String recommendationId,
        @NotBlank @Size(max = 16, message = "must be at most 16 characters") String type) {}
