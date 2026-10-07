package com.imin.iminapi.service.event;

import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.dto.event.EventDto;
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
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** EventDto sold/revenue/capacity read live totals, not the never-written event columns. */
@IminIntegrationTest
class EventServiceSalesFiguresTest {

    @Autowired EventService sut;
    @Autowired JdbcTemplate jdbc;

    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrganizationRepository organizations;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired RefundRepository refunds;
    @Autowired DisputeRepository disputes;

    AuthPrincipal principal;
    Organization org;
    User owner;

    @BeforeEach
    void setUp() {
        org = new Organization();
        org.setName("Sales Figures Org");
        org.setSlug("sales-org-" + UUID.randomUUID());
        org.setContactEmail("sales-org@example.com");
        org.setCountry("DE");
        org = organizations.save(org);

        owner = new User();
        owner.setEmail("sales-owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        principal = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        if (org != null) OrgRows.delete(jdbc, List.of(org.getId()));
    }

    private Event newEvent(String name) {
        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName(name);
        e.setSlug("sales-" + UUID.randomUUID());
        e.setVisibility(EventVisibility.PUBLIC);
        e.setStatus(EventStatus.LIVE);
        e.setCreatedBy(owner.getId());
        return events.save(e);
    }

    private TicketTier newTier(Event e, int quantity, int sold, boolean enabled) {
        TicketTier t = new TicketTier();
        t.setEventId(e.getId());
        t.setName("GA");
        t.setPriceMinor(1000);
        t.setQuantity(quantity);
        t.setSold(sold);
        t.setEnabled(enabled);
        return tiers.save(t);
    }

    private Order newOrder(Event e, long totalMinor) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(e.getId());
        o.setOrgId(org.getId());
        o.setEmail("buyer@example.com");
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().substring(0, 12));
        return orders.save(o);
    }

    private void newTicket(Event e, Order o, String state) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        t.setOrderId(o.getId());
        t.setEventId(e.getId());
        t.setTierId(UUID.randomUUID());
        t.setTierName("GA");
        t.setPriceMinor(1000);
        t.setState(state);
        tickets.save(t);
    }

    private void newDispute(Event e, Order o, DisputeStatus status, long amountMinor) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().substring(0, 12));
        d.setOrgId(org.getId());
        d.setEventId(e.getId());
        d.setOrderId(o.getId());
        d.setAmountMinor(amountMinor);
        d.setCurrency("eur");
        d.setStatus(status);
        d.setOpenedAt(Instant.now().minusSeconds(3600));
        disputes.save(d);
    }

    private void newRefund(Order o, long amountMinor, RefundStatus status) {
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
        refunds.save(r);
    }

    private Map<UUID, EventDto> listById() {
        return sut.list(principal, null, 1, 20).items().stream()
                .collect(Collectors.toMap(EventDto::id, Function.identity()));
    }

    @Test
    void list_reports_the_tier_sold_count_and_capacity() {
        Event e = newEvent("One Sold");
        newTier(e, 200, 1, true);

        var item = sut.list(principal, null, 1, 20).items().get(0);

        assertThat(item.sold()).isEqualTo(1);
        assertThat(item.capacity()).isEqualTo(200);
    }

    @Test
    void detail_reports_the_same_figures_as_the_list() {
        Event e = newEvent("One Sold");
        newTier(e, 200, 1, true);
        newOrder(e, 1000);

        EventDto dto = sut.detail(principal, e.getId());

        assertThat(dto.sold()).isEqualTo(1);
        assertThat(dto.capacity()).isEqualTo(200);
        assertThat(dto.revenueMinor()).isEqualTo(1000L);
    }

    @Test
    void capacity_counts_disabled_tiers_too() {
        Event e = newEvent("Two Tiers");
        newTier(e, 100, 0, true);
        newTier(e, 50, 0, false);

        assertThat(listById().get(e.getId()).capacity()).isEqualTo(150);
    }

    @Test
    void an_event_without_tiers_has_unknown_capacity_and_nothing_sold() {
        Event e = newEvent("No Tiers");

        EventDto dto = listById().get(e.getId());

        assertThat(dto.capacity()).isNull();
        assertThat(dto.sold()).isZero();
        assertThat(dto.revenueMinor()).isZero();
    }

    @Test
    void an_open_dispute_takes_its_revoked_ticket_and_face_value_off() {
        Event e = newEvent("Disputed");
        newTier(e, 100, 3, true);
        Order o = newOrder(e, 3000);
        newTicket(e, o, Ticket.STATE_ISSUED);
        newTicket(e, o, Ticket.STATE_ISSUED);
        newTicket(e, o, Ticket.STATE_REVOKED);
        newDispute(e, o, DisputeStatus.OPEN, 1000);

        EventDto dto = listById().get(e.getId());

        assertThat(dto.sold()).isEqualTo(2);
        assertThat(dto.revenueMinor()).isEqualTo(2000L);
    }

    @Test
    void a_succeeded_refund_comes_off_revenue_and_a_pending_one_does_not() {
        Event e = newEvent("Refunded");
        newTier(e, 100, 2, true);
        Order o = newOrder(e, 3000);
        newRefund(o, 1000, RefundStatus.SUCCEEDED);
        newRefund(o, 500, RefundStatus.PENDING);

        assertThat(listById().get(e.getId()).revenueMinor()).isEqualTo(2000L);
    }

    @Test
    void a_won_dispute_takes_nothing_off() {
        Event e = newEvent("Won");
        newTier(e, 100, 3, true);
        Order o = newOrder(e, 3000);
        newTicket(e, o, Ticket.STATE_REVOKED);
        newDispute(e, o, DisputeStatus.WON, 1000);

        EventDto dto = listById().get(e.getId());

        assertThat(dto.sold()).isEqualTo(3);
        assertThat(dto.revenueMinor()).isEqualTo(3000L);
    }

    @Test
    void events_on_one_page_keep_their_own_figures() {
        Event a = newEvent("A");
        newTier(a, 200, 5, true);
        newOrder(a, 5000);
        Event b = newEvent("B");
        newTier(b, 80, 2, true);
        Order ob = newOrder(b, 2000);
        newTicket(b, ob, Ticket.STATE_REVOKED);
        newDispute(b, ob, DisputeStatus.LOST, 1000);

        Map<UUID, EventDto> byId = listById();

        assertThat(byId.get(a.getId()).sold()).isEqualTo(5);
        assertThat(byId.get(a.getId()).capacity()).isEqualTo(200);
        assertThat(byId.get(a.getId()).revenueMinor()).isEqualTo(5000L);
        assertThat(byId.get(b.getId()).sold()).isEqualTo(1);
        assertThat(byId.get(b.getId()).capacity()).isEqualTo(80);
        assertThat(byId.get(b.getId()).revenueMinor()).isEqualTo(1000L);
    }
}
