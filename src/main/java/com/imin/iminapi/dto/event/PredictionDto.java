package com.imin.iminapi.dto.event;

import com.imin.iminapi.model.Prediction;

import java.time.Instant;
import java.util.UUID;

public record PredictionDto(
        UUID eventId, int score, int rangeLow, int rangeHigh,
        int confidencePct, String insight, String modelVersion, Instant computedAt) {
    public static PredictionDto from(Prediction p) {
        return new PredictionDto(p.getEventId(), p.getScore(), p.getRangeLow(), p.getRangeHigh(),
                p.getConfidencePct(), p.getInsight(), p.getModelVersion(), p.getComputedAt());
    }
}
