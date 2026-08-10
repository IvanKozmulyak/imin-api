package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.TicketTier;

import java.time.Instant;

/**
 * The single source of truth for "can a buyer purchase this tier right now?".
 *
 * <p>Extracted from {@code PublicTierDto.from} so the three surfaces that need the
 * answer agree by construction rather than by re-derivation:
 * <ul>
 *   <li>the event detail page ({@code PublicTierDto.onSale}),</li>
 *   <li>the listing card's {@code priceFromMinor} (min over purchasable tiers), and</li>
 *   <li>{@link NotifyReleaseSender} (does this event have anything to announce?).</li>
 * </ul>
 *
 * <p>Deliberately NOT expressed as JPQL — re-deriving on-sale semantics in SQL is
 * exactly how the listing and the detail page drifted apart in the first place.
 */
public final class TierAvailability {

    private TierAvailability() {}

    /** Units a buyer could still take, clamped at 0 so an oversold tier reads as empty. */
    public static int remaining(TicketTier tier) {
        return Math.max(0, tier.getQuantity() - tier.getReserved() - tier.getSold());
    }

    /**
     * True when {@code tier} is buyable at {@code now}: enabled, its event is neither
     * PAST nor CANCELLED, both the event-level and tier-level sale windows have opened
     * and not yet closed, and stock remains.
     */
    public static boolean isPurchasable(Event event, TicketTier tier, Instant now) {
        if (!tier.isEnabled()) return false;

        boolean eventOver = event.getStatus() == EventStatus.PAST
                || event.getStatus() == EventStatus.CANCELLED;
        if (eventOver) return false;

        boolean eventOpened = event.getOnSaleAt() == null || !now.isBefore(event.getOnSaleAt());
        boolean tierStarted = tier.getSaleStartsAt() == null || !now.isBefore(tier.getSaleStartsAt());
        if (!eventOpened || !tierStarted) return false;

        if (isClosed(event, tier, now)) return false;

        return remaining(tier) > 0;
    }

    /** True once either the tier's or the event's {@code saleClosesAt} has been reached. */
    public static boolean isClosed(Event event, TicketTier tier, Instant now) {
        boolean tierClosed = tier.getSaleClosesAt() != null && !now.isBefore(tier.getSaleClosesAt());
        boolean eventClosed = event.getSaleClosesAt() != null && !now.isBefore(event.getSaleClosesAt());
        return tierClosed || eventClosed;
    }
}
