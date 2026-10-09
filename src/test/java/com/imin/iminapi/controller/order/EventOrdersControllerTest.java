package com.imin.iminapi.controller.order;

import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.PromoCode;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Orders-tab row rendering, over real persistence. A chargeback revokes the order's
 * tickets, so without the dispute lookup the row is indistinguishable from a refund.
 */
@IminIntegrationTest
class EventOrdersControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired DisputeRepository disputes;
    @Autowired PromoCodeRepository promos;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;

    private static final Instant OPENED_AT = Instant.parse("2026-09-01T10:15:30Z");

    private Organization org;
    private User owner;
    private Event event;
    private TicketTier ga;
    private Authentication auth;
    private final List<UUID> extraOrgIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        org = fx.org();
        owner = fx.owner(org);
        event = fx.event(org, owner, EventStatus.LIVE, clock.instant().plusSeconds(86_400L * 10));
        event.setPublishedAt(clock.instant().minusSeconds(7200));
        event = events.save(event);

        ga = fx.tier(event, 1149, 100);
        ga.setName("GA");
        ga.setReserved(0);
        ga.setSold(1);
        ga.setEnabled(true);
        ga = tiers.save(ga);

        auth = new UsernamePasswordAuthenticationToken(fx.principal(owner),
                null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
    }

    /** Disputes are swept across every org, so the open ones this class leaves must go with their org. */
    @AfterEach
    void tearDown() {
        List<UUID> all = new ArrayList<>(extraOrgIds);
        all.add(org.getId());
        OrgRows.delete(jdbc, all);
    }

    private Order newOrder(long totalMinor) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail(fx.email("buyer"));
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        o.setCreatedAt(clock.instant().minusSeconds(600));
        return orders.save(o);
    }

    private Ticket newTicket(Order o, String state) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setOrderId(o.getId());
        t.setEventId(o.getEventId());
        t.setTierId(ga.getId());
        t.setTierName(ga.getName());
        t.setPriceMinor(ga.getPriceMinor());
        t.setState(state);
        return tickets.save(t);
    }

    private Dispute newDispute(Order o, DisputeStatus status, long amountMinor) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().substring(0, 12));
        d.setOrgId(org.getId());
        d.setEventId(event.getId());
        d.setOrderId(o.getId());
        d.setAmountMinor(amountMinor);
        d.setCurrency("eur");
        d.setStatus(status);
        d.setOpenedAt(OPENED_AT);
        return disputes.save(d);
    }

    @Test
    void open_dispute_row_is_disputed_not_refunded() throws Exception {
        Order o = newOrder(1149);
        newTicket(o, Ticket.STATE_REVOKED);
        newDispute(o, DisputeStatus.OPEN, 1149);

        mvc.perform(get("/api/v1/events/{id}/orders", event.getId()).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("disputed"))
            .andExpect(jsonPath("$[0].dispute.status").value("open"))
            .andExpect(jsonPath("$[0].dispute.amountMinor").value(1149))
            .andExpect(jsonPath("$[0].dispute.currency").value("eur"))
            .andExpect(jsonPath("$[0].dispute.openedAt").value(OPENED_AT.toString()));
    }

    @Test
    void lost_dispute_row_stays_disputed_and_carries_the_wire_status() throws Exception {
        Order o = newOrder(1149);
        newTicket(o, Ticket.STATE_REVOKED);
        newDispute(o, DisputeStatus.LOST, 1149);

        mvc.perform(get("/api/v1/events/{id}/orders", event.getId()).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("disputed"))
            .andExpect(jsonPath("$[0].dispute.status").value("lost"));
    }

    @Test
    void won_dispute_keeps_the_ticket_derived_status_and_still_shows_the_dispute() throws Exception {
        Order o = newOrder(1149);
        newTicket(o, Ticket.STATE_ISSUED);
        newDispute(o, DisputeStatus.WON, 1149);

        mvc.perform(get("/api/v1/events/{id}/orders", event.getId()).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("paid"))
            .andExpect(jsonPath("$[0].dispute.status").value("won"))
            .andExpect(jsonPath("$[0].dispute.amountMinor").value(1149));
    }

    @Test
    void withdrawn_reinstated_dispute_keeps_the_ticket_derived_status() throws Exception {
        Order o = newOrder(2298);
        newTicket(o, Ticket.STATE_ISSUED);
        Ticket refunded = newTicket(o, Ticket.STATE_ISSUED);
        refunded.setState(Ticket.STATE_REFUNDED);
        tickets.save(refunded);
        newDispute(o, DisputeStatus.WITHDRAWN_REINSTATED, 1149);

        mvc.perform(get("/api/v1/events/{id}/orders", event.getId()).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("partially_refunded"))
            .andExpect(jsonPath("$[0].dispute.status").value("withdrawn_reinstated"));
    }

    @Test
    void order_without_a_dispute_is_unchanged_and_has_a_null_dispute() throws Exception {
        Order o = newOrder(1149);
        newTicket(o, Ticket.STATE_ISSUED);

        mvc.perform(get("/api/v1/events/{id}/orders", event.getId()).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("paid"))
            .andExpect(jsonPath("$[0].dispute").doesNotExist());
    }

    /**
     * Five OPEN chargebacks stamped with the same {@code openedAt}: rank and timestamp both
     * tie, so the id decides. Without that tiebreak the row showed whichever dispute the
     * query happened to return first, and the same order could render five different amounts.
     */
    @Test
    void same_second_open_disputes_resolve_to_the_lowest_id() throws Exception {
        Order o = newOrder(2298);
        newTicket(o, Ticket.STATE_REVOKED);
        List<Dispute> sameSecond = new ArrayList<>();
        for (int i = 0; i < 5; i++) sameSecond.add(newDispute(o, DisputeStatus.OPEN, 500 + i * 100L));
        Dispute governing = sameSecond.stream().min(Comparator.comparing(Dispute::getId)).orElseThrow();

        mvc.perform(get("/api/v1/events/{id}/orders", event.getId()).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("disputed"))
            .andExpect(jsonPath("$[0].dispute.status").value("open"))
            .andExpect(jsonPath("$[0].dispute.amountMinor").value((int) governing.getAmountMinor()));
    }

    /** Several chargebacks on one order: the OPEN one governs, whatever order they arrived in. */
    @Test
    void open_dispute_governs_over_an_older_won_one_on_the_same_order() throws Exception {
        Order o = newOrder(1149);
        newTicket(o, Ticket.STATE_REVOKED);
        Dispute won = newDispute(o, DisputeStatus.WON, 500);
        won.setOpenedAt(OPENED_AT.plusSeconds(86_400));
        disputes.save(won);
        newDispute(o, DisputeStatus.OPEN, 1149);

        mvc.perform(get("/api/v1/events/{id}/orders", event.getId()).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("disputed"))
            .andExpect(jsonPath("$[0].dispute.status").value("open"))
            .andExpect(jsonPath("$[0].dispute.amountMinor").value(1149));
    }

    private static final Instant SCANNED_AT = Instant.parse("2026-09-02T19:45:12Z");

    /** An order with a promo code and one scanned ticket, the two fields the drawer adds. */
    private Order orderWithCodeAndScan() {
        PromoCode code = new PromoCode();
        code.setEventId(event.getId());
        code.setCode("VIP" + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        code.setDiscountPct(20);
        code.setMaxUses(10);
        code = promos.save(code);
        Order o = newOrder(1149);
        o.setPromoCodeId(code.getId());
        o = orders.save(o);
        Ticket t = newTicket(o, Ticket.STATE_REDEEMED);
        t.setRedeemedAt(SCANNED_AT);
        tickets.save(t);
        return o;
    }

    @Test
    void list_rows_carry_the_promo_code_and_the_scan_time() throws Exception {
        Order o = orderWithCodeAndScan();
        String code = promos.findById(o.getPromoCodeId()).orElseThrow().getCode();
        Order plain = newOrder(1149);
        plain.setCreatedAt(o.getCreatedAt().minusSeconds(60));
        orders.save(plain);
        newTicket(plain, Ticket.STATE_ISSUED);

        mvc.perform(get("/api/v1/events/{id}/orders", event.getId()).with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(o.getId().toString()))
            .andExpect(jsonPath("$[0].promoCode").value(code))
            .andExpect(jsonPath("$[0].tickets[0].redeemedAt").value(SCANNED_AT.toString()))
            .andExpect(jsonPath("$[1].id").value(plain.getId().toString()))
            .andExpect(jsonPath("$[1].promoCode").doesNotExist())
            .andExpect(jsonPath("$[1].tickets[0].redeemedAt").doesNotExist());
    }

    @Test
    void detail_returns_the_one_row() throws Exception {
        Order o = newOrder(1149);
        newTicket(o, Ticket.STATE_ISSUED);

        mvc.perform(get("/api/v1/events/{id}/orders/{orderId}", event.getId(), o.getId())
                    .with(authentication(auth)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(o.getId().toString()))
            .andExpect(jsonPath("$.status").value("paid"));
    }

    @Test
    void detail_of_another_orgs_order_is_404() throws Exception {
        Order o = newOrder(1149);
        newTicket(o, Ticket.STATE_ISSUED);
        Organization other = fx.org();
        extraOrgIds.add(other.getId());
        Authentication stranger = new UsernamePasswordAuthenticationToken(fx.principal(fx.owner(other)),
                null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));

        mvc.perform(get("/api/v1/events/{id}/orders/{orderId}", event.getId(), o.getId())
                    .with(authentication(stranger)))
            .andExpect(status().isNotFound());
    }

    /** The order exists and is the caller's, but not under this event: same 404 as a missing one. */
    @Test
    void detail_of_an_order_from_another_event_is_404() throws Exception {
        Event sibling = fx.event(org, owner, EventStatus.LIVE, clock.instant().plusSeconds(86_400L * 20));
        Order elsewhere = fx.order(sibling, fx.email("buyer"));
        fx.ticket(elsewhere, Ticket.STATE_ISSUED);

        mvc.perform(get("/api/v1/events/{id}/orders/{orderId}", event.getId(), elsewhere.getId())
                    .with(authentication(auth)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/events/{id}/orders/{orderId}", sibling.getId(), elsewhere.getId())
                    .with(authentication(auth)))
            .andExpect(status().isOk());
    }
}
