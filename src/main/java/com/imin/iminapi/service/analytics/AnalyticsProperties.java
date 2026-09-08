package com.imin.iminapi.service.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retention settings for the behavioural analytics log.
 *
 * <p>{@code event_funnel_events} has been append-only since V41 with no expiry
 * at all: every PAGE_VIEW and CHECKOUT_START beacon the buyer site has ever
 * fired is still there. The rows are not anonymous in the CNIL sense either —
 * {@code anon_id} is deliberately reused on the order (V62,
 * {@code orders.anon_id}), so a session row is joinable to a named purchaser.
 * Keeping that forever has no purpose the aggregates need; every funnel and
 * attribution reader works on a rolling window or a per-event count.
 */
@ConfigurationProperties(prefix = "imin.analytics")
public class AnalyticsProperties {

    /**
     * Days of funnel beacon history to keep. {@code 0} or negative disables the
     * purge entirely — an explicit "keep everything", not a silent default, so a
     * misconfiguration cannot quietly delete data either.
     */
    private int funnelRetentionDays = 90;

    public int getFunnelRetentionDays() { return funnelRetentionDays; }

    public void setFunnelRetentionDays(int funnelRetentionDays) {
        this.funnelRetentionDays = funnelRetentionDays;
    }
}
