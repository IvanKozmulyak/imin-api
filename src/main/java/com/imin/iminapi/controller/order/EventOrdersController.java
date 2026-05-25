package com.imin.iminapi.controller.order;

import com.imin.iminapi.controller.order.dto.OrderRowResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Dashboard endpoint backing the EventDetailPage "Orders" tab.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/orders")
public class EventOrdersController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final EventRepository events;
    private final OrderRepository orders;
    private final TicketRepository tickets;

    public EventOrdersController(EventRepository events,
                                 OrderRepository orders,
                                 TicketRepository tickets) {
        this.events = events;
        this.orders = orders;
        this.tickets = tickets;
    }

    @GetMapping
    public List<OrderRowResponse> list(@PathVariable UUID eventId,
                                       @RequestParam(name = "limit", required = false) Integer limit,
                                       @CurrentUser AuthPrincipal principal) {
        Event event = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!event.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Event");

        int capped = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(1, limit), MAX_LIMIT);
        List<Order> page = orders.findByEventIdOrderByCreatedAtDesc(
                eventId, PageRequest.of(0, capped, Sort.by(Sort.Direction.DESC, "createdAt")));
        if (page.isEmpty()) return List.of();

        Map<UUID, List<Ticket>> ticketsByOrder = loadTicketsByOrder(
                page.stream().map(Order::getId).toList());
        List<OrderRowResponse> rows = new ArrayList<>(page.size());
        for (Order o : page) {
            rows.add(toRow(o, ticketsByOrder.getOrDefault(o.getId(), List.of())));
        }
        return rows;
    }

    private Map<UUID, List<Ticket>> loadTicketsByOrder(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        List<Ticket> all = tickets.findByOrderIdInOrderByOrderIdAscCreatedAtAsc(orderIds);
        Map<UUID, List<Ticket>> byOrder = new HashMap<>(orderIds.size());
        for (Ticket t : all) byOrder.computeIfAbsent(t.getOrderId(), k -> new ArrayList<>()).add(t);
        return byOrder;
    }

    private static OrderRowResponse toRow(Order o, List<Ticket> orderTickets) {
        int totalTickets = orderTickets.size();
        int refundedCount = (int) orderTickets.stream()
            .filter(t -> Ticket.STATE_REFUNDED.equals(t.getState()))
            .count();
        // REVOKED tickets are no longer live either — counting them toward the
        // "not still held" total keeps an order with every ticket revoked from
        // mis-rendering as `paid` (which would offer a no-op Refund button).
        int inactiveCount = (int) orderTickets.stream()
            .filter(t -> Ticket.STATE_REFUNDED.equals(t.getState())
                      || Ticket.STATE_REVOKED.equals(t.getState()))
            .count();
        String status = inactiveCount == 0 ? "paid"
                      : inactiveCount == totalTickets ? "refunded"
                      : "partially_refunded";

        List<OrderRowResponse.TicketRow> ticketRows = orderTickets.stream()
            .map(t -> new OrderRowResponse.TicketRow(
                t.getId(), t.getTierName(), t.getPriceMinor(), t.getState()))
            .collect(Collectors.toList());

        return new OrderRowResponse(
            o.getId(),
            o.getId().toString().substring(0, 8),
            o.getEmail(),
            o.getTotalMinor(),
            o.getCurrency(),
            totalTickets,
            refundedCount,
            status,
            o.getCreatedAt(),
            ticketRows
        );
    }
}
