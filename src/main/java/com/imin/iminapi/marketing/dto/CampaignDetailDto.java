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
        /** IANA zone of the linked active event, for showing its send times; null without one. */
        String eventTimezone
) {
    public static CampaignDetailDto from(Campaign c, CampaignStatsDto stats, String segmentName, String eventTimezone) {
        return new CampaignDetailDto(
                c.getId(), c.getOrgId(), c.getChannel(), c.getName(), c.getStatus(),
                c.getSegmentId(), c.getEventId(), c.getOrigin(), c.getMomentumSuggestionId(),
                c.getScheduledAt(), c.getSentAt(), c.getRecipientCount(), c.getExcludedCount(),
                c.getSubject(), c.getPreheader(), c.getBodyMd(), c.getTemplateKey(),
                c.isSubjectAiGenerated(), c.isBodyAiGenerated(),
                c.getCreatedAt(), c.getUpdatedAt(), stats, segmentName, eventTimezone);
    }
}
