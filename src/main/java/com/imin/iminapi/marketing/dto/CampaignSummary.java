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
        Instant createdAt,
        /**
         * Attributed revenue in minor units (§3) — the list table's revenue column. Same
         * semantics as {@link CampaignDto#revMinor}: {@code null} = campaign never sent, so
         * nothing could be attributed (FE renders an em-dash); {@code 0} = sent and drove no
         * paid orders. Batched by the caller — one query for the whole page, not per row.
         */
        Long revMinor
) {
    /** Projection with no attributed revenue. See {@link #revMinor}. */
    public static CampaignSummary from(Campaign c) {
        return from(c, null);
    }

    /** Projection carrying the campaign's attributed revenue (list read path). */
    public static CampaignSummary from(Campaign c, Long revMinor) {
        return new CampaignSummary(
                c.getId(), c.getChannel(), c.getName(), c.getStatus(),
                c.getSegmentId(), c.getEventId(), c.getOrigin(),
                c.getRecipientCount(), c.getExcludedCount(), c.getLastError(),
                c.getScheduledAt(), c.getSentAt(), c.getCreatedAt(), revMinor);
    }
}
