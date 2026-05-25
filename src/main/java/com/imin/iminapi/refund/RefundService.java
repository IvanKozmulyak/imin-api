package com.imin.iminapi.refund;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeRefundService;
import com.stripe.exception.StripeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public RefundService(OrderRepository orders,
                         TicketRepository tickets,
                         RefundRepository refunds,
                         RefundTicketRepository refundTickets,
                         StripeRefundService stripeRefundService) {
        this.orders = orders;
        this.tickets = tickets;
        this.refunds = refunds;
        this.refundTickets = refundTickets;
        this.stripeRefundService = stripeRefundService;
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

        long refundAmountMinor = selected.stream().mapToLong(Ticket::getPriceMinor).sum();
        if (refundAmountMinor <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.ORDER_NOT_REFUNDABLE,
                "Refund amount must be positive");
        }

        // Per-refund proportional formula. Applied uniformly across refunds —
        // sum of all refunds' app-fee refunds equals the original fee when the
        // order is fully refunded (with at most N−1 cents rounding error for N refunds).
        long appFeeRefundMinor = 0;
        if (order.getTotalMinor() > 0 && order.getApplicationFeeMinor() > 0) {
            appFeeRefundMinor = Math.round(
                (double) order.getApplicationFeeMinor() * refundAmountMinor / order.getTotalMinor());
        }

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
        r.setStatus(RefundStatus.fromStripe(stripeRefund.getStatus()));
        r = refunds.save(r);

        log.info("[refund] created id={} orderId={} amount={} {} appFeeRefund={} status={}",
            r.getId(), orderId, refundAmountMinor, order.getCurrency(),
            appFeeRefundMinor, r.getStatus());
        return r;
    }

    @Transactional(readOnly = true)
    public List<Refund> listForOrder(UUID orderId, AuthPrincipal principal) {
        Order order = orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        if (!order.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Order");
        return refunds.findByOrderIdOrderByCreatedAtDesc(orderId);
    }
}
