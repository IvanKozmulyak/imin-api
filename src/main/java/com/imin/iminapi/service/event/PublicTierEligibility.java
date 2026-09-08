package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Shared "is this tier publicly buyable right now?" predicate.
 *
 * <p>Both {@code StripeCheckoutService} (when creating a Stripe Checkout session) and
 * {@code QuoteService} (when previewing totals) need exactly the same eligibility
 * rules so a buyer never gets a 200 from quote and a 404 from checkout on the next
 * click — or vice versa.
 *
 * <p>All failures collapse to {@link ApiException#notFound(String)} with {@code "Event"}
 * so the public envelope stays leak-safe (no distinguishing "wrong tier", "sold out",
 * "not on sale yet", etc.).
 *
 * <p>The Stripe-readiness check ({@code stripePriceId} non-blank + connected account
 * ready) is intentionally NOT included here — quote does not need it (it does no Stripe
 * round-trip), and folding it in would couple this helper to {@code StripeConnectService}.
 * Callers that hand off to Stripe must still run those checks separately.
 */
public final class PublicTierEligibility {

    private PublicTierEligibility() {}

    /**
     * Loads the tier, asserts it belongs to {@code event}, and asserts both the
     * <b>event</b> and the tier are currently buyable. Returns the loaded {@link TicketTier}.
     *
     * <p>The event-level half mirrors {@link TierAvailability#isPurchasable} — status is
     * neither PAST nor CANCELLED, and {@code event.onSaleAt} / {@code event.saleClosesAt}
     * bracket {@code now}. {@code EventRepository.findPublic} is deliberately
     * CANCELLED-tolerant (a cancelled event must stay reachable by share-link so the
     * detail page can render its banner), so without this the buy path would reserve
     * inventory and charge for a cancelled, past or not-yet-on-sale event.
     *
     * <p>Remaining stock is deliberately NOT checked here: quote must still price a
     * sold-out tier, and {@code InventoryService.reserve} already refuses the checkout.
     *
     * @throws ApiException 404 NOT_FOUND on any failure (event past/cancelled, event sale
     *                      window not open, tier missing, on a different event, disabled,
     *                      tier sale not yet open, tier sale closed)
     */
    public static TicketTier loadBuyableTier(TicketTierRepository tiers, Event event, UUID tierId, Instant now) {
        if (event.getStatus() == EventStatus.PAST || event.getStatus() == EventStatus.CANCELLED) {
            throw ApiException.notFound("Event");
        }
        if (event.getOnSaleAt() != null && now.isBefore(event.getOnSaleAt())) {
            throw ApiException.notFound("Event");
        }
        if (event.getSaleClosesAt() != null && !now.isBefore(event.getSaleClosesAt())) {
            throw ApiException.notFound("Event");
        }
        TicketTier tier = tiers.findByIdAndEventId(tierId, event.getId())
                .orElseThrow(() -> ApiException.notFound("Event"));
        if (!tier.isEnabled()) throw ApiException.notFound("Event");
        if (tier.getSaleStartsAt() != null && tier.getSaleStartsAt().isAfter(now)) {
            throw ApiException.notFound("Event");
        }
        if (tier.getSaleClosesAt() != null && !tier.getSaleClosesAt().isAfter(now)) {
            throw ApiException.notFound("Event");
        }
        return tier;
    }

    /**
     * If the buyer told us the per-unit price they were shown ({@code expected}), assert
     * it still matches {@code tier.priceMinor}. Mismatch → 409 PRICE_CHANGED with
     * {@code fields.currentPriceMinor} so the buyer FE can refresh and re-present.
     *
     * <p>{@code expected == null} is the back-compat path — older clients that don't
     * send the field get the current price applied silently, same as today. Only opt-in
     * clients benefit from the drift guard.
     */
    public static void assertExpectedPriceMatches(TicketTier tier, Integer expected) {
        if (expected == null) return;
        if (expected.intValue() == tier.getPriceMinor()) return;
        throw new ApiException(HttpStatus.CONFLICT, ErrorCode.PRICE_CHANGED,
                "Tier price has changed",
                Map.of("currentPriceMinor", Integer.toString(tier.getPriceMinor())));
    }
}
