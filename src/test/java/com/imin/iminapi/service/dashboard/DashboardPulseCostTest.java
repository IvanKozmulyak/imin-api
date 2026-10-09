package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.dto.dashboard.DashboardPulseResponse;
import com.imin.iminapi.dto.dashboard.DashboardPulseResponse.LastSale;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Pulse output on Postgres for every branch of the last-sale scan, and the statement count of one poll. */
@IminIntegrationTest
class DashboardPulseCostTest {

    @Autowired DashboardPulseService pulse;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;

    private final List<UUID> orgIds = new ArrayList<>();
    private Organization org;
    private User owner;
    private AuthPrincipal p;
    private Instant base;

    @BeforeEach
    void setUp() {
        org = fx.org();
        orgIds.add(org.getId());
        owner = fx.owner(org);
        p = fx.principal(owner);
        base = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(3600);
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void org_scope_skips_deleted_event_refunded_and_ticketless_orders_and_reports_the_next_one() {
        Event onSale = futureLive();
        Event deleted = deletedLive();
        order(deleted, 50, "GA:" + Ticket.STATE_ISSUED);
        order(onSale, 40, "VIP:" + Ticket.STATE_REFUNDED);
        order(onSale, 30);
        order(onSale, 20, "Late:" + Ticket.STATE_ISSUED, "Early Bird:" + Ticket.STATE_REDEEMED,
                "Late:" + Ticket.STATE_ISSUED, "VIP:" + Ticket.STATE_REFUNDED);
        order(onSale, 10, "Older:" + Ticket.STATE_ISSUED);

        assertThat(pulse.pulse(p, null)).isEqualTo(new DashboardPulseResponse(true, 1,
                new LastSale(base.plusSeconds(20), onSale.getId(), onSale.getName(), List.of("Late", "Early Bird"), 3)));
    }

    @Test
    void org_scope_with_every_order_skipped_has_no_last_sale() {
        Event onSale = futureLive();
        Event deleted = deletedLive();
        order(deleted, 30, "GA:" + Ticket.STATE_ISSUED);
        order(onSale, 20, "GA:" + Ticket.STATE_REFUNDED);
        order(onSale, 10);

        assertThat(pulse.pulse(p, null)).isEqualTo(new DashboardPulseResponse(true, 1, null));
    }

    @Test
    void event_scope_skips_the_refunded_order_and_reports_the_next_one() {
        Event ev = futureLive();
        Event other = futureLive();
        order(other, 40, "Elsewhere:" + Ticket.STATE_ISSUED);
        order(ev, 30, "VIP:" + Ticket.STATE_REFUNDED);
        order(ev, 20, "GA:" + Ticket.STATE_ISSUED, "GA:" + Ticket.STATE_ISSUED);

        assertThat(pulse.pulse(p, ev.getId())).isEqualTo(new DashboardPulseResponse(true, 1,
                new LastSale(base.plusSeconds(20), ev.getId(), ev.getName(), List.of("GA"), 2)));
    }

    @Test
    void org_scope_poll_does_not_query_per_skipped_order() {
        Event onSale = futureLive();
        for (int i = 0; i < 10; i++) order(deletedLive(), 100 + i, "GA:" + Ticket.STATE_ISSUED);
        order(onSale, 10, "GA:" + Ticket.STATE_ISSUED);

        long statements = statementsDuring(() -> pulse.pulse(p, null));

        assertThat(statements).as("on-sale count, orders, tickets, events — not one per skipped order")
                .isLessThanOrEqualTo(4);
    }

    @Test
    void event_scope_poll_does_not_reload_its_event_per_order() {
        Event ev = futureLive();
        order(ev, 30, "VIP:" + Ticket.STATE_REFUNDED);
        order(ev, 20, "GA:" + Ticket.STATE_ISSUED);

        long statements = statementsDuring(() -> pulse.pulse(p, ev.getId()));

        assertThat(statements).as("event, on-sale count, orders, tickets").isLessThanOrEqualTo(4);
    }

    private long statementsDuring(Supplier<DashboardPulseResponse> call) {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean wasOn = stats.isStatisticsEnabled();
        try {
            stats.clear();
            stats.setStatisticsEnabled(true);
            call.get();
            long n = stats.getPrepareStatementCount();
            System.out.printf("[pulse-cost] %d statements%n", n);
            return n;
        } finally {
            stats.setStatisticsEnabled(wasOn);
            stats.clear();
        }
    }

    private Event futureLive() {
        return fx.event(org, owner, EventStatus.LIVE, Instant.now().plusSeconds(7 * 86_400));
    }

    private Event deletedLive() {
        Event e = futureLive();
        jdbc.update("UPDATE events SET deleted_at = now() WHERE id = ?", e.getId());
        return e;
    }

    /** An order created {@code offsetSeconds} after {@code base}, with tickets given as "tier:state" in ticket order. */
    private Order order(Event event, int offsetSeconds, String... tickets) {
        Order o = fx.order(event, fx.email("pulse"));
        jdbc.update("UPDATE orders SET created_at = ? WHERE id = ?",
                Timestamp.from(base.plusSeconds(offsetSeconds)), o.getId());
        for (int i = 0; i < tickets.length; i++) {
            String[] tierState = tickets[i].split(":");
            Ticket t = fx.ticket(o, tierState[1]);
            jdbc.update("UPDATE tickets SET tier_name = ?, created_at = ? WHERE id = ?",
                    tierState[0], Timestamp.from(base.plusMillis(i)), t.getId());
        }
        return o;
    }
}
