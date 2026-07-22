package com.imin.iminapi.predictor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * GET /api/v1/events/{eventId}/prediction — the latest prediction state for an event
 * (frozen contract, task 86cav4766). {@code status}: none | pending | ready |
 * failed_benchmark_only. {@code result} present only for ready / failed_benchmark_only.
 *
 * <p>{@code dismissedCount} (task 86cav47a5): how many of the render's recommendations are
 * currently suppressed by dismissal memory — feeds the FE's "N dismissed — remembered" row.
 * {@code result.recommendations} already has those removed. Absent unless a result is present.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PredictionStatusResponse(
        String status,
        PredictionResult result,
        String inputHash,
        Instant generatedAt,
        Integer dismissedCount) {

    /** Backward-compatible 4-arg factory for states with no served result (none/pending/failed-read). */
    public PredictionStatusResponse(String status, PredictionResult result, String inputHash, Instant generatedAt) {
        this(status, result, inputHash, generatedAt, null);
    }

    public static final String STATUS_NONE = "none";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_FAILED_BENCHMARK_ONLY = "failed_benchmark_only";
}
