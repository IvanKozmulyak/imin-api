package com.imin.iminapi.refund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PropertyFlips;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.StripeClient;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Refund-all plan must be exactly what createRefund accepts, for every order of the event. */
@IminIntegrationTest
class EventRefundPlanControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StripeClient stripeClient;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired RefundRepository refunds;
    @Autowired RefundTicketRepository refundTickets;
    @Autowired DisputeRepository disputes;
    @Autowired Clock clock;
    @Autowired StripeProperties stripeProps;
    @Autowired PropertyFlips flips;
    final ObjectMapper om = new ObjectMapper();

    private final List<UUID> orgIds = new ArrayList<>();
    private AuthPrincipal principal;
    private Event event;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        orgIds.add(org.getId());
        User owner = fx.owner(org);
        principal = fx.principal(owner);
        event = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofDays(30)));
    }

    /** Disputes and refunds are swept across orgs, so this class's rows leave with their org. */
    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void redeemedTicket_isLeftOut_andTheRestOfItsOrderIsPlanned() throws Exception {
        Order order = paidOrder(3000);
        Ticket issued = fx.ticket(order, Ticket.STATE_ISSUED);
        fx.ticket(order, Ticket.STATE_REDEEMED);

        // One of two equal tickets: round(3000 × 1500 / 3000) = 1500.
        JsonNode plan = plan(principal);
        assertThat(plan.get("orders")).hasSize(1);
        JsonNode row = plan.get("orders").get(0);
        assertThat(row.get("orderId").asText()).isEqualTo(order.getId().toString());
        assertThat(row.get("shortCode").asText()).isEqualTo(order.getId().toString().substring(0, 8));
        assertThat(ids(row)).containsExactly(issued.getId());
        assertThat(row.get("amountMinor").asLong()).isEqualTo(1500);
        assertThat(row.get("currency").asText()).isEqualTo(order.getCurrency());
        assertThat(plan.get("ticketCount").asInt()).isEqualTo(1);
        assertThat(plan.get("totalAmountMinor").asLong()).isEqualTo(1500);
        assertThat(plan.get("currency").asText()).isEqualTo(order.getCurrency());
        assertThat(plan.get("skipped").get("redeemedTickets").asInt()).isEqualTo(1);
        assertThat(plan.get("skipped").get("disputedOrders").asInt()).isZero();
    }

    @Test
    void openDisputeOrder_isSkippedAndCounted_whileAnUndisputedOrderIsPlanned() throws Exception {
        Order disputed = paidOrder(1500);
        fx.ticket(disputed, Ticket.STATE_ISSUED);
        dispute(disputed, DisputeStatus.OPEN);
        Order clean = paidOrder(1500);
        Ticket cleanTicket = fx.ticket(clean, Ticket.STATE_ISSUED);

        JsonNode plan = plan(principal);
        assertThat(plan.get("orders")).hasSize(1);
        assertThat(plan.get("orders").get(0).get("orderId").asText()).isEqualTo(clean.getId().toString());
        assertThat(ids(plan.get("orders").get(0))).containsExactly(cleanTicket.getId());
        assertThat(plan.get("skipped").get("disputedOrders").asInt()).isEqualTo(1);
        assertThat(plan.get("totalAmountMinor").asLong()).isEqualTo(1500);
    }

    /** PENDING at Stripe, or REQUESTED with Stripe's outcome still unknown: both hold their tickets. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RefundStatus.class, names = {"PENDING", "REQUESTED"})
    void ticketClaimedByAPendingRefund_isLeftOut(RefundStatus inFlight) throws Exception {
        Order order = paidOrder(3000);
        Ticket claimed = fx.ticket(order, Ticket.STATE_ISSUED);
        Ticket free = fx.ticket(order, Ticket.STATE_ISSUED);
        pendingRefund(order, claimed, 1500, inFlight);

        // Remaining after the pending 1500: min(round(3000 × 1500 / 3000), 3000 − 1500) = 1500.
        JsonNode plan = plan(principal);
        assertThat(plan.get("orders")).hasSize(1);
        assertThat(ids(plan.get("orders").get(0))).containsExactly(free.getId());
        assertThat(plan.get("orders").get(0).get("amountMinor").asLong()).isEqualTo(1500);
        assertThat(plan.get("ticketCount").asInt()).isEqualTo(1);
        assertThat(plan.get("skipped").get("redeemedTickets").asInt()).isZero();
    }

    /**
     * Orders createRefund would refuse or that have nothing left: no PaymentIntent, a ticket no
     * longer live (revoked, refunded), and an order a PENDING refund already covers in full.
     */
    @Test
    void unrefundableOrdersAndDeadTickets_areLeftOutOfOrdersAndCounts() throws Exception {
        Order noPayment = fx.order(event, fx.email("buyer"));
        jdbc.update("UPDATE orders SET test_mode = ? WHERE id = ?", !stripeProps.isLiveKey(), noPayment.getId());
        fx.ticket(noPayment, Ticket.STATE_ISSUED);

        Order mixed = paidOrder(4500);
        Ticket live = fx.ticket(mixed, Ticket.STATE_ISSUED);
        fx.ticket(mixed, Ticket.STATE_REVOKED);
        fx.ticket(mixed, Ticket.STATE_REFUNDED);

        // The pending 3000 leaves nothing: min(round(3000 × 1500 / 3000), 3000 − 3000) = 0.
        Order covered = paidOrder(3000);
        Ticket claimed = fx.ticket(covered, Ticket.STATE_ISSUED);
        fx.ticket(covered, Ticket.STATE_ISSUED);
        pendingRefund(covered, claimed, 3000, RefundStatus.PENDING);

        // round(4500 × 1500 / 4500) = 1500 for the one live ticket.
        JsonNode plan = plan(principal);
        assertThat(plan.get("orders")).hasSize(1);
        assertThat(plan.get("orders").get(0).get("orderId").asText()).isEqualTo(mixed.getId().toString());
        assertThat(ids(plan.get("orders").get(0))).containsExactly(live.getId());
        assertThat(plan.get("ticketCount").asInt()).isEqualTo(1);
        assertThat(plan.get("totalAmountMinor").asLong()).isEqualTo(1500);
    }

    /** A test-mode order under the live key (or the reverse) does not exist at Stripe for that key. */
    @Test
    void orderFromTheOtherStripeMode_isNotPlanned_andCreateRefundRefusesIt() throws Exception {
        Order testOrder = paidOrder(1500);                 // stamped test under the suite's sk_test key
        Ticket testTicket = fx.ticket(testOrder, Ticket.STATE_ISSUED);
        flips.set(stripeProps, "secretKey", "sk_live_dummy");
        Order liveOrder = paidOrder(1500);
        fx.ticket(liveOrder, Ticket.STATE_ISSUED);

        JsonNode plan = plan(principal);
        assertThat(plan.get("orders")).hasSize(1);
        assertThat(plan.get("orders").get(0).get("orderId").asText()).isEqualTo(liveOrder.getId().toString());

        mvc.perform(post("/api/v1/orders/{id}/refund", testOrder.getId())
                        .with(auth(principal))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "ticketIds", List.of(testTicket.getId().toString()), "reason", "other"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_REFUNDABLE"));
        verifyNoInteractions(stripeClient);
    }

    /** The dialog shows one total; EUR and USD must never be added together. */
    @Test
    void ordersInTwoCurrencies_are409_insteadOfAMixedTotal() throws Exception {
        fx.ticket(paidOrder(1500), Ticket.STATE_ISSUED);
        Order usd = paidOrder(1500);
        jdbc.update("UPDATE orders SET currency = 'USD' WHERE id = ?", usd.getId());
        fx.ticket(usd, Ticket.STATE_ISSUED);

        mvc.perform(get("/api/v1/events/{id}/refund-plan", event.getId()).with(auth(principal)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    /** A promo order refunds what was paid, not face: the plan must quote the amount Stripe is sent. */
    @Test
    void promoOrderAmount_equalsWhatCreateRefundCharges() throws Exception {
        Order order = paidOrder(2000);                    // 3 × 1000 face, paid 2000 after a promo
        Ticket a = ticket(order, Ticket.STATE_ISSUED, 1000);
        Ticket b = ticket(order, Ticket.STATE_ISSUED, 1000);
        ticket(order, Ticket.STATE_REDEEMED, 1000);

        // round(2000 × 2000 / 3000) = round(1333.33) = 1333; the face sum would say 2000.
        JsonNode plan = plan(principal);
        JsonNode row = plan.get("orders").get(0);
        assertThat(ids(row)).containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(row.get("amountMinor").asLong()).isEqualTo(1333);
        assertThat(plan.get("totalAmountMinor").asLong()).isEqualTo(1333);

        com.stripe.service.RefundService stripeRefunds = mock(com.stripe.service.RefundService.class);
        when(stripeClient.refunds()).thenReturn(stripeRefunds);
        com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
        stripeRefund.setId("re_" + UUID.randomUUID().toString().replace("-", ""));
        stripeRefund.setStatus("pending");
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stripeRefund);

        mvc.perform(post("/api/v1/orders/{id}/refund", order.getId())
                        .with(auth(principal))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "ticketIds", ids(row).stream().map(UUID::toString).toList(),
                                "reason", "other"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.amountMinor").value(1333));

        ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(stripeRefunds).create(params.capture(), any(RequestOptions.class));
        assertThat(params.getValue().getAmount()).isEqualTo(row.get("amountMinor").asLong());
    }

    /** The Orders tab lists the newest 100; the plan must not inherit that cap. */
    @Test
    void moreThanAHundredOrders_areAllPlanned() throws Exception {
        Set<UUID> expected = new HashSet<>();
        for (int i = 0; i < 101; i++) {
            Order o = paidOrder(1500);
            fx.ticket(o, Ticket.STATE_ISSUED);
            expected.add(o.getId());
        }

        JsonNode plan = plan(principal);
        Set<UUID> planned = new HashSet<>();
        plan.get("orders").forEach(r -> planned.add(UUID.fromString(r.get("orderId").asText())));
        assertThat(planned).isEqualTo(expected);
        assertThat(plan.get("ticketCount").asInt()).isEqualTo(101);
        assertThat(plan.get("totalAmountMinor").asLong()).isEqualTo(101L * 1500);
    }

    /** 404, not 403: another org must not learn the event exists, let alone its orders. */
    @Test
    void anotherOrgsEvent_is404() throws Exception {
        fx.ticket(paidOrder(1500), Ticket.STATE_ISSUED);
        Organization other = fx.org();
        orgIds.add(other.getId());
        AuthPrincipal outsider = fx.principal(fx.owner(other));

        mvc.perform(get("/api/v1/events/{id}/refund-plan", event.getId()).with(auth(outsider)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    private JsonNode plan(AuthPrincipal p) throws Exception {
        return om.readTree(mvc.perform(get("/api/v1/events/{id}/refund-plan", event.getId()).with(auth(p)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static List<UUID> ids(JsonNode row) {
        List<UUID> out = new ArrayList<>();
        row.get("ticketIds").forEach(n -> out.add(UUID.fromString(n.asText())));
        return out;
    }

    private Order paidOrder(long totalMinor) {
        Order o = fx.order(event, fx.email("buyer"));
        // test_mode as checkout stamps it: the running key's mode.
        jdbc.update("UPDATE orders SET stripe_payment_intent_id = ?, application_fee_minor = 75, total_minor = ?, "
                        + "test_mode = ? WHERE id = ?",
                "pi_" + UUID.randomUUID().toString().replace("-", ""), totalMinor, !stripeProps.isLiveKey(),
                o.getId());
        return orders.findById(o.getId()).orElseThrow();
    }

    private Ticket ticket(Order order, String state, int priceMinor) {
        Ticket t = fx.ticket(order, state);
        t.setPriceMinor(priceMinor);
        return tickets.save(t);
    }

    /** PENDING carries a Stripe id; REQUESTED is an attempt whose Stripe outcome is not known yet. */
    private void pendingRefund(Order order, Ticket ticket, long amountMinor, RefundStatus status) {
        Refund r = new Refund();
        r.setOrderId(order.getId());
        r.setStripePaymentIntentId(order.getStripePaymentIntentId());
        if (status == RefundStatus.REQUESTED) {
            r.setStripeAttemptAt(clock.instant());
            r.setStripeAttempts(1);
        } else {
            r.setStripeRefundId("re_" + UUID.randomUUID());
        }
        r.setAmountMinor(amountMinor);
        r.setCurrency(order.getCurrency());
        r.setApplicationFeeRefundMinor(0);
        r.setReason(RefundReason.OTHER);
        r.setStatus(status);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        r = refunds.saveAndFlush(r);
        refundTickets.saveAndFlush(new RefundTicket(r.getId(), ticket.getId()));
    }

    private void dispute(Order o, DisputeStatus status) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().substring(0, 12));
        d.setOrgId(o.getOrgId());
        d.setEventId(o.getEventId());
        d.setOrderId(o.getId());
        d.setAmountMinor(o.getTotalMinor());
        d.setCurrency("eur");
        d.setStatus(status);
        d.setOpenedAt(Instant.parse("2026-09-01T10:15:30Z"));
        disputes.save(d);
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
