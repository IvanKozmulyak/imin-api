package com.imin.iminapi.dto.publicapi;

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
    /** Factory taking already-extracted primitives. */
    public static PublicTierDto of(
            UUID id,
            String name,
            String kindWire,
            int priceMinor,
            String currency,
            Instant tierSaleClosesAt,
            int sortOrder,
            int quantity,
            int sold,
            Instant eventOnSaleAt,
            Instant eventSaleClosesAt,
            boolean eventOver,        // status == PAST or CANCELLED
            Instant now
    ) {
        int remaining = Math.max(0, quantity - sold);
        boolean soldOut = remaining == 0;

        boolean tierClosed  = tierSaleClosesAt != null && !now.isBefore(tierSaleClosesAt);
        boolean eventClosed = eventSaleClosesAt != null && !now.isBefore(eventSaleClosesAt);
        boolean closed = tierClosed || eventClosed;

        boolean tierOpened = eventOnSaleAt == null || !now.isBefore(eventOnSaleAt);
        boolean onSale = !eventOver && tierOpened && !closed && !soldOut;

        return new PublicTierDto(id, name, kindWire, priceMinor, currency,
                tierSaleClosesAt, sortOrder, remaining, onSale, soldOut, closed);
    }
}
