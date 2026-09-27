package com.imin.iminapi.audienceplan.dto;

import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /api/v1/events/{eventId}/survey}. */
public record SurveySettingsRequest(@NotNull Boolean enabled) {}
