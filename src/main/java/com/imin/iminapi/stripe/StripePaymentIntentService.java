package com.imin.iminapi.stripe;

import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.model.ReservationStatus;
import com.imin.iminapi.model.TicketReservation;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.event.InventoryService;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** Matches {@code ticket_reservations.idempotency_key} (V93). */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

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

    /** Unkeyed form — no idempotency, the pre-mobile behaviour. */
    public NativeIntent create(UUID eventId, UUID tierId, int quantity, String promoCode,
                               Integer expectedPriceMinor, String buyerEmail,
                               boolean adsConsent, boolean marketingOptIn,
                               CheckoutAttribution attribution, String rawLocale) {
        return create(eventId, tierId, quantity, promoCode, expectedPriceMinor, buyerEmail,
                adsConsent, marketingOptIn, attribution, rawLocale, null);
    }

    /**
     * @param idempotencyKey the caller's {@code Idempotency-Key}, or null.
     *
     * <h2>Why this endpoint needs one and the hosted checkout never did</h2>
     *
     * <p>{@link InventoryService#reserve} writes a new hold on <b>every</b> call,
     * before Stripe is touched. The web could not retry a checkout — it was a
     * browser navigation — but a native client on a flaky link loses the
     * response and retries, and each retry would mint a second 30-minute hold on
     * real inventory <i>and</i> a second PaymentIntent. Added after v1.0.0 ships
     * this would protect only newer binaries; the launch cohort would keep the
     * duplicate-hold behaviour for the life of the install.
     */
    public NativeIntent create(UUID eventId, UUID tierId, int quantity, String promoCode,
                               Integer expectedPriceMinor, String buyerEmail,
                               boolean adsConsent, boolean marketingOptIn,
                               CheckoutAttribution attribution, String rawLocale,
                               String idempotencyKey) {

        String key = normalizeKey(idempotencyKey);

        // Before pricing, not after. A replay must never re-price: a tier price
        // change between the original call and the retry would otherwise charge
        // a different total than the one the buyer already confirmed on the
        // payment sheet. Not calling priceIt at all is what guarantees it.
        if (key != null) {
            NativeIntent replayed = replay(key);
            if (replayed != null) return replayed;
        }

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

        // Claim the key on the hold we just took, before Stripe is asked for
        // anything. The unique index — not the lookup above — is what settles a
        // concurrent duplicate: the loser gives its seats straight back and
        // replays the winner, so two racing retries produce one hold and one
        // PaymentIntent rather than two of each.
        if (key != null) {
            try {
                inventoryService.claimIdempotencyKey(p.reservationId(), key);
            } catch (DataIntegrityViolationException duplicate) {
                releaseQuietly(p.reservationId(), "IDEMPOTENT_REPLAY");
                NativeIntent replayed = replay(key);
                if (replayed != null) return replayed;
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                        "A checkout with this Idempotency-Key is already in progress");
            }
        }

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
            releaseQuietly(p.reservationId(), "STRIPE_PI_CREATE_FAILED");
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

    /**
     * The answer a previous request with this key already produced, or null when
     * the key has never been seen.
     *
     * <p><b>Every number here comes off the stored PaymentIntent, never off a
     * fresh price.</b> That is the whole point: the buyer confirmed a total on a
     * payment sheet bound to this intent, and Stripe will charge that total
     * whatever a tier costs now. Recomputing would let a price change between
     * the original call and the retry hand the app a figure Stripe disagrees
     * with.
     *
     * <p>The client secret cannot be stored — it is a credential, and it is not
     * derivable from the id — so the intent is re-read from Stripe. That is one
     * idempotent GET; no second intent is created and no second hold is taken.
     */
    private NativeIntent replay(String key) {
        TicketReservation existing = inventoryService.findByIdempotencyKey(key).orElse(null);
        if (existing == null) return null;

        if (existing.getStatus() != ReservationStatus.HELD) {
            // Confirmed (already paid) or released (expired, or the sweeper got
            // it). Handing back a client secret for either would bind a payment
            // sheet to seats that are no longer this buyer's.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "This checkout is no longer active — start a new one with a new Idempotency-Key");
        }

        String intentId = existing.getStripeSessionId();
        if (intentId == null || intentId.isBlank()) {
            // The key was claimed but the intent id never landed — the first
            // attempt died between the two writes. Refusing is the honest
            // answer: the hold is real, and inventing a second intent against it
            // is how a buyer ends up charged twice. The sweeper releases it at
            // expiry.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "A checkout with this Idempotency-Key is already in progress");
        }

        PaymentIntent intent;
        try {
            intent = stripeClient.paymentIntents().retrieve(intentId);
        } catch (StripeException e) {
            log.error("Stripe PaymentIntent retrieve failed on idempotent replay ({}): {}",
                    intentId, e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Payment could not be started", e);
        }

        Long amount = intent.getAmount();
        Long fee = intent.getApplicationFeeAmount();
        if (amount == null || fee == null || intent.getClientSecret() == null) {
            // Do not paper over this with zeros. A total the app renders must
            // trace to a real figure or must not be rendered at all.
            log.error("Stripe returned an unusable PaymentIntent on replay ({}): amount={} fee={}",
                    intentId, amount, fee);
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Payment could not be started");
        }

        log.info("Replaying PaymentIntent {} for a repeated Idempotency-Key (reservation {})",
                intentId, existing.getId());
        return new NativeIntent(intent.getClientSecret(), intent.getId(),
                amount, fee, intent.getCurrency());
    }

    /**
     * Trim to null, and reject anything over the column width rather than
     * truncating it — two distinct 200-character keys that share a 128-character
     * prefix must not collapse into one reservation.
     */
    private static String normalizeKey(String raw) {
        if (raw == null) return null;
        String key = raw.trim();
        if (key.isEmpty()) return null;
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
        }
        return key;
    }

    /** Give the seats back, and never let the cleanup mask the original failure. */
    private void releaseQuietly(UUID reservationId, String reason) {
        try {
            inventoryService.releaseReservation(reservationId, reason);
        } catch (Exception releaseFailure) {
            log.error("Failed to release reservation {} ({}): {}",
                    reservationId, reason, releaseFailure.getMessage(), releaseFailure);
        }
    }
}
