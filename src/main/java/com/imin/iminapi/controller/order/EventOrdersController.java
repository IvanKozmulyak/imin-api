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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Dashboard endpoint backing the EventDetailPage "Orders" tab.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/orders")
public class EventOrdersController {

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
                                       @CurrentUser AuthPrincipal principal) {
        Event event = events.findById(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!event.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Event");
        return orders.findByEventIdOrderByCreatedAtDesc(eventId).stream()
            .map(this::toRow)
            .toList();
    }

    private OrderRowResponse toRow(Order o) {
        List<Ticket> orderTickets = tickets.findByOrderIdOrderByCreatedAtAsc(o.getId());
        int totalTickets = orderTickets.size();
        int refundedCount = (int) orderTickets.stream()
            .filter(t -> Ticket.STATE_REFUNDED.equals(t.getState()))
            .count();
        String status = refundedCount == 0 ? "paid"
                      : refundedCount == totalTickets ? "refunded"
                      : "partially_refunded";

        List<OrderRowResponse.TicketRow> ticketRows = orderTickets.stream()
            .map(t -> new OrderRowResponse.TicketRow(
                t.getId(), t.getTierName(), t.getPriceMinor(), t.getState()))
            .toList();

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
