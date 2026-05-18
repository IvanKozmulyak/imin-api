package com.imin.iminapi.stripe;

import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.event.InventoryService;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.model.v2.core.Event;
import com.stripe.model.v2.core.EventNotification;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles Stripe webhook deliveries on {@code POST /api/v1/stripe/webhook}.
 *
 * <p>This single endpoint handles both Stripe webhook formats:
 * <ul>
 *   <li><b>V1 events</b> ({@code checkout.session.completed}, {@code payment_intent.succeeded}, …)
 *       — parsed by {@link Webhook#constructEvent}.</li>
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
 */
@Service
public class StripeWebhookService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookService.class);

    private final StripeClient stripeClient;
    private final StripeProperties props;
    private final PromoCodeRepository promos;
    private final InventoryService inventoryService;

    public StripeWebhookService(StripeClient stripeClient,
                                StripeProperties props,
                                PromoCodeRepository promos,
                                InventoryService inventoryService) {
        this.stripeClient = stripeClient;
        this.props = props;
        this.promos = promos;
        this.inventoryService = inventoryService;
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
            handleV1(rawBody, sigHeader, secret);
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

    private void handleV1(String rawBody, String sigHeader, String secret) {
        com.stripe.model.Event event;
        try {
            event = Webhook.constructEvent(rawBody, sigHeader, secret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe v1 webhook signature verification failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid Stripe signature");
        }

        String type = event.getType();
        log.info("Stripe v1 webhook received: type={} id={}", type, event.getId());

        switch (type) {
            case "checkout.session.completed" -> onCheckoutSessionCompleted(event);
            case "checkout.session.expired" -> onCheckoutSessionExpired(event);
            // payment_intent.succeeded is a secondary signal — checkout.session.completed
            // is what we key off for promo redemption. PI events still get ack'd silently.
            default -> { /* ignored, ack with 200 so Stripe stops retrying */ }
        }
    }

    private void onCheckoutSessionCompleted(com.stripe.model.Event event) {
        EventDataObjectDeserializer dod = event.getDataObjectDeserializer();
        Optional<StripeObject> obj = dod.getObject();
        if (obj.isEmpty()) {
            log.warn("checkout.session.completed had no deserialized object — apiVersion={}",
                    event.getApiVersion());
            return;
        }
        if (!(obj.get() instanceof Session session)) {
            log.warn("checkout.session.completed deserialized to unexpected type: {}",
                    obj.get().getClass().getName());
            return;
        }

        // Only paid sessions count as a promo redemption. Sessions with async payment
        // methods can complete with payment_status="unpaid" and finalize later via
        // checkout.session.async_payment_succeeded — we'd need to handle that event
        // separately if/when we accept those payment methods.
        if (!"paid".equals(session.getPaymentStatus())) {
            log.info("checkout.session.completed but paymentStatus={} — skipping promo increment",
                    session.getPaymentStatus());
            return;
        }

        Map<String, String> meta = session.getMetadata();

        // Inventory: promote the reservation to a confirmed sale. Legacy sessions
        // created before the inventory metadata existed will simply skip this step.
        // TODO: dedupe via processed_webhook_events
        TierMeta tierMeta = parseTierMeta(meta, session.getId());
        if (tierMeta != null) {
            inventoryService.confirmSold(tierMeta.tierId, tierMeta.qty);
            log.info("Confirmed sold qty={} on tier {} after session {}",
                    tierMeta.qty, tierMeta.tierId, session.getId());
        }

        if (meta == null) return;
        String promoIdRaw = meta.get("promo_id");
        if (promoIdRaw == null || promoIdRaw.isBlank()) {
            // No promo was applied to this session — nothing to do.
            return;
        }
        UUID promoId;
        try {
            promoId = UUID.fromString(promoIdRaw);
        } catch (IllegalArgumentException e) {
            log.warn("Session {} has malformed promo_id metadata: {}", session.getId(), promoIdRaw);
            return;
        }

        // Stripe delivers webhooks at-least-once, so this can double-count if Stripe retries
        // after a successful processing. The proper fix is a `processed_webhook_events` table
        // keyed on event id; for now the impact is bounded (a redelivery would push usedCount
        // ahead, possibly tripping maxUses slightly early, but never under-count).
        int rows = promos.incrementUsedCount(promoId);
        if (rows == 0) {
            log.warn("Promo code {} not found when handling session {} — skipped",
                    promoId, session.getId());
        } else {
            log.info("Incremented usedCount on promo {} after session {}",
                    promoId, session.getId());
        }
    }

    /**
     * Handle {@code checkout.session.expired}: the buyer never paid, so the seat hold
     * must be released back to the pool. Legacy sessions without inventory metadata are
     * skipped (nothing to release).
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

        // TODO: dedupe via processed_webhook_events
        TierMeta tierMeta = parseTierMeta(session.getMetadata(), session.getId());
        if (tierMeta == null) return;
        inventoryService.releaseReservation(tierMeta.tierId, tierMeta.qty);
        log.info("Released reservation qty={} on tier {} after session {} expired",
                tierMeta.qty, tierMeta.tierId, session.getId());
    }

    private record TierMeta(UUID tierId, int qty) {}

    /**
     * Extract the {@code tier_id} + {@code qty} pair from session metadata. Returns null
     * (and logs a warning) when either is missing or unparseable — the caller treats this
     * as "legacy session, skip the inventory step."
     */
    private TierMeta parseTierMeta(Map<String, String> meta, String sessionId) {
        if (meta == null) return null;
        String tierIdRaw = meta.get("tier_id");
        String qtyRaw = meta.get("qty");
        if (tierIdRaw == null || tierIdRaw.isBlank() || qtyRaw == null || qtyRaw.isBlank()) {
            log.warn("Session {} missing inventory metadata (tier_id/qty) — skipping inventory step", sessionId);
            return null;
        }
        try {
            return new TierMeta(UUID.fromString(tierIdRaw), Integer.parseInt(qtyRaw));
        } catch (IllegalArgumentException e) {
            log.warn("Session {} has malformed inventory metadata (tier_id={}, qty={}): {}",
                    sessionId, tierIdRaw, qtyRaw, e.getMessage());
            return null;
        }
    }
}
