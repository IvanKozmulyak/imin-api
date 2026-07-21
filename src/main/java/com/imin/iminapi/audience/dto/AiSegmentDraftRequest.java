package com.imin.iminapi.audience.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/v1/audience/segments/ai-draft} — the organizer's natural-language
 * audience description ("everyone who spent over €100", "people who attended at least 2 events").
 *
 * <p>Input-validated at the controller via {@code @Valid}: non-blank and capped at 500 characters.
 * This is DISTINCT from AI "couldn't map it" degradation: a well-formed prompt the model cannot
 * express returns a 200 with empty rules + {@code unsupported} reasons (see {@code AiSegmentService}),
 * never a 4xx.
 */
public record AiSegmentDraftRequest(
        @NotBlank(message = "Describe the audience you want to target")
        @Size(max = 500, message = "Keep the description under 500 characters")
        String prompt) {}
