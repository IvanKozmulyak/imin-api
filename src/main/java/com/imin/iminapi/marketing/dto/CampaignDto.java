package com.imin.iminapi.marketing.dto;

import com.imin.iminapi.marketing.model.Campaign;

import java.time.Instant;
import java.util.UUID;

/** Full campaign detail (spec §2.4 GET /campaigns/{id}). */
public record CampaignDto(
        UUID id,
        UUID orgId,
        String channel,
        String name,
        String status,
        UUID segmentId,
        UUID eventId,
        String origin,
        UUID momentumSuggestionId,
        Instant scheduledAt,
        Instant sentAt,
        Integer recipientCount,
        Integer excludedCount,
        // email-only
        String subject,
        String preheader,
        String bodyMd,
        Instant createdAt,
        Instant updatedAt
) {
    public static CampaignDto from(Campaign c) {
        return new CampaignDto(
                c.getId(), c.getOrgId(), c.getChannel(), c.getName(), c.getStatus(),
                c.getSegmentId(), c.getEventId(), c.getOrigin(), c.getMomentumSuggestionId(),
                c.getScheduledAt(), c.getSentAt(), c.getRecipientCount(), c.getExcludedCount(),
                c.getSubject(), c.getPreheader(), c.getBodyMd(),
                c.getCreatedAt(), c.getUpdatedAt());
    }
}
