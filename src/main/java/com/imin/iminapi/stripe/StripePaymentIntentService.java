package com.imin.iminapi.stripe;

import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.event.InventoryService;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Creates a PaymentIntent for the <b>native</b> Stripe PaymentSheet, which is
 * what makes real Apple Pay and Google Pay possible in the mobile app. The
 * hosted Checkout flow in {@link StripeCheckoutService} is unchanged and remains
 * what the web uses.
 *
 * <h2>Why this shares a prelude with the hosted flow</h2>
 *
 * <p>Both flows must agree on which tiers are buyable, what a ticket costs, what
 * the platform fee is, that inventory is held before money is asked for, and
 * which metadata the {@code payment_intent.succeeded} handler will read. Those
 * are the money and inventory invariants; two implementations of them is two
 * chances to be wrong. So everything up to "we have a held reservation and a
 * metadata map" comes from {@link StripeCheckoutService.PaidPrelude}, and this
 * class only differs in what it hands Stripe.
 *
 * <h2>The two real differences</h2>
 *
 * <ol>
 *   <li><b>Discounts are applied to the amount, not attached as a Coupon.</b> A
 *       one-shot Stripe Coupon is a hosted-Checkout construct; a PaymentIntent
 *       has a single amount. So the promo discount is subtracted here and the
 *       resulting amount is charged. The fee is still computed on the
 *       <i>undiscounted</i> subtotal, exactly as the hosted path does, so a
 *       promo never shrinks the platform's cut.</li>
 *   <li><b>There is no free path.</b> A zero total has nothing for a payment
 *       sheet to charge. Callers get 400 and are pointed at
 *       {@code POST /public/events/{id}/checkout}, which returns
 *       {@code kind: "order"} for that case.</li>
 * </ol>
 *
 * <p>Deliberately NOT {@code @Transactional}, for the same reason
 * {@link StripeCheckoutService#createCheckout} is not: the reservation takes a
 * pessimistic row lock, and holding it across a blocking Stripe HTTP call would
 * serialize every concurrent buyer of a hot tier behind remote I/O.
 */
@Service
public class StripePaymentIntentService {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentIntentService.class);

    private final StripeClient stripeClient;
    private final StripeCheckoutService checkoutService;
    private final InventoryService inventoryService;

    public StripePaymentIntentService(StripeClient stripeClient,
                                      StripeCheckoutService checkoutService,
                                      InventoryService inventoryService) {
        this.stripeClient = stripeClient;
        this.checkoutService = checkoutService;
        this.inventoryService = inventoryService;
    }

    /**
     * @param clientSecret    what the native PaymentSheet binds to. Short-lived,
     *                        scoped to this one intent; safe to hand the client.
     * @param amountMinor     what the buyer will be charged, net of any promo.
     * @param feeMinor        the buyer-visible service fee inside {@code amountMinor}.
     *                        The app must render this from here and never recompute it.
     */
    public record NativeIntent(String clientSecret, String paymentIntentId,
                               long amountMinor, long feeMinor, String currency) {}

    public NativeIntent create(UUID eventId, UUID tierId, int quantity, String promoCode,
                               Integer expectedPriceMinor, String buyerEmail,
                               boolean adsConsent, boolean marketingOptIn,
                               CheckoutAttribution attribution, String rawLocale) {

        // Price first, and reject a free total BEFORE anything is reserved and
        // before the connected-account gate runs. Reversing these two would 404
        // a free tier (blank stripePriceId in reserveAndBuildMetadata) instead of
        // explaining itself, and would take a hold it then had to clean up.
        StripeCheckoutService.Priced priced = checkoutService.priceIt(
                eventId, tierId, quantity, promoCode, expectedPriceMinor);

        if (priced.netTotalMinor() == 0L) {
            // A zero total has nothing for a payment sheet to charge. The hosted
            // endpoint owns that branch and issues the order outright; sending
            // the app around in a circle would be worse than saying so.
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "This ticket is free — use POST /api/v1/public/events/{eventId}/checkout");
        }

        StripeCheckoutService.PaidPrelude p = checkoutService.reserveAndBuildMetadata(
                priced, eventId, tierId, quantity, buyerEmail,
                adsConsent, marketingOptIn, attribution, rawLocale, true);

        // Charge the discounted ticket total plus the undiscounted fee — the same
        // arithmetic the hosted session performs across its two line items.
        long amount = p.netTotalMinor() + p.applicationFee();

        PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
                .setAmount(amount)
                .setCurrency(p.currency())
                .setApplicationFeeAmount(p.applicationFee())
                .setTransferData(PaymentIntentCreateParams.TransferData.builder()
                        .setDestination(p.org().getStripeAccountId())
                        .build())
                // Same grouping the hosted path uses, so per-event reconciliation
                // sees native and web payments in one bucket.
                .setTransferGroup(eventId.toString())
                .putAllMetadata(p.metadata())
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .build());

        if (buyerEmail != null && !buyerEmail.isBlank()) {
            builder.setReceiptEmail(buyerEmail.trim());
        }

        PaymentIntent intent;
        try {
            intent = stripeClient.paymentIntents().create(builder.build());
        } catch (StripeException e) {
            // Same rollback contract as the hosted path: we promised the buyer
            // nothing, so the seats go back to the pool.
            try {
                inventoryService.releaseReservation(p.reservationId(), "STRIPE_PI_CREATE_FAILED");
            } catch (Exception releaseFailure) {
                log.error("Failed to release reservation {} after PaymentIntent create failure: {}",
                        p.reservationId(), releaseFailure.getMessage(), releaseFailure);
            }
            log.error("Stripe PaymentIntent create failed (event {}, tier {}, reservation {}): {}",
                    eventId, tierId, p.reservationId(), e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Payment could not be started", e);
        }

        // Stamp the PI id onto the hold. Two jobs: forensics, and giving
        // ReservationSweeper the id it needs to CANCEL a native intent when the
        // hold expires — a PaymentIntent has no expires_at of its own, so an
        // uncancelled one stays payable against seats somebody else can now buy.
        try {
            inventoryService.attachSessionId(p.reservationId(), intent.getId());
        } catch (Exception e) {
            log.warn("Failed to attach payment intent {} to reservation {}: {}",
                    intent.getId(), p.reservationId(), e.getMessage());
        }

        return new NativeIntent(intent.getClientSecret(), intent.getId(),
                amount, p.applicationFee(), p.currency());
    }
}
