package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.model.Event;
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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Seeds orgs, events, paid orders and memberships through the real repositories. */
record FanFeatureFixtures(OrganizationRepository orgs, UserRepository users, EventRepository events,
                          OrderRepository orders, TicketRepository tickets,
                          ConsumerRepository consumers, MembershipRepository memberships) {

    record Org(UUID id, UUID ownerId) {}

    Org org(String timezone) {
        Organization o = new Organization();
        o.setName("Fan features org");
        o.setSlug("ff-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("ff-" + UUID.randomUUID() + "@test.com");
        o.setCountry("FR");
        o.setTimezone(timezone);
        o = orgs.save(o);
        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@test.com");
        owner.setOrgId(o.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);
        return new Org(o.getId(), owner.getId());
    }

    Event event(Org org, String genre, Instant startsAt) {
        Event e = new Event();
        e.setOrgId(org.id());
        e.setName("Night");
        e.setSlug("ff-ev-" + UUID.randomUUID().toString().substring(0, 8));
        e.setGenre(genre);
        e.setType("club");
        e.setVenueCity("Metz");
        e.setCreatedBy(org.ownerId());
        e.setStartsAt(startsAt);
        e.setEndsAt(startsAt.plus(6, ChronoUnit.HOURS));
        return events.save(e);
    }

    /** A stripe order with one ticket per state given. */
    Order paidOrder(Event event, String email, Instant createdAt, String... ticketStates) {
        Order o = new Order();
        o.setEventId(event.getId());
        o.setOrgId(event.getOrgId());
        o.setEmail(email);
        o.setTotalMinor(2500);
        o.setCurrency("EUR");
        o.setPaymentMethod("stripe");
        o.setToken(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setStripePaymentIntentId("pi_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        o.setCreatedAt(createdAt);
        o = orders.save(o);
        for (String state : ticketStates) {
            Ticket t = new Ticket();
            t.setOrderId(o.getId());
            t.setEventId(event.getId());
            t.setTierId(UUID.randomUUID());
            t.setTierName("GA");
            t.setPriceMinor(2500);
            t.setState(state);
            t.setToken(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
            if (Ticket.STATE_REDEEMED.equals(state)) t.setRedeemedAt(Instant.now());
            tickets.save(t);
        }
        return o;
    }

    Membership membership(UUID orgId, String email) {
        Consumer c = consumers.findByNormalizedEmail(email).orElseGet(() -> {
            Consumer fresh = new Consumer();
            fresh.setNormalizedEmail(email);
            return consumers.save(fresh);
        });
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(c.getConsumerId());
        return memberships.save(m);
    }

    static String email(String tag) {
        return tag + "-" + UUID.randomUUID() + "@example.com";
    }
}
