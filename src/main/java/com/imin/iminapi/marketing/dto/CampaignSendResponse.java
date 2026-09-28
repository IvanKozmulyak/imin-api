package com.imin.iminapi.marketing.dto;

import java.time.Instant;

/**
 * Outcome of {@code POST /campaigns/{id}/send}: the stored send time, or {@code armed} with no time for an
 * audience-plan slump arm that waits for Momentum.
 */
public record CampaignSendResponse(Instant scheduledAt, boolean armed) {}
