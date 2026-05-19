package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.publicapi.QuoteRequest;
import com.imin.iminapi.dto.publicapi.QuoteResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only price preview for the buyer-facing event detail page.
 *
 * <p>Unlike {@code StripeCheckoutService.createCheckoutSession}, this:
 * <ul>
 *   <li>does <b>not</b> hit Stripe (no Session, no Coupon, no PaymentIntent),</li>
 *   <li>does <b>not</b> reserve inventory (no row lock, no holds),</li>
 *   <li>does <b>not</b> increment {@code promo_codes.used_count} — that only fires
 *       on a paid {@code checkout.session.completed} webhook.</li>
 * </ul>
 *
 * <p>It exists so the buyer sees the discount preview and any "promo invalid" reason
 * before being redirected to a Stripe hosted page (where a 400 from the BE would
 * otherwise be the first signal that a code was bad).
 *
 * <h2>Service fee</h2>
 * The platform fee is buyer-visible and structured as
 * {@code fixedMinor × qty + round(subtotal × bps / 10000)} — default
 * {@code €0.99 per ticket + 5% of subtotal}. It is added on top of the discounted
 * subtotal, so {@code total = subtotal - discount + fee}. At checkout time this same
 * formula drives both an extra "Service fee" line item on the Stripe Checkout Session
 * (so the buyer is actually charged it) and the {@code application_fee_amount} on the
 * destination charge (so the platform keeps the fee while the organizer is paid the
 * net ticket revenue).
 *
 * <h2>Promo semantics</h2>
 * <ul>
 *   <li>No {@code promoCode} field → no {@code promo} block in the response.</li>
 *   <li>{@code promoCode} present but blank after trim → 400 (client bug, never sent it).</li>
 *   <li>Any other non-empty value → 200 with a {@code promo} block. If the code is
 *       unknown, disabled, or exhausted, {@code applied=false} + a {@code reason} string
 *       and {@code discountMinor=0}. The buyer sees why and can fix it.</li>
 * </ul>
 */
@Service
public class QuoteService {

    private static final int MAX_QUANTITY = 10;

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final PromoCodeRepository promos;
    private final StripeProperties stripeProps;
    private final Clock clock;

    public QuoteService(EventRepository events,
                        TicketTierRepository tiers,
                        PromoCodeRepository promos,
                        StripeProperties stripeProps,
                        Clock clock) {
        this.events = events;
        this.tiers = tiers;
        this.promos = promos;
        this.stripeProps = stripeProps;
        this.clock = clock;
    }

    /**
     * Buyer-paid platform fee. Kept package-private + static so
     * {@link com.imin.iminapi.stripe.StripeCheckoutService} can reuse the same math at
     * Session creation time — the quote and the eventual charge must never disagree.
     */
    public static long computeFee(long subtotal, int quantity, int bps, int fixedMinor) {
        long pct = Math.round(subtotal * (double) bps / 10000.0);
        long fixed = (long) fixedMinor * quantity;
        return Math.max(0L, pct + fixed);
    }

    @Transactional(readOnly = true)
    public QuoteResponse quote(UUID eventId, QuoteRequest request) {
        // 1. Body validation. We do this *before* event lookup so a malformed body
        //    returns 400 even when the eventId in the path doesn't exist, matching
        //    the public-API convention.
        Map<String, String> fields = new LinkedHashMap<>();
        UUID tierId = request == null ? null : request.tierId();
        Integer rawQty = request == null ? null : request.quantity();
        String rawPromo = request == null ? null : request.promoCode();

        if (tierId == null) {
            fields.put("tierId", "is required");
        }
        if (rawQty == null) {
            fields.put("quantity", "is required");
        } else if (rawQty < 1) {
            fields.put("quantity", "must be at least 1");
        } else if (rawQty > MAX_QUANTITY) {
            fields.put("quantity", "must be at most " + MAX_QUANTITY);
        }
        // Distinguish "field not supplied" (null) from "field supplied as empty string".
        // The former is fine; the latter is a client bug we surface as 400.
        boolean promoSupplied = rawPromo != null;
        String promoTrimmed = rawPromo == null ? null : rawPromo.trim();
        if (promoSupplied && (promoTrimmed == null || promoTrimmed.isEmpty())) {
            fields.put("promoCode", "must not be blank when supplied");
        }
        if (!fields.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid request body", fields);
        }

        // 2. Event must be publicly visible (draft/private/deleted → 404).
        Event event = events.findPublic(eventId).orElseThrow(() -> ApiException.notFound("Event"));

        // 3. Tier must belong to the event and be on sale right now. Same predicate
        //    StripeCheckoutService uses — see PublicTierEligibility for details.
        Instant now = clock.instant();
        TicketTier tier = PublicTierEligibility.loadBuyableTier(tiers, eventId, tierId, now);

        // 3a. Price-drift guard. No-op when the client didn't send `expectedPriceMinor`.
        PublicTierEligibility.assertExpectedPriceMatches(tier, request.expectedPriceMinor());

        // 4. Subtotal + buyer-visible service fee (see class doc).
        int unitPrice = tier.getPriceMinor();
        long subtotal = (long) unitPrice * rawQty;

        QuoteResponse.Promo promoDto = null;
        long discount = 0L;
        if (promoSupplied) {
            PromoEval eval = evaluatePromo(eventId, promoTrimmed, subtotal);
            promoDto = eval.dto();
            discount = eval.discountMinor();
        }

        // Free tier (priceMinor == 0) stays truly free — no flat fee on €0 tickets.
        long fee = unitPrice == 0
                ? 0L
                : computeFee(subtotal, rawQty,
                        stripeProps.getApplicationFeeBps(),
                        stripeProps.getApplicationFeeFixedMinor());
        long total = Math.max(0L, subtotal - discount + fee);

        return new QuoteResponse(
                event.getCurrency(),
                unitPrice,
                subtotal,
                discount,
                fee,
                total,
                promoDto);
    }

    /**
     * Mirrors {@code StripeCheckoutService.resolvePromoCode} but returns a structured
     * preview instead of throwing 400. A code that simply doesn't exist or isn't usable
     * still produces a 200 — the buyer needs to see "Expired" or "Invalid code" inline.
     *
     * <p>{@code used_count} is NOT incremented here. It increments only on a paid
     * {@code checkout.session.completed} webhook.
     */
    private PromoEval evaluatePromo(UUID eventId, String code, long subtotal) {
        Optional<PromoCode> match = promos.findByEventIdAndCodeIgnoreCase(eventId, code);
        if (match.isEmpty()) {
            return PromoEval.rejected(code, "Invalid code");
        }
        PromoCode promo = match.get();
        if (!promo.isEnabled()) {
            return PromoEval.rejected(promo.getCode(), "Promo code is no longer active");
        }
        if (promo.getUsedCount() >= promo.getMaxUses()) {
            return PromoEval.rejected(promo.getCode(),
                    "Promo code has reached its usage limit");
        }
        // Discount math mirrors what Stripe's Coupon ONCE/percent_off will apply at checkout.
        // Round half-up so a 10% off €25.01 (2501 minor) becomes 250 (2.50€), matching Stripe.
        long discount = Math.round(subtotal * (double) promo.getDiscountPct() / 100.0);
        if (discount > subtotal) discount = subtotal; // clamp
        return new PromoEval(
                new QuoteResponse.Promo(true, promo.getCode(), promo.getDiscountPct(), null),
                discount);
    }

    private record PromoEval(QuoteResponse.Promo dto, long discountMinor) {
        static PromoEval rejected(String code, String reason) {
            return new PromoEval(
                    new QuoteResponse.Promo(false, code, 0, reason),
                    0L);
        }
    }
}
