package com.imin.iminapi.support;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Unique-id rows for integration tests; every test owns what it creates, the database is shared. */
public class IminFixtures {

    private final OrganizationRepository orgs;
    private final UserRepository users;
    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final OrderRepository orders;
    private final TicketRepository tickets;
    private final Clock clock;

    public IminFixtures(OrganizationRepository orgs, UserRepository users, EventRepository events,
                        TicketTierRepository tiers, OrderRepository orders, TicketRepository tickets, Clock clock) {
        this.orgs = orgs;
        this.users = users;
        this.events = events;
        this.tiers = tiers;
        this.orders = orders;
        this.tickets = tickets;
        this.clock = clock;
    }

    private static String uid() {
        return UUID.randomUUID().toString();
    }

    public String email(String tag) {
        return tag + "-" + uid() + "@example.test";
    }

    public Organization org() {
        Organization o = new Organization();
        o.setName("Org " + uid().substring(0, 8));
        o.setSlug("org-" + uid());
        o.setContactEmail(email("org"));
        o.setCountry("DE");
        return orgs.save(o);
    }

    public User owner(Organization org) {
        User u = new User();
        u.setEmail(email("owner"));
        u.setOrgId(org.getId());
        u.setRole(UserRole.OWNER);
        return users.save(u);
    }

    public AuthPrincipal principal(User user) {
        return new AuthPrincipal(user.getId(), user.getOrgId(), user.getRole(), UUID.randomUUID());
    }

    /** PUBLIC, EUR, Europe/Berlin; a null {@code startsAt} leaves start and end unset, as a fresh draft has. */
    public Event event(Organization org, User createdBy, EventStatus status, Instant startsAt) {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setCreatedBy(createdBy.getId());
        e.setName("Event " + uid().substring(0, 8));
        e.setSlug("event-" + uid());
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(status);
        e.setCurrency("EUR");
        e.setTimezone("Europe/Berlin");
        if (startsAt != null) {
            e.setStartsAt(startsAt);
            e.setEndsAt(startsAt.plusSeconds(4 * 3600));
        }
        return events.save(e);
    }

    public TicketTier tier(Event event, int priceMinor, int quantity) {
        TicketTier t = new TicketTier();
        t.setEventId(event.getId());
        t.setName("Tier " + uid().substring(0, 8));
        t.setPriceMinor(priceMinor);
        t.setQuantity(quantity);
        return tiers.save(t);
    }

    /** Created now by the injected clock, so recency windows see it. */
    public Order order(Event event, String email) {
        Order o = new Order();
        o.setToken("ORD_" + uid());
        o.setEventId(event.getId());
        o.setOrgId(event.getOrgId());
        o.setEmail(email);
        o.setTotalMinor(1500L);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setCreatedAt(clock.instant());
        return orders.save(o);
    }

    public Ticket ticket(Order order, String state) {
        Ticket t = new Ticket();
        t.setToken("TKT_" + uid());
        t.setOrderId(order.getId());
        t.setEventId(order.getEventId());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setPriceMinor(1500);
        t.setState(state);
        return tickets.save(t);
    }
}
