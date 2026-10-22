package com.imin.iminapi.marketing.dto;

import com.imin.iminapi.marketing.model.Campaign;

import java.time.Instant;
import java.util.UUID;

/**
 * Campaign detail projection for GET /campaigns/{id} (spec §2.4 + §3). Carries every
 * {@link CampaignDto} field flat (so the FE `CampaignDetailDto extends CampaignDto`
 * contract still reads whole) plus the aggregate {@code stats} block. {@code stats} is
 * null until the first send materializes recipients.
 */
public record CampaignDetailDto(
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
        /** The email template this campaign renders with (V66). Never null (defaults 'classic'). */
        String templateKey,
        /** AI Act Art.50: the subject line is AI-generated (sticky once set). */
        boolean subjectAiGenerated,
        /** AI Act Art.50: the preheader or body is AI-generated (sticky once set). */
        boolean bodyAiGenerated,
        Instant createdAt,
        Instant updatedAt,
        // §3 aggregate stats (null until first send)
        CampaignStatsDto stats,
        /** Name of the org's segment {@code segmentId}, hidden plan/Momentum segments included; null when none or gone. */
        String segmentName,
        /** Built-in key of that segment (REPEAT, VIP, …) so clients show its name in their language; null for an organizer's own or none. */
        String segmentPrebuiltKey,
        /** IANA zone of the linked active event, for showing its send times; null without one. */
        String eventTimezone,
        /** Momentum trigger (launch_push|slump|urgency_72h|sold_out|clicked_no_buy) of the suggestion this campaign was approved from; null for manual campaigns. */
        String triggerType,
        /** Failed sends so far (0..3); Retry is accepted while it is below 3. */
        int attempts,
        /** Why the last send failed: legal_identity_missing|segment_missing|nothing_sent|provider_rejected|send_failed; null when none is stored. */
        String failureReason,
        /** Name of the linked active event of this org; null without one. */
        String eventName,
        /** Attributed revenue in minor units, as {@link CampaignDto#revMinor}: null until the campaign has sent. */
        Long revMinor,
        /** Live skip-reason counts over the campaign's skipped rows, mid-send diverts included; null when it has no rows. */
        java.util.Map<String, Long> skipReasons,
        /** Language of the fan-facing shell and unsubscribe page: en|es|fr|uk. Never null. */
        String locale,
        /**
         * Why a scheduled or sending campaign starts no batch now: {@code org_paused} (IMIN paused the org's marketing
         * sending after complaints or bounces; the reason is on GET /marketing/channels) or {@code moderation_hold}
         * (an active hold on the org or on this campaign's event); null when nothing holds it or it is not waiting.
         */
        String sendHold,
        /** With an event: people holding a live ticket for it are left out (skip reason {@code has_ticket}). */
        boolean skipTicketHolders,
        /** Stock alerts only: the facts every email quoted (frozen at the first drive), else those read for the draft. */
        StockFactsDto stockFacts
) {
    public static CampaignDetailDto from(Campaign c, CampaignStatsDto stats, String segmentName,
                                         String segmentPrebuiltKey, String eventTimezone,
                                         String triggerType, String failureReason, String eventName, Long revMinor,
                                         java.util.Map<String, Long> skipReasons, String sendHold) {
        return from(c, stats, segmentName, segmentPrebuiltKey, eventTimezone, triggerType, failureReason, eventName,
                revMinor, skipReasons, sendHold, null);
    }

    public static CampaignDetailDto from(Campaign c, CampaignStatsDto stats, String segmentName,
                                         String segmentPrebuiltKey, String eventTimezone,
                                         String triggerType, String failureReason, String eventName, Long revMinor,
                                         java.util.Map<String, Long> skipReasons, String sendHold,
                                         StockFactsDto stockFacts) {
        return new CampaignDetailDto(
                c.getId(), c.getOrgId(), c.getChannel(), c.getName(), c.getStatus(),
                c.getSegmentId(), c.getEventId(), c.getOrigin(), c.getMomentumSuggestionId(),
                c.getScheduledAt(), c.getSentAt(), c.getRecipientCount(), c.getExcludedCount(),
                c.getSubject(), c.getPreheader(), c.getBodyMd(), c.getTemplateKey(),
                c.isSubjectAiGenerated(), c.isBodyAiGenerated(),
                c.getCreatedAt(), c.getUpdatedAt(), stats, segmentName, segmentPrebuiltKey, eventTimezone, triggerType,
                c.getAttempts(), failureReason, eventName, revMinor, skipReasons, c.getLocale(), sendHold,
                c.isSkipTicketHolders(), stockFacts);
    }
}
