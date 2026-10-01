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
 *
 * <p>{@code dateCheck}: present when the date-check gate is open for the org and a check is linked to the event,
 * on every status. {@code stale} means the event's night is not a date that check scored.
 *
 * <p>{@code verdictFeedback}: the organizer's answer to "did the date verdict match?", present only alongside
 * {@code dateCheck}, for a past event that has an answer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PredictionStatusResponse(
        String status,
        PredictionResult result,
        String inputHash,
        Instant generatedAt,
        Integer dismissedCount,
        EventDateCheckDto dateCheck,
        DateVerdictFeedbackDto verdictFeedback) {

    /** Backward-compatible 4-arg factory for states with no served result (none/pending/failed-read). */
    public PredictionStatusResponse(String status, PredictionResult result, String inputHash, Instant generatedAt) {
        this(status, result, inputHash, generatedAt, null, null, null);
    }

    /** Backward-compatible 5-arg factory without a date check. */
    public PredictionStatusResponse(String status, PredictionResult result, String inputHash, Instant generatedAt,
                                    Integer dismissedCount) {
        this(status, result, inputHash, generatedAt, dismissedCount, null, null);
    }

    /** Backward-compatible 6-arg factory without a verdict answer. */
    public PredictionStatusResponse(String status, PredictionResult result, String inputHash, Instant generatedAt,
                                    Integer dismissedCount, EventDateCheckDto dateCheck) {
        this(status, result, inputHash, generatedAt, dismissedCount, dateCheck, null);
    }

    public static final String STATUS_NONE = "none";
    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_FAILED_BENCHMARK_ONLY = "failed_benchmark_only";
}
