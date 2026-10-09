package com.imin.iminapi.controller.order;

import com.imin.iminapi.controller.order.dto.OrderRowResponse;
import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrderStatusSearch;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.audit.AuditActions;
import com.imin.iminapi.service.audit.AuditLogger;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Dashboard endpoint backing the EventDetailPage "Orders" tab.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/orders")
public class EventOrdersController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    /**
     * Lower sorts first, i.e. wins: OPEN, then LOST, then newest openedAt, then lowest id.
     * The id is the tiebreak Stripe does not give us — two chargebacks opened in the same
     * second would otherwise render whichever row the query happened to return first.
     */
    private static final Comparator<Dispute> GOVERNING_ORDER =
            Comparator.comparingInt((Dispute d) -> switch (d.getStatus()) {
                        case OPEN -> 0;
                        case LOST -> 1;
                        default -> 2;
                    })
                    .thenComparing(d -> d.getOpenedAt() == null ? Instant.EPOCH : d.getOpenedAt(),
                            Comparator.reverseOrder())
                    .thenComparing(Dispute::getId);

    private final EventRepository events;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final DisputeRepository disputes;
    private final PromoCodeRepository promos;
    private final OrderStatusSearch search;
    private final AuditLogger audit;

    public EventOrdersController(EventRepository events,
                                 OrderRepository orders,
                                 TicketRepository tickets,
                                 DisputeRepository disputes,
                                 PromoCodeRepository promos,
                                 OrderStatusSearch search,
                                 AuditLogger audit) {
        this.events = events;
        this.orders = orders;
        this.tickets = tickets;
        this.disputes = disputes;
        this.promos = promos;
        this.search = search;
        this.audit = audit;
    }

    @GetMapping
    public List<OrderRowResponse> list(@PathVariable UUID eventId,
                                       @Parameter(schema = @Schema(allowableValues =
                                               {"paid", "partially_refunded", "refunded", "disputed"}))
                                       @RequestParam(name = "status", required = false) String status,
                                       @Parameter(description = "Case-insensitive email substring or shortCode prefix")
                                       @RequestParam(name = "q", required = false) String q,
                                       @RequestParam(name = "limit", required = false) Integer limit,
                                       @CurrentUser AuthPrincipal principal) {
        requireOwnEvent(eventId, principal);
        int capped = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(1, limit), MAX_LIMIT);
        return rows(search.find(eventId, statusFilter(status), searchTerm(q), capped));
    }

    /** One row; an order of another event, or of another org's event, is 404 like a missing one. */
    @GetMapping("/{orderId}")
    public OrderRowResponse detail(@PathVariable UUID eventId,
                                   @PathVariable UUID orderId,
                                   @CurrentUser AuthPrincipal principal) {
        requireOwnEvent(eventId, principal);
        List<OrderRowResponse> one = rows(search.findOne(eventId, orderId));
        if (one.isEmpty()) throw ApiException.notFound("Order");
        return one.get(0);
    }

    /**
     * Every order matching the list's filters, uncapped, for any org member. Audited after the CSV
     * is built so a refused request does not read as an export.
     */
    // ponytail: built in memory; stream it once one event reaches tens of thousands of orders.
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<String> export(@PathVariable UUID eventId,
                                         @Parameter(schema = @Schema(allowableValues =
                                                 {"paid", "partially_refunded", "refunded", "disputed"}))
                                         @RequestParam(name = "status", required = false) String status,
                                         @Parameter(description = "Case-insensitive email substring or shortCode prefix")
                                         @RequestParam(name = "q", required = false) String q,
                                         @CurrentUser AuthPrincipal principal) {
        requireOwnEvent(eventId, principal);
        List<OrderRowResponse> rows = rows(search.find(eventId, statusFilter(status), searchTerm(q), null));
        String csv = OrdersCsv.write(rows);
        audit.record(principal, AuditActions.ORDERS_EXPORTED, "event", eventId,
                "Orders CSV exported (" + rows.size() + " row(s))");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"orders-" + eventId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(csv);
    }

    private void requireOwnEvent(UUID eventId, AuthPrincipal principal) {
        Event event = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!event.getOrgId().equals(principal.orgId())) throw ApiException.notFound("Event");
    }

    private static String statusFilter(String status) {
        if (status == null || status.isBlank()) return null;
        String s = status.trim();
        if (!OrderStatusSearch.STATUSES.contains(s)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ErrorCode.FIELD_INVALID, "Validation failed",
                    Map.of("status", "must be one of paid, partially_refunded, refunded, disputed"));
        }
        return s;
    }

    private static String searchTerm(String q) {
        return q == null || q.isBlank() ? null : q.trim();
    }

    /** Rows in the order of {@code hits}; tickets, disputes and promo codes loaded once for all of them. */
    private List<OrderRowResponse> rows(List<OrderStatusSearch.Hit> hits) {
        if (hits.isEmpty()) return List.of();
        List<UUID> orderIds = hits.stream().map(OrderStatusSearch.Hit::orderId).toList();
        Map<UUID, Order> byId = orders.findAllById(orderIds).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));
        Map<UUID, List<Ticket>> ticketsByOrder = loadTicketsByOrder(orderIds);
        Map<UUID, Dispute> disputeByOrder = loadGoverningDisputes(orderIds);
        Map<UUID, String> promoCodes = loadPromoCodes(byId.values());
        List<OrderRowResponse> rows = new ArrayList<>(hits.size());
        for (OrderStatusSearch.Hit hit : hits) {
            Order o = byId.get(hit.orderId());
            if (o == null) continue;
            rows.add(toRow(o, hit.status(), ticketsByOrder.getOrDefault(o.getId(), List.of()),
                    disputeByOrder.get(o.getId()),
                    o.getPromoCodeId() == null ? null : promoCodes.get(o.getPromoCodeId())));
        }
        return rows;
    }

    private Map<UUID, String> loadPromoCodes(Collection<Order> page) {
        List<UUID> ids = page.stream().map(Order::getPromoCodeId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return promos.findAllById(ids).stream().collect(Collectors.toMap(PromoCode::getId, PromoCode::getCode));
    }

    private Map<UUID, List<Ticket>> loadTicketsByOrder(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        List<Ticket> all = tickets.findByOrderIdInOrderByOrderIdAscCreatedAtAsc(orderIds);
        Map<UUID, List<Ticket>> byOrder = new HashMap<>(orderIds.size());
        for (Ticket t : all) byOrder.computeIfAbsent(t.getOrderId(), k -> new ArrayList<>()).add(t);
        return byOrder;
    }

    /**
     * One dispute per order for the page: the OPEN one, else the LOST one, else the most
     * recently opened. An order can collect several chargebacks, and the row has to show
     * the one that is actually governing its money.
     */
    private Map<UUID, Dispute> loadGoverningDisputes(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        Map<UUID, Dispute> governing = new HashMap<>();
        for (Dispute d : disputes.findByOrderIdIn(orderIds)) {
            governing.merge(d.getOrderId(), d,
                    (a, b) -> GOVERNING_ORDER.compare(a, b) <= 0 ? a : b);
        }
        return governing;
    }

    /** {@code status} comes from {@link OrderStatusSearch}, the one definition of it. */
    private static OrderRowResponse toRow(Order o, String status, List<Ticket> orderTickets,
                                          Dispute dispute, String promoCode) {
        int totalTickets = orderTickets.size();
        int refundedCount = (int) orderTickets.stream()
            .filter(t -> Ticket.STATE_REFUNDED.equals(t.getState()))
            .count();

        List<OrderRowResponse.TicketRow> ticketRows = orderTickets.stream()
            .map(t -> new OrderRowResponse.TicketRow(
                t.getId(), t.getTierName(), t.getPriceMinor(), t.getState(), t.getRedeemedAt()))
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
            ticketRows,
            dispute == null ? null : new OrderRowResponse.DisputeRow(
                dispute.getStatus().toWire(),
                dispute.getAmountMinor(),
                dispute.getCurrency(),
                dispute.getOpenedAt()),
            promoCode
        );
    }
}
