package com.imin.iminapi.predictor.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/v1/events/{eventId}/prediction/feedback (frozen contract, task 86cav4766).
 * {@code type}: dismissed | executed.
 */
public record PredictionFeedbackRequest(
        @NotBlank String recommendationId,
        @NotBlank String type) {}
