package com.imin.iminapi.stripe;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
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

    public StripeCheckoutController(StripeCheckoutService checkout) {
        this.checkout = checkout;
    }

    @PostMapping("/{eventId}/checkout")
    public CheckoutResponse create(@PathVariable UUID eventId,
                                    @RequestBody CheckoutRequest body) {
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
