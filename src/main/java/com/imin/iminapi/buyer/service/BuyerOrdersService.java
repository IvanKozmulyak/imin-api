package com.imin.iminapi.buyer.service;

import com.imin.iminapi.buyer.dto.BuyerOrdersResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketState;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads a buyer's orders across every organizer they have ever bought from.
 *
 * <h2>The invariant a future reviewer will want to "fix"</h2>
 *
 * <p>This is the <b>first authenticated read in the platform that is not
 * org-scoped</b>. Every other one derives its tenant from
 * {@code AuthPrincipal.orgId}; this one is scoped by the account's
 * <b>verified</b> addresses and crosses organizers by design — that crossing is
 * the entire product proposition of buyer accounts. Adding an org filter here
 * would make the endpoint return nothing and look like a data bug.
 *
 * <p>The scope that <i>is</i> load-bearing lives in the repository query:
 * {@code e.verifiedAt is not null}. An unverified {@code buyer_account_emails}
 * row is a claim anybody can make about any address; only a redeemed code turns
 * it into a permission. Relax that predicate and adding an address becomes a
 * way to read a stranger's ticket history — each row here carries an
 * {@code orderToken}, which opens the order and its tickets with no further
 * authentication.
 *
 * <h2>Pagination is not optional</h2>
 *
 * <p>Keyset on {@code (created_at DESC, id DESC)}, default 20, hard maximum 50.
 * An unpaginated cross-org list is unbounded by construction: role addresses
 * like {@code tickets@venue.com} accumulate for as long as the venue exists,
 * every row carries an event block, and the response is {@code no-store} so
 * nothing downstream absorbs it. Retrofitting pagination once the frontend
 * assumes an array is a breaking change; shipping it now is a query parameter.
 *
 * <h2>Three queries per page, not 2N+1</h2>
 *
 * <p>Orders, then events by id, then tickets by order id — each a single batch.
 * A page of 50 orders costs three round trips whatever the buyer bought.
 */
@Service
public class BuyerOrdersService {

    /** Page size when the caller asks for none. */
    public static final int DEFAULT_PAGE_SIZE = 20;
    /** Hard ceiling. A caller asking for more gets this, not an error. */
    public static final int MAX_PAGE_SIZE = 50;

    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final EventRepository events;

    public BuyerOrdersService(OrderRepository orders, TicketRepository tickets, EventRepository events) {
        this.orders = orders;
        this.tickets = tickets;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public BuyerOrdersResponse page(UUID accountId, Integer requestedSize, String cursor) {
        int size = clamp(requestedSize);

        // One extra row decides whether there is a next page, without a second
        // COUNT over a join that would have to scan the whole set to answer it.
        PageRequest probe = PageRequest.of(0, size + 1);
        List<Order> rows = cursor == null || cursor.isBlank()
                ? orders.findForBuyerAccount(accountId, probe)
                : decodeAndFetch(accountId, cursor, probe);

        boolean hasMore = rows.size() > size;
        List<Order> page = hasMore ? rows.subList(0, size) : rows;

        return new BuyerOrdersResponse(project(page), nextCursor(hasMore, page));
    }

    private List<Order> decodeAndFetch(UUID accountId, String cursor, PageRequest probe) {
        BuyerOrdersCursor decoded = BuyerOrdersCursor.decode(cursor);
        return orders.findForBuyerAccountBefore(accountId, decoded.createdAt(), decoded.id(), probe);
    }

    private static String nextCursor(boolean hasMore, List<Order> page) {
        if (!hasMore || page.isEmpty()) return null;
        Order last = page.get(page.size() - 1);
        return BuyerOrdersCursor.encode(last.getCreatedAt(), last.getId());
    }

    private static int clamp(Integer requested) {
        if (requested == null) return DEFAULT_PAGE_SIZE;
        return Math.min(Math.max(1, requested), MAX_PAGE_SIZE);
    }

    private List<BuyerOrdersResponse.Item> project(List<Order> page) {
        if (page.isEmpty()) return List.of();

        List<UUID> orderIds = page.stream().map(Order::getId).toList();
        Map<UUID, List<Ticket>> ticketsByOrder = new HashMap<>();
        for (Ticket ticket : tickets.findByOrderIdInOrderByOrderIdAscCreatedAtAsc(orderIds)) {
            ticketsByOrder.computeIfAbsent(ticket.getOrderId(), k -> new ArrayList<>()).add(ticket);
        }

        Map<UUID, Event> eventsById = new HashMap<>();
        for (Event event : events.findAllById(page.stream().map(Order::getEventId).distinct().toList())) {
            eventsById.put(event.getId(), event);
        }

        List<BuyerOrdersResponse.Item> items = new ArrayList<>(page.size());
        for (Order order : page) {
            List<Ticket> orderTickets = ticketsByOrder.getOrDefault(order.getId(), List.of());
            items.add(new BuyerOrdersResponse.Item(
                    order.getToken(),
                    order.getCreatedAt(),
                    order.getTotalMinor(),
                    order.getCurrency(),
                    // An order whose event has been hard-deleted still exists and
                    // still cost money; the row renders with a null event rather
                    // than vanishing from the buyer's history.
                    eventBlock(eventsById.get(order.getEventId())),
                    orderTickets.size(),
                    states(orderTickets)));
        }
        return items;
    }

    private static BuyerOrdersResponse.Event eventBlock(Event event) {
        if (event == null) return null;
        return new BuyerOrdersResponse.Event(
                event.getId(), event.getName(), event.getSlug(),
                event.getStartsAt(), event.getEndsAt(), event.getTimezone(),
                event.getVenueName(), event.getVenueCity(), event.getPosterUrl());
    }

    private static BuyerOrdersResponse.States states(List<Ticket> orderTickets) {
        int issued = 0, redeemed = 0, refunded = 0, revoked = 0;
        for (Ticket ticket : orderTickets) {
            // fromWire folds the legacy 'pre' rows into ISSUED, so the buyer
            // never sees two vocabularies for one state.
            switch (TicketState.fromWire(ticket.getState())) {
                case ISSUED -> issued++;
                case REDEEMED -> redeemed++;
                case REFUNDED -> refunded++;
                case REVOKED -> revoked++;
            }
        }
        return new BuyerOrdersResponse.States(issued, redeemed, refunded, revoked);
    }
}
