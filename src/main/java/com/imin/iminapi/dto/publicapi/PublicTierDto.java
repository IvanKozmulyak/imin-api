package com.imin.iminapi.dto.publicapi;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.service.event.QuoteService;
import com.imin.iminapi.service.event.TierAvailability;

import java.time.Instant;
import java.util.UUID;

public record PublicTierDto(
        UUID id,
        String name,
        int priceMinor,
        /**
         * What one ticket actually costs: {@code priceMinor} plus the buyer-paid
         * booking fee, computed with {@link QuoteService#computeFee} so the detail
         * page, the listing card's {@code priceFromMinor} and the Stripe Session can
         * never disagree.
         *
         * <p>Exists because {@code priceMinor} alone is not a lawful first display
         * price (Code conso. L112-1, arrêté du 3 décembre 1987, CRD Art.6(1)(e)): the
         * listing card already shows all-in, so the same event read cheaper on its own
         * page than in the list it was reached from. {@code priceMinor} is unchanged —
         * the fee itself is {@code priceAllInMinor - priceMinor}.
         *
         * <p>Free tiers stay 0: the fee is waived on a €0 net total, matching
         * QuoteService and StripeCheckoutService's free path.
         */
        long priceAllInMinor,
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
    public static PublicTierDto from(TicketTier tier, Event e, Instant now,
                                     int feeBps, int feeFixedMinor) {
        int remaining = TierAvailability.remaining(tier);
        boolean soldOut = remaining == 0;
        boolean closed = TierAvailability.isClosed(e, tier, now);
        boolean onSale = TierAvailability.isPurchasable(e, tier, now);

        int price = tier.getPriceMinor();
        long allIn = price == 0 ? 0L : price + QuoteService.computeFee(price, 1, feeBps, feeFixedMinor);

        return new PublicTierDto(tier.getId(), tier.getName(), price, allIn, e.getCurrency(),
                tier.getSaleStartsAt(), tier.getSaleClosesAt(), tier.getSortOrder(),
                remaining, onSale, soldOut, closed);
    }
}
