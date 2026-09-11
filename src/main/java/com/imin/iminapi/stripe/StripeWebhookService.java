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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stripe webhook dispatcher. Two endpoints, two payload styles, two signing secrets.
 *
 * <ul>
 *   <li>{@link #handleV1Endpoint} — entry for {@code /api/v1/stripe/webhook/v1}.
 *       Parses V1 payloads via {@link Webhook#constructEvent} using
 *       {@code STRIPE_WEBHOOK_SECRET_V1}. Subscribes to {@code payment_intent.succeeded},
 *       {@code payment_intent.processing}, {@code payment_intent.payment_failed},
 *       {@code payment_intent.canceled}, {@code checkout.session.expired},
 *       {@code checkout.session.async_payment_succeeded},
 *       {@code checkout.session.async_payment_failed},
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
    private final com.imin.iminapi.dispute.DisputeIngestService disputeIngest;
    private final CheckoutAmountVerifier amountVerifier;
    private final Clock clock;

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
                                SettlementIngestService settlementIngest,
                                com.imin.iminapi.dispute.DisputeIngestService disputeIngest,
                                CheckoutAmountVerifier amountVerifier,
                                Clock clock) {
        this.stripeClient = stripeClient;
        this.props = props;
        this.promos = promos;
        this.inventoryService = inventoryService;
        this.dedup = dedup;
        this.paidCheckoutService = paidCheckoutService;
        this.refundService = refundService;
        this.settlementIngest = settlementIngest;
        this.disputeIngest = disputeIngest;
        this.amountVerifier = amountVerifier;
        this.clock = clock;
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

        // Scope gate. Both Dashboard endpoints post to this URL, so a Connect-scoped copy of a
        // fulfilment event would otherwise be fulfilled a second time under a different event id.
        if (!scopeMatches(type, event.getAccount())) {
            log.warn("[stripe-webhook] v1 wrong-scope type={} eventId={} account={} — ignoring",
                    type, eventId, event.getAccount());
            return;
        }

        switch (type) {
            case "payment_intent.succeeded" -> {
                log.info("[stripe-webhook] v1 dispatch → payment_intent.succeeded eventId={}", eventId);
                onPaymentIntentSucceeded(event);
            }
            case "payment_intent.processing" -> {
                log.info("[stripe-webhook] v1 dispatch → payment_intent.processing eventId={}", eventId);
                onPaymentIntentProcessing(event);
            }
            case "payment_intent.payment_failed" -> {
                log.info("[stripe-webhook] v1 dispatch → payment_intent.payment_failed eventId={}", eventId);
                onPaymentIntentFailed(event);
            }
            case "payment_intent.canceled" -> {
                log.info("[stripe-webhook] v1 dispatch → payment_intent.canceled eventId={}", eventId);
                onPaymentIntentCanceled(event);
            }
            case "checkout.session.expired" -> {
                log.info("[stripe-webhook] v1 dispatch → checkout.session.expired eventId={}", eventId);
                onCheckoutSessionExpired(event);
            }
            case "checkout.session.async_payment_failed" -> {
                log.info("[stripe-webhook] v1 dispatch → checkout.session.async_payment_failed eventId={}", eventId);
                onAsyncPaymentFailed(event);
            }
            // The async twin of checkout.session.completed: money moved, but fulfilment stays on
            // payment_intent.succeeded, which is the event that proves it.
            case "checkout.session.async_payment_succeeded" -> log.info(
                    "[stripe-webhook] v1 ignored type=checkout.session.async_payment_succeeded eventId={} — fulfilment is on payment_intent.succeeded",
                    eventId);
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

    /**
     * Fulfilment and refund events are ours: they describe money on the PLATFORM account, and a
     * connected-account copy of one is a duplicate we must not act on twice.
     */
    private static final Set<String> PLATFORM_SCOPED_TYPES = Set.of(
            "payment_intent.succeeded",
            "payment_intent.processing",
            "payment_intent.payment_failed",
            "payment_intent.canceled",
            "checkout.session.expired",
            "checkout.session.completed",
            "checkout.session.async_payment_succeeded",
            "checkout.session.async_payment_failed",
            "refund.updated",
            "refund.failed",
            "charge.refund.updated");

    /** Payouts settle ON the connected account, so a platform-scoped copy resolves to no org. */
    private static final Set<String> CONNECT_SCOPED_TYPES = Set.of(
            "payout.created", "payout.paid", "payout.failed");

    /**
     * Whether this delivery came in on the scope its handler is built for. {@code account} is
     * null on the "Your account" endpoint and the connected {@code acct_...} on the other.
     *
     * <p>{@code transfer.*}, {@code charge.refunded} and {@code charge.dispute.*} are deliberately
     * ungated: {@link SettlementIngestService} takes {@code event.getAccount()} as its org-resolution
     * fallback and its charge retrieve retries on the connected account, so both scopes are
     * supported ingestion paths, not duplicates.
     */
    private static boolean scopeMatches(String type, String account) {
        if (PLATFORM_SCOPED_TYPES.contains(type)) return account == null;
        if (CONNECT_SCOPED_TYPES.contains(type)) return account != null;
        return true;
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

        // Refuse to fulfil an amount we never priced. Nothing is confirmed, issued or
        // incremented; the reservation stays HELD and an operator picks it up from the log.
        CheckoutAmountVerifier.Result amount =
                amountVerifier.verify(meta, pi.getAmount(), pi.getCurrency());
        if (!amount.checked()) {
            // Never a refusal: a checkout created before the stamp existed is still a real,
            // paid order. WARN so a stamp that stops arriving after the deploy is visible.
            log.warn("[stripe-webhook] payment_intent.succeeded {} amount check skipped — {}",
                    pi.getId(), amount.skipReason());
        } else if (!amount.match()) {
            log.error("[AMOUNT_MISMATCH] paymentIntentId={} reservationId={} eventId={} tierId={} qty={} "
                            + "expected={} {} actual={} {}",
                    pi.getId(), reservationId, meta.get("event_id"), meta.get("tier_id"), meta.get("qty"),
                    amount.expectedMinor(), amount.expectedCurrency(), pi.getAmount(), pi.getCurrency());
            return;
        }

        // Resolve the buyer from Stripe BEFORE taking the tier lock. confirmSold below runs a
        // SELECT … FOR UPDATE on the ticket tier inside this transaction and holds it to commit,
        // and issuance needs up to two blocking Stripe round trips to find the buyer address.
        // Doing them under the lock queued every concurrent buyer of that tier behind Stripe's
        // latency (80s default read timeout) and could exhaust the pool during an on-sale. These
        // reads are pure lookups with no DB dependency, so hoisting them changes no ordering.
        PaidCheckoutService.BuyerResolution buyer = paidCheckoutService.prepareIssuance(pi);

        if (reservationId != null) {
            inventoryService.confirmSold(reservationId);
        } else {
            log.info("[stripe-webhook] payment_intent.succeeded {} has no reservation_id metadata — pre-V27 event, skipping inventory step",
                    pi.getId());
        }

        // Persist Order + N Ticket rows for the buyer. Idempotent on PI id, so a Stripe retry is a
        // noop. Publishes TicketsIssuedEvent on success; the @Async listener emails the buyer with
        // the tickets and QR. Returns true ONLY on the first successful issuance for this PI.
        boolean issued = paidCheckoutService.issuePaidOrder(pi, buyer);

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
                        // Either the code is gone or it is already at its cap — the capped
                        // UPDATE (events-8) refuses both. The ticket is issued regardless:
                        // money moved, and a redemption we cannot record is not the buyer's
                        // problem.
                        log.warn("Promo code {} not incremented for payment_intent {} — not found "
                                        + "or already at its usage cap; skipped",
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
     * {@code payment_intent.payment_failed} is NOT terminal for a card: the same PaymentIntent
     * stays payable inside its Checkout Session, so a buyer who fixes a decline or completes 3DS
     * on the second try must still own the seat. Releasing here handed it away and the later
     * {@code succeeded} landed on a RELEASED row — an {@code [OVERSOLD]} tier and a poller that
     * had already answered FAILED.
     *
     * <p>The one case that IS terminal is an async method (SEPA/iDEAL/Klarna) that failed days
     * after {@code payment_intent.processing}: that intent cannot be retried. Everything else
     * drains through {@code checkout.session.expired} or the {@code ReservationSweeper}.
     */
    private void onPaymentIntentFailed(com.stripe.model.Event event) {
        PaymentIntent pi = extractPaymentIntent(event, "payment_intent.payment_failed");
        if (pi == null) return;

        UUID reservationId = parseReservationId(pi.getMetadata());
        log.info("[stripe-webhook] payment_intent.payment_failed paymentIntentId={} reservationId={} lastError={}",
                pi.getId(), reservationId,
                pi.getLastPaymentError() == null ? null : pi.getLastPaymentError().getMessage());
        if (reservationId == null) {
            log.info("[stripe-webhook] payment_intent.payment_failed {} has no reservation_id metadata — pre-V27 event, skipping",
                    pi.getId());
            return;
        }
        if (inventoryService.isAsyncProcessing(reservationId)) {
            inventoryService.releaseReservation(reservationId, "WEBHOOK_FAILED");
            return;
        }
        log.info("[stripe-webhook] payment_intent.payment_failed {} reservation {} left HELD — the PaymentIntent is still retryable",
                pi.getId(), reservationId);
    }

    /**
     * {@code payment_intent.processing}: an async method (SEPA/iDEAL/Klarna) has been accepted and
     * will settle in days, not in the 30-minute checkout window. Push the hold out to
     * {@code imin.stripe.async-payment-hold-days} so the {@code ReservationSweeper} stops seeing
     * the row as expired, and record that this hold is now async — which is what makes a later
     * {@code payment_failed} on it terminal.
     */
    private void onPaymentIntentProcessing(com.stripe.model.Event event) {
        PaymentIntent pi = extractPaymentIntent(event, "payment_intent.processing");
        if (pi == null) return;

        UUID reservationId = parseReservationId(pi.getMetadata());
        log.info("[stripe-webhook] payment_intent.processing paymentIntentId={} reservationId={}",
                pi.getId(), reservationId);
        if (reservationId == null) {
            log.info("[stripe-webhook] payment_intent.processing {} has no reservation_id metadata — pre-V27 event, skipping",
                    pi.getId());
            return;
        }
        Instant newExpiry = clock.instant().plus(Duration.ofDays(props.getAsyncPaymentHoldDays()));
        inventoryService.markAsyncProcessing(reservationId, newExpiry);
    }

    /**
     * {@code payment_intent.canceled} is the deterministic terminal signal a failed attempt is not:
     * the intent can never be paid again, so the seats go back now instead of waiting for the
     * session TTL. It also closes the loop on {@code ReservationSweeper.cancelIfNativeIntent},
     * whose {@code paymentIntents().cancel} round-trips back here.
     */
    private void onPaymentIntentCanceled(com.stripe.model.Event event) {
        PaymentIntent pi = extractPaymentIntent(event, "payment_intent.canceled");
        if (pi == null) return;

        UUID reservationId = parseReservationId(pi.getMetadata());
        log.info("[stripe-webhook] payment_intent.canceled paymentIntentId={} reservationId={}",
                pi.getId(), reservationId);
        if (reservationId == null) {
            log.info("[stripe-webhook] payment_intent.canceled {} has no reservation_id metadata — pre-V27 event, skipping",
                    pi.getId());
            return;
        }
        inventoryService.releaseReservation(reservationId, "WEBHOOK_CANCELED");
    }

    /**
     * {@code checkout.session.async_payment_failed}: the SEPA/iDEAL/Klarna payment behind this
     * session definitively failed. Terminal — release the hold, whose expiry was pushed days out
     * by {@code payment_intent.processing}.
     */
    private void onAsyncPaymentFailed(com.stripe.model.Event event) {
        EventDataObjectDeserializer dod = event.getDataObjectDeserializer();
        Optional<StripeObject> obj = dod.getObject();
        if (obj.isEmpty()) {
            log.warn("checkout.session.async_payment_failed had no deserialized object — apiVersion={}",
                    event.getApiVersion());
            return;
        }
        if (!(obj.get() instanceof Session session)) {
            log.warn("checkout.session.async_payment_failed deserialized to unexpected type: {}",
                    obj.get().getClass().getName());
            return;
        }

        UUID reservationId = parseReservationId(session.getMetadata());
        log.info("[stripe-webhook] checkout.session.async_payment_failed sessionId={} reservationId={}",
                session.getId(), reservationId);
        if (reservationId != null) {
            inventoryService.releaseReservation(reservationId, "WEBHOOK_ASYNC_FAILED");
            return;
        }
        if (inventoryService.releaseReservationBySessionId(session.getId(), "WEBHOOK_ASYNC_FAILED")) {
            log.info("[stripe-webhook] released reservation for session {} via session-id fallback",
                    session.getId());
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
        // The payment intent, charge and amount travel with the status so a refund we never
        // created (organizer refunded from the Stripe Dashboard) can be back-resolved to its Order.
        refundService.handleWebhookStatusChange(
            stripeRefund.getId(),
            newStatus,
            stripeRefund.getFailureReason(),
            stripeRefund.getFailureReason(),   // Stripe Refund only exposes failure_reason
            stripeRefund.getPaymentIntent(),
            stripeRefund.getCharge(),
            stripeRefund.getAmount());
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
        settlementIngest.ingestTransfer(transfer, event.getAccount(), reversed, createdAt(event));
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
        settlementIngest.ingestPayout(payout, event.getAccount(), createdAt(event));
    }

    /**
     * Handle {@code charge.refunded}: a refund clawed back funds that backed a destination-charge
     * transfer. Mirror the reversal onto the backing transfer's settlement row so the read-model
     * reflects it. Partial refunds fire this repeatedly; the upsert converges on one row.
     */
    private void onChargeRefunded(com.stripe.model.Event event) {
        com.stripe.model.Charge charge = extractCharge(event, "charge.refunded");
        if (charge == null) return;
        settlementIngest.ingestChargeRefunded(charge, event.getAccount(), createdAt(event));
    }

    /**
     * Handle the {@code charge.dispute.*} family, which has two independent consumers. The
     * settlements ingest annotates the read-model behind the Payouts UI; the dispute ingest
     * owns the {@code disputes} registry — it revokes the buyer's tickets, tells the organizer,
     * and is what the payout guard and the per-event net reduction read. Order matters only for
     * the logs; both share this handler's transaction.
     */
    private void onDispute(com.stripe.model.Event event, String eventType) {
        com.stripe.model.Dispute dispute = extractDispute(event, eventType);
        if (dispute == null) return;
        settlementIngest.ingestDispute(dispute, event.getAccount(), eventType, createdAt(event));
        disputeIngest.ingest(dispute, event.getAccount(), eventType, createdAt(event));
    }

    /**
     * The Stripe {@code event.created} timestamp, used by the settlements read-model to drop an
     * out-of-order delivery instead of letting it rewrite settled state. Null when Stripe
     * omitted it (no ordering information — the ingest then falls back to its terminal guards).
     */
    private static java.time.Instant createdAt(com.stripe.model.Event event) {
        return event.getCreated() == null ? null : java.time.Instant.ofEpochSecond(event.getCreated());
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
