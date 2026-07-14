package com.imin.iminapi.marketing.dto;

/**
 * Email campaign stats block (spec §3). Counts from campaign_recipients status +
 * open/click timestamps, with {@code attributedPurchases} from the utm_campaign
 * funnel read-model ({@link com.imin.iminapi.marketing.service.CampaignAttributionService}).
 */
public record CampaignStatsDto(
        long sent,
        long delivered,
        long opened,
        long clicked,
        long bounced,
        long unsubscribed,
        long complained,
        long attributedPurchases
) {}
