package com.imin.iminapi.stripe;

import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public (unauthenticated) native-checkout endpoint. Returns a PaymentIntent
 * client secret for the Stripe native PaymentSheet — the only way to get a real
 * Apple Pay / Google Pay sheet rather than a browser redirect.
 *
 * <p>Unauthenticated on purpose, exactly like the hosted {@code /checkout}
 * sibling: buying a ticket does not require an account, and requiring one here
 * would make the native flow stricter than the web for no security gain. It
 * carries the same per-IP rate limit, which is what stops a loop minting
 * unbounded intents and holding real inventory for 30-minute windows.
 */
@RestController
@RequestMapping("/api/v1/public/events")
public class StripePaymentIntentController {

    private final StripePaymentIntentService intents;
    private final RateLimiter rateLimiter;

    public StripePaymentIntentController(StripePaymentIntentService intents, RateLimiter rateLimiter) {
        this.intents = intents;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/{eventId}/payment-intent")
    public ResponseEntity<StripePaymentIntentService.NativeIntent> create(
            @PathVariable UUID eventId,
            // @Valid is required, not decorative: Spring only cascades bean
            // validation into a @RequestBody when the parameter carries it, so
            // without this the @NotNull/@Min/@Max on PaymentIntentRequest below
            // never run and a quantity of 500 reaches the service.
            @Valid @RequestBody PaymentIntentRequest body,
            // Optional, and optional on purpose: the web flow never sends one and
            // must keep working unchanged. When a native client does send one, a
            // retry replays the first call's PaymentIntent instead of taking a
            // second 30-minute hold on real inventory. Same header name as the
            // refund, payout and campaign endpoints already use.
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest http) {
        // Shares the "checkout" bucket with the hosted endpoint deliberately: the
        // two are the same scarce operation (a Stripe object plus a real
        // inventory hold), so they must share one budget or the limit is bypassable
        // by alternating between them.
        rateLimiter.consume("checkout", "ip:" + http.getRemoteAddr());
        if (body == null || body.tierId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST, "tierId is required");
        }
        int quantity = body.quantity() == null ? 1 : body.quantity();
        CheckoutAttribution attribution = new CheckoutAttribution(
                body.utmSource(), body.utmMedium(), body.utmCampaign(), body.anonId());

        StripePaymentIntentService.NativeIntent intent = intents.create(
                eventId, body.tierId(), quantity, body.promoCode(), body.expectedPriceMinor(),
                body.email(), Boolean.TRUE.equals(body.adsConsent()),
                Boolean.TRUE.equals(body.marketingOptIn()), attribution, body.locale(),
                idempotencyKey);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(intent);
    }

    /** Mirrors {@code StripeCheckoutController.CheckoutRequest} field-for-field. */
    public record PaymentIntentRequest(@NotNull UUID tierId,
                                        @Min(1) @Max(10) Integer quantity,
                                        String promoCode,
                                        Integer expectedPriceMinor,
                                        String email,
                                        Boolean adsConsent,
                                        Boolean marketingOptIn,
                                        String utmSource,
                                        String utmMedium,
                                        String utmCampaign,
                                        String anonId,
                                        String locale) {}
}
