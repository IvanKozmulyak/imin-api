package com.imin.iminapi.audienceplan.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /api/v1/public/surveys/{token}}. Every answer is optional; the email and consent fields
 * are sent only with the ticked box. Any other property lands in {@code unknownFields} and is refused, so a
 * client can never add an identity question. Validation is in {@code SurveyService}.
 */
public record SurveyResponseRequest(
        String homeCommune,
        List<String> otherGenres,
        String heardFrom,
        String ageBand,
        Boolean firstTime,
        String noticeVersion,
        String locale,
        Boolean consentGiven,
        String email,
        String consentText,
        String consentTextVersion,
        @JsonAnySetter @Schema(hidden = true) Map<String, Object> unknownFields) {}
