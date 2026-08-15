package com.imin.iminapi.stripe;

import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Public (unauthenticated) buyer-facing endpoint. Returns a hosted Stripe Checkout URL
 * that the buyer's browser should be redirected to.
 */
@RestController
@RequestMapping("/api/v1/public/events")
public class StripeCheckoutController {

    private final StripeCheckoutService checkout;
    private final RateLimiter rateLimiter;

    public StripeCheckoutController(StripeCheckoutService checkout, RateLimiter rateLimiter) {
        this.checkout = checkout;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/{eventId}/checkout")
    public CheckoutResponse create(@PathVariable UUID eventId,
                                    @RequestBody CheckoutRequest body,
                                    HttpServletRequest http) {
        // Throttle the unauthenticated checkout per client IP before doing any Stripe work, so a
        // loop can't mint unbounded Coupons/Sessions or lock real inventory for 30-min windows.
        rateLimiter.consume("checkout", "ip:" + http.getRemoteAddr());
        if (body == null || body.tierId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "tierId is required");
        }
        int quantity = body.quantity() == null ? 1 : body.quantity();
        String promoCode = body.promoCode();
        // Nullable; treat null as false. Rides the buyer's cookie-consent ads-consent (§7)
        // into orders.ads_consent — the lawful basis gate for the server-side Meta CAPI event.
        boolean adsConsent = Boolean.TRUE.equals(body.adsConsent());
        // Nullable; null => false. Pre-ticked soft opt-in — the buyer LEFT it ticked.
        boolean marketingOptIn = Boolean.TRUE.equals(body.marketingOptIn());
        // Last-touch attribution the browser landed with (V62). All fields optional and
        // untrusted buyer input — the record trims, caps to the column widths, and
        // normalizes blank to null, so a hostile or over-long param can't break the insert.
        CheckoutAttribution attribution = new CheckoutAttribution(
                body.utmSource(), body.utmMedium(), body.utmCampaign(), body.anonId());
        StripeCheckoutService.CheckoutResult result = checkout.createCheckout(eventId, body.tierId(), quantity,
                promoCode, body.expectedPriceMinor(), body.email(), adsConsent, marketingOptIn,
                attribution, body.locale());
        return new CheckoutResponse(result.url(), result.kind(), result.sessionId(), result.orderToken());
    }

    public record CheckoutRequest(@NotNull UUID tierId,
                                   @Min(1) @Max(10) Integer quantity,
                                   // Optional buyer-supplied promo code. Validated and applied
                                   // server-side; null/blank means "no discount".
                                   String promoCode,
                                   // Optional per-unit price the buyer was shown. Mismatch
                                   // with the current tier price → 409 PRICE_CHANGED.
                                   Integer expectedPriceMinor,
                                   // REQUIRED when the computed total is 0 (free flow);
                                   // the BE has no other place to collect it because Stripe
                                   // Checkout is skipped. Ignored on paid flow today.
                                   String email,
                                   // Buyer's cookie-consent ads-consent decision from the
                                   // imin-public consent banner (§7). Nullable; null ⇒ false.
                                   // Persisted to orders.ads_consent; gates the Meta CAPI event.
                                   Boolean adsConsent,
                                   // Buyer's email-marketing SOFT opt-in from the buy page. The
                                   // checkbox is PRE-TICKED (default on) and the buyer may untick
                                   // it before paying, so true means "left ticked", not an
                                   // affirmative action. Nullable; null => false. Persisted to
                                   // orders.marketing_opt_in; becomes the channel='email',
                                   // basis='soft_opt_in' consent proof row at fulfilment.
                                   Boolean marketingOptIn,
                                   // Last-touch attribution captured by imin-public on landing
                                   // (V62) and replayed here from sessionStorage — by the time the
                                   // buyer clicks buy, the URL no longer carries the utm_* params.
                                   // All nullable (organic arrival / storage disabled). Ride the
                                   // Stripe metadata onto orders.utm_*; utm_campaign holds the
                                   // campaign UUID and is the per-campaign revenue join key.
                                   String utmSource,
                                   String utmMedium,
                                   String utmCampaign,
                                   // The /track beacon's per-session id (sessionStorage
                                   // 'imin.anon') — REUSED, not a second identifier — so an order
                                   // can be joined back to the visit that drove it.
                                   String anonId,
                                   // Buyer's UI language (en/es/fr/uk), read from the browser by
                                   // imin-public. Normalized server-side; anything unsupported is
                                   // stored as null ⇒ English email. Never a validation error.
                                   // Snapshotted onto orders.buyer_locale (V78) — the buyer has no
                                   // account, so the order is the only place it can live.
                                   String locale) {}

    /**
     * {@code url} is unchanged and still first — imin-public reads only that.
     * {@code kind} is {@code "stripe"} or {@code "order"}; {@code sessionId} is
     * present only for {@code "stripe"}.
     */
    public record CheckoutResponse(String url, String kind, String sessionId, String orderToken) {}
}
