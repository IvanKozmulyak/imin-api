package com.imin.iminapi.refund;

import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.refund.event.RefundFailedEvent;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeProperties;
import com.imin.iminapi.stripe.StripeRefundService;
import com.stripe.exception.StripeException;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Refund chokepoint. All refund creation flows through {@link #createRefund} —
 * validation, authorization, persistence, and the Stripe API call live here.
 *
 * <p>Two phases. {@link RefundAttemptStore#open} commits the REQUESTED row and its ticket claims
 * BEFORE Stripe is called, and Stripe's answer is recorded in a second committed transaction, so
 * a rollback can never erase a refund Stripe already made and a retry with any key, ticket set or
 * amount hits the committed claim. An answer that does not prove whether a refund exists leaves the
 * row REQUESTED (409 {@code REFUND_IN_PROGRESS}); {@link RefundAttemptReconciler} resolves it by
 * listing the PaymentIntent's refunds for {@code metadata.imin_refund_id}. Claims are released
 * only once Stripe has refused or the refund object failed.
 *
 * <p>Inventory is released by whichever path first moves the row to SUCCEEDED (the synchronous
 * answer, the reconciler, or the refund webhook); each move is a status-conditional UPDATE.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    /** The outcome at Stripe is unknown and the reconciler will resolve it. */
    static final String IN_PROGRESS_MESSAGE = "imin could not confirm this refund with Stripe yet. Its tickets "
        + "stay reserved for it and imin checks with Stripe again every few minutes, so do not issue it again.";
    /** A reconciler refusal: Stripe holds no refund and the order is now charged back. */
    static final String DISPUTED_UNSENT_MESSAGE =
        "Stripe has no record of this refund and the order is now disputed, so imin did not send it.";
    static final String IDEMPOTENCY_CONSTRAINT = "refunds_order_idem_unique";
    /** Above the live call's 110 s ceiling (30 s connect + 80 s read), so a call still in flight is never raced. */
    public static final java.time.Duration RECONCILE_MIN_AGE = java.time.Duration.ofMinutes(5);

    /** What one reconciler pass did with one row. */
    public enum Resolution { SKIPPED, ADOPTED, RESENT, REFUSED, UNRESOLVED }

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final RefundRepository refunds;
    private final RefundTicketRepository refundTickets;
    private final StripeRefundService stripeRefundService;
    private final DisputeRepository disputes;
    private final ApplicationEventPublisher publisher;
    private final StripeProperties stripeProps;
    private final RefundAttemptStore store;
    private final RefundInventoryRelease inventoryRelease;
    private final Clock clock;

    public RefundService(OrderRepository orders,
                         TicketRepository tickets,
                         RefundRepository refunds,
                         RefundTicketRepository refundTickets,
                         StripeRefundService stripeRefundService,
                         DisputeRepository disputes,
                         ApplicationEventPublisher publisher,
                         StripeProperties stripeProps,
                         RefundAttemptStore store,
                         RefundInventoryRelease inventoryRelease,
                         Clock clock) {
        this.orders = orders;
        this.tickets = tickets;
        this.refunds = refunds;
        this.refundTickets = refundTickets;
        this.stripeRefundService = stripeRefundService;
        this.disputes = disputes;
        this.publisher = publisher;
        this.stripeProps = stripeProps;
        this.store = store;
        this.inventoryRelease = inventoryRelease;
        this.clock = clock;
    }

    /**
     * Not transactional on purpose: the attempt row commits before Stripe is called and the outcome
     * commits after, each through {@link RefundAttemptStore}. Joining a caller's transaction is safe
     * for the validation reads only.
     */
    public Refund createRefund(UUID orderId, AuthPrincipal principal,
                               String idempotencyKey, List<UUID> ticketIds,
                               RefundReason reason) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_IDEMPOTENCY_KEY,
                "Idempotency-Key header is required");
        }

        // Org check FIRST, before any refund row can be returned. The replay short-circuit
        // below hands back stripeRefundId, amounts, status and ticket ids, and the approve
        // path's key is guessable by construction ("refund-request-" + requestId, both
        // halves of which the buyer holds) — so answering it ahead of this check was a
        // cross-org read of exactly the kind the 404-not-403 handling here exists to stop.
        Order order = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (!order.getOrgId().equals(principal.orgId())) {
            // 404, not 403 — leak-safe (don't reveal that the order exists for another org)
            throw ApiException.notFound("Order");
        }

        // Idempotency short-circuit before the ticket work so retries don't redo it. The
        // unique (order_id, idempotency_key) index also protects against race-stacked POSTs
        // at INSERT time.
        Optional<Refund> existing = refunds.findByOrderIdAndIdempotencyKey(orderId, idempotencyKey);
        if (existing.isPresent()) {
            log.info("[refund] idempotent replay orderId={} key={} → returning existing {}",
                orderId, idempotencyKey, existing.get().getId());
            return existing.get();
        }

        // A charged-back order is already being clawed back, so refunding it would either
        // pay the buyer twice or bounce off Stripe as charge_disputed. After the replay
        // short-circuit on purpose: a refund taken before the dispute keeps returning its row.
        if (isBlockedByDispute(orderId)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_DISPUTED,
                "Order is disputed and cannot be refunded");
        }

        if (ticketIds == null || ticketIds.isEmpty() || new HashSet<>(ticketIds).size() != ticketIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "ticketIds must be a non-empty unique list");
        }

        if (!hasStripePayment(order)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE,
                "Order has no Stripe payment to refund");
        }
        if (!matchesStripeMode(order)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE,
                "Order was paid in Stripe " + (order.isTestMode() ? "test" : "live")
                + " mode and cannot be refunded with the running " + stripeProps.keyMode() + " key");
        }

        List<Ticket> selected = tickets.findByIdInAndOrderId(ticketIds, orderId);
        if (selected.size() != ticketIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "One or more ticketIds do not belong to this order");
        }

        Set<UUID> alreadyRefunded = claimedTicketIds(ticketIds);
        if (!alreadyRefunded.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.TICKET_ALREADY_REFUNDED,
                "One or more selected tickets have already been refunded",
                Map.of("ticketIds",
                    alreadyRefunded.stream().map(UUID::toString).collect(Collectors.joining(","))));
        }
        for (Ticket t : selected) {
            if (isRedeemed(t)) {
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.TICKET_REDEEMED,
                    "Redeemed tickets cannot be refunded",
                    Map.of("ticketId", t.getId().toString()));
            }
        }

        long refundAmountMinor = computeRefundAmountMinor(order, selected);
        if (refundAmountMinor <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE,
                "Refund amount must be positive");
        }

        // Application-fee refund stays proportional to refund amount over order total, and is
        // clamped to the fee not yet refunded on this order — per-refund rounding otherwise
        // makes N refunds sum ABOVE the original fee, which Stripe rejects outright.
        long appFeeRefundMinor = computeAppFeeRefundMinor(order, refundAmountMinor);

        Refund draft = new Refund();
        draft.setOrderId(orderId);
        draft.setStripePaymentIntentId(order.getStripePaymentIntentId());
        draft.setAmountMinor(refundAmountMinor);
        draft.setCurrency(order.getCurrency());
        draft.setApplicationFeeRefundMinor(appFeeRefundMinor);
        draft.setReason(reason == null ? RefundReason.OTHER : reason);
        draft.setInitiatedByUserId(principal.userId());
        draft.setIdempotencyKey(idempotencyKey);

        Refund row;
        try {
            row = store.open(draft, selected.stream().map(Ticket::getId).toList(), now());
        } catch (DataIntegrityViolationException race) {
            if (violates(race, IDEMPOTENCY_CONSTRAINT)) {
                // A concurrent POST with the same key committed first: replay its row.
                Optional<Refund> winner = refunds.findByOrderIdAndIdempotencyKey(orderId, idempotencyKey);
                if (winner.isPresent()) {
                    log.info("[refund] idempotent replay (raced) orderId={} key={} → returning existing {}",
                        orderId, idempotencyKey, winner.get().getId());
                    return winner.get();
                }
            }
            // UNIQUE(refund_tickets.ticket_id) raced: another refund claimed a ticket after the pre-check.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.TICKET_ALREADY_REFUNDED,
                "One or more selected tickets were just refunded by another request");
        }

        Refund out = attempt(row);
        log.info("[refund] created id={} orderId={} amount={} {} appFeeRefund={} status={}",
            out.getId(), orderId, refundAmountMinor, order.getCurrency(), appFeeRefundMinor, out.getStatus());
        return out;
    }

    /**
     * One Stripe create for a committed REQUESTED row, with the amount, fee, reason and variant
     * stored on it, then the outcome recorded. Shared by the live request and the reconciler.
     *
     * @throws ApiException {@code REFUND_IN_PROGRESS} when the outcome is unknown (the row stays
     *                      REQUESTED with its claims); a refusal code once Stripe has refused.
     */
    private Refund attempt(Refund row) {
        boolean platform = row.isPlatformFunded();
        com.stripe.model.Refund stripeRefund;
        try {
            stripeRefund = send(row, platform);
        } catch (StripeException | RuntimeException e) {
            StripeRefundOutcome outcome = StripeRefundOutcome.classify(e);
            if (outcome == StripeRefundOutcome.UNCERTAIN) throw inProgress(row, e);
            if (outcome == StripeRefundOutcome.BALANCE_INSUFFICIENT && !platform) {
                stripeRefund = fundFromPlatformBalance(row, (StripeException) e);
            } else if (outcome == StripeRefundOutcome.BALANCE_INSUFFICIENT) {
                refuse(row, (StripeException) e);
                throw platformRefused((StripeException) e);
            } else {
                refuse(row, (StripeException) e);
                throw mapStripeFailure((StripeException) e);
            }
        }
        try {
            return store.recordOutcome(row.getId(), stripeRefund);
        } catch (RuntimeException e) {
            // Stripe holds a refund imin could not record: the claim stays and the reconciler adopts it.
            log.error("[REFUND_FINALIZE_FAILED] refundId={} stripeRefundId={}", row.getId(), stripeRefund.getId(), e);
            throw inProgress(row, null);
        }
    }

    private com.stripe.model.Refund send(Refund row, boolean platform) throws StripeException {
        return stripeRefundService.create(
            row.getStripePaymentIntentId(), row.getAmountMinor(), row.getCurrency(), row.getReason(),
            row.getApplicationFeeRefundMinor(), !platform, stripeKeyFor(row.getId(), platform),
            row.getId().toString());
    }

    /**
     * Second and last attempt after {@code balance_insufficient}: the same refund with
     * {@code reverse_transfer=false}, so the money leaves the PLATFORM balance instead of the
     * connected account's. {@code platform_funded} is committed first (it picks the key and is what
     * the reconciler re-sends), and marks the refund for recovery from the org's next payout.
     */
    private com.stripe.model.Refund fundFromPlatformBalance(Refund row, StripeException original) {
        boolean switched;
        try {
            switched = store.switchToPlatform(row.getId(), now());
        } catch (RuntimeException write) {
            // No platform send without the committed flag; the reconciler retries the whole attempt.
            log.error("[refund] could not commit the platform-funded switch refundId={}", row.getId(), write);
            throw inProgress(row, write);
        }
        if (!switched) {
            // Another writer moved the row since it was read; its outcome is not ours to decide.
            throw inProgress(row, original);
        }
        try {
            com.stripe.model.Refund funded = send(row, true);
            log.warn("[refund] connected balance short for orderId={} — refunded {} {} from the PLATFORM "
                    + "balance; recovered from the org's next payout",
                row.getOrderId(), row.getAmountMinor(), row.getCurrency());
            return funded;
        } catch (StripeException | RuntimeException second) {
            if (StripeRefundOutcome.classify(second) == StripeRefundOutcome.UNCERTAIN) throw inProgress(row, second);
            // The recovery attempt's own refusal must never replace the balance_insufficient signal.
            log.error("[refund] platform-funded retry ALSO failed orderId={} — {}", row.getOrderId(),
                second.getMessage(), second);
            refuse(row, (StripeException) second);
            throw platformRefused(original);
        }
    }

    /** Records a refusal; a failure to record it is logged, never thrown over the refusal itself. */
    private void refuse(Refund row, StripeException e) {
        try {
            store.recordRefusal(row.getId(), e.getCode(), e.getMessage());
        } catch (RuntimeException write) {
            log.error("[refund] could not record Stripe's refusal refundId={} — the reconciler retries it",
                row.getId(), write);
        }
    }

    private ApiException platformRefused(StripeException original) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE,
            "The connected account's balance is too low to fund this refund and the platform-funded "
            + "retry failed too. Top up the Stripe balance or contact support.",
            Map.of("stripeCode", original.getCode() == null ? "balance_insufficient" : original.getCode()));
    }

    private ApiException inProgress(Refund row, Throwable cause) {
        Map<String, String> fields = new HashMap<>();
        fields.put("refundId", row.getId().toString());
        if (cause instanceof StripeException se && se.getCode() != null) fields.put("stripeCode", se.getCode());
        if (cause != null) {
            log.warn("[refund] Stripe outcome unknown refundId={} orderId={} — kept REQUESTED for the reconciler",
                row.getId(), row.getOrderId(), cause);
        }
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.REFUND_IN_PROGRESS, IN_PROGRESS_MESSAGE, Map.copyOf(fields));
    }

    /**
     * One reconciler pass over an unresolved attempt. Claims it (compare-and-set on
     * {@code stripe_attempt_at}), then: adopts the Stripe refund carrying its metadata; or, when
     * Stripe has none, refuses it on a disputed order or re-sends it with its stored inputs and key.
     * Rows with no attempt time (pre-V179) are skipped; the reconciler only reports them.
     */
    public Resolution resolveAttempt(UUID refundId) {
        Refund row = refunds.findById(refundId).orElse(null);
        if (row == null || row.getStatus() != RefundStatus.REQUESTED || row.getStripeRefundId() != null
                || row.getStripeAttemptAt() == null) {
            return Resolution.SKIPPED;
        }
        Instant now = now();
        if (!store.claimForReconcile(refundId, row.getStripeAttemptAt(), now.minus(RECONCILE_MIN_AGE), now)) {
            return Resolution.SKIPPED;
        }

        List<com.stripe.model.Refund> onStripe;
        try {
            onStripe = stripeRefundService.listByPaymentIntent(row.getStripePaymentIntentId());
        } catch (StripeException | RuntimeException e) {
            log.warn("[refund] reconcile could not list refunds refundId={} pi={}", refundId,
                row.getStripePaymentIntentId(), e);
            return Resolution.UNRESOLVED;
        }
        List<com.stripe.model.Refund> ours = onStripe.stream()
            .filter(r -> r.getMetadata() != null
                && refundId.toString().equals(r.getMetadata().get(StripeRefundService.IMIN_REFUND_ID)))
            .toList();
        if (!ours.isEmpty()) {
            if (ours.size() > 1) {
                log.error("[REFUND_SECOND_STRIPE_REFUND] refundId={} stripeRefunds={}", refundId,
                    ours.stream().map(com.stripe.model.Refund::getId).toList());
            }
            store.recordOutcome(refundId, ours.get(0));
            return Resolution.ADOPTED;
        }
        if (isBlockedByDispute(row.getOrderId())) {
            store.recordRefusal(refundId, "order_disputed", DISPUTED_UNSENT_MESSAGE);
            return Resolution.REFUSED;
        }
        try {
            attempt(row);
            return Resolution.RESENT;
        } catch (ApiException e) {
            return e.code() == ErrorCode.REFUND_IN_PROGRESS ? Resolution.UNRESOLVED : Resolution.REFUSED;
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean violates(Throwable t, String constraint) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ConstraintViolationException cve && constraint.equals(cve.getConstraintName())) return true;
            if (c.getMessage() != null && c.getMessage().contains(constraint)) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }

    // The refusal rules of createRefund, shared with EventRefundPlanService so a planned
    // refund is exactly one createRefund accepts.

    /** An OPEN or LOST chargeback is already clawing the money back. */
    boolean isBlockedByDispute(UUID orderId) {
        return disputes.hasOpenOrLostByOrderId(orderId);
    }

    static boolean hasStripePayment(Order order) {
        return order.getStripePaymentIntentId() != null && !order.getStripePaymentIntentId().isBlank();
    }

    /** The running key only sees payments made in its own mode; the other mode's 404s at Stripe. */
    boolean matchesStripeMode(Order order) {
        return order.isTestMode() != stripeProps.isLiveKey();
    }

    /** Ids among {@code ticketIds} already claimed by a refund row (UNIQUE refund_tickets.ticket_id). */
    Set<UUID> claimedTicketIds(Collection<UUID> ticketIds) {
        return ticketIds.isEmpty() ? Set.of() : refundTickets.findRefundedTicketIds(ticketIds);
    }

    static boolean isRedeemed(Ticket ticket) {
        return Ticket.STATE_REDEEMED.equals(ticket.getState());
    }

    /**
     * A DEFINITIVE refusal only: Stripe processed the request and created no refund, so 422 with
     * Stripe's own message. Unknown outcomes never reach here; they are {@code REFUND_IN_PROGRESS}.
     */
    private ApiException mapStripeFailure(StripeException e) {
        Map<String, String> fields = e.getCode() == null ? null : Map.of("stripeCode", e.getCode());
        String message = e.getMessage() == null ? "Stripe refund failed" : e.getMessage();
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.STRIPE_REFUND_FAILED, message, fields);
    }

    @Transactional(readOnly = true)
    public List<Refund> listForOrder(UUID orderId, AuthPrincipal principal) {
        Order order = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (!order.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Order");
        return refunds.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    /**
     * Applies Stripe's record of a refund: the refund webhooks' entry point, and the reconciler's for a
     * PENDING refund whose webhook never came. Idempotent: a repeat of the same state is a no-op.
     */
    @Transactional
    public void applyStripeRefund(com.stripe.model.Refund stripeRefund) {
        handleWebhookStatusChange(
            stripeRefund.getId(),
            RefundStatus.fromStripe(stripeRefund.getStatus()),
            stripeRefund.getFailureReason(),
            stripeRefund.getFailureReason(),   // Stripe Refund only exposes failure_reason
            stripeRefund.getPaymentIntent(),
            stripeRefund.getCharge(),
            stripeRefund.getAmount(),
            stripeRefund.getMetadata() == null
                ? null : stripeRefund.getMetadata().get(StripeRefundService.IMIN_REFUND_ID));
    }

    /**
     * Called through {@link #applyStripeRefund} on the refund events. Performs
     * the race-safe status transition and (on SUCCEEDED) the inventory release and email publish.
     * Late events past a terminal state are no-ops.
     *
     * <p>A refund not yet linked by id is matched by {@code iminRefundId} (its
     * {@code metadata.imin_refund_id}) when that row has the same PaymentIntent, is still REQUESTED and
     * has no Stripe id yet, so a webhook that beats the live call's recording never materializes a
     * duplicate row. Any other case is materialized as an unknown refund.
     */
    @Transactional
    public void handleWebhookStatusChange(String stripeRefundId, RefundStatus newStatus,
                                          String failureCode, String failureMessage,
                                          String stripePaymentIntentId, String stripeChargeId,
                                          Long stripeAmountMinor, String iminRefundId) {
        Refund refund = refunds.findByStripeRefundId(stripeRefundId).orElse(null);
        if (refund == null) {
            refund = adoptByMetadata(stripeRefundId, stripePaymentIntentId, stripeChargeId, iminRefundId);
        }
        if (refund == null) {
            refund = materializeUnknownRefund(stripeRefundId, stripePaymentIntentId,
                stripeChargeId, stripeAmountMinor);
            if (refund == null) return;
        }
        if (refund.getStatus() == newStatus) return;
        if (refund.getStatus().isTerminal()) {
            log.info("[refund-webhook] refund {} already terminal ({}); ignoring late {}",
                refund.getId(), refund.getStatus(), newStatus);
            return;
        }

        int won = refunds.updateStatusIfCurrent(refund.getId(), refund.getStatus(), newStatus);
        if (won == 0) {
            log.info("[refund-webhook] refund {} concurrently transitioned — no-op", refund.getId());
            return;
        }

        if (newStatus == RefundStatus.SUCCEEDED) {
            inventoryRelease.release(refund.getId());
            log.info("[refund-webhook] refund {} SUCCEEDED — inventory released, email queued",
                refund.getId());
        } else if (newStatus == RefundStatus.FAILED || newStatus == RefundStatus.CANCELED) {
            // No money moved, so the tickets must become refundable again. The
            // refund_tickets rows are the claim — UNIQUE(ticket_id) means leaving them
            // behind blocks every retry with 409 TICKET_ALREADY_REFUNDED forever, while
            // the money side (sumActiveAmountByOrderId) already treats the refund as
            // inactive. Same transaction as the status flip, so the two never disagree.
            long released = refundTickets.deleteByRefundId(refund.getId());
            // Free the client key too, so a same-key retry or re-approve opens a new attempt.
            refunds.freeClientKey(refund.getId(), RefundAttemptStore.failedKey(refund.getId()));
            if (newStatus == RefundStatus.FAILED) {
                // Conditional UPDATE only flipped status; persist failure detail separately.
                Refund reloaded = refunds.findById(refund.getId()).orElseThrow();
                reloaded.setFailureCode(failureCode);
                reloaded.setFailureMessage(failureMessage);
                refunds.save(reloaded);
                publisher.publishEvent(new RefundFailedEvent(refund.getId()));
                log.warn("[refund-webhook] refund {} FAILED code={} message={} — released {} ticket claim(s)",
                    refund.getId(), failureCode, failureMessage, released);
            } else {
                log.info("[refund-webhook] refund {} CANCELED — released {} ticket claim(s)",
                    refund.getId(), released);
            }
        }
    }

    /** The row named by the refund's metadata, linked to this Stripe id; null when it cannot be. */
    private Refund adoptByMetadata(String stripeRefundId, String stripePaymentIntentId,
                                   String stripeChargeId, String iminRefundId) {
        if (iminRefundId == null || iminRefundId.isBlank()) return null;
        UUID id;
        try {
            id = UUID.fromString(iminRefundId.trim());
        } catch (IllegalArgumentException notOurs) {
            return null;
        }
        Refund named = refunds.findById(id).orElse(null);
        if (named == null || !Objects.equals(named.getStripePaymentIntentId(), stripePaymentIntentId)) return null;
        if (named.getStripeRefundId() == null && named.getStatus() != RefundStatus.REQUESTED) {
            // imin recorded this attempt as refused, yet Stripe holds a refund for it: keep that money visible.
            log.error("[REFUND_ADOPTED_AFTER_REFUSAL] refundId={} status={} stripeRefundId={}",
                id, named.getStatus(), stripeRefundId);
            return null;
        }
        if (named.getStripeRefundId() == null) {
            refunds.adoptStripeRefund(id, stripeRefundId, stripeChargeId);
            named = refunds.findById(id).orElseThrow();
        }
        if (stripeRefundId.equals(named.getStripeRefundId())) return named;
        // Our row already maps to another Stripe refund: this is a second one, so keep it visible.
        log.error("[REFUND_SECOND_STRIPE_REFUND] refundId={} existing={} new={}",
            id, named.getStripeRefundId(), stripeRefundId);
        return null;
    }

    /**
     * A refund Stripe reports that we never created — the organizer refunded from the Stripe
     * Dashboard. Back-resolve the Order from the payment intent and materialize the row so the
     * money is not invisible to us; the caller then runs the normal status transition on it.
     *
     * <p>A refund of exactly the order's remaining total claims every still-refundable ticket,
     * so the succeeded transition revokes them and decrements {@code sold}. A partial amount
     * has no ticket mapping we can infer — the row is written with none and logged as
     * {@code [UNMAPPED_PARTIAL_REFUND]} for an operator to reconcile.
     *
     * @return the materialized refund, or null when nothing could be resolved.
     */
    private Refund materializeUnknownRefund(String stripeRefundId, String stripePaymentIntentId,
                                            String stripeChargeId, Long stripeAmountMinor) {
        if (stripePaymentIntentId == null || stripePaymentIntentId.isBlank() || stripeAmountMinor == null) {
            log.warn("[refund-webhook] no DB row for stripe refund {} and no payment intent/amount to "
                + "back-resolve it — skipping", stripeRefundId);
            return null;
        }
        Order order = orders.findByStripePaymentIntentId(stripePaymentIntentId).orElse(null);
        if (order == null) {
            log.warn("[refund-webhook] no DB row for stripe refund {} and no order for payment intent {} "
                + "— skipping (likely another platform's refund)", stripeRefundId, stripePaymentIntentId);
            return null;
        }

        long remaining = order.getTotalMinor() - refunds.sumActiveAmountByOrderId(order.getId());
        boolean full = stripeAmountMinor == remaining;

        Refund r = new Refund();
        r.setOrderId(order.getId());
        r.setStripeRefundId(stripeRefundId);
        r.setStripeChargeId(stripeChargeId);
        r.setStripePaymentIntentId(stripePaymentIntentId);
        r.setAmountMinor(stripeAmountMinor);
        r.setCurrency(order.getCurrency());
        r.setApplicationFeeRefundMinor(computeAppFeeRefundMinor(order, stripeAmountMinor));
        r.setReason(RefundReason.OTHER);
        r.setStatus(RefundStatus.REQUESTED);
        // No initiating user: the actor is in the Stripe Dashboard, not in imin.
        r.setInitiatedByUserId(null);
        r.setIdempotencyKey("stripe-webhook:" + stripeRefundId);
        r = refunds.save(r);

        if (!full) {
            log.error("[UNMAPPED_PARTIAL_REFUND] orderId={} stripeRefundId={} amount={} — money moved, "
                + "no ticket mapping; ops must reconcile", order.getId(), stripeRefundId, stripeAmountMinor);
            return r;
        }

        List<UUID> candidates = tickets.findByOrderId(order.getId()).stream()
            .filter(t -> !Ticket.STATE_REFUNDED.equals(t.getState()))
            .map(Ticket::getId)
            .toList();
        Set<UUID> alreadyClaimed = candidates.isEmpty()
            ? Set.of() : refundTickets.findRefundedTicketIds(candidates);
        List<UUID> claimable = candidates.stream().filter(id -> !alreadyClaimed.contains(id)).toList();
        try {
            List<RefundTicket> rows = new ArrayList<>(claimable.size());
            for (UUID ticketId : claimable) rows.add(new RefundTicket(r.getId(), ticketId));
            // saveAllAndFlush, same as the create path: UNIQUE(refund_tickets.ticket_id) is the
            // decision, and it is only consulted at flush time.
            refundTickets.saveAllAndFlush(rows);
        } catch (DataIntegrityViolationException race) {
            log.error("[refund-webhook] stripe refund {} on order {} raced a ticket claim — row kept, "
                + "tickets NOT mapped; ops must reconcile", stripeRefundId, order.getId());
            return r;
        }
        log.warn("[refund-webhook] materialized dashboard-initiated refund {} on order {} amount={} — "
            + "claimed {} ticket(s)", stripeRefundId, order.getId(), stripeAmountMinor, claimable.size());
        return r;
    }

    /**
     * Refund principal = totalMinor × (selected face / order face), clamped to
     * remaining (= totalMinor − prior active refunds). Anchoring on totalMinor
     * (not face price) means promo-discounted orders refund the actual paid
     * amount; the clamp absorbs cross-refund rounding drift so a full-order
     * refund always equals exactly totalMinor.
     *
     * <p>Package-private so {@code RefundRequestService} can reuse the same
     * math for previews and approvals.
     */
    long computeRefundAmountMinor(Order order, List<Ticket> selected) {
        long totalMinor = order.getTotalMinor();
        if (totalMinor <= 0) return 0;

        long selectedFace = selected.stream().mapToLong(Ticket::getPriceMinor).sum();
        long orderFace = tickets.findByOrderId(order.getId()).stream()
            .mapToLong(Ticket::getPriceMinor).sum();
        if (orderFace <= 0 || selectedFace <= 0) return 0;

        long proposed = Math.round((double) totalMinor * selectedFace / orderFace);

        long priorRefunded = refunds.sumActiveAmountByOrderId(order.getId());
        long remaining = totalMinor - priorRefunded;
        if (remaining <= 0) return 0;

        return Math.min(proposed, remaining);
    }

    /**
     * Stripe idempotency key of a refund attempt: the committed row id, with {@code :platform} for
     * the {@code reverse_transfer=false} variant (Stripe rejects a replayed key whose params differ).
     * Stripe drops keys after ~24 h; past that the reconciler's metadata lookup is what stops a repeat.
     */
    static String stripeKeyFor(UUID refundId, boolean platform) {
        return "refund_" + refundId + (platform ? ":platform" : "");
    }

    /**
     * The application-fee share to refund alongside {@code refundAmountMinor}, CLAMPED to the
     * fee not yet refunded on this order.
     *
     * <p>The proportion is rounded per refund, so the shares can sum to more than the original
     * fee: three equal tickets on total 1000 with fee 149 each refund 333, and each fee share
     * rounds to 50 — 150 against a 149 fee. Stripe rejects that third
     * {@code applicationFees().refunds().create} with an invalid_request_error, and no retry
     * fixes it because the arithmetic is deterministic. The clamp mirrors the one
     * {@link #computeRefundAmountMinor} already applies to the principal.
     */
    long computeAppFeeRefundMinor(Order order, long refundAmountMinor) {
        if (order.getTotalMinor() <= 0 || order.getApplicationFeeMinor() <= 0) return 0;
        long proportional = Math.round(
            (double) order.getApplicationFeeMinor() * refundAmountMinor / order.getTotalMinor());
        long remainingFee = order.getApplicationFeeMinor()
            - refunds.sumActiveApplicationFeeRefundMinorByOrderId(order.getId());
        return Math.max(0, Math.min(proportional, remainingFee));
    }
}
