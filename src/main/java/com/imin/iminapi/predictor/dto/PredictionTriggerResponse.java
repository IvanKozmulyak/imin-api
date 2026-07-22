package com.imin.iminapi.predictor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * POST /api/v1/events/{eventId}/prediction (frozen contract, task 86cav4766):
 * 202 {@code {predictionId, status:"pending"}} when a scoring run was dispatched (or is
 * already in flight), 200 {@code {status:"ready", cached:true, result, dismissedCount}} on an
 * input-hash hit. {@code dismissedCount} (task 86cav47a5) mirrors the GET envelope — the cached
 * {@code result.recommendations} already has dismissed ones filtered out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PredictionTriggerResponse(
        UUID predictionId,
        String status,
        Boolean cached,
        PredictionResult result,
        Integer dismissedCount) {

    /** Backward-compatible 4-arg factory for the pending path (no result, no dismissed count). */
    public PredictionTriggerResponse(UUID predictionId, String status, Boolean cached, PredictionResult result) {
        this(predictionId, status, cached, result, null);
    }
}
