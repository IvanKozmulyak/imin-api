package com.imin.iminapi.stripe;

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
        String url = checkout.createCheckoutSession(eventId, body.tierId(), quantity,
                promoCode, body.expectedPriceMinor(), body.email());
        return new CheckoutResponse(url);
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
                                   String email) {}

    public record CheckoutResponse(String url) {}
}
