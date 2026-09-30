package com.imin.iminapi.predictor.dto;

import java.time.Instant;
import java.util.Map;

/** One answered question; wire values lower-case, {@code facts} are the template params. */
public record FindingDto(String questionId, String kind, String status, int strength, int weight, String sourceKind,
                         String window, boolean stopFactor, String templateKey, Map<String, Object> facts, String url,
                         String quote, Instant fetchedAt) {}
