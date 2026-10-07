package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
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
import com.imin.iminapi.service.dashboard.DashboardRevenue.Window;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The org home's window and per-event money against real queries. Fixtures follow the plan's
 * worked example: A live 2 tickets, B refunded, C charged back (LOST), D test mode with a
 * partial refund and an OPEN test dispute, E outside the window.
 */
@IminIntegrationTest
class DashboardRevenueTest {

    @Autowired DashboardRevenue revenue;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired RefundRepository refunds;
    @Autowired DisputeRepository disputes;

    private final List<UUID> orgIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();
    private final List<UUID> tierIds = new ArrayList<>();
    private final List<UUID> orderIds = new ArrayList<>();
    private final List<UUID> ticketIds = new ArrayList<>();
    private final List<UUID> refundIds = new ArrayList<>();
    private final List<UUID> disputeIds = new ArrayList<>();
    private final Map<UUID, UUID> tierByEvent = new HashMap<>();

    private Instant now;
    private Instant since;
    private Organization org;
    private Event event;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        since = now.minus(30, ChronoUnit.DAYS);
        org = org();
        event = event(org);
    }

    @AfterEach
    void tearDown() {
        disputes.deleteAllById(disputeIds);
        refunds.deleteAllById(refundIds);
        tickets.deleteAllById(ticketIds);
        orders.deleteAllById(orderIds);
        tiers.deleteAllById(tierIds);
        events.deleteAllById(eventIds);
        users.deleteAllById(userIds);
        orgs.deleteAllById(orgIds);
    }

    @Test
    void worked_example_window_includes_test_mode_and_nets_refunds_fee_and_chargebacks() {
        workedExample(event);

        // gross 12045 − refunds 2149 − fee (1045 − 149) − organizer shares (C 1000 + D 4000) = 4000,
        // i.e. A's stake 4398 − 398. D: 5349 − 1000 refunded leaves 4349 of stake, less its 349 fee.
        assertThat(revenue.forOrgWindow(org.getId(), since, now)).isEqualTo(new Window(4_000, 3));
    }

    @Test
    void prior_window_holds_only_the_older_order() {
        workedExample(event);

        assertThat(revenue.forOrgWindow(org.getId(), since.minus(30, ChronoUnit.DAYS), since))
                .isEqualTo(new Window(3_000, 1));
    }

    @Test
    void a_refund_that_has_not_succeeded_is_not_netted() {
        Order a = orderA(event, now.minus(1, ChronoUnit.DAYS));
        refund(a, 4_398, 398, RefundStatus.PENDING);

        assertThat(revenue.forOrgWindow(org.getId(), since, now).netRevenueMinor()).isEqualTo(4_000L);
    }

    @Test
    void a_won_dispute_is_not_withheld() {
        Order a = orderA(event, now.minus(1, ChronoUnit.DAYS));
        dispute(a, 4_398, DisputeStatus.WON, false);

        assertThat(revenue.forOrgWindow(org.getId(), since, now).netRevenueMinor()).isEqualTo(4_000L);
    }

    @Test
    void the_window_includes_since_and_excludes_until_for_orders_refunds_disputes_and_tickets() {
        Instant from = now.minus(10, ChronoUnit.DAYS);
        Instant until = now.minus(1, ChronoUnit.DAYS);
        orderA(event, from);
        Order b = order(event, 1_149, 149, false, until);
        ticket(b, Ticket.STATE_ISSUED);
        refund(b, 1_149, 149, RefundStatus.SUCCEEDED);
        dispute(b, 1_149, DisputeStatus.LOST, false);

        assertThat(revenue.forOrgWindow(org.getId(), from, until)).isEqualTo(new Window(4_000, 2));
    }

    @Test
    void another_orgs_orders_refunds_disputes_and_tickets_are_not_counted() {
        orderA(event, now.minus(1, ChronoUnit.DAYS));
        Organization other = org();
        Event otherEvent = event(other);
        Order x = orderA(otherEvent, now.minus(1, ChronoUnit.DAYS));
        refund(x, 1_000, 0, RefundStatus.SUCCEEDED);
        dispute(x, 1_000, DisputeStatus.OPEN, false);

        assertThat(revenue.forOrgWindow(org.getId(), since, now)).isEqualTo(new Window(4_000, 2));
    }

    @Test
    void window_tickets_sold_leave_out_refunded_and_revoked() {
        Order o = order(event, 5_000, 0, false, now.minus(1, ChronoUnit.DAYS));
        ticket(o, Ticket.STATE_ISSUED);
        ticket(o, "pre");
        ticket(o, Ticket.STATE_REDEEMED);
        ticket(o, Ticket.STATE_REFUNDED);
        ticket(o, Ticket.STATE_REVOKED);

        assertThat(revenue.forOrgWindow(org.getId(), since, now).ticketsSold()).isEqualTo(3L);
    }

    @Test
    void event_net_uses_the_same_formula_over_that_event_only() {
        Event older = event(org);
        workedExample(event, older);

        assertThat(revenue.netForEvent(event.getId())).isEqualTo(4_000L);
        assertThat(revenue.netForEvent(older.getId())).isEqualTo(3_000L);
    }

    @Test
    void event_tickets_leave_out_refunded_and_revoked_and_other_events() {
        Event older = event(org);
        workedExample(event, older);

        assertThat(revenue.ticketsForEvent(event.getId())).isEqualTo(3L);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private void workedExample(Event e) {
        workedExample(e, e);
    }

    /** A, B, C, D on {@code e} inside the 30-day window; E on {@code olderEvent}, 40 days ago. */
    private void workedExample(Event e, Event olderEvent) {
        Instant in = now.minus(1, ChronoUnit.DAYS);
        orderA(e, in);

        Order b = order(e, 1_149, 149, false, in);
        ticket(b, Ticket.STATE_REFUNDED);
        refund(b, 1_149, 149, RefundStatus.SUCCEEDED);

        Order c = order(e, 1_149, 149, false, in);
        ticket(c, Ticket.STATE_REVOKED);
        dispute(c, 1_149, DisputeStatus.LOST, false);

        Order d = order(e, 5_349, 349, true, in);
        ticket(d, Ticket.STATE_ISSUED);
        refund(d, 1_000, 0, RefundStatus.SUCCEEDED);
        dispute(d, 5_349, DisputeStatus.OPEN, true);

        Order old = order(olderEvent, 3_249, 249, false, now.minus(40, ChronoUnit.DAYS));
        ticket(old, Ticket.STATE_ISSUED);
    }

    /** Order A: 2 × 2000 face plus a 398 booking fee, one ticket issued and one redeemed. */
    private Order orderA(Event e, Instant createdAt) {
        Order a = order(e, 4_398, 398, false, createdAt);
        ticket(a, Ticket.STATE_ISSUED);
        ticket(a, Ticket.STATE_REDEEMED);
        return a;
    }

    private Organization org() {
        Organization o = new Organization();
        o.setName("Org");
        o.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("o@test.example");
        o.setCountry("FR");
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
    }

    private Event event(Organization o) {
        User u = new User();
        u.setOrgId(o.getId());
        u.setEmail("u-" + UUID.randomUUID() + "@test.example");
        u.setRole(UserRole.OWNER);
        u = users.save(u);
        userIds.add(u.getId());

        Event e = new Event();
        e.setOrgId(o.getId());
        e.setName("E");
        e.setSlug("e-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStatus(EventStatus.PAST);
        e.setCurrency("EUR");
        e.setEndsAt(now.minus(2, ChronoUnit.DAYS));
        e.setCreatedBy(u.getId());
        e = events.save(e);
        eventIds.add(e.getId());

        TicketTier t = new TicketTier();
        t.setEventId(e.getId());
        t.setName("GA");
        t.setPriceMinor(2_000);
        t.setQuantity(100);
        UUID tierId = tiers.save(t).getId();
        tierIds.add(tierId);
        tierByEvent.put(e.getId(), tierId);
        return e;
    }

    private Order order(Event e, long totalMinor, long appFeeMinor, boolean testMode, Instant createdAt) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(e.getId());
        o.setOrgId(e.getOrgId());
        o.setEmail("buyer@test.example");
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setApplicationFeeMinor(appFeeMinor);
        o.setPaymentMethod("card");
        o.setTestMode(testMode);
        o.setCreatedAt(createdAt);
        o = orders.save(o);
        orderIds.add(o.getId());
        return o;
    }

    private void ticket(Order o, String state) {
        UUID tierId = tierByEvent.get(o.getEventId());
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setOrderId(o.getId());
        t.setEventId(o.getEventId());
        t.setTierId(tierId);
        t.setTierName("GA");
        t.setPriceMinor(2_000);
        t.setState(state);
        ticketIds.add(tickets.save(t).getId());
    }

    private void refund(Order o, long amountMinor, long feeRefundMinor, RefundStatus status) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setAmountMinor(amountMinor);
        r.setCurrency("eur");
        r.setApplicationFeeRefundMinor(feeRefundMinor);
        r.setReason(RefundReason.OTHER);
        r.setStatus(status);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        refundIds.add(refunds.save(r).getId());
    }

    private void dispute(Order o, long amountMinor, DisputeStatus status, boolean testMode) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        d.setOrgId(o.getOrgId());
        d.setEventId(o.getEventId());
        d.setOrderId(o.getId());
        d.setAmountMinor(amountMinor);
        d.setCurrency("eur");
        d.setStatus(status);
        d.setTestMode(testMode);
        disputeIds.add(disputes.save(d).getId());
    }
}
