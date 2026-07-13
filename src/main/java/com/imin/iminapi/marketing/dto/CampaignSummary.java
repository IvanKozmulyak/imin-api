package com.imin.iminapi.marketing.dto;

import com.imin.iminapi.marketing.model.Campaign;

import java.time.Instant;
import java.util.UUID;

/** Paged list-row projection (spec §2.4 GET /campaigns, §2.6 A.2 table). */
public record CampaignSummary(
        UUID id,
        String channel,
        String name,
        String status,
        UUID segmentId,
        UUID eventId,
        String origin,
        Integer recipientCount,
        Integer excludedCount,
        String lastError,
        Instant scheduledAt,
        Instant sentAt,
        Instant createdAt
) {
    public static CampaignSummary from(Campaign c) {
        return new CampaignSummary(
                c.getId(), c.getChannel(), c.getName(), c.getStatus(),
                c.getSegmentId(), c.getEventId(), c.getOrigin(),
                c.getRecipientCount(), c.getExcludedCount(), c.getLastError(),
                c.getScheduledAt(), c.getSentAt(), c.getCreatedAt());
    }
}
