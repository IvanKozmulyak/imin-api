package com.imin.iminapi.refund;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The organizer refund-request surface over the real service: org scoping, search and approval. */
@IminIntegrationTest
class RefundRequestControllerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StripeClient stripeClient;
    @Autowired RefundRequestRepository requests;
    @Autowired RefundRepository refunds;
    @Autowired RefundReferenceGenerator references;
    @Autowired Clock clock;

    private final List<UUID> orgIds = new ArrayList<>();
    private Organization org;
    private AuthPrincipal me;
    private Event event;

    @BeforeEach
    void setUp() {
        org = fx.org();
        orgIds.add(org.getId());
        User owner = fx.owner(org);
        me = fx.principal(owner);
        event = fx.event(org, owner, EventStatus.LIVE, clock.instant().plus(Duration.ofDays(30)));
    }

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, orgIds);
    }

    /** 404, not 403: another org's requests stay invisible, even with a real request in that org. */
    @Test
    void list_ofAnotherOrg_is404() throws Exception {
        Organization other = fx.org();
        orgIds.add(other.getId());
        User otherOwner = fx.owner(other);
        pendingRequest(fx.event(other, otherOwner, EventStatus.LIVE, clock.instant().plus(Duration.ofDays(30))));

        mvc.perform(get("/api/v1/orgs/{orgId}/refund-requests", other.getId()).with(auth(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void list_ofOwnOrg_returnsItsRequests_andAQuotedReferenceNarrowsToOne() throws Exception {
        RefundRequest quoted = pendingRequest(event);
        RefundRequest otherOne = pendingRequest(event);

        mvc.perform(get("/api/v1/orgs/{orgId}/refund-requests", org.getId()).with(auth(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].id", containsInAnyOrder(
                        quoted.getId().toString(), otherOne.getId().toString())));

        // The customer quotes the code without its prefix and in lower case.
        String typed = quoted.getReference().substring("REQ-".length()).toLowerCase();
        mvc.perform(get("/api/v1/orgs/{orgId}/refund-requests", org.getId())
                        .param("search", typed)
                        .with(auth(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(quoted.getId().toString()))
                .andExpect(jsonPath("$[0].reference").value(quoted.getReference()));
    }

    /** A typo or stale bookmark in ?status= is a client mistake, not a 500. */
    @Test
    void list_withAnUnknownStatus_is400() throws Exception {
        mvc.perform(get("/api/v1/orgs/{orgId}/refund-requests", org.getId())
                        .param("status", "open")
                        .with(auth(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void approve_ofAPendingRequest_issuesTheRefundThroughStripe() throws Exception {
        RefundRequest rr = pendingRequest(event);
        RefundService stripeRefunds = mock(RefundService.class);
        when(stripeClient.refunds()).thenReturn(stripeRefunds);
        com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
        stripeRefund.setId("re_" + UUID.randomUUID().toString().replace("-", ""));
        stripeRefund.setCharge("ch_" + UUID.randomUUID().toString().replace("-", ""));
        stripeRefund.setStatus("pending");
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(stripeRefund);

        mvc.perform(post("/api/v1/orgs/{orgId}/refund-requests/{id}/approve", org.getId(), rr.getId())
                        .with(auth(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirm\":true,\"note\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.refundStatus").value("pending"));

        ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeRefunds, times(1)).create(params.capture(), options.capture());
        // No booking fee on this order, so no fee share is asked back; the transfer is still reversed.
        assertThat(params.getValue().getPaymentIntent()).isEqualTo(jdbc.queryForObject(
                "SELECT stripe_payment_intent_id FROM orders WHERE id = ?", String.class, rr.getOrderId()));
        assertThat(params.getValue().getAmount()).isEqualTo(1500L);
        assertThat(params.getValue().getReverseTransfer()).isTrue();
        assertThat(params.getValue().getRefundApplicationFee()).isFalse();
        assertThat(params.getValue().getReason()).isEqualTo(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER);
        UUID ticketId = jdbc.queryForObject("SELECT id FROM tickets WHERE order_id = ?", UUID.class, rr.getOrderId());
        // A re-press of Confirm replays the same Stripe key.
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo(com.imin.iminapi.refund.RefundService
                .stripeIdempotencyKey(rr.getOrderId(), "refund-request-" + rr.getId(), List.of(ticketId), 1500L, ""));
        RefundRequest decided = requests.findById(rr.getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(RefundRequestStatus.APPROVED);
        assertThat(decided.getPendingMarker()).as("the one-open-per-order slot is released").isNull();
        Refund refund = refunds.findById(decided.getRefundId()).orElseThrow();
        assertThat(refund.getOrderId()).isEqualTo(rr.getOrderId());
        assertThat(refund.getStripeRefundId()).isEqualTo(stripeRefund.getId());
        assertThat(refund.getAmountMinor()).isEqualTo(1500L);
    }

    /** A submitted request: a paid order with one live ticket and its open request. */
    private RefundRequest pendingRequest(Event e) {
        String buyer = fx.email("buyer");
        Order o = fx.order(e, buyer);
        // test_mode as checkout stamps it under the suite's sk_test key.
        jdbc.update("UPDATE orders SET stripe_payment_intent_id = ?, test_mode = true WHERE id = ?",
                "pi_" + UUID.randomUUID().toString().replace("-", ""), o.getId());
        fx.ticket(o, Ticket.STATE_ISSUED);
        RefundRequest rr = new RefundRequest();
        rr.setReference(references.next());
        rr.setOrderId(o.getId());
        rr.setOrgId(e.getOrgId());
        rr.setEventId(e.getId());
        rr.setBuyerEmail(buyer);
        rr.setReason(RefundRequestReason.CANT_ATTEND);
        rr.setExplanation("can't make it");
        rr.setStatus(RefundRequestStatus.PENDING);
        rr.setPendingMarker(o.getId());
        return requests.save(rr);
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
