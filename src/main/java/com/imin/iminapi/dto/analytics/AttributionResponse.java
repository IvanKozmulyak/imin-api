package com.imin.iminapi.dto.analytics;

import java.util.List;

/**
 * UTM attribution overview for an org. Revenue is attributed last-touch by
 * channel ({@code utm_source}). ROAS is deliberately out of scope (needs
 * ad-spend, a later phase).
 *
 * <p>{@code attributedRevenueMinor} is the org's total paid revenue pool (minor
 * units) that the channels divide up. {@code untaggedPct} is the share of all
 * visits that carried no {@code utm_source} (0 when there are no visits).
 * {@code repeatBuyerPct} is the share of distinct buyers who placed more than
 * one order.
 */
public record AttributionResponse(
        long attributedRevenueMinor,
        int untaggedPct,
        int repeatBuyerPct,
        List<Channel> channels) {

    /** Per-{@code utm_source} revenue + visit count. */
    public record Channel(String source, long revenueMinor, int visits) {}
}
