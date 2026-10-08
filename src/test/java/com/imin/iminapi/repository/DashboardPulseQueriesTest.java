package com.imin.iminapi.repository;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Each test inserts the row its predicate excludes first; dropping that predicate turns it red. On Postgres. */
@IminIntegrationTest
class DashboardPulseQueriesTest {

    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired IminFixtures fx;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;

    private static final long DAY = 86_400;

    private final List<UUID> orgIds = new ArrayList<>();
    private Instant now;
    private UUID orgA;
    private UUID userA;
    private UUID orgB;
    private UUID userB;

    @BeforeEach
    void setUp() {
        now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Organization a = fx.org();
        Organization b = fx.org();
        orgIds.addAll(List.of(a.getId(), b.getId()));
        orgA = a.getId();
        userA = fx.owner(a).getId();
        orgB = b.getId();
        userB = fx.owner(b).getId();
    }

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void another_orgs_newer_order_is_not_returned() {
        UUID theirs = event(orgB, userB, EventStatus.LIVE, now.plusSeconds(DAY), null, null, null);
        UUID ours = event(orgA, userA, EventStatus.LIVE, now.plusSeconds(DAY), null, null, null);
        order(orgB, theirs, now.minusSeconds(60));
        Order mine = order(orgA, ours, now.minusSeconds(3600));

        List<Order> got = orders.findByOrgIdOrderByCreatedAtDesc(orgA, PageRequest.of(0, 32));

        assertThat(got).extracting(Order::getId).containsExactly(mine.getId());
    }

    @Test
    void org_orders_come_newest_first() {
        UUID ev = event(orgA, userA, EventStatus.LIVE, now.plusSeconds(DAY), null, null, null);
        Order older = order(orgA, ev, now.minusSeconds(3600));
        Order newer = order(orgA, ev, now.minusSeconds(60));

        assertThat(orders.findByOrgIdOrderByCreatedAtDesc(orgA, PageRequest.of(0, 32)))
                .extracting(Order::getId).containsExactly(newer.getId(), older.getId());
    }

    @Test
    void open_window_with_bounds_and_without_bounds_is_counted() {
        UUID unbounded = event(orgA, userA, EventStatus.LIVE, now.plusSeconds(DAY), null, null, null);
        UUID bounded = event(orgA, userA, EventStatus.LIVE, now.plusSeconds(DAY),
                now.minusSeconds(DAY), now.plusSeconds(3600), null);

        assertThat(events.countOnSaleByOrg(orgA, now)).isEqualTo(2);
        assertThat(events.countOnSaleById(unbounded, now)).isEqualTo(1);
        assertThat(events.countOnSaleById(bounded, now)).isEqualTo(1);
    }

    @Test
    void another_orgs_on_sale_event_is_not_counted_for_this_org() {
        event(orgB, userB, EventStatus.LIVE, now.plusSeconds(DAY), null, null, null);
        onSale(orgA);

        assertThat(events.countOnSaleByOrg(orgA, now)).isEqualTo(1);
    }

    @Test
    void count_by_id_counts_only_that_event() {
        UUID other = onSale(orgA);
        UUID draft = event(orgA, userA, EventStatus.DRAFT, now.plusSeconds(DAY), null, null, null);

        assertThat(events.countOnSaleById(draft, now)).isZero();
        assertThat(events.countOnSaleById(other, now)).isEqualTo(1);
    }

    @Test
    void sale_closed_in_the_past_is_not_counted() {
        UUID closed = event(orgA, userA, EventStatus.LIVE, now.plusSeconds(DAY), null, now.minusSeconds(60), null);
        onSale(orgA);

        assertExcluded(closed);
    }

    @Test
    void sale_opening_in_the_future_is_not_counted() {
        UUID notYet = event(orgA, userA, EventStatus.LIVE, now.plusSeconds(DAY), now.plusSeconds(60), null, null);
        onSale(orgA);

        assertExcluded(notYet);
    }

    @Test
    void event_that_already_started_is_not_counted() {
        UUID started = event(orgA, userA, EventStatus.LIVE, now.minusSeconds(60), null, null, null);
        onSale(orgA);

        assertExcluded(started);
    }

    @Test
    void deleted_live_event_is_not_counted() {
        UUID deleted = event(orgA, userA, EventStatus.LIVE, now.plusSeconds(DAY), null, null, now.minusSeconds(60));
        onSale(orgA);

        assertExcluded(deleted);
    }

    @Test
    void draft_is_not_counted() {
        UUID draft = event(orgA, userA, EventStatus.DRAFT, now.plusSeconds(DAY), null, null, null);
        onSale(orgA);

        assertExcluded(draft);
    }

    /** The org count sees only the one on-sale event; the excluded event's own count is 0. */
    private void assertExcluded(UUID excluded) {
        assertThat(events.countOnSaleByOrg(orgA, now)).isEqualTo(1);
        assertThat(events.countOnSaleById(excluded, now)).isZero();
    }

    private UUID onSale(UUID orgId) {
        return event(orgId, orgId.equals(orgA) ? userA : userB, EventStatus.LIVE, now.plusSeconds(2 * DAY),
                null, null, null);
    }

    private UUID event(UUID orgId, UUID userId, EventStatus status, Instant startsAt,
                       Instant onSaleAt, Instant saleClosesAt, Instant deletedAt) {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setCreatedBy(userId);
        e.setName("Night");
        e.setSlug("pulse-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(status);
        e.setCurrency("EUR");
        e.setStartsAt(startsAt);
        e.setOnSaleAt(onSaleAt);
        e.setSaleClosesAt(saleClosesAt);
        e.setDeletedAt(deletedAt);
        return events.save(e).getId();
    }

    private Order order(UUID orgId, UUID eventId, Instant createdAt) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(eventId);
        o.setOrgId(orgId);
        o.setEmail(fx.email("buyer"));
        o.setTotalMinor(0);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        o.setCreatedAt(createdAt);
        return orders.save(o);
    }
}
