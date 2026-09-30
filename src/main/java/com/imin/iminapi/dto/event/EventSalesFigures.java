package com.imin.iminapi.dto.event;

/**
 * Live sales figures for one event, as the event Overview computes them.
 * {@code capacity} is null when the event has no tiers (zero capacity is unknown capacity).
 */
public record EventSalesFigures(int sold, Integer capacity, long revenueMinor) {

    /** A fresh draft, or a caller without live totals wired: nothing sold, capacity unknown. */
    public static final EventSalesFigures EMPTY = new EventSalesFigures(0, null, 0L);
}
