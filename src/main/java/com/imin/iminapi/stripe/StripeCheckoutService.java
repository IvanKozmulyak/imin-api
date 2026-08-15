package com.imin.iminapi.stripe;

import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.service.event.FreeCheckoutService;
import com.imin.iminapi.service.event.InventoryService;
import com.imin.iminapi.service.event.PublicTierEligibility;
import com.imin.iminapi.service.event.QuoteService;

import java.util.List;
import java.util.Locale;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
 *   <li>{@code application_fee_amount} skims our platform cut (default
 *       {@code €0.99 per ticket + 5% of subtotal}) off the top — equal to the
 *       buyer-visible "Service fee" line item, so the organizer receives the net
 *       ticket revenue and we keep the entire fee.</li>
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
    private final InventoryService inventoryService;
    private final FreeCheckoutService freeCheckoutService;
    private final StripeProperties props;
    private final Clock clock;

    public StripeCheckoutService(StripeClient stripeClient,
                                  EventRepository events,
                                  TicketTierRepository tiers,
                                  OrganizationRepository orgs,
                                  PromoCodeRepository promos,
                                  StripeConnectService connectService,
                                  InventoryService inventoryService,
                                  FreeCheckoutService freeCheckoutService,
                                  StripeProperties props,
                                  Clock clock) {
        this.stripeClient = stripeClient;
        this.events = events;
        this.tiers = tiers;
        this.orgs = orgs;
        this.promos = promos;
        this.connectService = connectService;
        this.inventoryService = inventoryService;
        this.freeCheckoutService = freeCheckoutService;
        this.props = props;
        this.clock = clock;
    }

    /**
     * What a checkout call produced. {@code kind} exists because the two
     * branches return structurally different things through what used to be one
     * {@code url} string: a Stripe-hosted session the buyer must be redirected
     * to, or a finished free order's page. Clients switched on the URL's shape;
     * now they switch on this.
     *
     * @param sessionId the Stripe Checkout Session id, or null on the free path.
     *                  Clients poll {@code GET /api/v1/public/checkout/{sessionId}}
     *                  with it to learn when issuance finished.
     */
    public record CheckoutResult(String kind, String url, String sessionId, String orderToken) {

        public static final String KIND_STRIPE = "stripe";
        public static final String KIND_ORDER = "order";

        public static CheckoutResult stripe(String url, String sessionId) {
            return new CheckoutResult(KIND_STRIPE, url, sessionId, null);
        }

        /**
         * The free path finishes the purchase outright. {@code orderToken} is
         * the same value embedded in {@code url} — returned as a field so a
         * client does not have to slice it out of a web URL it should not be
         * parsing. Two of the six events in the design handoff are free, so
         * this is a real app path, not an edge case.
         */
        public static CheckoutResult order(String url, String orderToken) {
            return new CheckoutResult(KIND_ORDER, url, null, orderToken);
        }
    }

    /**
     * @param promoCode optional buyer-supplied code. Validated against the event's promo list;
     *                  on success a one-shot Stripe Coupon is attached to the Session so the
     *                  discount appears at checkout. Invalid code → 400 INVALID_PROMO_CODE (we
     *                  deliberately do NOT collapse to 404, because here it's a buyer-fixable
     *                  typo, not a "this event doesn't exist" question).
     * @return the Stripe-hosted Checkout URL. Buyer is sent here directly; we never see the card.
     */
    public String createCheckoutSession(UUID eventId, UUID tierId, int quantity, String promoCode) {
        return createCheckoutSession(eventId, tierId, quantity, promoCode, null, null, false, false,
                CheckoutAttribution.NONE);
    }

    public String createCheckoutSession(UUID eventId, UUID tierId, int quantity,
                                         String promoCode, Integer expectedPriceMinor) {
        return createCheckoutSession(eventId, tierId, quantity, promoCode, expectedPriceMinor, null, false, false,
                CheckoutAttribution.NONE);
    }

    public String createCheckoutSession(UUID eventId, UUID tierId, int quantity,
                                         String promoCode, Integer expectedPriceMinor, String buyerEmail) {
        return createCheckoutSession(eventId, tierId, quantity, promoCode, expectedPriceMinor, buyerEmail, false, false,
                CheckoutAttribution.NONE);
    }

    /** Pre-V62 form kept for existing callers — no last-touch attribution captured. */
    public String createCheckoutSession(UUID eventId, UUID tierId, int quantity,
                                         String promoCode, Integer expectedPriceMinor, String buyerEmail,
                                         boolean adsConsent, boolean marketingOptIn) {
        return createCheckoutSession(eventId, tierId, quantity, promoCode, expectedPriceMinor, buyerEmail,
                adsConsent, marketingOptIn, CheckoutAttribution.NONE);
    }

    /** Pre-V78 form kept for existing callers — buyer locale unknown ⇒ English emails. */
    public String createCheckoutSession(UUID eventId, UUID tierId, int quantity,
                                         String promoCode, Integer expectedPriceMinor, String buyerEmail,
                                         boolean adsConsent, boolean marketingOptIn,
                                         CheckoutAttribution attribution) {
        return createCheckoutSession(eventId, tierId, quantity, promoCode, expectedPriceMinor, buyerEmail,
                adsConsent, marketingOptIn, attribution, null);
    }

    /**
     * Deliberately NOT {@code @Transactional}. Each DB mutation here goes through an
     * independently-transactional {@link InventoryService} / {@link FreeCheckoutService} method,
     * so wrapping the whole flow in one transaction would hold the pessimistic tier row-lock
     * taken by {@link InventoryService#reserve} across TWO blocking Stripe HTTP calls (coupon +
     * session create) — serializing every concurrent buyer of a hot tier behind remote I/O. By
     * leaving the boundary off, {@code reserve} commits and releases its lock before we call
     * Stripe; the explicit release-on-failure path below and the ReservationSweeper keep holds
     * correct if Stripe then fails or this process dies mid-flight.
     */
    public CheckoutResult createCheckout(UUID eventId, UUID tierId, int quantity,
                                         String promoCode, Integer expectedPriceMinor, String buyerEmail,
                                         boolean adsConsent, boolean marketingOptIn,
                                         CheckoutAttribution attribution, String rawLocale) {
        if (attribution == null) attribution = CheckoutAttribution.NONE;
        // Normalize once, here, so neither the free path nor the Stripe metadata can ever
        // carry an unsupported tag. null = "no preference" ⇒ English emails (V78).
        String buyerLocale = EmailLocale.normalizeOrNull(rawLocale);
        if (quantity < 1 || quantity > 10) {
            // 400, not 404 — quantity is a client bug, not an event-discovery question.
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "quantity must be between 1 and 10");
        }

        // 1. Load + validate event (must be publicly visible).
        Event event = events.findPublic(eventId).orElseThrow(() -> ApiException.notFound("Event"));

        // 2. Load + validate tier (belongs to event, enabled, in sale window, has price+quantity).
        // Shared eligibility predicate so quote and checkout never disagree on buyability — see PublicTierEligibility.
        Instant now = clock.instant();
        TicketTier tier = PublicTierEligibility.loadBuyableTier(tiers, eventId, tierId, now);

        // 2a. Price-drift guard. No-op when the client didn't send `expectedPriceMinor`.
        // When supplied and mismatched → 409 PRICE_CHANGED with `currentPriceMinor` in fields.
        PublicTierEligibility.assertExpectedPriceMatches(tier, expectedPriceMinor);

        // 2b. Resolve promo (if any) BEFORE deciding free vs paid. An invalid promo
        // throws 400 regardless of the eventual flow — that's a buyer-fixable typo.
        PromoCode promo = resolvePromoCode(eventId, promoCode);

        // 2c. Compute the net total after discount. Same math the quote endpoint
        // uses (see QuoteService.evaluatePromo). If the net is 0 — whether because
        // the tier itself is free OR a 100%-off promo zeroed out a paid tier — we
        // bypass Stripe entirely. Stripe Checkout *can* handle $0 sessions, but
        // routing them through Stripe is wasted latency and a redundant network
        // hop for the buyer; keeping the free path local also avoids needing a
        // Stripe-side org account, useful for free events from orgs that haven't
        // finished onboarding.
        long subtotal = (long) tier.getPriceMinor() * quantity;
        long discount = computeDiscount(promo, subtotal);
        long netTotal = Math.max(0L, subtotal - discount);

        if (netTotal == 0L) {
            String email = buyerEmail == null ? null : buyerEmail.trim();
            if (email == null || email.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                        "email is required for free tickets",
                        Map.of("email", "required for free tickets"));
            }
            if (!email.contains("@")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                        "email is invalid",
                        Map.of("email", "must be a valid email address"));
            }
            Order order;
            try {
                order = freeCheckoutService.issueFreeOrder(event, tier, quantity, email, promo, adsConsent,
                        marketingOptIn, attribution, buyerLocale);
            } catch (ApiException e) {
                // Inventory shortage → collapse to leak-safe 404 like the paid path.
                if (e.status() == HttpStatus.CONFLICT) {
                    throw ApiException.notFound("Event");
                }
                throw e;
            }
            // Best-effort confirmation email; failures are logged inside the service.
            // Branded ticket-issued email now rides TicketsIssuedEvent (published inside
            // issueFreeOrder) — same renderer/template as the paid path; no inline send.
            return CheckoutResult.order(freeCheckoutService.orderUrl(order), order.getToken());
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
        // Authoritative readiness: force-refresh from Stripe (degrading to the mirror if Stripe
        // is down) rather than trusting a possibly-stale local mirror to gate money movement.
        StripeConnectService.StatusResult status = connectService.getStatusLive(org.getId());
        if (!status.readyToReceivePayments()) {
            throw ApiException.notFound("Event");
        }

        // Promo was resolved earlier (before the free-vs-paid branch).

        // 4a. Reserve inventory BEFORE creating the Stripe Session. This locks the tier row
        // and atomically claims `quantity` seats. If we can't reserve, we collapse to 404 to
        // preserve the leak-safe behavior (don't reveal "sold out vs not found vs wrong tier"
        // to the buyer-facing public API). If reserve succeeds but Stripe later fails, we
        // release the hold below before rethrowing so the buyer doesn't get a phantom hold.
        //
        // The reservation row is written with its expires_at mirroring the Stripe session's
        // expires_at (Stripe minimum: 30 minutes), so the ReservationSweeper releases the
        // hold even if checkout.session.expired never reaches us.
        Instant expiresAt = clock.instant().plus(Duration.ofMinutes(props.getCheckoutSessionTtlMinutes()));
        UUID reservationId;
        try {
            // sessionId is null at this point — we haven't called Stripe yet. The webhook
            // resolves the hold via the reservation_id we stamp into metadata below.
            reservationId = inventoryService.reserve(tierId, quantity, expiresAt, null);
        } catch (ApiException e) {
            if (e.status() == HttpStatus.CONFLICT) {
                // "Not enough tickets" — remap so the buyer can't enumerate inventory state.
                throw ApiException.notFound("Event");
            }
            throw e;
        }

        // 5. Compute platform fee. Same formula QuoteService uses so the buyer's
        // displayed total matches what Stripe charges. The fee is computed on the
        // *undiscounted* subtotal so a promo doesn't shrink our cut. The application
        // fee equals the buyer-visible service-fee line item — the organizer is paid
        // the net ticket revenue (subtotal − discount), platform keeps the fee.
        long subtotalMinor = (long) tier.getPriceMinor() * quantity;
        long applicationFee = QuoteService.computeFee(subtotalMinor, quantity,
                props.getApplicationFeeBps(), props.getApplicationFeeFixedMinor());
        String currency = event.getCurrency() == null
                ? "eur" : event.getCurrency().toLowerCase(Locale.ROOT);

        // 6. Build the session.
        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(tier.getStripePriceId())
                        .setQuantity((long) quantity)
                        .build());

        // Buyer-visible "Service fee" line item — created inline with price_data so it
        // doesn't need a stored Stripe Price. It's a separate line so a promo coupon
        // (scoped via applies_to.products below) can't discount the fee.
        if (applicationFee > 0L) {
            builder.addLineItem(SessionCreateParams.LineItem.builder()
                    .setQuantity(1L)
                    .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                            .setCurrency(currency)
                            .setUnitAmount(applicationFee)
                            .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                    .setName("Service fee")
                                    .build())
                            .build())
                    .build());
        }

        // Build the metadata map ONCE and mirror it onto both the Session and the
        // underlying PaymentIntent. The webhook keys fulfilment off
        // payment_intent.succeeded (the PI is what tells us the money moved), so
        // it must see the same reservation_id/tier_id/qty/event_id/promo_id the
        // Session would. reservation_id is the primary inventory key — the
        // webhook calls InventoryService.{confirmSold,releaseReservation}(reservationId);
        // tier_id and qty remain for issuance (PaidCheckoutService reads them
        // when it materializes Order + Ticket rows).
        Map<String, String> metadata = new HashMap<>();
        metadata.put("reservation_id", reservationId.toString());
        metadata.put("tier_id", tierId.toString());
        metadata.put("qty", String.valueOf(quantity));
        metadata.put("event_id", eventId.toString());
        // Ride the buyer's cookie-consent ads-consent decision (§7) through Stripe session
        // metadata so PaidCheckoutService can snapshot it onto orders.ads_consent at
        // webhook-driven order creation. Only "true" enables the server-side CAPI event.
        metadata.put("ads_consent", String.valueOf(adsConsent));
        metadata.put("marketing_opt_in", String.valueOf(marketingOptIn));
        // Ride the landing utm_* + anon_id (V62) the same way so PaidCheckoutService can
        // snapshot them onto orders.utm_* at webhook-driven order creation — that's what
        // turns per-campaign revenue from a visit-share estimate into a true per-order sum.
        // Absent fields are omitted rather than written as "null".
        attribution.putInto(metadata);
        // Buyer's UI language (V78). The Order is only created at webhook fulfilment, so
        // the locale has to survive the Stripe round-trip like every other checkout-time
        // fact; PaidCheckoutService reads it back onto orders.buyer_locale. Omitted when
        // unknown, so a missing key means "no preference", not "English was chosen".
        if (buyerLocale != null) {
            metadata.put("buyer_locale", buyerLocale);
        }

        String couponId = null;
        if (promo != null) {
            // Create a one-shot Stripe Coupon on the platform account and attach it. The
            // coupon is scoped to the ticket Product (applies_to.products) so it never
            // discounts the service-fee line item — promos discount tickets only.
            couponId = createOneShotCoupon(promo, eventId, tier.getStripeProductId());
            metadata.put("promo_id", promo.getId().toString());
        }

        // Stripe Checkout's documented minimum lifetime is 30 minutes — anything shorter
        // is rejected with `expires_at` "must be at least 30 minutes in the future"
        // (https://docs.stripe.com/api/checkout/sessions/create#create_checkout_session-expires_at).
        // The reservation row computed above uses the same instant so the ReservationSweeper
        // releases the hold even if the checkout.session.expired webhook never lands.
        long expiresAtEpoch = expiresAt.getEpochSecond();

        builder
                // payment_intent_data — destination charge with application fee.
                // The PaymentIntent is created on the *platform* account; transfer_data.destination
                // moves the net to the connected account asynchronously. Metadata is mirrored
                // onto the PI so the payment_intent.succeeded webhook handler can read the same
                // tier_id/qty/promo_id that the Session carries.
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .setApplicationFeeAmount(applicationFee)
                        .setTransferData(SessionCreateParams.PaymentIntentData.TransferData.builder()
                                .setDestination(org.getStripeAccountId())
                                .build())
                        // Track B (Phase 3): group all PaymentIntents for this event under
                        // transfer_group=<eventId> for reconciliation / future per-event
                        // transfer queries. Additive only — destination/application_fee are
                        // unchanged, and on_behalf_of is intentionally NOT set (recipient
                        // accounts lack the card_payments capability it requires).
                        .setTransferGroup(eventId.toString())
                        .putAllMetadata(metadata)
                        .build())
                .setExpiresAt(expiresAtEpoch)
                .setSuccessUrl(props.getPublicReturnUrlBase()
                        + "/e/" + eventId + "/success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(props.getPublicReturnUrlBase() + "/e/" + eventId)
                // Stamp inventory-relevant metadata so the webhook can find the right tier
                // and amount on checkout.session.expired (releaseReservation).
                .putAllMetadata(metadata);

        if (couponId != null) {
            builder.addDiscount(SessionCreateParams.Discount.builder()
                    .setCoupon(couponId)
                    .build());
        }

        // Prefill + lock the buyer email on the hosted Checkout when we have it, so issuance can
        // read it straight back from the Session (no dependency on post-hoc charge/session lookups).
        if (buyerEmail != null && !buyerEmail.isBlank()) {
            builder.setCustomerEmail(buyerEmail.trim());
        }

        SessionCreateParams params = builder.build();

        Session session;
        try {
            // V1 endpoint — Checkout Sessions only exist in V1. (V2 has no /checkout namespace yet.)
            session = stripeClient.checkout().sessions().create(params);
        } catch (StripeException e) {
            // Roll back the inventory hold — we promised the buyer nothing, so the seats
            // must go back to the pool. Best-effort: if release itself throws (e.g. the
            // tier was deleted in between), log it but keep the original Stripe error as
            // the user-facing cause.
            try {
                inventoryService.releaseReservation(reservationId, "STRIPE_CREATE_FAILED");
            } catch (Exception releaseFailure) {
                log.error("Failed to release reservation {} after Stripe session create failure: {}",
                        reservationId, releaseFailure.getMessage(), releaseFailure);
            }
            // Best-effort: delete the one-shot coupon we minted above — it was never attached to a
            // live session, so leaving it dangles a useless object on the platform account.
            if (couponId != null) {
                try {
                    stripeClient.coupons().delete(couponId);
                } catch (Exception couponFailure) {
                    log.warn("Failed to delete orphaned coupon {} after session create failure: {}",
                            couponId, couponFailure.getMessage());
                }
            }
            log.error("Stripe checkout session create failed (event {}, tier {}, reservation {}): {}",
                    eventId, tierId, reservationId, e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Checkout session could not be created", e);
        }

        // Best-effort: stamp the Stripe session id onto the reservation row for
        // forensics / fallback resolution. A failure here is harmless — the
        // reservation_id metadata on the session already drives the webhook path.
        try {
            inventoryService.attachSessionId(reservationId, session.getId());
        } catch (Exception e) {
            log.warn("Failed to attach session id {} to reservation {}: {}",
                    session.getId(), reservationId, e.getMessage());
        }
        return CheckoutResult.stripe(session.getUrl(), session.getId());
    }

    /**
     * Pre-discriminant form. Kept because five overloads and their callers
     * expect a bare URL; new callers should use {@link #createCheckout}.
     */
    public String createCheckoutSession(UUID eventId, UUID tierId, int quantity,
                                         String promoCode, Integer expectedPriceMinor, String buyerEmail,
                                         boolean adsConsent, boolean marketingOptIn,
                                         CheckoutAttribution attribution, String rawLocale) {
        return createCheckout(eventId, tierId, quantity, promoCode, expectedPriceMinor, buyerEmail,
                adsConsent, marketingOptIn, attribution, rawLocale).url();
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
     * Same math as {@link com.imin.iminapi.service.event.QuoteService} so the
     * preview and the eventual charge can never disagree on the discount. Rounded
     * half-up to match Stripe Coupon behaviour, clamped at the subtotal so a
     * 100%+ discount stays at the subtotal rather than going negative.
     */
    private static long computeDiscount(PromoCode promo, long subtotal) {
        if (promo == null) return 0L;
        long discount = Math.round(subtotal * (double) promo.getDiscountPct() / 100.0);
        return Math.min(discount, subtotal);
    }

    /**
     * Create a single-use Stripe Coupon that mirrors our promo. Duration=ONCE so the
     * discount applies only to this checkout. When {@code ticketProductId} is set, the
     * coupon is scoped via {@code applies_to.products} so it discounts the ticket only,
     * never the buyer-visible service-fee line item. Metadata.promo_id lets the webhook
     * tie a paid session back to our PromoCode row for usage tracking.
     */
    private String createOneShotCoupon(PromoCode promo, UUID eventId, String ticketProductId) {
        CouponCreateParams.Builder params = CouponCreateParams.builder()
                .setPercentOff(BigDecimal.valueOf(promo.getDiscountPct()))
                .setDuration(CouponCreateParams.Duration.ONCE)
                .setName(promo.getCode())
                .putMetadata("promo_id", promo.getId().toString())
                .putMetadata("event_id", eventId.toString());
        if (ticketProductId != null && !ticketProductId.isBlank()) {
            params.setAppliesTo(CouponCreateParams.AppliesTo.builder()
                    .addProduct(ticketProductId)
                    .build());
        }
        CouponCreateParams built = params.build();
        try {
            Coupon coupon = stripeClient.coupons().create(built);
            return coupon.getId();
        } catch (StripeException e) {
            log.error("Stripe coupon create failed for promo {} on event {}: {}",
                    promo.getId(), eventId, e.getMessage(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Promo code could not be applied — please try again", e);
        }
    }
}
