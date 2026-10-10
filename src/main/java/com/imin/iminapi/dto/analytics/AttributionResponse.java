package com.imin.iminapi.dto.analytics;

import java.util.List;

/**
 * UTM attribution overview for an org. Revenue is attributed last-touch by
 * channel ({@code utm_source}). ROAS is deliberately out of scope (needs
 * ad-spend, a later phase).
 *
 * <p>{@code attributedRevenueMinor} is the sum of tagged live-mode orders' totals less their
 * SUCCEEDED refunds and LOST chargebacks, each order clamped at zero (minor units). {@code untaggedPct} is the share
 * of distinct visitors with at least one beacon carrying no {@code utm_source} (0 when none).
 */
public record AttributionResponse(
        long attributedRevenueMinor,
        int untaggedPct,
        List<Channel> channels) {

    /** Per-{@code utm_source} net revenue; {@code visits} is distinct visitors (anon ids) seen on that source. */
    public record Channel(String source, long revenueMinor, int visits) {}
}
