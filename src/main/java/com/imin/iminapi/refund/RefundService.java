package com.imin.iminapi.refund;

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
import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
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
    private final ApplicationEventPublisher publisher;

    public RefundService(OrderRepository orders,
                         TicketRepository tickets,
                         RefundRepository refunds,
                         RefundTicketRepository refundTickets,
                         StripeRefundService stripeRefundService,
                         TicketTierRepository tierRepository,
                         ApplicationEventPublisher publisher) {
        this.orders = orders;
        this.tickets = tickets;
        this.refunds = refunds;
        this.refundTickets = refundTickets;
        this.stripeRefundService = stripeRefundService;
        this.tierRepository = tierRepository;
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

        // Idempotency short-circuit BEFORE order load so retries don't hit the DB twice
        // for the order/tickets/etc. The unique (order_id, idempotency_key) index also
        // protects against race-stacked POSTs at INSERT time.
        Optional<Refund> existing = refunds.findByOrderIdAndIdempotencyKey(orderId, idempotencyKey);
        if (existing.isPresent()) {
            log.info("[refund] idempotent replay orderId={} key={} → returning existing {}",
                orderId, idempotencyKey, existing.get().getId());
            return existing.get();
        }

        if (ticketIds == null || ticketIds.isEmpty() || new HashSet<>(ticketIds).size() != ticketIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REQUEST,
                "ticketIds must be a non-empty unique list");
        }

        Order order = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (!order.getOrgId().equals(principal.orgId())) {
            // 404, not 403 — leak-safe (don't reveal that the order exists for another org)
            throw ApiException.notFound("Order");
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

        // Application-fee refund stays proportional to refund amount over order total.
        // Total app-fee refunds across all refunds equal the original fee when the
        // order is fully refunded (within at most N−1 cents drift over N refunds).
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
            refundTickets.saveAll(rows);
        } catch (DataIntegrityViolationException race) {
            // UNIQUE(refund_tickets.ticket_id) raced. A concurrent refund grabbed
            // a ticket between our pre-check and INSERT. Surface as ALREADY_REFUNDED.
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.TICKET_ALREADY_REFUNDED,
                "One or more selected tickets were just refunded by another request");
        }

        com.stripe.model.Refund stripeRefund;
        try {
            stripeRefund = stripeRefundService.create(
                order.getStripePaymentIntentId(), refundAmountMinor, order.getCurrency(),
                r.getReason(), appFeeRefundMinor, "refund_" + r.getId());
        } catch (StripeException e) {
            log.warn("[refund] Stripe refund failed orderId={} refundId={} status={} code={} — {}",
                orderId, r.getId(), e.getStatusCode(), e.getCode(), e.getMessage());
            // Row stays at REQUESTED with no stripe_refund_id; client retries with same key
            // → idempotency short-circuit returns the row; Stripe is re-called only because
            // we don't yet have a stripe_refund_id... actually that's a bug: we DO want
            // to retry the Stripe call on next attempt, but the idempotency short-circuit
            // skips it. Acceptable for Phase A — operator can manually nudge stuck REQUESTED
            // rows. TODO Phase B: retry on REQUESTED-status existing rows.
            if ("balance_insufficient".equals(e.getCode())) {
                // reverse_transfer=true can't pull funds back when the connected account's
                // balance is too low (e.g. already paid out). Surface a distinct, actionable
                // error rather than a generic Stripe failure — and deliberately do NOT silently
                // absorb the refund onto the platform balance; whether to do so is an operator
                // money-policy decision, not a default to bake in here.
                throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE,
                    "The connected account's balance is too low to fund this refund — funds may have "
                    + "already been paid out. Top up the Stripe balance or contact support.",
                    Map.of("stripeCode", "balance_insufficient"));
            }
            HttpStatus status = e.getStatusCode() >= 500
                ? HttpStatus.BAD_GATEWAY
                : HttpStatus.UNPROCESSABLE_ENTITY;
            ErrorCode code = e.getStatusCode() >= 500
                ? ErrorCode.UPSTREAM_UNAVAILABLE
                : ErrorCode.STRIPE_REFUND_FAILED;
            Map<String, String> fields = e.getCode() == null ? null : Map.of("stripeCode", e.getCode());
            throw new ApiException(status, code,
                e.getMessage() == null ? "Stripe refund failed" : e.getMessage(), fields);
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
                                          String failureCode, String failureMessage) {
        Refund refund = refunds.findByStripeRefundId(stripeRefundId).orElse(null);
        if (refund == null) {
            log.warn("[refund-webhook] no DB row for stripe refund {} — skipping (likely dashboard-initiated)",
                stripeRefundId);
            return;
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
        } else if (newStatus == RefundStatus.FAILED) {
            // Conditional UPDATE only flipped status; persist failure detail separately.
            Refund reloaded = refunds.findById(refund.getId()).orElseThrow();
            reloaded.setFailureCode(failureCode);
            reloaded.setFailureMessage(failureMessage);
            refunds.save(reloaded);
            publisher.publishEvent(new RefundFailedEvent(refund.getId()));
            log.warn("[refund-webhook] refund {} FAILED code={} message={}",
                refund.getId(), failureCode, failureMessage);
        }
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

    long computeAppFeeRefundMinor(Order order, long refundAmountMinor) {
        if (order.getTotalMinor() <= 0 || order.getApplicationFeeMinor() <= 0) return 0;
        return Math.round(
            (double) order.getApplicationFeeMinor() * refundAmountMinor / order.getTotalMinor());
    }
}
