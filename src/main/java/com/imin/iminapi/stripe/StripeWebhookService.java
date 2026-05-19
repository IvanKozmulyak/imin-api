package com.imin.iminapi.stripe;

import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.event.InventoryService;
import com.imin.iminapi.service.ticket.PaidCheckoutService;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.model.v2.core.Event;
import com.stripe.model.v2.core.EventNotification;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles Stripe webhook deliveries on {@code POST /api/v1/stripe/webhook}.
 *
 * <p>This single endpoint handles both Stripe webhook formats:
 * <ul>
 *   <li><b>V1 events</b> ({@code payment_intent.succeeded}, {@code payment_intent.payment_failed},
 *       {@code checkout.session.expired}, …) — parsed by {@link Webhook#constructEvent}.</li>
 *   <li><b>V2 thin events</b> ({@code v2.core.account.requirements.updated},
 *       {@code v2.core.account.recipient.capability_status_updated}) — parsed by
 *       {@link StripeClient#parseEventNotification}.</li>
 * </ul>
 *
 * <p>Both schemes use the same HMAC-SHA256 signature algorithm, so one shared
 * {@code STRIPE_WEBHOOK_SECRET} works for either. Routing happens by JSON-peeking
 * the body's {@code object} field — {@code "v2.core.event"} → v2 path, else v1.
 * The peek is on unverified data, but the only consequence of a wrong peek is the
 * wrong parser failing signature verification, which we reject.
 *
 * <p><b>Idempotency.</b> Stripe delivers events at-least-once. The first thing
 * {@link #handleV1} does after parsing is call {@link WebhookEventDedupService}
 * to INSERT the event id into {@code processed_webhook_events}. A duplicate-key
 * means the event was already processed — we log and ack. The dispatch + the
 * dedup INSERT share a single REQUIRED transaction, so a handler exception
 * rolls the INSERT back and Stripe's retry can re-process from scratch.
 *
 * <p><b>Fulfilment signal.</b> We key off {@code payment_intent.succeeded} for
 * inventory confirm + promo redemption, not {@code checkout.session.completed},
 * because PI is what tells us the money actually moved. The Session-create
 * call mirrors session-level metadata onto {@code payment_intent_data.metadata}
 * so the PI handler sees the same {@code tier_id} / {@code qty} / {@code promo_id}
 * fields the Session would have carried.
 */
@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final StripeClient stripeClient;
    private final StripeProperties props;
    private final PromoCodeRepository promos;
    private final InventoryService inventoryService;
    private final WebhookEventDedupService dedup;
    private final PaidCheckoutService paidCheckoutService;

    /**
     * Proxied self-reference used so {@link #handle} can invoke {@link #handleV1}
     * through Spring's transaction proxy. A direct {@code this.handleV1(...)} call
     * would bypass the proxy and silently drop the {@code @Transactional}
     * advice, losing the rollback-on-failure idempotency contract. Injected
     * with {@code @Lazy} to break the circular dependency at startup.
     *
     * <p>In unit tests this field stays null and the fallback at the call site
     * drops back to {@code this.handleV1(...)} — fine, because the tests inject
     * a mock {@link WebhookEventDedupService} directly rather than relying on
     * the JDBC transaction.
     */
    private StripeWebhookService self;

    public StripeWebhookService(StripeClient stripeClient,
                                StripeProperties props,
                                PromoCodeRepository promos,
                                InventoryService inventoryService,
                                WebhookEventDedupService dedup,
                                PaidCheckoutService paidCheckoutService) {
        this.stripeClient = stripeClient;
        this.props = props;
        this.promos = promos;
        this.inventoryService = inventoryService;
        this.dedup = dedup;
        this.paidCheckoutService = paidCheckoutService;
    }

    @Autowired
    void setSelf(@Lazy StripeWebhookService self) {
        this.self = self;
    }

    /**
     * @param rawBody  the unmodified request body (signature is computed over the exact bytes).
     * @param sigHeader the value of the {@code Stripe-Signature} header.
     */
    public void handle(String rawBody, String sigHeader) {
        String secret = props.getWebhookSecret();
        if (secret == null || secret.isBlank()) {
            // 503 — we can't verify, so reject. The operator should set STRIPE_WEBHOOK_SECRET.
            log.warn("Refusing Stripe webhook: STRIPE_WEBHOOK_SECRET not configured");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Stripe webhook handler not configured");
        }
        if (sigHeader == null || sigHeader.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Missing Stripe-Signature header");
        }

        if (looksLikeV2ThinEvent(rawBody)) {
            handleV2(rawBody, sigHeader, secret);
        } else {
            // Go through the proxied self-reference so @Transactional on handleV1
            // is honored in production. Tests bypass Spring entirely and self is
            // null; fall back to a direct call.
            StripeWebhookService target = self == null ? this : self;
            target.handleV1(rawBody, sigHeader, secret);
        }
    }

    private static boolean looksLikeV2ThinEvent(String body) {
        // V2 thin events carry {"object":"v2.core.event", ...} at top level. Plain
        // substring check is enough — the matching parser will reject mismatches via
        // signature verification, so a false positive can't smuggle in bad data.
        return body != null && body.contains("\"v2.core.event\"");
    }

    private void handleV2(String rawBody, String sigHeader, String secret) {
        EventNotification notification;
        try {
            notification = stripeClient.parseEventNotification(rawBody, sigHeader, secret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe v2 webhook signature verification failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid Stripe signature");
        }

        String type = notification.getType();
        String id = notification.getId();
        log.info("Stripe v2 webhook received: type={} id={}", type, id);

        if ("v2.core.account.requirements.updated".equals(type)
                || "v2.core.account.recipient.capability_status_updated".equals(type)) {
            try {
                Event full = stripeClient.v2().core().events().retrieve(id);
                log.info("Stripe account state event: type={} eventId={} created={}",
                        full.getType(), full.getId(), full.getCreated());
                // No DB state to update — connect status is fetched live per the user's instruction.
            } catch (StripeException e) {
                log.warn("Failed to fetch full Stripe event {} (type {}): {}",
                        id, type, e.getMessage());
            }
        }
    }

    /**
     * Dispatch a V1 webhook.
     *
     * <p>{@code Propagation.REQUIRED} (the default; spelled out for clarity) keeps the dedup
     * INSERT and any handler DB writes in a single transaction — on a handler exception the
     * INSERT rolls back, so Stripe's retry will re-process from scratch rather than seeing
     * a phantom "already done" marker.
     *
     * <p>The method is public so Spring's proxy can apply the transaction advice when
     * {@link #handle} invokes it through the framework — direct {@code this.handleV1(...)}
     * calls from inside this class still work but would bypass the proxy. Tests bypass
     * Spring entirely and rely on Mockito mocks for the JDBC dedup, so the missing
     * transaction wrapper is fine there.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void handleV1(String rawBody, String sigHeader, String secret) {
        com.stripe.model.Event event;
        try {
            event = Webhook.constructEvent(rawBody, sigHeader, secret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe v1 webhook signature verification failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid Stripe signature");
        }

        String type = event.getType();
        String eventId = event.getId();
        log.info("Stripe v1 webhook received: type={} id={}", type, eventId);

        // Idempotency gate. INSERT a marker row; duplicate-key means a prior delivery
        // already finished this event's side-effects. We log + ack with 200 so Stripe
        // stops retrying. The INSERT participates in the surrounding @Transactional,
        // so any later exception rolls it back and the next Stripe retry re-runs the
        // whole handler.
        if (!dedup.tryRecord(eventId, type)) {
            log.info("Stripe v1 webhook {} (type={}) already processed — skipping", eventId, type);
            return;
        }

        switch (type) {
            case "payment_intent.succeeded" -> onPaymentIntentSucceeded(event);
            case "payment_intent.payment_failed" -> onPaymentIntentFailed(event);
            case "checkout.session.expired" -> onCheckoutSessionExpired(event);
            // checkout.session.completed is intentionally a no-op. Fulfilment moved
            // to payment_intent.succeeded because PI is what tells us the money
            // actually moved (Session.completed can fire for unpaid async sessions
            // and adds an extra latency hop on the happy path).
            case "checkout.session.completed" -> log.debug(
                    "checkout.session.completed received but ignored — fulfilment is on payment_intent.succeeded");
            default -> { /* ignored, ack with 200 so Stripe stops retrying */ }
        }
    }

    /**
     * Fulfilment: PI succeeded → promote the reservation to a confirmed sale, and
     * (when applicable) increment the promo's used_count. Reads the same
     * {@code tier_id} / {@code qty} / {@code promo_id} metadata that
     * {@link StripeCheckoutService} stamps onto both the Session and the PI.
     */
    private void onPaymentIntentSucceeded(com.stripe.model.Event event) {
        PaymentIntent pi = extractPaymentIntent(event, "payment_intent.succeeded");
        if (pi == null) return;

        Map<String, String> meta = pi.getMetadata();
        TierMeta tierMeta = parseTierMeta(meta, pi.getId());
        if (tierMeta != null) {
            inventoryService.confirmSold(tierMeta.tierId, tierMeta.qty);
            log.info("Confirmed sold qty={} on tier {} after payment_intent {}",
                    tierMeta.qty, tierMeta.tierId, pi.getId());
        }

        if (meta != null) {
            String promoIdRaw = meta.get("promo_id");
            if (promoIdRaw != null && !promoIdRaw.isBlank()) {
                try {
                    UUID promoId = UUID.fromString(promoIdRaw);
                    int rows = promos.incrementUsedCount(promoId);
                    if (rows == 0) {
                        log.warn("Promo code {} not found when handling payment_intent {} — skipped",
                                promoId, pi.getId());
                    } else {
                        log.info("Incremented usedCount on promo {} after payment_intent {}",
                                promoId, pi.getId());
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("PaymentIntent {} has malformed promo_id metadata: {}",
                            pi.getId(), promoIdRaw);
                }
            }
        }

        // Persist Order + N Ticket rows for the buyer. Idempotent on PI id, so a
        // Stripe retry is a noop. Publishes TicketsIssuedEvent on success; the
        // @Async listener then emails the buyer with the tickets and QR.
        paidCheckoutService.issuePaidOrder(pi);
    }

    /**
     * Compensating release: PI failed (card declined, 3DS failure, etc.) → return
     * the held seats to the pool now, rather than waiting for
     * {@code checkout.session.expired} (which only fires when the session times
     * out, not when an attempt fails inside the session).
     */
    private void onPaymentIntentFailed(com.stripe.model.Event event) {
        PaymentIntent pi = extractPaymentIntent(event, "payment_intent.payment_failed");
        if (pi == null) return;

        TierMeta tierMeta = parseTierMeta(pi.getMetadata(), pi.getId());
        if (tierMeta == null) return;
        inventoryService.releaseReservation(tierMeta.tierId, tierMeta.qty);
        log.info("Released reservation qty={} on tier {} after payment_intent {} failed",
                tierMeta.qty, tierMeta.tierId, pi.getId());
    }

    private PaymentIntent extractPaymentIntent(com.stripe.model.Event event, String label) {
        EventDataObjectDeserializer dod = event.getDataObjectDeserializer();
        Optional<StripeObject> obj = dod.getObject();
        if (obj.isEmpty()) {
            log.warn("{} had no deserialized object — apiVersion={}", label, event.getApiVersion());
            return null;
        }
        if (!(obj.get() instanceof PaymentIntent pi)) {
            log.warn("{} deserialized to unexpected type: {}", label, obj.get().getClass().getName());
            return null;
        }
        return pi;
    }

    /**
     * Handle {@code checkout.session.expired}: the buyer never paid, so the seat hold
     * must be released back to the pool. Legacy sessions without inventory metadata are
     * skipped (nothing to release). Idempotency is enforced at the top of
     * {@link #handleV1} so a delivered-twice expiry can't double-release.
     */
    private void onCheckoutSessionExpired(com.stripe.model.Event event) {
        EventDataObjectDeserializer dod = event.getDataObjectDeserializer();
        Optional<StripeObject> obj = dod.getObject();
        if (obj.isEmpty()) {
            log.warn("checkout.session.expired had no deserialized object — apiVersion={}",
                    event.getApiVersion());
            return;
        }
        if (!(obj.get() instanceof Session session)) {
            log.warn("checkout.session.expired deserialized to unexpected type: {}",
                    obj.get().getClass().getName());
            return;
        }

        TierMeta tierMeta = parseTierMeta(session.getMetadata(), session.getId());
        if (tierMeta == null) return;
        inventoryService.releaseReservation(tierMeta.tierId, tierMeta.qty);
        log.info("Released reservation qty={} on tier {} after session {} expired",
                tierMeta.qty, tierMeta.tierId, session.getId());
    }

    private record TierMeta(UUID tierId, int qty) {}

    /**
     * Extract the {@code tier_id} + {@code qty} pair from event metadata. Returns null
     * (and logs a warning) when either is missing or unparseable — the caller treats this
     * as "legacy event, skip the inventory step."
     */
    private TierMeta parseTierMeta(Map<String, String> meta, String contextId) {
        if (meta == null) return null;
        String tierIdRaw = meta.get("tier_id");
        String qtyRaw = meta.get("qty");
        if (tierIdRaw == null || tierIdRaw.isBlank() || qtyRaw == null || qtyRaw.isBlank()) {
            log.warn("Event {} missing inventory metadata (tier_id/qty) — skipping inventory step", contextId);
            return null;
        }
        try {
            return new TierMeta(UUID.fromString(tierIdRaw), Integer.parseInt(qtyRaw));
        } catch (IllegalArgumentException e) {
            log.warn("Event {} has malformed inventory metadata (tier_id={}, qty={}): {}",
                    contextId, tierIdRaw, qtyRaw, e.getMessage());
            return null;
        }
    }
}
