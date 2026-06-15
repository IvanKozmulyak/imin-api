package com.imin.iminapi.repository;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class TicketAggregateQueryTest {

    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;

    // tickets.tier_id has no FK, so a random UUID is fine.
    private final UUID gaTier = UUID.randomUUID();

    // Seeded per-test: orders.event_id FKs events(id), events.created_by FKs users(id).
    private UUID eventId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        wipe();
        Organization org = new Organization();
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hi@test.example");
        org.setCountry("DE");
        org = orgs.save(org);
        orgId = org.getId();

        // events.created_by has an FK to users(id), so seed a real owner.
        User owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event event = new Event();
        event.setOrgId(org.getId());
        event.setName("Sales Night");
        event.setSlug("sales-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setStartsAt(Instant.now().plusSeconds(86_400));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);
        eventId = event.getId();
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        tickets.deleteAll();
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private Order order() {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(eventId);
        o.setOrgId(orgId);
        o.setEmail("buyer@example.com");
        o.setTotalMinor(0);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        return orders.save(o);
    }

    private void ticket(Order o, String state) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setOrderId(o.getId());
        t.setEventId(eventId);
        t.setTierId(gaTier);
        t.setTierName("GA");
        t.setPriceMinor(1500);
        t.setState(state);
        tickets.save(t);
    }

    @Test
    void tier_aggregates_exclude_refunded_and_revoked_and_count_redeemed() {
        Order o = order();
        ticket(o, Ticket.STATE_ISSUED);    // sold
        ticket(o, Ticket.STATE_REDEEMED);  // sold + redeemed
        ticket(o, Ticket.STATE_REFUNDED);  // excluded
        ticket(o, Ticket.STATE_REVOKED);   // excluded

        Map<UUID, Object[]> byTier = new HashMap<>();
        for (Object[] row : tickets.tierAggregates(eventId)) byTier.put((UUID) row[0], row);
        Object[] ga = byTier.get(gaTier);

        assertThat(((Number) ga[2]).longValue()).isEqualTo(2L);     // sold
        assertThat(((Number) ga[3]).longValue()).isEqualTo(3000L);  // gross = 2 * 1500
        assertThat(((Number) ga[4]).longValue()).isEqualTo(1L);     // redeemed

        assertThat(tickets.attendeeRows(eventId)).hasSize(2); // only sold rows
        assertThat(orders.countByEventId(eventId)).isEqualTo(1L);
    }
}
