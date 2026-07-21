package com.imin.iminapi.predictor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * POST /api/v1/events/{eventId}/prediction (frozen contract, task 86cav4766):
 * 202 {@code {predictionId, status:"pending"}} when a scoring run was dispatched (or is
 * already in flight), 200 {@code {status:"ready", cached:true, result}} on an input-hash hit.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PredictionTriggerResponse(
        UUID predictionId,
        String status,
        Boolean cached,
        PredictionResult result) {}
