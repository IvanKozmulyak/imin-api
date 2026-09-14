package com.imin.iminapi.service.ticket;

import com.imin.iminapi.dispute.DisputeIngestService;
import com.imin.iminapi.email.EmailLocale;
import com.imin.iminapi.marketing.service.MetaCapiOutboxWriter;
import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.model.CheckoutConsent;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionListParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Persists an {@link Order} + N {@link Ticket} rows when a paid Stripe
 * {@code payment_intent.succeeded} webhook arrives. Idempotent: a second
 * delivery with the same PaymentIntent id is a no-op thanks to the in-handler
 * short-circuit + the {@code orders_stripe_payment_intent_id_unique}
 * constraint added in V26.
 *
 * <p><b>Transactional in its own right.</b> On the webhook path REQUIRED joins the
 * caller's transaction, so the writes still commit alongside the dedup INSERT and
 * the inventory confirmation or roll back together and Stripe retries. On the
 * {@link PaidFulfilmentReconciler} path — a bare {@code @Scheduled} method — there
 * is no ambient transaction, and without one here every {@code save} committed
 * separately (a crash mid-loop left a ticket-less Order that every later tick skips)
 * and {@code publishEvent} fired with no synchronization active, so Spring dropped
 * {@link TicketsIssuedEvent} for all three {@code AFTER_COMMIT} listeners. The buyer
 * the reconciler exists to rescue got rows and no ticket email.
 */
@Service
public class PaidCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(PaidCheckoutService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final StripeClient stripeClient;
    private final ApplicationEventPublisher publisher;
    private final MetaCapiOutboxWriter metaCapiOutboxWriter;
    private final StripeProperties stripeProps;
    private final DisputeIngestService disputeIngest;

    public PaidCheckoutService(OrderRepository orders,
                                TicketRepository tickets,
                                EventRepository events,
                                TicketTierRepository tiers,
                                StripeClient stripeClient,
                                ApplicationEventPublisher publisher,
                                MetaCapiOutboxWriter metaCapiOutboxWriter,
                                StripeProperties stripeProps,
                                DisputeIngestService disputeIngest) {
        this.orders = orders;
        this.tickets = tickets;
        this.events = events;
        this.tiers = tiers;
        this.stripeClient = stripeClient;
        this.publisher = publisher;
        this.metaCapiOutboxWriter = metaCapiOutboxWriter;
        this.stripeProps = stripeProps;
        this.disputeIngest = disputeIngest;
    }

    /**
     * Resolve the buyer email + session id for a PI BEFORE any inventory work starts.
     *
     * <p>Split out of {@link #issuePaidOrder} on purpose. The webhook transaction takes a
     * {@code SELECT … FOR UPDATE} on the ticket tier ({@code InventoryService.confirmSold}) and
     * holds it until commit; resolving the buyer inside that window meant up to two blocking
     * Stripe round trips (stripe-java's default read timeout is 80s) with the tier row locked
     * and a pooled DB connection held, so every concurrent buyer of that tier queued behind
     * Stripe's latency. The caller now resolves FIRST, then locks.
     *
     * @return the resolution, or {@code null} when there is nothing to resolve — a malformed PI
     *         or an order that already exists (the idempotent short-circuit, kept cheap so a
     *         redelivery still costs no Stripe calls).
     */
    public BuyerResolution prepareIssuance(PaymentIntent pi) {
        if (pi == null || pi.getId() == null) return null;
        if (orders.findByStripePaymentIntentId(pi.getId()).isPresent()) return null;
        return resolveBuyerAndSession(pi);
    }

    /**
     * @return {@code true} only when THIS call created the Order (first successful issuance);
     *         {@code false} on any idempotent short-circuit (already issued, missing/invalid
     *         metadata, duplicate-key race). Callers use the boolean to gate side effects that
     *         must happen exactly once per paid order — e.g. incrementing promo usage.
     */
    @Transactional
    public boolean issuePaidOrder(PaymentIntent pi) {
        return issuePaidOrder(pi, null);
    }

    /**
     * As {@link #issuePaidOrder(PaymentIntent)}, but reusing a {@link BuyerResolution} the caller
     * already obtained from {@link #prepareIssuance} outside the tier-lock window. A null
     * {@code prepared} falls back to resolving inline, which is what the reconciler and the
     * tests do.
     */
    public boolean issuePaidOrder(PaymentIntent pi, BuyerResolution prepared) {
        if (pi == null || pi.getId() == null) {
            log.warn("issuePaidOrder called with null PI — skipping");
            return false;
        }

        // Belt: short-circuit if already issued. The suspenders are the UNIQUE
        // constraint on orders.stripe_payment_intent_id; either is sufficient
        // by itself but we want fast happy-path noop on retry without an
        // INSERT attempt.
        if (orders.findByStripePaymentIntentId(pi.getId()).isPresent()) {
            log.info("Order already exists for PaymentIntent {} — skipping (idempotent)", pi.getId());
            return false;
        }

        Map<String, String> meta = pi.getMetadata();
        if (meta == null) {
            log.warn("PaymentIntent {} has no metadata — skipping issuance", pi.getId());
            return false;
        }
        String tierIdRaw = meta.get("tier_id");
        String qtyRaw = meta.get("qty");
        String eventIdRaw = meta.get("event_id");
        if (tierIdRaw == null || qtyRaw == null || eventIdRaw == null) {
            log.warn("PaymentIntent {} missing required metadata (tier_id/qty/event_id) — skipping",
                    pi.getId());
            return false;
        }

        UUID tierId;
        UUID eventId;
        int qty;
        try {
            tierId = UUID.fromString(tierIdRaw);
            eventId = UUID.fromString(eventIdRaw);
            qty = Integer.parseInt(qtyRaw);
        } catch (IllegalArgumentException e) {
            log.warn("PaymentIntent {} has malformed metadata: {}", pi.getId(), e.getMessage());
            return false;
        }
        if (qty < 1) {
            log.warn("PaymentIntent {} has non-positive qty={} — skipping", pi.getId(), qty);
            return false;
        }

        Event event = events.findById(eventId).orElseThrow(() ->
                new IllegalStateException("Event " + eventId + " for PI " + pi.getId() + " is missing"));
        TicketTier tier = tiers.findById(tierId).orElseThrow(() ->
                new IllegalStateException("Tier " + tierId + " for PI " + pi.getId() + " is missing"));

        BuyerResolution resolved = prepared != null ? prepared : resolveBuyerAndSession(pi);
        if (resolved.buyerEmail() == null) {
            throw new IllegalStateException("Could not resolve buyer email for PI " + pi.getId()
                    + " — webhook will be retried by Stripe");
        }

        Order order = new Order();
        order.setToken(randomToken());
        order.setEventId(event.getId());
        order.setOrgId(event.getOrgId());
        order.setEmail(resolved.buyerEmail().trim().toLowerCase(Locale.ROOT));
        order.setTotalMinor(pi.getAmount() == null ? 0L : pi.getAmount());
        order.setCurrency(pi.getCurrency() == null
                ? event.getCurrency()
                : pi.getCurrency().toLowerCase(Locale.ROOT));
        order.setPaymentMethod("stripe");
        // Which Stripe mode took this money (V130). A test-mode order is kept but never
        // counted towards a live payout.
        order.setTestMode(!stripeProps.isLiveKey());
        order.setStripePaymentIntentId(pi.getId());
        order.setStripeSessionId(resolved.sessionId());
        // Buyer's cookie-consent ads-consent decision (§7), stamped into the session/PI
        // metadata at checkout by StripeCheckoutService. Snapshotted onto orders.ads_consent;
        // gates the server-side Meta CAPI event (MetaCapiOutboxWriter). Absent/anything-but-
        // "true" defaults false (V60 default), so historical/unconsented orders never emit.
        order.setAdsConsent("true".equals(meta.get("ads_consent")));
        // Email-marketing soft opt-in from the buy page (pre-ticked, buyer left it ticked),
        // stamped into metadata by StripeCheckoutService. The AudienceOrderProjector turns
        // it into the basis='soft_opt_in' consent row.
        order.setMarketingOptIn("true".equals(meta.get("marketing_opt_in")));
        // Last-touch utm_* + anon_id (V62) captured on landing and carried through the
        // session/PI metadata. Missing keys → null: sessions created before V62 that were
        // still in flight at deploy, and organic buyers who arrived with no tags at all.
        // This is what makes per-campaign revenue a true per-order sum rather than an estimate.
        CheckoutAttribution.fromMetadata(meta).applyTo(order);
        // Terms acceptance + the verbatim marketing-checkbox sentence the buyer read
        // (V97), stamped into the metadata at checkout. Missing keys → not recorded,
        // which is what every order placed before the buyer site sent them has.
        CheckoutConsent.fromMetadata(meta).applyTo(order);
        // Buyer's UI language (V78), stamped into the session/PI metadata at checkout.
        // Absent key (pre-V78 sessions in flight at deploy, or a buyer whose language we
        // don't support) → null ⇒ English emails, same as every historical order.
        order.setBuyerLocale(EmailLocale.normalizeOrNull(meta.get("buyer_locale")));
        order.setApplicationFeeMinor(pi.getApplicationFeeAmount() == null ? 0L : pi.getApplicationFeeAmount());

        String promoIdRaw = meta.get("promo_id");
        if (promoIdRaw != null && !promoIdRaw.isBlank()) {
            try {
                order.setPromoCodeId(UUID.fromString(promoIdRaw));
            } catch (IllegalArgumentException ignored) {
                log.warn("Malformed promo_id on PI {}: {}", pi.getId(), promoIdRaw);
            }
        }

        // No duplicate-key catch here, deliberately. Order uses GenerationType.UUID,
        // so Hibernate defers the INSERT to flush/commit (FreeCheckoutService spells
        // this out for the same entity) — the orders_stripe_payment_intent_id_unique
        // constraint added in V26 therefore fires at the first auto-flush below or at
        // commit, never at this call. A catch here could not run, and swallowing the
        // violation would be wrong anyway: rolling the losing delivery back is what
        // makes it correct. Stripe retries, and the retry lands on the idempotent
        // short-circuit at the top of this method.
        orders.save(order);

        for (int i = 0; i < qty; i++) {
            Ticket t = new Ticket();
            t.setToken(randomToken());
            t.setOrderId(order.getId());
            t.setEventId(event.getId());
            t.setTierId(tier.getId());
            t.setTierName(tier.getName());
            t.setPriceMinor(tier.getPriceMinor());
            t.setState(Ticket.STATE_ISSUED);
            tickets.save(t);
        }

        // A chargeback delivered before this webhook revoked nothing; attach it now so the tickets
        // are revoked in the same transaction. The issuance email still ships a QR for a revoked
        // ticket — suppressing it is a deferred follow-up; the door check is what stops entry.
        int attachedDisputes = disputeIngest.attachOrphansForOrder(order);
        if (attachedDisputes > 0) {
            log.warn("Order {} (PI {}) was already disputed — attached {} dispute(s) and revoked its tickets",
                    order.getId(), pi.getId(), attachedDisputes);
        }

        publisher.publishEvent(new TicketsIssuedEvent(order.getId()));
        metaCapiOutboxWriter.writeForOrder(order.getId());
        log.info("Issued {} ticket(s) for PI {} → order {}", qty, pi.getId(), order.getId());
        return true;
    }

    /**
     * One Stripe lookup that returns both the buyer email and the session id.
     *
     * <p>The hosted path never carries the buyer address in metadata — it rides the
     * Checkout Session ({@code setCustomerEmail}) and is recovered by listing the
     * Session for this PaymentIntent. <b>A natively-created PaymentIntent has no
     * Session at all</b>, so that source is structurally empty, and the Stripe
     * PaymentSheet does not populate {@code billing_details.email} by default.
     * Without the {@code buyer_email} metadata fallback below, every native purchase
     * throws at {@code issuePaidOrder} and Stripe retries forever: the buyer is
     * charged and never receives a ticket. ({@code receipt_email} is not a way out —
     * nothing in this codebase reads it back.)
     */
    private BuyerResolution resolveBuyerAndSession(PaymentIntent pi) {
        Map<String, String> meta = pi.getMetadata() == null ? Map.of() : pi.getMetadata();

        // A native PI has no Checkout Session, so listing sessions for it is a
        // guaranteed-empty round trip and the address can only come from metadata.
        // StripePaymentIntentService stamps both keys via the shared prelude.
        if ("native".equals(meta.get("client"))) {
            String fromCharge = readChargeEmail(pi);
            return new BuyerResolution(fromCharge != null ? fromCharge : trimToNull(meta.get("buyer_email")), null);
        }

        String emailFromCharge = readChargeEmail(pi);

        String email = emailFromCharge;
        String sessionId = null;
        try {
            var coll = stripeClient.checkout().sessions().list(
                    SessionListParams.builder()
                            .setPaymentIntent(pi.getId())
                            .setLimit(1L)
                            .build());
            if (coll != null && coll.getData() != null && !coll.getData().isEmpty()) {
                Session s = coll.getData().get(0);
                sessionId = s.getId();
                if (email == null) {
                    if (s.getCustomerDetails() != null && s.getCustomerDetails().getEmail() != null) {
                        email = s.getCustomerDetails().getEmail();
                    } else if (s.getCustomerEmail() != null) {
                        email = s.getCustomerEmail();
                    }
                }
            }
        } catch (StripeException e) {
            log.warn("Failed to list sessions for PI {}: {}", pi.getId(), e.getMessage());
        }
        // Last resort for a hosted PI whose session lookup failed or came back empty.
        // Costs nothing and turns an unfulfillable order into a fulfilled one.
        if (email == null) email = trimToNull(meta.get("buyer_email"));
        return new BuyerResolution(email, sessionId);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String readChargeEmail(PaymentIntent pi) {
        if (pi.getLatestCharge() == null) return null;
        try {
            Charge c = stripeClient.charges().retrieve(pi.getLatestCharge());
            if (c != null && c.getBillingDetails() != null) {
                return c.getBillingDetails().getEmail();
            }
        } catch (StripeException e) {
            log.warn("Failed to retrieve charge {} for PI {}: {}",
                    pi.getLatestCharge(), pi.getId(), e.getMessage());
        }
        return null;
    }

    /** Buyer email + Checkout Session id for a PaymentIntent, resolved from Stripe. */
    public record BuyerResolution(String buyerEmail, String sessionId) {}

    private static String randomToken() {
        byte[] bytes = new byte[24];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
