package com.imin.iminapi.marketing.dto;

/** Email campaign stats (spec §3). opened/clicked skip unsubscribe-link clicks and objected members (rows stamped before
 *  21 October 2026 may include both); attributedPurchases = tagged checkout starts, attributedOrders = live orders behind revMinor. */
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
