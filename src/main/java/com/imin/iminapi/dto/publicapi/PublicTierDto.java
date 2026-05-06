package com.imin.iminapi.dto.publicapi;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.TicketTier;

import java.time.Instant;
import java.util.UUID;

public record PublicTierDto(
        UUID id,
        String name,
        String kind,           // wireValue() like "earlyBird"
        int priceMinor,
        String currency,
        Instant saleClosesAt,
        int sortOrder,
        int remaining,
        boolean onSale,
        boolean soldOut,
        boolean closed
) {
    /** Factory deriving all flags from the tier entity, its event, and the current instant. */
    public static PublicTierDto from(TicketTier tier, Event e, Instant now) {
        String kindWire = tier.getKind().wireValue();
        String currency = e.getCurrency();
        boolean eventOver = e.getStatus() == EventStatus.PAST || e.getStatus() == EventStatus.CANCELLED;

        int remaining = Math.max(0, tier.getQuantity() - tier.getSold());
        boolean soldOut = remaining == 0;

        Instant tierSaleClosesAt = tier.getSaleClosesAt();
        boolean tierClosed  = tierSaleClosesAt != null && !now.isBefore(tierSaleClosesAt);
        boolean eventClosed = e.getSaleClosesAt() != null && !now.isBefore(e.getSaleClosesAt());
        boolean closed = tierClosed || eventClosed;

        boolean tierOpened = e.getOnSaleAt() == null || !now.isBefore(e.getOnSaleAt());
        boolean onSale = !eventOver && tierOpened && !closed && !soldOut;

        return new PublicTierDto(tier.getId(), tier.getName(), kindWire, tier.getPriceMinor(), currency,
                tierSaleClosesAt, tier.getSortOrder(), remaining, onSale, soldOut, closed);
    }
}
