package com.imin.iminapi.marketing.dto;

/**
 * Email campaign stats (spec §3). {@code delivered} = accepted by the recipient's mail provider (including people who later unsubscribed or
 * complained, excluding bounces); {@code failed} never left; {@code skipped} is live, mid-send diverts included;
 * {@code queued} = still pending. opened/clicked skip unsubscribe-link clicks and objected members (rows stamped before
 * 21 October 2026 may include both); attributedPurchases = tagged checkout starts, attributedOrders = live orders behind revMinor.
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
        long attributedOrders,
        long queued,
        long failed,
        long skipped
) {}
