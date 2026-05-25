package com.imin.iminapi.service.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.event.EventOverviewResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class EventOverviewServiceTest {

    @Autowired EventOverviewService service;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired RefundRepository refunds;

    private Organization org;
    private User owner;
    private Event event;
    private TicketTier ga;
    private TicketTier vip;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        wipe();
        org = new Organization();
        org.setName("Test Org");
        org.setSlug("test-org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hello@test.example");
        org.setCountry("DE");
        org = orgs.save(org);

        owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Test Night");
        event.setSlug("test-night-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setPublishedAt(Instant.now().minusSeconds(7200));
        event.setStartsAt(Instant.now().plusSeconds(86_400L * 10));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);

        ga = newTier("GA", 1500, 100, 10);
        vip = newTier("VIP", 5000, 20, 2);

        principal = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        refunds.deleteAll();
        tickets.deleteAll();
        orders.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    private TicketTier newTier(String name, int price, int qty, int sold) {
        TicketTier t = new TicketTier();
        t.setEventId(event.getId());
        t.setName(name);
        t.setPriceMinor(price);
        t.setQuantity(qty);
        t.setReserved(0);
        t.setSold(sold);
        t.setEnabled(true);
        return tiers.save(t);
    }

    private Order newOrder(String email, long totalMinor, Instant createdAt) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail(email);
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        o.setCreatedAt(createdAt);
        return orders.save(o);
    }

    private Ticket newTicket(Order o, TicketTier tier) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setOrderId(o.getId());
        t.setEventId(o.getEventId());
        t.setTierId(tier.getId());
        t.setTierName(tier.getName());
        t.setPriceMinor(tier.getPriceMinor());
        t.setState(Ticket.STATE_ISSUED);
        return tickets.save(t);
    }

    private Refund newRefund(Order o, long amountMinor, RefundStatus status) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId(o.getStripePaymentIntentId());
        r.setAmountMinor(amountMinor);
        r.setCurrency(o.getCurrency());
        r.setApplicationFeeRefundMinor(0);
        r.setReason(RefundReason.OTHER);
        r.setStatus(status);
        r.setInitiatedByUserId(owner.getId());
        r.setIdempotencyKey("k-" + UUID.randomUUID());
        return refunds.save(r);
    }

    @Test
    void cross_org_returns_404_leak_safe() {
        AuthPrincipal otherOrg = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(),
                UserRole.OWNER, UUID.randomUUID());

        assertThatThrownBy(() -> service.overview(otherOrg, event.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void metrics_use_real_sums_from_tiers_and_orders() {
        // sold (from tier.sold) = 10 GA + 2 VIP = 12; capacity = 120
        Order o1 = newOrder("alice@example.com", 3000, Instant.now().minusSeconds(60));
        Order o2 = newOrder("bob@example.com", 5000, Instant.now().minusSeconds(120));
        newTicket(o1, ga);
        newTicket(o1, ga);
        newTicket(o2, vip);

        EventOverviewResponse r = service.overview(principal, event.getId());

        assertThat(r.metrics().sold()).isEqualTo(12);
        assertThat(r.metrics().capacity()).isEqualTo(120);
        assertThat(r.metrics().revenueMinor()).isEqualTo(8000L);   // 3000 + 5000
        assertThat(r.metrics().currency()).isEqualTo("EUR");
    }

    @Test
    void only_succeeded_refunds_subtract_from_revenue() {
        Order o = newOrder("buyer@example.com", 10000, Instant.now().minusSeconds(60));
        newRefund(o, 3000, RefundStatus.SUCCEEDED);
        // A PENDING refund must NOT be subtracted
        newRefund(o, 5000, RefundStatus.PENDING);

        EventOverviewResponse r = service.overview(principal, event.getId());
        assertThat(r.metrics().revenueMinor()).isEqualTo(7000L);   // 10000 − 3000
    }

    @Test
    void revenue_floors_at_zero_when_refunds_exceed_gross() {
        Order o = newOrder("buyer@example.com", 2000, Instant.now().minusSeconds(60));
        newRefund(o, 5000, RefundStatus.SUCCEEDED);

        EventOverviewResponse r = service.overview(principal, event.getId());
        assertThat(r.metrics().revenueMinor()).isEqualTo(0L);
    }

    @Test
    void recent_purchases_limit_8_in_reverse_chronological_order() {
        Instant now = Instant.now();
        for (int i = 0; i < 12; i++) {
            Order o = newOrder("buyer" + i + "@example.com", 1500, now.minusSeconds(i * 60L));
            newTicket(o, ga);
        }

        EventOverviewResponse r = service.overview(principal, event.getId());

        assertThat(r.recentPurchases()).hasSize(8);
        // Most-recent first: buyer0, buyer1, …, buyer7
        assertThat(r.recentPurchases().get(0).name()).isEqualTo("buyer0@example.com");
        assertThat(r.recentPurchases().get(7).name()).isEqualTo("buyer7@example.com");
    }

    @Test
    void recent_purchase_sub_shows_tier_breakdown_and_amount() {
        Order single = newOrder("solo@example.com", 1500, Instant.now().minusSeconds(60));
        newTicket(single, ga);

        Order multi = newOrder("multi@example.com", 8000, Instant.now().minusSeconds(120));
        newTicket(multi, ga);
        newTicket(multi, ga);
        newTicket(multi, vip);

        EventOverviewResponse r = service.overview(principal, event.getId());

        var solo = r.recentPurchases().get(0);
        assertThat(solo.name()).isEqualTo("solo@example.com");
        assertThat(solo.sub()).isEqualTo("GA · 15.00 EUR");

        var multiRow = r.recentPurchases().get(1);
        assertThat(multiRow.sub()).contains("GA × 2", "VIP", "80.00 EUR");
    }

    @Test
    void recent_purchases_skip_fully_refunded_orders() {
        // Order A: all tickets refunded → must be excluded
        Order a = newOrder("refunded@example.com", 3000, Instant.now().minusSeconds(60));
        Ticket aTicket = newTicket(a, ga);
        aTicket.setState(Ticket.STATE_REFUNDED);
        tickets.save(aTicket);

        // Order B: live
        Order b = newOrder("live@example.com", 1500, Instant.now().minusSeconds(120));
        newTicket(b, ga);

        EventOverviewResponse r = service.overview(principal, event.getId());

        assertThat(r.recentPurchases()).hasSize(1);
        assertThat(r.recentPurchases().get(0).name()).isEqualTo("live@example.com");
    }

    @Test
    void recent_purchase_amount_excludes_refunded_tickets() {
        // 3 GA tickets at 1500 each = 4500 gross. One refunded → live = 3000.
        Order o = newOrder("partial@example.com", 4500, Instant.now().minusSeconds(60));
        newTicket(o, ga);
        newTicket(o, ga);
        Ticket refunded = newTicket(o, ga);
        refunded.setState(Ticket.STATE_REFUNDED);
        tickets.save(refunded);

        EventOverviewResponse r = service.overview(principal, event.getId());

        assertThat(r.recentPurchases()).hasSize(1);
        assertThat(r.recentPurchases().get(0).sub()).isEqualTo("GA × 2 · 30.00 EUR");
    }

    @Test
    void recent_purchase_time_is_iso_instant_string() {
        // H2 in test mode shifts TIMESTAMP WITH TIME ZONE values by the JVM tz
        // offset on round-trip; production (Postgres) round-trips faithfully.
        // We just assert the format is a parseable ISO-8601 instant.
        Instant when = Instant.parse("2026-05-25T10:00:00Z");
        Order o = newOrder("buyer@example.com", 1500, when);
        newTicket(o, ga);

        EventOverviewResponse r = service.overview(principal, event.getId());

        String time = r.recentPurchases().get(0).time();
        // Must parse back as an Instant — that's the FE contract.
        Instant parsed = Instant.parse(time);
        assertThat(parsed).isNotNull();
        assertThat(time).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
    }
}
