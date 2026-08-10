package com.imin.iminapi.dto.publicapi;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.service.event.TierAvailability;

import java.time.Instant;
import java.util.UUID;

public record PublicTierDto(
        UUID id,
        String name,
        int priceMinor,
        String currency,
        Instant saleStartsAt,
        Instant saleClosesAt,
        int sortOrder,
        int remaining,
        boolean onSale,
        boolean soldOut,
        boolean closed
) {
    /**
     * Factory deriving all flags from the tier entity, its event, and the current instant.
     *
     * <p>{@code onSale} delegates to {@link TierAvailability#isPurchasable} — that helper
     * IS this predicate, lifted out so the listing's {@code priceFromMinor} and the
     * notify-me release sender answer "buyable?" the same way.
     */
    public static PublicTierDto from(TicketTier tier, Event e, Instant now) {
        int remaining = TierAvailability.remaining(tier);
        boolean soldOut = remaining == 0;
        boolean closed = TierAvailability.isClosed(e, tier, now);
        boolean onSale = TierAvailability.isPurchasable(e, tier, now);

        return new PublicTierDto(tier.getId(), tier.getName(), tier.getPriceMinor(), e.getCurrency(),
                tier.getSaleStartsAt(), tier.getSaleClosesAt(), tier.getSortOrder(),
                remaining, onSale, soldOut, closed);
    }
}
