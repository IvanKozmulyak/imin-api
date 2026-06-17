package com.imin.iminapi.stripe;

import com.imin.iminapi.refund.RefundService;
import com.imin.iminapi.refund.RefundStatus;
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
 * Stripe webhook dispatcher. Two endpoints, two payload styles, two signing secrets.
 *
 * <ul>
 *   <li>{@link #handleV1Endpoint} — entry for {@code /api/v1/stripe/webhook/v1}.
 *       Parses V1 payloads via {@link Webhook#constructEvent} using
 *       {@code STRIPE_WEBHOOK_SECRET_V1}. Subscribes to {@code payment_intent.succeeded},
 *       {@code payment_intent.payment_failed}, {@code checkout.session.expired},
 *       {@code refund.updated}, {@code refund.failed}, {@code charge.refund.updated}, and the
 *       Track A settlements-ingestion events {@code transfer.created}, {@code transfer.reversed},
 *       {@code payout.created}, {@code payout.paid}, {@code payout.failed}, {@code charge.refunded},
 *       and the {@code charge.dispute.*} family ({@code created}, {@code closed},
 *       {@code funds_withdrawn}, {@code funds_reinstated}).</li>
 *   <li>{@link #handleV2Endpoint} — entry for {@code /api/v1/stripe/webhook/v2}.
 *       Parses V2 thin events via {@link StripeClient#parseEventNotification} using
 *       {@code STRIPE_WEBHOOK_SECRET_V2}. Subscribes to the bracket-notation account events
 *       Stripe actually emits, e.g. {@code v2.core.account[requirements].updated} and
 *       {@code v2.core.account[configuration.recipient].capability_status_updated}
 *       (see {@link #V2_ACCOUNT_STATE_TYPES}).</li>
 * </ul>
 *
 * <p><b>Idempotency.</b> Stripe delivers events at-least-once. {@link #handleV1Endpoint}
 * INSERTs the event id into {@code processed_webhook_events} as the first thing inside
 * its transaction — a duplicate-key means the event was already processed and we
 * log+ack. V2 thin events are idempotent by design (they're queries into Stripe's
 * own state, not state mutations on our side), so no dedup gate is applied.
 *
 * <p><b>Inventory resolution.</b> Webhook handlers prefer the
 * {@code reservation_id} metadata stamped by {@link StripeCheckoutService} when the
 * Session was created. Falling back to {@code session.id} lookup handles in-flight
 * sessions that were created before the metadata-passthrough deploy; events with
 * neither identifier are pre-V27 legacy holds and are skipped (the V27 migration
 * already wiped their counter contributions).
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
    private final RefundService refundService;
    private final SettlementIngestService settlementIngest;

    /**
     * Proxied self-reference so {@link #handleV1Endpoint} can invoke
     * {@link #handleV1Transactional} through Spring's transaction proxy. A direct
     * {@code this.handleV1Transactional(...)} call would bypass the proxy and
     * silently drop the {@code @Transactional} advice, losing the rollback-on-
     * failure idempotency contract. Injected with {@code @Lazy} to break the
     * circular dependency at startup.
     *
     * <p>In unit tests this field stays null and the fallback at the call site
     * drops back to a direct call — fine, because tests inject a mock
     * {@link WebhookEventDedupService} directly rather than relying on the JDBC transaction.
     */
    private StripeWebhookService self;

    /** Optional collaborator — null in unit tests that don't exercise the v2 connect path. */
    private StripeConnectStatusMirror connectMirror;

    @Autowired(required = false)
    public void setConnectMirror(StripeConnectStatusMirror connectMirror) {
        this.connectMirror = connectMirror;
    }

    public StripeWebhookService(StripeClient stripeClient,
                                StripeProperties props,
                                PromoCodeRepository promos,
                                InventoryService inventoryService,
                                WebhookEventDedupService dedup,
                                PaidCheckoutService paidCheckoutService,
                                RefundService refundService,
                                SettlementIngestService settlementIngest) {
        this.stripeClient = stripeClient;
        this.props = props;
        this.promos = promos;
        this.inventoryService = inventoryService;
        this.dedup = dedup;
        this.paidCheckoutService = paidCheckoutService;
        this.refundService = refundService;
        this.settlementIngest = settlementIngest;
    }

    @Autowired
    void setSelf(@Lazy StripeWebhookService self) {
        this.self = self;
    }

    // ── V1 endpoint ────────────────────────────────────────────────────────────

    /**
     * Entry point for {@code POST /api/v1/stripe/webhook/v1}.
     *
     * @param rawBody   unmodified request body (signature is computed over the exact bytes).
     * @param sigHeader value of the {@code Stripe-Signature} header.
     */
    public void handleV1Endpoint(String rawBody, String sigHeader) {
        String secret = props.getWebhookSecretV1();
        requireSecret(secret, "STRIPE_WEBHOOK_SECRET_V1");
        requireSignature(sigHeader);

        // Route through the proxied self-reference so @Transactional on
        // handleV1Transactional is honored in production. Tests bypass Spring
        // entirely and self is null; fall back to a direct call.
        StripeWebhookService target = self == null ? this : self;
        target.handleV1Transactional(rawBody, sigHeader, secret);
    }

    /**
     * Verify a V1 webhook against the account-scope secret first, then the optional
     * Connect-scope secret. imin runs TWO Dashboard endpoints on this same {@code /webhook/v1}
     * URL: a "Your account" endpoint ({@code STRIPE_WEBHOOK_SECRET_V1} — platform events incl.
     * {@code transfer.*}/{@code charge.refunded}/{@code charge.dispute.*}) and a "Connected
     * accounts" endpoint ({@code STRIPE_WEBHOOK_SECRET_CONNECT} — {@code payout.*}, which fire
     * on the connected account). Each endpoint signs with its OWN secret, so we try both.
     * The Connect secret is optional: when blank this is identical to single-secret verification.
     */
    private com.stripe.model.Event constructV1Event(String rawBody, String sigHeader, String primarySecret) {
        try {
            return Webhook.constructEvent(rawBody, sigHeader, primarySecret);
        } catch (SignatureVerificationException primaryFail) {
            String connectSecret = props.getWebhookSecretConnect();
            boolean haveConnect = connectSecret != null && !connectSecret.isBlank();
            if (haveConnect) {
                try {
                    return Webhook.constructEvent(rawBody, sigHeader, connectSecret);
                } catch (SignatureVerificationException connectFail) {
                    // both secrets failed — fall through to the error below
                }
            }
            log.warn("Stripe v1 webhook signature verification failed (tried v1{}): {}",
                    haveConnect ? "+connect" : "", primaryFail.getMessage());
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Invalid Stripe signature");
        }
    }

    /**
     * Dispatch a V1 webhook.
     *
     * <p>{@code Propagation.REQUIRED} keeps the dedup INSERT and any handler DB writes
     * in a single transaction — on a handler exception the INSERT rolls back, so
     * Stripe's retry will re-process from scratch rather than seeing a phantom
     * "already done" marker.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void handleV1Transactional(String rawBody, String sigHeader, String secret) {
        com.stripe.model.Event event = constructV1Event(rawBody, sigHeader, secret);

        String type = event.getType();
        String eventId = event.getId();
        String apiVersion = event.getApiVersion();
        log.info("[stripe-webhook] v1 parsed type={} id={} apiVersion={} createdAt={}",
                type, eventId, apiVersion, event.getCreated());

        if (!dedup.tryRecord(eventId, type)) {
            log.info("[stripe-webhook] v1 dedup-hit eventId={} type={} — skipping (already processed)",
                    eventId, type);
            return;
        }

        switch (type) {
            case "payment_intent.succeeded" -> {
                log.info("[stripe-webhook] v1 dispatch → payment_intent.succeeded eventId={}", eventId);
                onPaymentIntentSucceeded(event);
            }
            case "payment_intent.payment_failed" -> {
                log.info("[stripe-webhook] v1 dispatch → payment_intent.payment_failed eventId={}", eventId);
                onPaymentIntentFailed(event);
            }
            case "checkout.session.expired" -> {
                log.info("[stripe-webhook] v1 dispatch → checkout.session.expired eventId={}", eventId);
                onCheckoutSessionExpired(event);
            }
            // refund.updated / refund.failed are the unified events that fire for ALL refund
            // types (Stripe Acacia 2024-10-28); charge.refund.updated is the legacy alias that
            // only fires for "selected payment methods". All three carry a Refund as
            // data.object, so route them identically. processed_webhook_events dedups overlapping
            // deliveries and RefundService's status-conditional UPDATE makes a duplicate
            // transition a no-op — so subscribing to all three is safe and closes the gap where
            // refunds on non-"selected" payment methods never transitioned out of PENDING.
            case "charge.refund.updated", "refund.updated", "refund.failed" -> {
                log.info("[stripe-webhook] v1 dispatch → {} eventId={}", type, eventId);
                onChargeRefundUpdated(event);
            }
            // ── Track A settlements read-model ingestion ──
            // transfer.* / payout.* / charge.refunded / charge.dispute.* mirror Stripe's payout
            // state into the `settlements` read-model so the /payouts endpoints can be served from
            // our DB. These move NO money — fulfilment + refund money flow stays on the cases above.
            case "transfer.created" -> {
                log.info("[stripe-webhook] v1 dispatch → transfer.created eventId={}", eventId);
                onTransfer(event, false);
            }
            case "transfer.reversed" -> {
                log.info("[stripe-webhook] v1 dispatch → transfer.reversed eventId={}", eventId);
                onTransfer(event, true);
            }
            case "payout.created", "payout.paid", "payout.failed" -> {
                log.info("[stripe-webhook] v1 dispatch → {} eventId={}", type, eventId);
                onPayout(event);
            }
            case "charge.refunded" -> {
                log.info("[stripe-webhook] v1 dispatch → charge.refunded eventId={}", eventId);
                onChargeRefunded(event);
            }
            case "charge.dispute.created", "charge.dispute.closed",
                 "charge.dispute.funds_withdrawn", "charge.dispute.funds_reinstated" -> {
                log.info("[stripe-webhook] v1 dispatch → {} eventId={}", type, eventId);
                onDispute(event, type);
            }
            // checkout.session.completed is intentionally a no-op. Fulfilment moved
            // to payment_intent.succeeded because PI is what tells us the money
            // actually moved (Session.completed can fire for unpaid async sessions
            // and adds an extra latency hop on the happy path).
            case "checkout.session.completed" -> log.info(
                    "[stripe-webhook] v1 ignored type=checkout.session.completed eventId={} — fulfilment is on payment_intent.succeeded",
                    eventId);
            default -> log.info("[stripe-webhook] v1 ignored type={} eventId={} — not subscribed",
                    type, eventId);
        }
    }

    // ── V2 endpoint ────────────────────────────────────────────────────────────

    /** Entry point for {@code POST /api/v1/stripe/webhook/v2}. */
    public void handleV2Endpoint(String rawBody, String sigHeader) {
        String secret = props.getWebhookSecretV2();
        requireSecret(secret, "STRIPE_WEBHOOK_SECRET_V2");
        requireSignature(sigHeader);

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
        log.info("[stripe-webhook] v2 parsed type={} id={}", type, id);

        // Stripe delivers v2 core account events in BRACKET notation, e.g.
        // "v2.core.account[requirements].updated" and
        // "v2.core.account[configuration.recipient].capability_status_updated"
        // (NOT the dot-notation we previously matched, which never fired — leaving the
        // Connect mirror permanently stale). Match the whole v2.core.account[...] family by
        // prefix: every such event is just a "go re-fetch the account" notification, and
        // syncFromStripe is idempotent, so handling the full family is safe and future-proof
        // against Stripe adding new sub-resource events. See V2_ACCOUNT_STATE_TYPES for the
        // canonical list to subscribe in the Dashboard.
        if (type != null && type.startsWith("v2.core.account")) {
            try {
                Event full = stripeClient.v2().core().events().retrieve(id);
                log.info("[stripe-webhook] v2 account-state event type={} eventId={} created={}",
                        full.getType(), full.getId(), full.getCreated());
                String accountId = extractRelatedObjectId(full);
                if (accountId == null) {
                    log.warn("[stripe-webhook] v2 account-state event {} has no related_object.id — cannot mirror", id);
                } else if (connectMirror == null) {
                    log.warn("[stripe-webhook] v2 account-state event {} — connect mirror not wired", id);
                } else {
                    connectMirror.syncFromStripe(accountId);
                }
            } catch (StripeException e) {
                // Don't ack 200 on a fetch failure — that silently drops the state change and
                // Stripe never retries. Surface a non-2xx so Stripe re-delivers; the
                // StripeConnectStatusSweeper is the slower backstop if retries are also exhausted.
                log.warn("[stripe-webhook] v2 failed to fetch full event id={} type={} — {} (returning 502 for retry)",
                        id, type, e.getMessage());
                throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNAVAILABLE,
                        "Failed to fetch Stripe v2 event");
            }
        } else {
            log.info("[stripe-webhook] v2 ignored type={} id={} — not subscribed", type, id);
        }
    }

    /**
     * Canonical v2 thin-event types to subscribe the {@code /webhook/v2} endpoint to in the
     * Stripe Dashboard. Bracket notation is the literal {@code event.type} Stripe sends.
     * Matching in {@link #handleV2Endpoint} is by {@code "v2.core.account"} prefix so this set
     * is documentation, not the gate.
     */
    static final java.util.Set<String> V2_ACCOUNT_STATE_TYPES = java.util.Set.of(
            "v2.core.account[requirements].updated",
            "v2.core.account[configuration.recipient].capability_status_updated",
            "v2.core.account[configuration.recipient].updated",
            "v2.core.account[future_requirements].updated",
            "v2.core.account.updated");

    /**
     * Pull the {@code related_object.id} off a v2 Event. The base {@link Event} class
     * doesn't expose it (each concrete subclass like
     * {@code V2CoreAccountIncludingRequirementsUpdatedEvent} adds its own field), so we
     * reflectively read whatever concrete subtype Stripe's deserializer produced. Returns
     * null if the field is absent or unreadable.
     */
    private static String extractRelatedObjectId(Event event) {
        if (event == null) return null;
        try {
            var method = event.getClass().getMethod("getRelatedObject");
            Object related = method.invoke(event);
            if (related == null) return null;
            var idMethod = related.getClass().getMethod("getId");
            Object idValue = idMethod.invoke(related);
            return idValue == null ? null : idValue.toString();
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    // ── shared validation ─────────────────────────────────────────────────────

    private static void requireSecret(String secret, String envVarName) {
        if (secret == null || secret.isBlank()) {
            // 503 — we can't verify, so reject. The operator should set the env var.
            log.warn("Refusing Stripe webhook: {} not configured", envVarName);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "Stripe webhook handler not configured");
        }
    }

    private static void requireSignature(String sigHeader) {
        if (sigHeader == null || sigHeader.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                    "Missing Stripe-Signature header");
        }
    }

    // ── V1 handlers ────────────────────────────────────────────────────────────

    /**
     * Fulfilment: PI succeeded → promote the reservation to a confirmed sale, and
     * (when applicable) increment the promo's used_count. Reads the same
     * {@code reservation_id} / {@code promo_id} metadata that
     * {@link StripeCheckoutService} stamps onto both the Session and the PI.
     */
    private void onPaymentIntentSucceeded(com.stripe.model.Event event) {
        PaymentIntent pi = extractPaymentIntent(event, "payment_intent.succeeded");
        if (pi == null) return;

        Map<String, String> meta = pi.getMetadata();
        UUID reservationId = parseReservationId(meta);
        log.info("[stripe-webhook] payment_intent.succeeded paymentIntentId={} reservationId={} amount={} currency={}",
                pi.getId(), reservationId, pi.getAmount(), pi.getCurrency());
        if (reservationId != null) {
            inventoryService.confirmSold(reservationId);
        } else {
            log.info("[stripe-webhook] payment_intent.succeeded {} has no reservation_id metadata — pre-V27 event, skipping inventory step",
                    pi.getId());
        }

        // Persist Order + N Ticket rows for the buyer. Idempotent on PI id, so a Stripe retry is a
        // noop. Publishes TicketsIssuedEvent on success; the @Async listener emails the buyer with
        // the tickets and QR. Returns true ONLY on the first successful issuance for this PI.
        boolean issued = paidCheckoutService.issuePaidOrder(pi);

        // Increment promo usage ONLY on first issuance, tying it to the same idempotency boundary
        // as Order creation — so a second, distinct-event-id delivery for the same PI that slips
        // past the event-id dedup can't double-count a redemption.
        if (issued && meta != null) {
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

        UUID reservationId = parseReservationId(pi.getMetadata());
        log.info("[stripe-webhook] payment_intent.payment_failed paymentIntentId={} reservationId={} lastError={}",
                pi.getId(), reservationId,
                pi.getLastPaymentError() == null ? null : pi.getLastPaymentError().getMessage());
        if (reservationId != null) {
            inventoryService.releaseReservation(reservationId, "WEBHOOK_FAILED");
        } else {
            log.info("[stripe-webhook] payment_intent.payment_failed {} has no reservation_id metadata — pre-V27 event, skipping",
                    pi.getId());
        }
    }

    /**
     * Handle {@code checkout.session.expired}: the buyer never paid, so the seat
     * hold must be released back to the pool. Idempotency is enforced at the
     * top of {@link #handleV1Transactional} so a delivered-twice expiry can't
     * double-release.
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

        UUID reservationId = parseReservationId(session.getMetadata());
        log.info("[stripe-webhook] checkout.session.expired sessionId={} reservationId={}",
                session.getId(), reservationId);
        if (reservationId != null) {
            inventoryService.releaseReservation(reservationId, "WEBHOOK_EXPIRED");
            return;
        }
        // Fallback: legacy session created before the metadata-passthrough deploy
        // but still within Stripe's retention window. Resolve via session id.
        if (inventoryService.releaseReservationBySessionId(session.getId(), "WEBHOOK_EXPIRED")) {
            log.info("[stripe-webhook] released reservation for session {} via session-id fallback",
                    session.getId());
        }
    }

    /**
     * Handle {@code charge.refund.updated}: Stripe is telling us a Refund changed
     * status (PENDING → SUCCEEDED, or PENDING → FAILED). The event's
     * {@code data.object} IS the Refund (Stripe nests the refund inside the
     * charge.refund.updated event directly, not the parent Charge).
     *
     * <p>Idempotency is enforced two ways: the {@link WebhookEventDedupService}
     * at the top of {@link #handleV1Transactional} no-ops replays of the same
     * {@code event.id}, and {@link RefundService#handleWebhookStatusChange}
     * uses a status-conditional UPDATE so only one transaction wins the
     * transition.
     */
    private void onChargeRefundUpdated(com.stripe.model.Event event) {
        EventDataObjectDeserializer dod = event.getDataObjectDeserializer();
        Optional<StripeObject> obj = dod.getObject();
        if (obj.isEmpty()) {
            log.warn("charge.refund.updated had no deserialized object — apiVersion={}",
                event.getApiVersion());
            return;
        }
        if (!(obj.get() instanceof com.stripe.model.Refund stripeRefund)) {
            log.warn("charge.refund.updated deserialized to unexpected type: {}",
                obj.get().getClass().getName());
            return;
        }
        RefundStatus newStatus = RefundStatus.fromStripe(stripeRefund.getStatus());
        log.info("[stripe-webhook] charge.refund.updated refundId={} status={} mapped={}",
            stripeRefund.getId(), stripeRefund.getStatus(), newStatus);
        refundService.handleWebhookStatusChange(
            stripeRefund.getId(),
            newStatus,
            stripeRefund.getFailureReason(),
            stripeRefund.getFailureReason());   // Stripe Refund only exposes failure_reason
    }

    // ── Track A settlements ingestion handlers ──────────────────────────────────

    /**
     * Handle {@code transfer.created} / {@code transfer.reversed}: mirror the Stripe
     * {@link com.stripe.model.Transfer} into the settlements read-model. Org is the transfer
     * destination ({@code acct_...}); {@code event.getAccount()} is passed as the fallback for
     * connected-account-scoped deliveries. Delegates to {@link SettlementIngestService}, which
     * runs in this same transaction and upserts idempotently on the transfer id.
     */
    private void onTransfer(com.stripe.model.Event event, boolean reversed) {
        com.stripe.model.Transfer transfer = extractTransfer(event,
                reversed ? "transfer.reversed" : "transfer.created");
        if (transfer == null) return;
        settlementIngest.ingestTransfer(transfer, event.getAccount(), reversed);
    }

    /**
     * Handle {@code payout.created} / {@code payout.paid} / {@code payout.failed}: mirror the
     * Stripe {@link com.stripe.model.Payout} into the settlements read-model. Payouts settle ON
     * the connected account, so the org is resolved from {@code event.getAccount()} — the payout
     * itself carries no destination-org field.
     */
    private void onPayout(com.stripe.model.Event event) {
        com.stripe.model.Payout payout = extractPayout(event, "payout.*");
        if (payout == null) return;
        settlementIngest.ingestPayout(payout, event.getAccount());
    }

    /**
     * Handle {@code charge.refunded}: a refund clawed back funds that backed a destination-charge
     * transfer. Mirror the reversal onto the backing transfer's settlement row so the read-model
     * reflects it. Partial refunds fire this repeatedly; the upsert converges on one row.
     */
    private void onChargeRefunded(com.stripe.model.Event event) {
        com.stripe.model.Charge charge = extractCharge(event, "charge.refunded");
        if (charge == null) return;
        settlementIngest.ingestChargeRefunded(charge, event.getAccount());
    }

    /**
     * Handle the {@code charge.dispute.*} family: a dispute puts settled funds at risk (or
     * reinstates them). Annotate the settlements read-model with the disputed amount + status.
     * All four event types deliver a {@link com.stripe.model.Dispute} as {@code data.object}; the
     * {@code eventType} string drives the won/lost/withdrawn/reinstated branch in the ingest service.
     */
    private void onDispute(com.stripe.model.Event event, String eventType) {
        com.stripe.model.Dispute dispute = extractDispute(event, eventType);
        if (dispute == null) return;
        settlementIngest.ingestDispute(dispute, event.getAccount(), eventType);
    }

    private com.stripe.model.Transfer extractTransfer(com.stripe.model.Event event, String label) {
        EventDataObjectDeserializer dod = event.getDataObjectDeserializer();
        Optional<StripeObject> obj = dod.getObject();
        if (obj.isEmpty()) {
            log.warn("{} had no deserialized object — apiVersion={}", label, event.getApiVersion());
            return null;
        }
        if (!(obj.get() instanceof com.stripe.model.Transfer transfer)) {
            log.warn("{} deserialized to unexpected type: {}", label, obj.get().getClass().getName());
            return null;
        }
        return transfer;
    }

    private com.stripe.model.Payout extractPayout(com.stripe.model.Event event, String label) {
        EventDataObjectDeserializer dod = event.getDataObjectDeserializer();
        Optional<StripeObject> obj = dod.getObject();
        if (obj.isEmpty()) {
            log.warn("{} had no deserialized object — apiVersion={}", label, event.getApiVersion());
            return null;
        }
        if (!(obj.get() instanceof com.stripe.model.Payout payout)) {
            log.warn("{} deserialized to unexpected type: {}", label, obj.get().getClass().getName());
            return null;
        }
        return payout;
    }

    private com.stripe.model.Charge extractCharge(com.stripe.model.Event event, String label) {
        EventDataObjectDeserializer dod = event.getDataObjectDeserializer();
        Optional<StripeObject> obj = dod.getObject();
        if (obj.isEmpty()) {
            log.warn("{} had no deserialized object — apiVersion={}", label, event.getApiVersion());
            return null;
        }
        if (!(obj.get() instanceof com.stripe.model.Charge charge)) {
            log.warn("{} deserialized to unexpected type: {}", label, obj.get().getClass().getName());
            return null;
        }
        return charge;
    }

    private com.stripe.model.Dispute extractDispute(com.stripe.model.Event event, String label) {
        EventDataObjectDeserializer dod = event.getDataObjectDeserializer();
        Optional<StripeObject> obj = dod.getObject();
        if (obj.isEmpty()) {
            log.warn("{} had no deserialized object — apiVersion={}", label, event.getApiVersion());
            return null;
        }
        if (!(obj.get() instanceof com.stripe.model.Dispute dispute)) {
            log.warn("{} deserialized to unexpected type: {}", label, obj.get().getClass().getName());
            return null;
        }
        return dispute;
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
     * Extract the {@code reservation_id} from event metadata. Returns null
     * (and logs) when missing or unparseable — the caller can fall back to a
     * session-id lookup or treat it as a legacy event.
     */
    private UUID parseReservationId(Map<String, String> meta) {
        if (meta == null) return null;
        String raw = meta.get("reservation_id");
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Malformed reservation_id metadata: {}", raw);
            return null;
        }
    }
}
