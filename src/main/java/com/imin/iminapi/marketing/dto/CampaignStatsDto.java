package com.imin.iminapi.marketing.dto;

/**
 * Email campaign stats block (spec §3). {@code attributedPurchases} = checkout-start sessions carrying the campaign's
 * tag, abandoned ones included; {@code attributedOrders} = live orders behind its attributed revenue.
 */
public record CampaignStatsDto(
        long sent,
        long delivered,
        long opened,
        long clicked,
        long bounced,
        long unsubscribed,
        long complained,
        long attributedPurchases,
        long attributedOrders
) {}
