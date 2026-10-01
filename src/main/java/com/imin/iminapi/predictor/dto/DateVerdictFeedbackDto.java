package com.imin.iminapi.predictor.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The organizer's stored answer to "did the date verdict match?". {@code answeredAt} moves on every save;
 * {@code dateCheckId}, {@code forDate} and {@code verdict} are the rated row as it was at the first answer
 * ({@code dateCheckId} null once that check is deleted).
 */
public record DateVerdictFeedbackDto(
        @Schema(allowableValues = {"yes", "partly", "no"}) String answer,
        String comment,
        Instant answeredAt,
        UUID dateCheckId,
        LocalDate forDate,
        @Schema(allowableValues = {"good", "adjust", "move"}) String verdict) {}
