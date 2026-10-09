package com.imin.iminapi.refund;

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
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PgFaults;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.StripeCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The reconciler resolves an attempt Stripe never answered without ever sending a second refund. */
@IminIntegrationTest
class RefundAttemptReconcilerTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StripeClient stripeClient;
    @Autowired RefundRepository refunds;
    @Autowired com.imin.iminapi.refund.RefundService refundService;
    @Autowired RefundAttemptReconciler reconciler;
    @Autowired DisputeRepository disputes;
    @Autowired MutableClock clock;
    final ObjectMapper om = new ObjectMapper();

    private final List<UUID> orgIds = new ArrayList<>();
    private AuthPrincipal principal;
    private Event event;
    private com.stripe.service.RefundService stripeRefunds;

    @BeforeEach
    void setUp() throws Exception {
        Organization org = fx.org();
        orgIds.add(org.getId());
        User owner = fx.owner(org);
        principal = fx.principal(owner);
        event = fx.event(org, owner, EventStatus.LIVE, Clock.systemUTC().instant().plus(Duration.ofDays(30)));
        stripeRefunds = mock(com.stripe.service.RefundService.class);
        when(stripeClient.refunds()).thenReturn(stripeRefunds);
        // Stripe holds no refund unless a test says otherwise.
        when(stripeRefunds.list(any(RefundListParams.class))).thenAnswer(inv -> page(false, List.of()));
    }

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, orgIds);
    }

    @ParameterizedTest(name = "onSecondPage={0}")
    @ValueSource(booleans = {false, true})
    void refundFoundOnStripeByMetadata_isAdoptedWithoutCreate(boolean onSecondPage) throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = fx.ticket(order, Ticket.STATE_ISSUED);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("read timeout"));
        UUID id = uncertainPost(order, ticket);

        com.stripe.model.Refund match = stripeRefund("pending");
        match.setMetadata(Map.of("imin_refund_id", id.toString()));
        com.stripe.model.Refund other = stripeRefund("succeeded");
        when(stripeRefunds.list(argThat((RefundListParams p) -> p != null
                && order.getStripePaymentIntentId().equals(p.getPaymentIntent())))).thenAnswer(inv -> {
            RefundListParams p = inv.getArgument(0);
            if (!onSecondPage) return page(false, List.of(other, match));
            return p.getStartingAfter() == null ? page(true, List.of(other)) : page(false, List.of(match));
        });
        clock.advance(Duration.ofMinutes(6));
        reconcile();

        Refund after = refunds.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(after.getStripeRefundId()).isEqualTo(match.getId());
        verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));
    }

    enum Variant { PLAIN, PLATFORM, CAP_LOWERED }

    /** Re-sent with the stored amount, key and funding variant: the same request Stripe may already hold. */
    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(Variant.class)
    void refundMissingOnStripe_isResentWithTheStoredKeyAmountAndVariant(Variant variant) throws Exception {
        Order order = paidOrder(variant == Variant.CAP_LOWERED ? 3000 : 1500);
        Ticket ticket = fx.ticket(order, Ticket.STATE_ISSUED);
        if (variant == Variant.CAP_LOWERED) fx.ticket(order, Ticket.STATE_ISSUED);
        if (variant == Variant.PLATFORM) {
            when(stripeRefunds.create(argThat((RefundCreateParams p) -> p != null && p.getReverseTransfer()),
                    any(RequestOptions.class)))
                    .thenThrow(new InvalidRequestException("Insufficient funds", "amount", "req_1",
                            "balance_insufficient", 400, null));
            when(stripeRefunds.create(argThat((RefundCreateParams p) -> p != null && !p.getReverseTransfer()),
                    any(RequestOptions.class)))
                    .thenThrow(new ApiConnectionException("read timeout"))
                    .thenReturn(stripeRefund("pending"));
        } else {
            when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiConnectionException("read timeout"))
                    .thenReturn(stripeRefund("pending"));
        }
        // One ticket of 1500 on a total of 1500, or one of two on 3000: round(3000 × 1500 / 3000) = 1500.
        UUID id = uncertainPost(order, ticket);
        if (variant == Variant.CAP_LOWERED) {
            // A Dashboard partial of 500 lowers the remaining cap to 3000 − 1500 − 500 = 1000.
            refundService.handleWebhookStatusChange("re_dash" + UUID.randomUUID(), RefundStatus.SUCCEEDED, null, null,
                    order.getStripePaymentIntentId(), "ch_dash", 500L, null);
        }
        clock.advance(Duration.ofMinutes(6));
        reconcile();

        ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeRefunds, times(variant == Variant.PLATFORM ? 3 : 2)).create(params.capture(), options.capture());
        List<RequestOptions> keys = options.getAllValues();
        RefundCreateParams resent = params.getAllValues().get(params.getAllValues().size() - 1);
        boolean platform = variant == Variant.PLATFORM;
        assertThat(keys.get(keys.size() - 1).getIdempotencyKey())
                .isEqualTo(keys.get(keys.size() - 2).getIdempotencyKey())
                .isEqualTo(com.imin.iminapi.refund.RefundService.stripeKeyFor(id, platform));
        assertThat(resent.getAmount()).isEqualTo(1500L);
        assertThat(resent.getReverseTransfer()).isEqualTo(!platform);
        assertThat(refunds.findById(id).orElseThrow().getStatus()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void resentRefundRefused_marksFailedAndReleasesTickets() throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = fx.ticket(order, Ticket.STATE_ISSUED);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("read timeout"))
                .thenThrow(new InvalidRequestException("Charge has already been refunded.", null, "req_2",
                        "charge_already_refunded", 400, null));
        UUID id = uncertainPost(order, ticket);
        clock.advance(Duration.ofMinutes(6));
        reconcile();

        Refund after = refunds.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(after.getFailureCode()).isEqualTo("charge_already_refunded");
        assertThat(claims(id)).isEmpty();
        verify(stripeRefunds, times(2)).create(any(RefundCreateParams.class), any(RequestOptions.class));
    }

    @Test
    void refundMissingOnStripeOfADisputedOrder_isFailedWithoutCreate() throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = fx.ticket(order, Ticket.STATE_ISSUED);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("read timeout"));
        UUID id = uncertainPost(order, ticket);
        dispute(order);
        clock.advance(Duration.ofMinutes(6));
        reconcile();

        Refund after = refunds.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(after.getFailureCode()).isEqualTo("order_disputed");
        assertThat(after.getFailureMessage()).isEqualTo(
                "Stripe has no record of this refund and the order is now disputed, so imin did not send it.");
        assertThat(claims(id)).isEmpty();
        verify(stripeRefunds, times(1)).list(listOf(order));
        verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));
    }

    enum Excluded { TOO_YOUNG, OTHER_STRIPE_MODE, CLAIM_LOST }

    /** Each case seeds an eligible row too, so a skipped tick cannot pass for an excluded row. */
    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(Excluded.class)
    void rowsTheReconcilerMustNotTouch_getNoStripeCall(Excluded excluded) throws Exception {
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("read timeout"));
        Order eligible = paidOrder(1500);
        uncertainPost(eligible, fx.ticket(eligible, Ticket.STATE_ISSUED));
        Order skipped = paidOrder(1500);
        if (excluded == Excluded.TOO_YOUNG) clock.advance(Duration.ofMinutes(5));
        UUID skippedId = uncertainPost(skipped, fx.ticket(skipped, Ticket.STATE_ISSUED));
        if (excluded == Excluded.OTHER_STRIPE_MODE) {
            jdbc.update("UPDATE orders SET test_mode = false WHERE id = ?", skipped.getId());
        }
        clock.advance(Duration.ofMinutes(excluded == Excluded.TOO_YOUNG ? 1 : 6));

        if (excluded == Excluded.CLAIM_LOST) {
            try (PgFaults.Fault ignored = PgFaults.skipUpdates(jdbc, "refunds", "id", skippedId)) {
                reconcile();
            }
        } else {
            reconcile();
        }

        verify(stripeRefunds).list(listOf(eligible));
        verify(stripeRefunds, never()).list(listOf(skipped));
        verify(stripeRefunds, times(1)).create(
                argThat((RefundCreateParams p) -> p != null && skipped.getStripePaymentIntentId().equals(p.getPaymentIntent())),
                any(RequestOptions.class));
        Refund untouched = refunds.findById(skippedId).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(untouched.getStripeAttempts()).isEqualTo(1);
    }

    @ParameterizedTest(name = "listFails={0}")
    @ValueSource(booleans = {true, false})
    void uncertainResend_keepsTheClaim(boolean listFails) throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = fx.ticket(order, Ticket.STATE_ISSUED);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("read timeout"));
        UUID id = uncertainPost(order, ticket);
        if (listFails) {
            when(stripeRefunds.list(listOf(order))).thenThrow(new ApiConnectionException("list timeout"));
        }
        clock.advance(Duration.ofMinutes(6));
        reconcile();

        Refund after = refunds.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(after.getStripeAttempts()).isEqualTo(2);
        assertThat(claims(id)).containsExactly(ticket.getId());
        verify(stripeRefunds).list(listOf(order));
        verify(stripeRefunds, times(listFails ? 1 : 2)).create(any(RefundCreateParams.class), any(RequestOptions.class));
    }

    /** Another claimer bumped the attempt a moment ago; a pass that read the row afresh must not claim it too. */
    @Test
    void rowJustBumpedByAnotherClaimer_isNotClaimedAgain() throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = fx.ticket(order, Ticket.STATE_ISSUED);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("read timeout"));
        UUID id = uncertainPost(order, ticket);
        clock.advance(Duration.ofMinutes(6));
        jdbc.update("UPDATE refunds SET stripe_attempt_at = ?, stripe_attempts = 2 WHERE id = ?",
                java.sql.Timestamp.from(clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)), id);

        assertThat(refundService.resolveAttempt(id)).isEqualTo(com.imin.iminapi.refund.RefundService.Resolution.SKIPPED);

        verify(stripeRefunds, never()).list(listOf(order));
        assertThat(refunds.findById(id).orElseThrow().getStripeAttempts()).isEqualTo(2);
    }

    // ── helpers ──

    /** POSTs a refund whose Stripe call has no known outcome; returns the REQUESTED row id. */
    private UUID uncertainPost(Order order, Ticket ticket) throws Exception {
        String body = mvc.perform(post("/api/v1/orders/{id}/refund", order.getId())
                        .with(auth(principal))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "ticketIds", List.of(ticket.getId().toString()), "reason", "other"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFUND_IN_PROGRESS"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(om.readTree(body).get("error").get("fields").get("refundId").asText());
    }

    private void reconcile() {
        jdbc.update("UPDATE shedlock SET lock_until = locked_at WHERE name = 'refund_attempt_reconcile'");
        reconciler.reconcile();
    }

    private static RefundListParams listOf(Order o) {
        return argThat((RefundListParams p) -> p != null && o.getStripePaymentIntentId().equals(p.getPaymentIntent()));
    }

    private Order paidOrder(long totalMinor) {
        Order o = fx.order(event, fx.email("buyer"));
        String pi = "pi_" + UUID.randomUUID().toString().replace("-", "");
        // test_mode as checkout stamps it under the suite's sk_test key.
        jdbc.update("UPDATE orders SET stripe_payment_intent_id = ?, application_fee_minor = 0, total_minor = ?, "
                + "test_mode = true WHERE id = ?", pi, totalMinor, o.getId());
        o.setStripePaymentIntentId(pi);
        return o;
    }

    private void dispute(Order o) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().substring(0, 12));
        d.setOrgId(o.getOrgId());
        d.setEventId(o.getEventId());
        d.setOrderId(o.getId());
        d.setAmountMinor(1500);
        d.setCurrency("eur");
        d.setStatus(DisputeStatus.OPEN);
        d.setOpenedAt(Instant.parse("2026-09-01T10:15:30Z"));
        disputes.save(d);
    }

    private List<UUID> claims(UUID refundId) {
        return jdbc.queryForList("SELECT ticket_id FROM refund_tickets WHERE refund_id = ?", UUID.class, refundId);
    }

    private static com.stripe.model.Refund stripeRefund(String status) {
        com.stripe.model.Refund r = new com.stripe.model.Refund();
        r.setId("re_" + UUID.randomUUID().toString().replace("-", ""));
        r.setCharge("ch_" + UUID.randomUUID().toString().replace("-", ""));
        r.setStatus(status);
        return r;
    }

    private static StripeCollection<com.stripe.model.Refund> page(boolean hasMore, List<com.stripe.model.Refund> data) {
        StripeCollection<com.stripe.model.Refund> c = new StripeCollection<>();
        c.setData(data);
        c.setHasMore(hasMore);
        return c;
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
