package com.imin.iminapi.stripe;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Coupon;
import com.stripe.model.checkout.Session;
import com.stripe.param.CouponCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.imin.iminapi.security.ErrorCode;

/**
 * Creates Stripe Checkout sessions for buyer-facing ticket purchases.
 *
 * <p>The flow is a <b>Destination Charge</b>:
 * <ul>
 *   <li>Payment runs on the <em>platform</em> account.</li>
 *   <li>{@code transfer_data.destination} points at the org's connected account so the net
 *       proceeds end up on the connected balance.</li>
 *   <li>{@code application_fee_amount} skims our platform cut (default 5%) off the top.</li>
 * </ul>
 *
 * <p>All validation failures collapse to a leak-safe 404 ({@code Event not found}) — exactly
 * matching the existing public event envelope behavior. We don't reveal whether the event
 * doesn't exist vs. is unlisted vs. has the wrong tier.
 */
@Service
public class StripeCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(StripeCheckoutService.class);

    private final StripeClient stripeClient;
    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final OrganizationRepository orgs;
    private final PromoCodeRepository promos;
    private final StripeConnectService connectService;
    private final StripeProperties props;
    private final Clock clock;

    public StripeCheckoutService(StripeClient stripeClient,
                                  EventRepository events,
                                  TicketTierRepository tiers,
                                  OrganizationRepository orgs,
                                  PromoCodeRepository promos,
                                  StripeConnectService connectService,
                                  StripeProperties props,
                                  Clock clock) {
        this.stripeClient = stripeClient;
        this.events = events;
        this.tiers = tiers;
        this.orgs = orgs;
        this.promos = promos;
        this.connectService = connectService;
        this.props = props;
        this.clock = clock;
    }

    /**
     * @param promoCode optional buyer-supplied code. Validated against the event's promo list;
     *                  on success a one-shot Stripe Coupon is attached to the Session so the
     *                  discount appears at checkout. Invalid code → 400 INVALID_PROMO_CODE (we
     *                  deliberately do NOT collapse to 404, because here it's a buyer-fixable
     *                  typo, not a "this event doesn't exist" question).
     * @return the Stripe-hosted Checkout URL. Buyer is sent here directly; we never see the card.
     */
    @Transactional(readOnly = true)
    public String createCheckoutSession(UUID eventId, UUID tierId, int quantity, String promoCode) {
        if (quantity < 1 || quantity > 10) {
            // 400, not 404 — quantity is a client bug, not an event-discovery question.
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "quantity must be between 1 and 10");
        }

        // 1. Load + validate event (must be publicly visible).
        Event event = events.findPublic(eventId).orElseThrow(() -> ApiException.notFound("Event"));

        // 2. Load + validate tier (belongs to event, enabled, in sale window, has price+quantity).
        TicketTier tier = tiers.findByIdAndEventId(tierId, eventId)
                .orElseThrow(() -> ApiException.notFound("Event")); // leak-safe — see class doc

        Instant now = clock.instant();
        if (!tier.isEnabled()) throw ApiException.notFound("Event");
        if (tier.getSaleStartsAt() != null && tier.getSaleStartsAt().isAfter(now)) {
            throw ApiException.notFound("Event");
        }
        if (tier.getSaleClosesAt() != null && !tier.getSaleClosesAt().isAfter(now)) {
            throw ApiException.notFound("Event");
        }
        if (tier.getQuantity() - tier.getSold() < quantity) {
            throw ApiException.notFound("Event"); // also leak-safe: hides the live remaining count.
        }
        if (tier.getStripePriceId() == null || tier.getStripePriceId().isBlank()) {
            // Product sync failed earlier (best-effort). Surface as 404 to the buyer — they have nothing
            // they can do about it — and let the organizer see the dashboard error.
            log.warn("Tier {} has no Stripe price id — checkout blocked", tier.getId());
            throw ApiException.notFound("Event");
        }

        // 3. Load org + verify the connected account is ready.
        Organization org = orgs.findById(event.getOrgId()).orElseThrow(() -> ApiException.notFound("Event"));
        if (org.getStripeAccountId() == null || org.getStripeAccountId().isBlank()) {
            throw ApiException.notFound("Event");
        }
        // Live check — never cached.
        StripeConnectService.StatusResult status = connectService.getStatus(
                new com.imin.iminapi.security.AuthPrincipal(null, org.getId(), null, null), org.getId());
        if (!status.readyToReceivePayments()) {
            throw ApiException.notFound("Event");
        }

        // 4. Resolve the promo code (if any). Validation errors are 400 — the buyer can fix them.
        PromoCode promo = resolvePromoCode(eventId, promoCode);

        // 5. Compute platform fee (basis points → minor units), bounded by total.
        // The fee is computed on the *undiscounted* subtotal — Stripe applies the coupon to the
        // total, but the platform fee follows the line items, so a buyer using a 20% off code
        // sees the discount reflected only in the final amount charged, not in our cut.
        long totalMinor = (long) tier.getPriceMinor() * quantity;
        long applicationFee = Math.round(totalMinor * (double) props.getApplicationFeeBps() / 10000.0);

        // 6. Build the session.
        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(tier.getStripePriceId())
                        .setQuantity((long) quantity)
                        .build())
                // payment_intent_data — destination charge with application fee.
                // The PaymentIntent is created on the *platform* account; transfer_data.destination
                // moves the net to the connected account asynchronously.
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .setApplicationFeeAmount(applicationFee)
                        .setTransferData(SessionCreateParams.PaymentIntentData.TransferData.builder()
                                .setDestination(org.getStripeAccountId())
                                .build())
                        .build())
                .setSuccessUrl(props.getPublicReturnUrlBase()
                        + "/e/" + eventId + "/success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(props.getPublicReturnUrlBase() + "/e/" + eventId);

        if (promo != null) {
            // Create a one-shot Stripe Coupon on the platform account and attach it. We don't
            // pre-create Stripe Coupons at promo-save time because (a) the promo can be edited
            // up until publish, (b) we'd then have to keep two systems in sync, and (c) a fresh
            // coupon per checkout lets us tag it with metadata for the webhook to read back.
            String couponId = createOneShotCoupon(promo, eventId);
            builder.addDiscount(SessionCreateParams.Discount.builder()
                    .setCoupon(couponId)
                    .build());
            // Stamp the promo id on the session itself so the checkout.session.completed
            // webhook can find the PromoCode row without having to round-trip back to Stripe
            // to fetch the coupon. session.metadata is what the webhook will read.
            builder.putMetadata("promo_id", promo.getId().toString());
            builder.putMetadata("event_id", eventId.toString());
        }

        SessionCreateParams params = builder.build();

        Session session;
        try {
            // V1 endpoint — Checkout Sessions only exist in V1. (V2 has no /checkout namespace yet.)
            session = stripeClient.checkout().sessions().create(params);
        } catch (StripeException e) {
            log.error("Stripe checkout session create failed (event {}, tier {}): {}",
                    eventId, tierId, e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Checkout session could not be created", e);
        }
        return session.getUrl();
    }

    /**
     * Look up + validate a buyer-supplied promo code. Returns null when none was supplied.
     * Throws 400 INVALID_REQUEST when one was supplied but doesn't apply. Codes are matched
     * case-insensitively against the event's stored set; stored codes are already uppercased
     * by the wizard's reconcile path.
     */
    private PromoCode resolvePromoCode(UUID eventId, String rawCode) {
        if (rawCode == null) return null;
        String code = rawCode.trim();
        if (code.isEmpty()) return null;
        String normalized = code.toUpperCase(Locale.ROOT);

        PromoCode promo = promos.findByEventId(eventId).stream()
                .filter(p -> normalized.equals(p.getCode() == null
                        ? null : p.getCode().toUpperCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        ErrorCode.INVALID_REQUEST,
                        "Invalid promo code",
                        Map.of("promoCode", "not found")));

        if (!promo.isEnabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Promo code is no longer active",
                    Map.of("promoCode", "disabled"));
        }
        if (promo.getUsedCount() >= promo.getMaxUses()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Promo code has reached its usage limit",
                    Map.of("promoCode", "exhausted"));
        }
        return promo;
    }

    /**
     * Create a single-use Stripe Coupon that mirrors our promo. Duration=ONCE so the
     * discount applies only to this checkout. Metadata.promo_id lets the webhook tie a
     * paid session back to our PromoCode row for usage tracking.
     */
    private String createOneShotCoupon(PromoCode promo, UUID eventId) {
        CouponCreateParams params = CouponCreateParams.builder()
                .setPercentOff(BigDecimal.valueOf(promo.getDiscountPct()))
                .setDuration(CouponCreateParams.Duration.ONCE)
                .setName(promo.getCode())
                .putMetadata("promo_id", promo.getId().toString())
                .putMetadata("event_id", eventId.toString())
                .build();
        try {
            Coupon coupon = stripeClient.coupons().create(params);
            return coupon.getId();
        } catch (StripeException e) {
            log.error("Stripe coupon create failed for promo {} on event {}: {}",
                    promo.getId(), eventId, e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Promo code could not be applied — please try again", e);
        }
    }
}
