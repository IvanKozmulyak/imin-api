package com.imin.iminapi.predictor.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One radar re-check of an event's night. Both ends are frozen at run time: {@code verdictBefore}/{@code riskBefore}
 * are the baseline's (null when it did not score the night), {@code verdictAfter}/{@code riskAfter} the run's own.
 * {@code alert} is {@code sent} when this run claimed the event's alert day.
 */
public record RadarRunDto(
        UUID dateCheckId,
        int milestone,
        LocalDate night,
        Instant checkedAt,
        @Schema(allowableValues = {"good", "adjust", "move", "not_enough_data"}) String verdictBefore,
        @Schema(allowableValues = {"good", "adjust", "move", "not_enough_data"}) String verdictAfter,
        Integer riskBefore,
        Integer riskAfter,
        @Schema(allowableValues = {"sent", "none"}) String alert) {

    public static final String ALERT_SENT = "sent";
    public static final String ALERT_NONE = "none";
}
