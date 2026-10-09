package com.imin.iminapi.refund;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.refund.dto.EventRefundPlanResponse;
import com.imin.iminapi.refund.dto.EventRefundPlanResponse.EventRefundPlanOrder;
import com.imin.iminapi.refund.dto.EventRefundPlanResponse.EventRefundPlanSkipped;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Read-only plan for the dashboard's Refund all: for every order of the event, the tickets
 * {@link RefundService#createRefund} would accept and the amount it would charge. Uses the
 * refusal predicates and amount maths of RefundService itself, so the two cannot drift.
 */
@Service
public class EventRefundPlanService {

    private final EventRepository events;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final RefundService refundService;

    public EventRefundPlanService(EventRepository events, OrderRepository orders,
                                  TicketRepository tickets, RefundService refundService) {
        this.events = events;
        this.orders = orders;
        this.tickets = tickets;
        this.refundService = refundService;
    }

    @Transactional(readOnly = true)
    public EventRefundPlanResponse planForEvent(UUID eventId, AuthPrincipal principal) {
        Event event = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        // 404, not 403 — another org must not learn the event exists.
        if (!event.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Event");

        List<EventRefundPlanOrder> planned = new ArrayList<>();
        long totalAmountMinor = 0;
        int ticketCount = 0;
        int disputedOrders = 0;
        int redeemedTickets = 0;

        // ponytail: ~5 small queries per order, fine to a few thousand orders per event;
        // batch tickets, claims and refund sums by order id if an event outgrows that.
        for (Order order : orders.findByEventIdOrderByCreatedAtDesc(eventId)) {
            if (refundService.isBlockedByDispute(order.getId())) {
                disputedOrders++;
                continue;
            }
            if (!RefundService.hasStripePayment(order) || !refundService.matchesStripeMode(order)) continue;

            List<Ticket> orderTickets = tickets.findByOrderId(order.getId());
            Set<UUID> claimed = refundService.claimedTicketIds(
                orderTickets.stream().map(Ticket::getId).toList());
            List<Ticket> selected = new ArrayList<>();
            for (Ticket t : orderTickets) {
                // Refunded and revoked tickets are no longer live; a plan must not resurrect them.
                if (claimed.contains(t.getId()) || Ticket.STATE_REFUNDED.equals(t.getState())
                        || Ticket.STATE_REVOKED.equals(t.getState())) {
                    continue;
                }
                if (RefundService.isRedeemed(t)) {
                    redeemedTickets++;
                    continue;
                }
                selected.add(t);
            }
            if (selected.isEmpty()) continue;

            long amountMinor = refundService.computeRefundAmountMinor(order, selected);
            if (amountMinor <= 0) continue;

            planned.add(new EventRefundPlanOrder(order.getId(), order.getId().toString().substring(0, 8),
                selected.stream().map(Ticket::getId).toList(), amountMinor, order.getCurrency()));
            totalAmountMinor += amountMinor;
            ticketCount += selected.size();
        }

        // A total is only meaningful in one currency; never sum EUR with USD.
        Set<String> currencies = new HashSet<>();
        for (EventRefundPlanOrder o : planned) currencies.add(o.currency().toUpperCase(Locale.ROOT));
        if (currencies.size() > 1) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                "The event has refundable orders in more than one currency " + new TreeSet<>(currencies));
        }
        String currency = planned.isEmpty() ? event.getCurrency() : planned.get(0).currency();

        return new EventRefundPlanResponse(currency, totalAmountMinor, ticketCount, planned,
            new EventRefundPlanSkipped(disputedOrders, redeemedTickets));
    }
}
