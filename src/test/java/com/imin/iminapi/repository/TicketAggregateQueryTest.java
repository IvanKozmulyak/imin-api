package com.imin.iminapi.repository;

import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The tier aggregate and attendee native queries, on Postgres. */
@IminIntegrationTest
class TicketAggregateQueryTest {

    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;

    // tickets.tier_id has no FK, so a random UUID is fine.
    private final UUID gaTier = UUID.randomUUID();

    // Seeded per-test: orders.event_id FKs events(id), events.created_by FKs users(id).
    private UUID eventId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        orgId = org.getId();
        eventId = fx.event(org, fx.owner(org), EventStatus.LIVE, clock.instant().plus(Duration.ofDays(1))).getId();
    }

    private Order order() {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(eventId);
        o.setOrgId(orgId);
        o.setEmail(fx.email("buyer"));
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
