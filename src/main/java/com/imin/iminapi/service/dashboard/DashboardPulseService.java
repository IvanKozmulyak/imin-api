package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.dto.dashboard.DashboardPulseResponse;
import com.imin.iminapi.dto.dashboard.DashboardPulseResponse.LastSale;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class DashboardPulseService {

    // ponytail: only the newest 32 orders are scanned; a run of 32 fully refunded orders reads as no sale.
    static final int ORDER_SCAN_LIMIT = 32;

    private final EventRepository events;
    private final OrderRepository orders;
    private final TicketRepository tickets;

    public DashboardPulseService(EventRepository events, OrderRepository orders, TicketRepository tickets) {
        this.events = events;
        this.orders = orders;
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    public DashboardPulseResponse pulse(AuthPrincipal p, UUID eventIdOrNull) {
        Instant now = Instant.now();
        long onSaleCount;
        List<Order> recent;
        if (eventIdOrNull != null) {
            Event e = events.findActive(eventIdOrNull).orElseThrow(() -> ApiException.notFound("Event"));
            if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
            onSaleCount = events.countOnSaleById(eventIdOrNull, now);
            recent = orders.findByEventIdOrderByCreatedAtDesc(eventIdOrNull,
                    PageRequest.of(0, ORDER_SCAN_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt")));
        } else {
            onSaleCount = events.countOnSaleByOrg(p.orgId(), now);
            recent = orders.findByOrgIdOrderByCreatedAtDesc(p.orgId(), PageRequest.of(0, ORDER_SCAN_LIMIT));
        }
        int count = (int) Math.min(Integer.MAX_VALUE, onSaleCount);
        return new DashboardPulseResponse(count > 0, count, lastSale(recent));
    }

    private LastSale lastSale(List<Order> recent) {
        if (recent.isEmpty()) return null;
        Map<UUID, List<Ticket>> byOrder = tickets.findByOrderIdInOrderByOrderIdAscCreatedAtAsc(
                        recent.stream().map(Order::getId).toList())
                .stream().collect(Collectors.groupingBy(Ticket::getOrderId));
        for (Order o : recent) {
            List<Ticket> live = byOrder.getOrDefault(o.getId(), List.of()).stream()
                    .filter(t -> !Ticket.STATE_REFUNDED.equals(t.getState()))
                    .toList();
            if (live.isEmpty()) continue;
            Optional<Event> event = events.findActive(o.getEventId());
            if (event.isEmpty()) continue;
            List<String> tierNames = new ArrayList<>(
                    live.stream().map(Ticket::getTierName).collect(Collectors.toCollection(LinkedHashSet::new)));
            return new LastSale(o.getCreatedAt(), o.getEventId(), event.get().getName(), tierNames, live.size());
        }
        return null;
    }
}
