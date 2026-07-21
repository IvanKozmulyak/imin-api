package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.*;
import com.imin.iminapi.predictor.model.EventSalesDaily;
import com.imin.iminapi.predictor.repository.EventSalesDailyRepository;
import com.imin.iminapi.predictor.service.SalesTrajectoryService;
import com.imin.iminapi.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2 (sales trajectory) — materialization correctness, backfill idempotency, and the
 * normalized read (% of final vs days-to-event). Spec §6.2.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class SalesTrajectoryServiceTest {

    @Autowired SalesTrajectoryService service;
    @Autowired EventSalesDailyRepository daily;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired TicketRepository tickets;
    @Autowired OrderRepository orders;

    private Organization org;
    private User owner;
    private Event event;
    private UUID orderId;
    private UUID tierA;
    private UUID tierB;

    // Event in UTC so ticket instants map to the intended calendar days without offset surprises.
    private static final Instant STARTS = Instant.parse("2026-03-10T20:00:00Z");

    @BeforeEach
    void setUp() {
        wipe();
        org = new Organization();
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("h@test.example");
        org.setCountry("NL");
        org = orgs.save(org);

        owner = new User();
        owner.setEmail("o-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("Trajectory Night");
        e.setSlug("traj-" + UUID.randomUUID().toString().substring(0, 8));
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setTimezone("UTC");
        e.setStartsAt(STARTS);
        e.setCreatedBy(owner.getId());
        e.setCurrency("EUR");
        event = events.save(e);

        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail("buyer@example.com");
        o.setTotalMinor(1000);
        o.setCurrency("EUR");
        o.setPaymentMethod("free");
        orderId = orders.save(o).getId();

        tierA = UUID.randomUUID();
        tierB = UUID.randomUUID();

        // tier A: 2 on 03-01, 3 on 03-03 ; tier B: 1 on 03-01, 4 on 03-05
        sold(tierA, "2026-03-01T10:00:00Z", 2);
        sold(tierA, "2026-03-03T10:00:00Z", 3);
        sold(tierB, "2026-03-01T12:00:00Z", 1);
        sold(tierB, "2026-03-05T18:00:00Z", 4);
        // a refunded ticket must NOT appear in the trajectory
        ticket(tierA, "2026-03-02T10:00:00Z", "refunded");
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        daily.deleteAll();
        tickets.deleteAll();
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private void sold(UUID tierId, String at, int n) {
        for (int i = 0; i < n; i++) ticket(tierId, at, "issued");
    }

    private void ticket(UUID tierId, String at, String state) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setOrderId(orderId);
        t.setEventId(event.getId());
        t.setTierId(tierId);
        t.setTierName("Tier");
        t.setPriceMinor(1000);
        t.setState(state);
        t.setCreatedAt(Instant.parse(at));
        tickets.save(t);
    }

    @Test
    void materialize_buildsPerTierDailyCumulative_andIsIdempotent() {
        service.materialize(event.getId());

        List<EventSalesDaily> rows = daily.findByEventIdOrderBySalesDateAscTierIdAsc(event.getId());
        // tier A: 2 days, tier B: 2 days -> 4 rows (refunded excluded)
        assertThat(rows).hasSize(4);

        EventSalesDaily a3 = rows.stream()
                .filter(r -> r.getTierId().equals(tierA) && r.getSalesDate().equals(LocalDate.parse("2026-03-03")))
                .findFirst().orElseThrow();
        assertThat(a3.getDailySold()).isEqualTo(3);
        assertThat(a3.getCumulativeSold()).isEqualTo(5); // 2 + 3

        EventSalesDaily b5 = rows.stream()
                .filter(r -> r.getTierId().equals(tierB) && r.getSalesDate().equals(LocalDate.parse("2026-03-05")))
                .findFirst().orElseThrow();
        assertThat(b5.getCumulativeSold()).isEqualTo(5); // 1 + 4

        // idempotent: a second run replaces, not appends
        service.materialize(event.getId());
        assertThat(daily.findByEventIdOrderBySalesDateAscTierIdAsc(event.getId())).hasSize(4);
    }

    @Test
    void normalizedCurve_reportsPctOfFinalAndDaysToEvent() {
        service.materialize(event.getId());

        var curve = service.normalizedCurve(event.getId());
        assertThat(curve.finalTotal()).isEqualTo(10); // 5 (A) + 5 (B)
        assertThat(curve.points()).hasSize(3); // days 03-01, 03-03, 03-05

        var p1 = curve.points().get(0);
        assertThat(p1.salesDate()).isEqualTo(LocalDate.parse("2026-03-01"));
        assertThat(p1.cumulativeSold()).isEqualTo(3);          // 2 (A) + 1 (B)
        assertThat(p1.pctOfFinal()).isEqualTo(0.3);
        assertThat(p1.daysToEvent()).isEqualTo(9);             // 03-01 -> 03-10

        var p3 = curve.points().get(2);
        assertThat(p3.salesDate()).isEqualTo(LocalDate.parse("2026-03-05"));
        assertThat(p3.cumulativeSold()).isEqualTo(10);
        assertThat(p3.pctOfFinal()).isEqualTo(1.0);
        assertThat(p3.daysToEvent()).isEqualTo(5);
    }

    @Test
    void materialize_clearsRows_whenNoSoldTickets() {
        service.materialize(event.getId());
        assertThat(daily.findByEventIdOrderBySalesDateAscTierIdAsc(event.getId())).isNotEmpty();

        tickets.deleteAll(); // all sales gone
        service.materialize(event.getId());
        assertThat(daily.findByEventIdOrderBySalesDateAscTierIdAsc(event.getId())).isEmpty();
    }
}
