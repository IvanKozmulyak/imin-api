package com.imin.iminapi.service.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the public event listing endpoint.
 *
 * <p>{@code lowStockThreshold} drives the {@code lowStock} flag on each
 * {@code PublicEventListItem}: an event that is not sold out but whose total
 * remaining inventory (across enabled tiers) is at or below this value is
 * flagged so the FE can render an "Almost gone" badge. Default 10.
 */
@ConfigurationProperties(prefix = "imin.public")
public class PublicListingProperties {
    /** Total-remaining cutoff (inclusive) below which a not-sold-out event is "low stock". */
    private int lowStockThreshold = 10;

    public int getLowStockThreshold() { return lowStockThreshold; }
    public void setLowStockThreshold(int lowStockThreshold) { this.lowStockThreshold = lowStockThreshold; }
}
