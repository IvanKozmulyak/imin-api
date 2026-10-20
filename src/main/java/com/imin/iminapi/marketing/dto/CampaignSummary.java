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
        /** Deprecated: read {@code failureReason}. The stored free text, kept for older clients. */
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
        Long revMinor,
        /** Name of the org's segment {@code segmentId}, hidden origins included; null when none or gone. */
        String segmentName,
        /** Name of the linked, non-deleted event of this org; null without one. */
        String eventName,
        /** IANA zone of that same event, for showing its send times; null without one. */
        String eventTimezone,
        /** Why the last send failed: legal_identity_missing|segment_missing|nothing_sent|send_failed; null when none is stored. */
        String failureReason,
        /** Rows that left the queue, as {@code stats.sent}; the three counts are null unless email and a row left. */
        Long sentCount,
        /** Rows with a recorded open, as {@code stats.opened}; estimates (image loading, image blocking). */
        Long openedCount,
        /** Rows with a recorded click, as {@code stats.clicked} (which says what older rows count). */
        Long clickedCount
) {
    /** Projection for the list read path: revenue, the batch-resolved segment and event context, and the counts. */
    public static CampaignSummary from(Campaign c, Long revMinor, String segmentName,
                                       String eventName, String eventTimezone, String failureReason,
                                       Long sentCount, Long openedCount, Long clickedCount) {
        return new CampaignSummary(
                c.getId(), c.getChannel(), c.getName(), c.getStatus(),
                c.getSegmentId(), c.getEventId(), c.getOrigin(),
                c.getRecipientCount(), c.getExcludedCount(), c.getLastError(),
                c.getScheduledAt(), c.getSentAt(), c.getCreatedAt(), revMinor,
                segmentName, eventName, eventTimezone, failureReason,
                sentCount, openedCount, clickedCount);
    }
}
