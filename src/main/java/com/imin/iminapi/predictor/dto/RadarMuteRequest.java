package com.imin.iminapi.predictor.dto;

/** PUT /api/v1/events/{eventId}/prediction/radar/mute; {@code muted} is required (checked in the service). */
public record RadarMuteRequest(Boolean muted) {}
