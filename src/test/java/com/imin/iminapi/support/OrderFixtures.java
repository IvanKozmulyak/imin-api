package com.imin.iminapi.support;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.UserRepository;

import java.time.Instant;
import java.util.UUID;

/**
 * Org → event → order → ticket fixtures, shared by the buyer-account tests.
 *
 * <p>Lives here rather than being copied into each test class because the buyer
 * order list is a <b>cross-org</b> read: several of its tests need two or three
 * independent organizers in one scenario, and a per-class copy of this chain
 * drifts the moment a NOT NULL column is added.
 */
public final class OrderFixtures {

    private OrderFixtures() {}

    public static Event event(OrganizationRepository orgs, UserRepository users, EventRepository events,
                              String name, Instant startsAt) {
        Organization org = new Organization();
        org.setName(name + " Org");
        org.setSlug("org-" + UUID.randomUUID());
        org.setContactEmail("org-" + UUID.randomUUID() + "@example.com");
        org.setCountry("DE");
        org = orgs.save(org);

        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event event = new Event();
        event.setOrgId(org.getId());
        event.setName(name);
        event.setSlug("event-" + UUID.randomUUID());
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setCurrency("EUR");
        event.setStartsAt(startsAt);
        event.setEndsAt(startsAt.plusSeconds(4 * 3600));
        event.setTimezone("Europe/Berlin");
        event.setVenueName("Venue " + name);
        event.setVenueCity("Berlin");
        event.setPosterUrl("https://cdn.example.com/" + UUID.randomUUID() + ".png");
        event.setCreatedBy(owner.getId());
        return events.save(event);
    }

    /** An order on {@code event} for {@code email}. {@code createdAt} is explicit so keyset paging can be tested. */
    public static Order order(OrderRepository orders, Event event, String email, Instant createdAt) {
        Order order = new Order();
        order.setToken("ORD_" + UUID.randomUUID());
        order.setEventId(event.getId());
        order.setOrgId(event.getOrgId());
        order.setEmail(email);
        order.setTotalMinor(1500L);
        order.setCurrency("EUR");
        order.setPaymentMethod("stripe");
        order.setCreatedAt(createdAt);
        return orders.save(order);
    }

    public static Ticket ticket(TicketRepository tickets, Order order, String state) {
        Ticket ticket = new Ticket();
        ticket.setToken("TKT_" + UUID.randomUUID());
        ticket.setOrderId(order.getId());
        ticket.setEventId(order.getEventId());
        ticket.setTierId(UUID.randomUUID());
        ticket.setTierName("GA");
        ticket.setPriceMinor(1500);
        ticket.setState(state);
        return tickets.save(ticket);
    }
}
