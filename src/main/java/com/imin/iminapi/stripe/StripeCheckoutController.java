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
        String url = checkout.createCheckoutSession(eventId, body.tierId(), quantity);
        return new CheckoutResponse(url);
    }

    public record CheckoutRequest(@NotNull UUID tierId,
                                   @Min(1) @Max(10) Integer quantity) {}

    public record CheckoutResponse(String url) {}
}
