package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.TicketTier;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

/** The organizer webapp's almost-gone rule, so list rows and the event page agree on the same chip. */
public final class AlmostGone {

    static final double SHARE = 0.2;
    static final int MAX = 30;

    private AlmostGone() {}

    /** Tickets left on the scarcest almost-gone tier of an event on sale now; empty when none is. */
    public static OptionalInt scarcestLeft(Event event, List<TicketTier> tiers, Instant now) {
        if (!onSaleNow(event, now)) return OptionalInt.empty();
        int best = Integer.MAX_VALUE;
        for (TicketTier t : tiers) {
            if (t.getQuantity() <= 0 || !t.isEnabled()) continue;
            if (t.getSaleStartsAt() != null && t.getSaleStartsAt().isAfter(now)) continue;
            if (t.getSaleClosesAt() != null && !t.getSaleClosesAt().isAfter(now)) continue;
            int left = Math.max(0, t.getQuantity() - t.getSold() - t.getReserved());
            int limit = Math.min(MAX, (int) Math.ceil(t.getQuantity() * SHARE));
            if (left >= 1 && left <= limit) best = Math.min(best, left);
        }
        return best == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(best);
    }

    private static boolean onSaleNow(Event e, Instant now) {
        if (e.getStatus() != EventStatus.LIVE) return false;
        if (e.getStartsAt() == null || !e.getStartsAt().isAfter(now)) return false;
        if (e.getOnSaleAt() != null && e.getOnSaleAt().isAfter(now)) return false;
        return e.getSaleClosesAt() == null || e.getSaleClosesAt().isAfter(now);
    }
}
