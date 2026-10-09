package com.imin.iminapi.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.stripe.StripeClient;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.service.RefundService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The organizer refund endpoint over the real RefundService; only Stripe is faked. */
@IminIntegrationTest
class RefundControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StripeClient stripeClient;
    @Autowired RefundRepository refunds;
    @Autowired Clock clock;
    final ObjectMapper om = new ObjectMapper();

    private final List<UUID> orgIds = new ArrayList<>();
    private AuthPrincipal principal;
    private Event event;
    private Order order;
    private Ticket ticket;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        orgIds.add(org.getId());
        User owner = fx.owner(org);
        principal = fx.principal(owner);
        event = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofDays(30)));
        order = paidOrder(event);
        ticket = fx.ticket(order, Ticket.STATE_ISSUED);
    }

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, orgIds);
    }

    @Test
    void missingIdempotencyKey_is400() throws Exception {
        mvc.perform(refund(order, body(List.of(ticket.getId()), "other")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    /** 404, not 403: another org's order must not even be confirmed to exist, and no money moves. */
    @Test
    void anotherOrgsOrder_is404_andStripeIsUntouched() throws Exception {
        Organization other = fx.org();
        orgIds.add(other.getId());
        User otherOwner = fx.owner(other);
        Order theirs = paidOrder(fx.event(other, otherOwner, EventStatus.LIVE,
                clock.instant().plus(Duration.ofDays(30))));
        Ticket theirTicket = fx.ticket(theirs, Ticket.STATE_ISSUED);

        mvc.perform(refund(theirs, body(List.of(theirTicket.getId()), "other")).header("Idempotency-Key", "k"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        verifyNoInteractions(stripeClient);
    }

    @Test
    void emptyTicketIds_is400() throws Exception {
        mvc.perform(refund(order, body(List.of(), "other")).header("Idempotency-Key", "k"))
                .andExpect(status().isBadRequest());
    }

    /** The FE sends the lowercase wire form; the constant name is accepted too. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"REQUESTED_BY_CUSTOMER", "requested_by_customer"})
    void anyReasonCasing_is202_andStoresARefundWithThatReason(String reason) throws Exception {
        RefundService stripeRefunds = mock(RefundService.class);
        when(stripeClient.refunds()).thenReturn(stripeRefunds);
        com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
        stripeRefund.setId("re_" + UUID.randomUUID().toString().replace("-", ""));
        stripeRefund.setCharge("ch_" + UUID.randomUUID().toString().replace("-", ""));
        stripeRefund.setStatus("pending");
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stripeRefund);

        String clientKey = "k-" + UUID.randomUUID();
        // Order total 1500 for one 1500 ticket; fee share round(75 x 1500 / 1500) = 75.
        String id = om.readTree(mvc.perform(refund(order, body(List.of(ticket.getId()), reason))
                                .header("Idempotency-Key", clientKey))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status").value("pending"))
                        .andExpect(jsonPath("$.amountMinor").value(1500))
                        .andExpect(jsonPath("$.applicationFeeRefundMinor").value(75))
                        .andExpect(jsonPath("$.reason").value("requested_by_customer"))
                        .andExpect(jsonPath("$.ticketIds[0]").value(ticket.getId().toString()))
                        .andReturn().getResponse().getContentAsString())
                .get("id").asText();

        Refund stored = refunds.findById(UUID.fromString(id)).orElseThrow();
        assertThat(stored.getOrderId()).isEqualTo(order.getId());
        assertThat(stored.getReason()).isEqualTo(RefundReason.REQUESTED_BY_CUSTOMER);
        assertThat(stored.getStripeRefundId()).isEqualTo(stripeRefund.getId());
        assertThat(stored.getStatus()).isEqualTo(RefundStatus.PENDING);

        // What reached Stripe: the order's payment, the full amount, transfer reversed with the fee share.
        ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeRefunds).create(params.capture(), options.capture());
        assertThat(params.getValue().getPaymentIntent()).isEqualTo(jdbc.queryForObject(
                "SELECT stripe_payment_intent_id FROM orders WHERE id = ?", String.class, order.getId()));
        assertThat(params.getValue().getAmount()).isEqualTo(1500L);
        assertThat(params.getValue().getReverseTransfer()).isTrue();
        assertThat(params.getValue().getRefundApplicationFee()).isTrue();
        assertThat(params.getValue().getReason()).isEqualTo(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER);
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo(com.imin.iminapi.refund.RefundService
                .stripeIdempotencyKey(order.getId(), clientKey, List.of(ticket.getId()), 1500L, ""));
    }

    private Order paidOrder(Event e) {
        Order o = fx.order(e, fx.email("buyer"));
        // test_mode as checkout stamps it under the suite's sk_test key.
        jdbc.update("UPDATE orders SET stripe_payment_intent_id = ?, application_fee_minor = 75, test_mode = true "
                        + "WHERE id = ?",
                "pi_" + UUID.randomUUID().toString().replace("-", ""), o.getId());
        return o;
    }

    private String body(List<UUID> ticketIds, String reason) throws Exception {
        return om.writeValueAsString(Map.of(
                "ticketIds", ticketIds.stream().map(UUID::toString).toList(),
                "reason", reason));
    }

    private MockHttpServletRequestBuilder refund(Order o, String body) {
        return post("/api/v1/orders/{id}/refund", o.getId())
                .with(auth(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
