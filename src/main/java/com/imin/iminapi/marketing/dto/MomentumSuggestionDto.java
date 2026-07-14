package com.imin.iminapi.marketing.dto;

import com.imin.iminapi.marketing.model.MomentumSuggestion;

import java.time.Instant;
import java.util.UUID;

/**
 * API view of a momentum suggestion (spec §6.4). metricsSnapshot / draftPayload
 * are surfaced as raw JSON strings — the FE parses them (types in webapp types.ts).
 */
public record MomentumSuggestionDto(
        UUID id,
        UUID eventId,
        String triggerType,
        String status,
        String metricsSnapshot,
        String draftPayload,
        UUID campaignId,
        Instant suggestedAt) {

    public static MomentumSuggestionDto from(MomentumSuggestion s) {
        return new MomentumSuggestionDto(
                s.getId(), s.getEventId(), s.getTriggerType(), s.getStatus(),
                s.getMetricsSnapshot(), s.getDraftPayload(), s.getCampaignId(), s.getSuggestedAt());
    }
}
