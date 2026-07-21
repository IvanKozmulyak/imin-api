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
        /**
         * The email template this campaign renders with (V66): a builtin key
         * ('classic'|'midnight'|'poster'|'mono') or a saved org template's UUID string.
         * Never null on the wire — the column defaults to 'classic'.
         */
        String templateKey,
        Instant createdAt,
        Instant updatedAt,
        /**
         * Attributed revenue in minor units (§3): the TRUE per-order sum of this org's
         * orders whose {@code utm_campaign} is this campaign's id (V62).
         *
         * <p>{@code null} means "not applicable / nothing to report yet" — the campaign has
         * never sent, so no link carrying its tag is in anyone's inbox and no order could
         * possibly be attributed. The FE renders null as an em-dash and deliberately
         * "never invents money when revMinor is absent".
         *
         * <p>{@code 0} is a REAL answer, distinct from null: the campaign sent and drove no
         * paid orders. Campaigns that sent before V62 also read 0 — their orders carry no
         * utm_campaign and cannot be back-filled, because the tag was never captured.
         */
        Long revMinor
) {
    /**
     * Projection with no attributed revenue — for campaigns that cannot have any yet
     * (create/patch/duplicate/approve all return drafts). See {@link #revMinor}.
     */
    public static CampaignDto from(Campaign c) {
        return from(c, null);
    }

    /** Projection carrying the campaign's attributed revenue (read paths). */
    public static CampaignDto from(Campaign c, Long revMinor) {
        return new CampaignDto(
                c.getId(), c.getOrgId(), c.getChannel(), c.getName(), c.getStatus(),
                c.getSegmentId(), c.getEventId(), c.getOrigin(), c.getMomentumSuggestionId(),
                c.getScheduledAt(), c.getSentAt(), c.getRecipientCount(), c.getExcludedCount(),
                c.getSubject(), c.getPreheader(), c.getBodyMd(), c.getTemplateKey(),
                c.getCreatedAt(), c.getUpdatedAt(), revMinor);
    }
}
