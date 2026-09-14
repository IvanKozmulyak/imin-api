package com.imin.iminapi.refund;

import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.refund.event.RefundConfirmedEvent;
import com.imin.iminapi.refund.event.RefundFailedEvent;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeRefundService;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Refund chokepoint. All refund creation flows through {@link #createRefund} —
 * validation, authorization, persistence, and the Stripe API call live here.
 *
 * <p>Inventory release does NOT happen here. The {@code charge.refund.updated}
 * webhook is the sole authority for transitioning {@code PENDING → SUCCEEDED}
 * and releasing inventory, because Stripe refunds can stay pending for hours
 * on debit cards. A pure webhook-driven release means one code path and no
 * double-release risk.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final RefundRepository refunds;
    private final RefundTicketRepository refundTickets;
    private final StripeRefundService stripeRefundService;
    private final TicketTierRepository tierRepository;
    private final DisputeRepository disputes;
    private final ApplicationEventPublisher publisher;

    public RefundService(OrderRepository orders,
                         TicketRepository tickets,
                         RefundRepository refunds,
                         RefundTicketRepository refundTickets,
                         StripeRefundService stripeRefundService,
                         TicketTierRepository tierRepository,
                         DisputeRepository disputes,
                         ApplicationEventPublisher publisher) {
        this.orders = orders;
        this.tickets = tickets;
        this.refunds = refunds;
        this.refundTickets = refundTickets;
        this.stripeRefundService = stripeRefundService;
        this.tierRepository = tierRepository;
        this.disputes = disputes;
        this.publisher = publisher;
    }

    @Transactional
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
        if (disputes.hasOpenOrLostByOrderId(orderId)) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_DISPUTED,
                "Order is disputed and cannot be refunded");
        }

        if (ticketIds == null || ticketIds.isEmpty() || new HashSet<>(ticketIds).size() != ticketIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "ticketIds must be a non-empty unique list");
        }

        if (order.getStripePaymentIntentId() == null || order.getStripePaymentIntentId().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE,
                "Order has no Stripe payment to refund");
        }

        List<Ticket> selected = tickets.findByIdInAndOrderId(ticketIds, orderId);
        if (selected.size() != ticketIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "One or more ticketIds do not belong to this order");
        }

        Set<UUID> alreadyRefunded = refundTickets.findRefundedTicketIds(ticketIds);
        if (!alreadyRefunded.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.TICKET_ALREADY_REFUNDED,
                "One or more selected tickets have already been refunded",
                Map.of("ticketIds",
                    alreadyRefunded.stream().map(UUID::toString).collect(Collectors.joining(","))));
        }
        for (Ticket t : selected) {
            if (Ticket.STATE_REDEEMED.equals(t.getState())) {
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

        Refund r = new Refund();
        r.setOrderId(orderId);
        r.setStripePaymentIntentId(order.getStripePaymentIntentId());
        r.setAmountMinor(refundAmountMinor);
        r.setCurrency(order.getCurrency());
        r.setApplicationFeeRefundMinor(appFeeRefundMinor);
        r.setReason(reason == null ? RefundReason.OTHER : reason);
        r.setStatus(RefundStatus.REQUESTED);
        r.setInitiatedByUserId(principal.userId());
        r.setIdempotencyKey(idempotencyKey);
        r = refunds.save(r);

        try {
            List<RefundTicket> rows = new ArrayList<>(selected.size());
            for (Ticket t : selected) rows.add(new RefundTicket(r.getId(), t.getId()));
            // saveAllAndFlush, not saveAll: RefundTicket carries an application-assigned
            // @IdClass id, so save() merges and only QUEUES the INSERT. Without this flush
            // UNIQUE(refund_tickets.ticket_id) is not consulted until commit — i.e. after
            // the Stripe call below has already moved real money — and the catch under it
            // is unreachable. Flushing claims the tickets before we spend anything.
            refundTickets.saveAllAndFlush(rows);
        } catch (DataIntegrityViolationException race) {
            // UNIQUE(refund_tickets.ticket_id) raced. A concurrent refund grabbed
            // a ticket between our pre-check and INSERT. Surface as ALREADY_REFUNDED.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.TICKET_ALREADY_REFUNDED,
                "One or more selected tickets were just refunded by another request");
        }

        String stripeKey = stripeIdempotencyKey(orderId, idempotencyKey, ticketIds, refundAmountMinor, "");
        com.stripe.model.Refund stripeRefund;
        try {
            stripeRefund = stripeRefundService.create(
                order.getStripePaymentIntentId(), refundAmountMinor, order.getCurrency(),
                r.getReason(), appFeeRefundMinor, true, stripeKey);
        } catch (StripeException e) {
            log.warn("[refund] Stripe refund failed orderId={} refundId={} status={} code={} — {}",
                orderId, r.getId(), e.getStatusCode(), e.getCode(), e.getMessage());
            // Everything written above is rolled back with this throw (ApiException is a
            // RuntimeException and this method is @Transactional), so the retry re-enters
            // with an empty DB. That is survivable ONLY because the Stripe key above is
            // derived from durable inputs rather than from the discarded row id — the retry
            // replays the identical Stripe request and Stripe returns the original Refund.
            if ("balance_insufficient".equals(e.getCode())) {
                stripeRefund = fundFromPlatformBalance(order, r, refundAmountMinor, appFeeRefundMinor,
                    orderId, idempotencyKey, ticketIds, e);
                r.setPlatformFunded(true);
            } else {
                throw mapStripeFailure(e);
            }
        }

        r.setStripeRefundId(stripeRefund.getId());
        r.setStripeChargeId(stripeRefund.getCharge());
        RefundStatus initialStatus = RefundStatus.fromStripe(stripeRefund.getStatus());
        r.setStatus(initialStatus);
        r = refunds.save(r);

        // Stripe can return SUCCEEDED synchronously (e.g. instant refunds in
        // test mode or many real-world card flows). The webhook will still
        // arrive, but handleWebhookStatusChange short-circuits when the row
        // is already in the same terminal state — so without releasing here,
        // tier.sold would never be decremented and the refunded tickets would
        // not return to inventory. Mirror the SUCCEEDED-path side effects.
        if (initialStatus == RefundStatus.SUCCEEDED) {
            finalizeSucceeded(r);
        }

        log.info("[refund] created id={} orderId={} amount={} {} appFeeRefund={} status={}",
            r.getId(), orderId, refundAmountMinor, order.getCurrency(),
            appFeeRefundMinor, r.getStatus());
        return r;
    }

    /**
     * Second and last attempt after {@code balance_insufficient}: the same refund with
     * {@code reverse_transfer=false}, so the money leaves the PLATFORM balance instead of the
     * connected account's. {@code platformFunded} marks it for recovery: the next payout tick
     * reverses the destination transfer of this charge, which is what moves the money back.
     *
     * <p>A distinct idempotency key is mandatory — Stripe rejects a replayed key whose
     * parameters differ, and {@code reverse_transfer} differs.
     */
    private com.stripe.model.Refund fundFromPlatformBalance(
            Order order, Refund r, long refundAmountMinor, long appFeeRefundMinor,
            UUID orderId, String idempotencyKey, List<UUID> ticketIds, StripeException original) {
        try {
            com.stripe.model.Refund funded = stripeRefundService.create(
                order.getStripePaymentIntentId(), refundAmountMinor, order.getCurrency(),
                r.getReason(), appFeeRefundMinor, false,
                stripeIdempotencyKey(orderId, idempotencyKey, ticketIds, refundAmountMinor, ":platform"));
            log.warn("[refund] connected balance short for orderId={} — refunded {} {} from the PLATFORM "
                    + "balance; recovered from the org's next payout",
                orderId, refundAmountMinor, order.getCurrency());
            return funded;
        } catch (StripeException | RuntimeException second) {
            // The recovery attempt's own failure must never replace the balance_insufficient
            // signal the operator has to act on, so it is logged and the original is thrown.
            log.error("[refund] platform-funded retry ALSO failed orderId={} — {}", orderId,
                second.getMessage(), second);
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE,
                "The connected account's balance is too low to fund this refund and the platform-funded "
                + "retry failed too. Top up the Stripe balance or contact support.",
                Map.of("stripeCode", original.getCode() == null ? "balance_insufficient" : original.getCode()));
        }
    }

    /**
     * A Stripe failure that never reached Stripe (connection error, status 0) or that Stripe
     * asked us to slow down on (429) is RETRYABLE, not a 422 the client must give up on: the
     * key from {@link #stripeIdempotencyKey} is derived from durable inputs, so a client retry
     * replays the identical request and Stripe returns the original Refund rather than a second one.
     */
    private ApiException mapStripeFailure(StripeException e) {
        Map<String, String> fields = e.getCode() == null ? null : Map.of("stripeCode", e.getCode());
        String message = e.getMessage() == null ? "Stripe refund failed" : e.getMessage();
        if (e instanceof ApiConnectionException || e.getStatusCode() == 0) {
            return new ApiException(HttpStatus.BAD_GATEWAY, ErrorCode.UPSTREAM_UNAVAILABLE, message, fields);
        }
        if (e instanceof RateLimitException || e.getStatusCode() == 429) {
            return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.UPSTREAM_UNAVAILABLE, message, fields);
        }
        HttpStatus status = e.getStatusCode() >= 500 ? HttpStatus.BAD_GATEWAY : HttpStatus.UNPROCESSABLE_ENTITY;
        ErrorCode code = e.getStatusCode() >= 500 ? ErrorCode.UPSTREAM_UNAVAILABLE : ErrorCode.STRIPE_REFUND_FAILED;
        return new ApiException(status, code, message, fields);
    }

    /**
     * SUCCEEDED-path side effects: release inventory (decrement tier.sold,
     * flip tickets to REFUNDED) and publish the confirmation event so the
     * buyer email goes out. Idempotent — safe to call from both the
     * synchronous create flow and the webhook handler.
     */
    private void finalizeSucceeded(Refund refund) {
        releaseInventoryAndMarkTickets(refund);
        publisher.publishEvent(new RefundConfirmedEvent(refund.getId()));
    }

    @Transactional(readOnly = true)
    public List<Refund> listForOrder(UUID orderId, AuthPrincipal principal) {
        Order order = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (!order.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Order");
        return refunds.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    /**
     * Called by {@link com.imin.iminapi.stripe.StripeWebhookService} on
     * {@code charge.refund.updated}. Performs the race-safe status transition and
     * (on SUCCEEDED) the inventory release and email publish. Late events past a
     * terminal state are no-ops.
     */
    @Transactional
    public void handleWebhookStatusChange(String stripeRefundId, RefundStatus newStatus,
                                          String failureCode, String failureMessage,
                                          String stripePaymentIntentId, String stripeChargeId,
                                          Long stripeAmountMinor) {
        Refund refund = refunds.findByStripeRefundId(stripeRefundId).orElse(null);
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
            finalizeSucceeded(refund);
            log.info("[refund-webhook] refund {} SUCCEEDED — inventory released, email queued",
                refund.getId());
        } else if (newStatus == RefundStatus.FAILED || newStatus == RefundStatus.CANCELED) {
            // No money moved, so the tickets must become refundable again. The
            // refund_tickets rows are the claim — UNIQUE(ticket_id) means leaving them
            // behind blocks every retry with 409 TICKET_ALREADY_REFUNDED forever, while
            // the money side (sumActiveAmountByOrderId) already treats the refund as
            // inactive. Same transaction as the status flip, so the two never disagree.
            long released = refundTickets.deleteByRefundId(refund.getId());
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

    private void releaseInventoryAndMarkTickets(Refund refund) {
        List<UUID> ticketIds = refundTickets.findTicketIdsByRefundId(refund.getId());
        List<Ticket> ticketsForRefund = tickets.findAllById(ticketIds);
        Map<UUID, Long> qtyByTier = ticketsForRefund.stream()
            .collect(Collectors.groupingBy(Ticket::getTierId, Collectors.counting()));

        // Lock tier rows in deterministic order to avoid two concurrent refunds
        // deadlocking on cross-tier locks. Sort by tierId.
        qtyByTier.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                TicketTier tier = tierRepository.findByIdForUpdate(entry.getKey())
                    .orElseThrow(() -> new IllegalStateException("Missing tier " + entry.getKey()));
                int decrement = entry.getValue().intValue();
                int currentSold = tier.getSold();
                if (currentSold < decrement) {
                    log.warn("[refund] tier {} sold={} < decrement={} — clamping (inventory drift)",
                        tier.getId(), currentSold, decrement);
                }
                tier.setSold(Math.max(0, currentSold - decrement));
                tierRepository.save(tier);
            });

        for (Ticket t : ticketsForRefund) t.setState(Ticket.STATE_REFUNDED);
        tickets.saveAll(ticketsForRefund);
        log.info("[refund] released {} ticket(s) across {} tier(s) for refund {}",
            ticketsForRefund.size(), qtyByTier.size(), refund.getId());
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
     * Stripe idempotency key for a refund attempt, derived from inputs that survive a
     * rollback: the order, the client's Idempotency-Key, the exact ticket selection and
     * the computed amount. Deliberately NOT the refund row id — the row is created inside
     * the same transaction as the Stripe call, so a failed call discards it and every
     * retry would mint a new id, a new key and therefore a SECOND real refund.
     *
     * <p>Same inputs ⇒ same key ⇒ Stripe replays and returns the original Refund. Different
     * inputs (a different ticket set, or a different amount because another refund landed
     * meanwhile) ⇒ different key, which is correct: that is genuinely a different refund,
     * and it also keeps Stripe from rejecting a reused key carrying changed parameters.
     *
     * <p>{@code suffix} separates the platform-funded second attempt from the first: same
     * refund, different {@code reverse_transfer}, so it must not replay the first key.
     */
    static String stripeIdempotencyKey(UUID orderId, String clientKey,
                                       List<UUID> ticketIds, long amountMinor, String suffix) {
        String material = orderId + ":" + clientKey + ":"
            + ticketIds.stream().map(UUID::toString).sorted().collect(Collectors.joining(","))
            + ":" + amountMinor + suffix;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
            // 7 + 64 chars — well inside Stripe's 255.
            return "refund_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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
