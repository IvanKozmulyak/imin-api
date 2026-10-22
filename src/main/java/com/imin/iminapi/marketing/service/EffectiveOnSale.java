package com.imin.iminapi.marketing.service;

import com.imin.iminapi.model.TicketTier;

import java.time.Instant;
import java.util.List;

/** When an event's tickets became buyable, for Momentum's on-sale clock. */
public final class EffectiveOnSale {

    private EffectiveOnSale() {}

    /**
     * The event's own on-sale date; else its publish time (creation time when never published), unless every
     * enabled tier opens later, in which case the earliest enabled tier start. Disabled tiers never count.
     */
    public static Instant of(Instant eventOnSaleAt, Instant publishedAt, Instant createdAt, List<TicketTier> tiers) {
        if (eventOnSaleAt != null) return eventOnSaleAt;
        Instant base = publishedAt != null ? publishedAt : createdAt;
        Instant earliestTier = null;
        for (TicketTier t : tiers == null ? List.<TicketTier>of() : tiers) {
            if (!t.isEnabled()) continue;
            Instant start = t.getSaleStartsAt();
            // An untimed tier, or one already open at publish, was buyable from the base instant.
            if (start == null || (base != null && !start.isAfter(base))) return base;
            if (earliestTier == null || start.isBefore(earliestTier)) earliestTier = start;
        }
        return earliestTier != null ? earliestTier : base;
    }
}
