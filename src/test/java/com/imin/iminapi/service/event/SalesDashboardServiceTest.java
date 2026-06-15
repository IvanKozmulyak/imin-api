package com.imin.iminapi.service.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.event.SalesDashboardResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.FunnelEvent;
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
import com.imin.iminapi.repository.FunnelEventRepository;
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
class SalesDashboardServiceTest {

    @Autowired SalesDashboardService service;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired RefundRepository refunds;
    @Autowired FunnelEventRepository funnel;

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
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hi@test.example");
        org.setCountry("DE");
        org = orgs.save(org);

        owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Night");
        event.setSlug("night-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setStartsAt(Instant.now().plusSeconds(86_400));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);

        ga = newTier("GA", 1500, 100);
        vip = newTier("VIP", 5000, 20);

        principal = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        funnel.deleteAll(); refunds.deleteAll(); tickets.deleteAll();
        orders.deleteAll(); tiers.deleteAll(); events.deleteAll();
        users.deleteAll(); orgs.deleteAll();
    }

    private TicketTier newTier(String name, int price, int qty) {
        TicketTier t = new TicketTier();
        t.setEventId(event.getId());
        t.setName(name);
        t.setPriceMinor(price);
        t.setQuantity(qty);
        t.setReserved(0);
        t.setSold(0);
        t.setEnabled(true);
        return tiers.save(t);
    }

    private Order newOrder(long totalMinor) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail("b@example.com");
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        return orders.save(o);
    }

    private void newTicket(Order o, TicketTier tier, String state) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setOrderId(o.getId());
        t.setEventId(o.getEventId());
        t.setTierId(tier.getId());
        t.setTierName(tier.getName());
        t.setPriceMinor(tier.getPriceMinor());
        t.setState(state);
        tickets.save(t);
    }

    private void newTicket(Order o, TicketTier tier, String state, String tierNameSnapshot) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setOrderId(o.getId());
        t.setEventId(o.getEventId());
        t.setTierId(tier.getId());
        t.setTierName(tierNameSnapshot);
        t.setPriceMinor(tier.getPriceMinor());
        t.setState(state);
        tickets.save(t);
    }

    private void funnelRow(String stage, String anon) {
        FunnelEvent e = new FunnelEvent();
        e.setEventId(event.getId());
        e.setStage(stage);
        e.setAnonId(anon);
        funnel.save(e);
    }

    @Test
    void renamed_tier_does_not_split_or_drop_sales() {
        // tier `ga` (live name "GA") sold under two historical snapshot names.
        Order o = newOrder(3000);
        newTicket(o, ga, Ticket.STATE_ISSUED, "Early Bird"); // old snapshot name
        newTicket(o, ga, Ticket.STATE_ISSUED, "GA");          // current name

        SalesDashboardResponse r = service.dashboard(principal, event.getId());

        // both tickets must count toward the single GA tier and the headline
        assertThat(r.tiles().ticketsSold()).isEqualTo(2);
        var gaRow = r.tiers().stream().filter(t -> t.tierId().equals(ga.getId().toString()))
                .findFirst().orElseThrow();
        assertThat(gaRow.sold()).isEqualTo(2);
        assertThat(gaRow.grossRevenueMinor()).isEqualTo(3000L);
        // reconciliation still holds
        int tierSold = r.tiers().stream().mapToInt(SalesDashboardResponse.TierBreakdown::sold).sum();
        assertThat(tierSold).isEqualTo(r.tiles().ticketsSold());
    }

    @Test
    void cross_org_returns_404_leak_safe() {
        AuthPrincipal other = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(),
                UserRole.OWNER, UUID.randomUUID());
        assertThatThrownBy(() -> service.dashboard(other, event.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void tiers_reconcile_with_headline_and_check_in_rate_is_correct() {
        Order o1 = newOrder(3000);
        newTicket(o1, ga, Ticket.STATE_ISSUED);
        newTicket(o1, ga, Ticket.STATE_REDEEMED);
        Order o2 = newOrder(5000);
        newTicket(o2, vip, Ticket.STATE_ISSUED);
        // noise that must NOT count toward sold:
        newTicket(o2, vip, Ticket.STATE_REFUNDED);

        SalesDashboardResponse r = service.dashboard(principal, event.getId());

        // headline: 3 sold (2 GA + 1 VIP), gross = 1500+1500+5000 = 8000
        assertThat(r.tiles().ticketsSold()).isEqualTo(3);
        assertThat(r.tiles().grossRevenueMinor()).isEqualTo(8000L);
        assertThat(r.tiles().checkedIn()).isEqualTo(1);
        assertThat(r.tiles().checkInRatePct()).isEqualTo(100.0 / 3.0);
        assertThat(r.tiles().capacity()).isEqualTo(120);

        // reconciliation invariant
        int tierSold = r.tiers().stream().mapToInt(SalesDashboardResponse.TierBreakdown::sold).sum();
        long tierGross = r.tiers().stream()
                .mapToLong(SalesDashboardResponse.TierBreakdown::grossRevenueMinor).sum();
        assertThat(tierSold).isEqualTo(r.tiles().ticketsSold());
        assertThat(tierGross).isEqualTo(r.tiles().grossRevenueMinor());

        // top-converting: GA 2/100 = 2% vs VIP 1/20 = 5% → VIP first
        assertThat(r.topConvertingTiers().get(0).name()).isEqualTo("VIP");
    }

    @Test
    void net_revenue_subtracts_only_succeeded_refunds() {
        Order o = newOrder(10000);
        newTicket(o, ga, Ticket.STATE_ISSUED);
        Refund r1 = new Refund();
        r1.setOrderId(o.getId());
        r1.setStripePaymentIntentId(o.getStripePaymentIntentId());
        r1.setAmountMinor(3000);
        r1.setCurrency("eur");
        r1.setApplicationFeeRefundMinor(0);
        r1.setReason(RefundReason.OTHER);
        r1.setStatus(RefundStatus.SUCCEEDED);
        r1.setInitiatedByUserId(owner.getId());
        r1.setIdempotencyKey("k-" + UUID.randomUUID());
        refunds.save(r1);

        SalesDashboardResponse r = service.dashboard(principal, event.getId());
        assertThat(r.tiles().netRevenueMinor()).isEqualTo(7000L); // 10000 - 3000
    }

    @Test
    void funnel_counts_distinct_sessions_and_payments_and_dropoff() {
        // 3 distinct page-view sessions, 2 distinct checkout sessions
        funnelRow(FunnelEvent.STAGE_PAGE_VIEW, "a");
        funnelRow(FunnelEvent.STAGE_PAGE_VIEW, "a");
        funnelRow(FunnelEvent.STAGE_PAGE_VIEW, "b");
        funnelRow(FunnelEvent.STAGE_PAGE_VIEW, "c");
        funnelRow(FunnelEvent.STAGE_CHECKOUT_START, "a");
        funnelRow(FunnelEvent.STAGE_CHECKOUT_START, "b");
        // 1 completed payment (= 1 order)
        Order o = newOrder(1500);
        newTicket(o, ga, Ticket.STATE_ISSUED);

        SalesDashboardResponse r = service.dashboard(principal, event.getId());

        assertThat(r.funnel().stages()).extracting(SalesDashboardResponse.Funnel.Stage::stage)
                .containsExactly("PAGE_VIEW", "CHECKOUT_START", "PAYMENTS_COMPLETED");
        assertThat(r.funnel().stages()).extracting(SalesDashboardResponse.Funnel.Stage::count)
                .containsExactly(3L, 2L, 1L);
        // drop-off: view→checkout lost 1 of 3 (33.33%); checkout→payment lost 1 of 2 (50%)
        assertThat(r.funnel().dropOff().get(0).lostCount()).isEqualTo(1L);
        assertThat(r.funnel().dropOff().get(1).lostCount()).isEqualTo(1L);
        assertThat(r.funnel().dropOff().get(1).lostPct()).isEqualTo(50.0);
    }
}
