package com.imin.iminapi.refund;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import com.imin.iminapi.support.PgFaults;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.IdempotencyException;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A refund Stripe made is never lost to a rollback, and no retry shape moves the money twice. */
@IminIntegrationTest
class RefundDurabilityTest {

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired StripeClient stripeClient;
    @Autowired RefundRepository refunds;
    @Autowired com.imin.iminapi.refund.RefundService refundService;
    @Autowired RefundAttemptReconciler reconciler;
    @Autowired RefundRequestRepository requests;
    @Autowired RefundReferenceGenerator references;
    @Autowired MutableClock clock;
    final ObjectMapper om = new ObjectMapper();

    private final List<UUID> orgIds = new ArrayList<>();
    private AuthPrincipal principal;
    private Event event;
    private TicketTier tier;
    private com.stripe.service.RefundService stripeRefunds;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        orgIds.add(org.getId());
        User owner = fx.owner(org);
        principal = fx.principal(owner);
        event = fx.event(org, owner, EventStatus.LIVE, Clock.systemUTC().instant().plus(Duration.ofDays(30)));
        tier = fx.tier(event, 1500, 100);
        jdbc.update("UPDATE ticket_tiers SET sold = 10 WHERE id = ?", tier.getId());
        stripeRefunds = mock(com.stripe.service.RefundService.class);
        when(stripeClient.refunds()).thenReturn(stripeRefunds);
    }

    @AfterEach
    void cleanUp() {
        OrgRows.delete(jdbc, orgIds);
    }

    /**
     * Stripe refunded, then recording it failed (the ticket write). The committed attempt holds the
     * ticket, so a retry with a new key is refused, and the reconciler adopts the one Stripe refund.
     */
    @Test
    void rollbackAfterStripeSuccess_retryWithNewKey_movesMoneyOnce() throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = ticket(order);
        com.stripe.model.Refund made = stripeRefund("succeeded");
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenReturn(made);

        String id;
        try (PgFaults.Fault ignored = PgFaults.failWrites(jdbc, "tickets", "id", ticket.getId())) {
            id = field(refund(order, "k1-" + UUID.randomUUID(), ticket.getId())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("REFUND_IN_PROGRESS")), "refundId");
        }
        UUID rowId = UUID.fromString(id);
        assertThat(row(rowId).getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(claims(rowId)).containsExactly(ticket.getId());

        refund(order, "k2-" + UUID.randomUUID(), ticket.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_ALREADY_REFUNDED"));
        verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));

        made.setMetadata(Map.of("imin_refund_id", id));
        when(stripeRefunds.list(any(RefundListParams.class))).thenReturn(page(false, made));
        clock.advance(Duration.ofMinutes(6));
        reconcile();

        Refund after = row(rowId);
        assertThat(after.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(after.getStripeRefundId()).isEqualTo(made.getId());
        assertThat(ticketState(ticket)).isEqualTo(Ticket.STATE_REFUNDED);
        assertThat(sold()).isEqualTo(9);
        verify(stripeRefunds).list(org.mockito.ArgumentMatchers.<RefundListParams>argThat(
                p -> order.getStripePaymentIntentId().equals(p.getPaymentIntent())));
        verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));
    }

    @Test
    void uncertainAttempt_holdsTicketsAgainstAChangedSelection_andReplaysTheSameKey() throws Exception {
        Order order = paidOrder(3000);
        Ticket a = ticket(order);
        Ticket b = ticket(order);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("read timeout"));
        String k1 = "k1-" + UUID.randomUUID();

        String id = field(refund(order, k1, a.getId(), b.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFUND_IN_PROGRESS")), "refundId");

        refund(order, "k2-" + UUID.randomUUID(), a.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TICKET_ALREADY_REFUNDED"));
        refund(order, k1, a.getId(), b.getId())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("requested"))
                .andExpect(jsonPath("$.id").value(id));

        ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeRefunds, times(1)).create(params.capture(), options.capture());
        assertThat(options.getValue().getIdempotencyKey())
                .isEqualTo(com.imin.iminapi.refund.RefundService.stripeKeyFor(UUID.fromString(id), false));
        assertThat(params.getValue().getMetadata()).isEqualTo(Map.of("imin_refund_id", id));
    }

    @Test
    void syncFailedRefund_releasesItsTickets() throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = ticket(order);
        com.stripe.model.Refund failed = stripeRefund("failed");
        failed.setFailureReason("expired_or_canceled_card");
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(failed, stripeRefund("pending"));

        String k1 = "k1-" + UUID.randomUUID();
        String id = field(refund(order, k1, ticket.getId())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("failed")), "id");
        Refund row = row(UUID.fromString(id));
        assertThat(row.getFailureCode()).isEqualTo("expired_or_canceled_card");
        assertThat(row.getStripeRefundId()).isEqualTo(failed.getId());
        assertThat(row.getIdempotencyKey()).isEqualTo("failed:" + id);
        assertThat(claims(row.getId())).isEmpty();

        // The same key now opens a new attempt instead of replaying the failed one.
        String second = field(refund(order, k1, ticket.getId())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending")), "id");
        assertThat(second).isNotEqualTo(id);
        verify(stripeRefunds, times(2)).create(any(RefundCreateParams.class), any(RequestOptions.class));
    }

    /** An idempotency_error proves an earlier request with this key reached Stripe: not a refusal. */
    @Test
    void idempotencyError_isRefundInProgress_andKeepsTheClaim() throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = ticket(order);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new IdempotencyException("Keys for idempotent requests can only be used with the "
                        + "same parameters", "req_1", null, 400));

        String id = field(refund(order, "k1-" + UUID.randomUUID(), ticket.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFUND_IN_PROGRESS")), "refundId");

        assertThat(row(UUID.fromString(id)).getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(claims(UUID.fromString(id))).containsExactly(ticket.getId());
    }

    @Test
    void definitiveRefusal_freesTicketsAndClientKey() throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = ticket(order);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new InvalidRequestException("Charge has already been refunded.", null, "req_1",
                        "charge_already_refunded", 400, null))
                .thenReturn(stripeRefund("pending"));
        String k1 = "k1-" + UUID.randomUUID();

        refund(order, k1, ticket.getId())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("STRIPE_REFUND_FAILED"))
                .andExpect(jsonPath("$.error.fields.stripeCode").value("charge_already_refunded"));
        Refund failed = refunds.findByOrderIdOrderByCreatedAtDesc(order.getId()).get(0);
        assertThat(failed.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("charge_already_refunded");
        assertThat(failed.getIdempotencyKey()).isEqualTo("failed:" + failed.getId());
        assertThat(claims(failed.getId())).isEmpty();

        String second = field(refund(order, k1, ticket.getId())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending")), "id");
        assertThat(second).isNotEqualTo(failed.getId().toString());
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        verify(stripeRefunds, times(2)).create(any(RefundCreateParams.class), options.capture());
        assertThat(options.getAllValues().get(1).getIdempotencyKey())
                .isNotEqualTo(options.getAllValues().get(0).getIdempotencyKey());
    }

    /** The claim is committed before Stripe is called, so a concurrent request is refused at once. */
    @Test
    void secondRequestDuringFirstStripeCall_isRefusedWithoutStripe() throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = ticket(order);
        List<Throwable> secondOutcome = new ArrayList<>();
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
            CompletableFuture<Void> b = CompletableFuture.runAsync(() -> {
                try {
                    refundService.createRefund(order.getId(), principal, "kb-" + UUID.randomUUID(),
                            List.of(ticket.getId()), RefundReason.OTHER);
                } catch (Throwable t) {
                    secondOutcome.add(t);
                }
            });
            b.get(5, TimeUnit.SECONDS);
            return stripeRefund("pending");
        });

        refund(order, "ka-" + UUID.randomUUID(), ticket.getId())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending"));

        assertThat(secondOutcome).singleElement()
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.TICKET_ALREADY_REFUNDED));
        verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));
    }

    /** Whichever of the webhook and the live answer lands first, one row and one inventory release. */
    @ParameterizedTest(name = "webhookFirst={0}")
    @ValueSource(booleans = {true, false})
    void webhookBeforeOrAfterFinalize_releasesInventoryOnce(boolean webhookFirst) throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = ticket(order);
        com.stripe.model.Refund made = stripeRefund("succeeded");
        made.setPaymentIntent(order.getStripePaymentIntentId());
        made.setAmount(1500L);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
            RefundCreateParams p = inv.getArgument(0);
            @SuppressWarnings("unchecked")
            String rowId = ((Map<String, String>) p.getMetadata()).get("imin_refund_id");
            made.setMetadata(Map.of("imin_refund_id", rowId));
            if (webhookFirst) webhook(made);
            return made;
        });

        refund(order, "k-" + UUID.randomUUID(), ticket.getId()).andExpect(status().isAccepted());
        if (!webhookFirst) webhook(made);

        List<Refund> rows = refunds.findByOrderIdOrderByCreatedAtDesc(order.getId());
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
            assertThat(r.getStripeRefundId()).isEqualTo(made.getId());
        });
        assertThat(sold()).isEqualTo(9);
        assertThat(ticketState(ticket)).isEqualTo(Ticket.STATE_REFUNDED);
    }

    /** The approve answered 5xx after the refund committed; approving again links that refund. */
    @Test
    void approveRetryAfterLostApproval_linksTheExistingRefund() throws Exception {
        Order order = paidOrder(1500);
        ticket(order);
        RefundRequest rr = pendingRequest(order);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(stripeRefund("pending"));

        try (PgFaults.Fault ignored = PgFaults.failWrites(jdbc, "refund_requests", "id", rr.getId())) {
            approve(rr).andExpect(status().is5xxServerError());
        }
        assertThat(requests.findById(rr.getId()).orElseThrow().getStatus()).isEqualTo(RefundRequestStatus.PENDING);
        Refund committed = refunds.findByOrderIdOrderByCreatedAtDesc(order.getId()).get(0);
        assertThat(committed.getStatus()).isEqualTo(RefundStatus.PENDING);

        approve(rr)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.refundId").value(committed.getId().toString()))
                .andExpect(jsonPath("$.refundStatus").value("pending"));
        RefundRequest decided = requests.findById(rr.getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(RefundRequestStatus.APPROVED);
        assertThat(decided.getRefundId()).isEqualTo(committed.getId());
        verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));
    }

    @Test
    void webhookMetadataNamingARowWithAnotherStripeId_materializesTheSecondRefund() {
        Order order = paidOrder(1500);
        Ticket ticket = ticket(order);
        Refund first = new Refund();
        first.setOrderId(order.getId());
        first.setStripePaymentIntentId(order.getStripePaymentIntentId());
        first.setStripeRefundId("re_A" + UUID.randomUUID());
        first.setAmountMinor(1500);
        first.setCurrency(order.getCurrency());
        first.setReason(RefundReason.OTHER);
        first.setStatus(RefundStatus.SUCCEEDED);
        first.setIdempotencyKey("k-" + UUID.randomUUID());
        first = refunds.saveAndFlush(first);
        jdbc.update("INSERT INTO refund_tickets (refund_id, ticket_id) VALUES (?, ?)", first.getId(), ticket.getId());
        String reB = "re_B" + UUID.randomUUID();

        refundService.handleWebhookStatusChange(reB, RefundStatus.SUCCEEDED, null, null,
                order.getStripePaymentIntentId(), "ch_B", 1500L, first.getId().toString());

        assertThat(row(first.getId()).getStripeRefundId()).isEqualTo(first.getStripeRefundId());
        assertThat(refunds.findByStripeRefundId(reB)).hasValueSatisfying(
                r -> assertThat(r.getOrderId()).isEqualTo(order.getId()));
    }

    /** A refund the webhook reports failed frees its key: retrying it, or re-approving its request, opens a new attempt. */
    @ParameterizedTest(name = "viaApprove={0}")
    @ValueSource(booleans = {false, true})
    void webhookFailedRefund_freesTheKey_soARetryOpensANewAttempt(boolean viaApprove) throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = ticket(order);
        com.stripe.model.Refund first = stripeRefund("pending");
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenReturn(first, stripeRefund("pending"));
        String key = "k1-" + UUID.randomUUID();
        RefundRequest rr = viaApprove ? pendingRequest(order) : null;

        if (viaApprove) {
            // The approve loses its own commit, so the request is still PENDING when the refund fails.
            try (PgFaults.Fault ignored = PgFaults.failWrites(jdbc, "refund_requests", "id", rr.getId())) {
                approve(rr).andExpect(status().is5xxServerError());
            }
        } else {
            refund(order, key, ticket.getId()).andExpect(status().isAccepted());
        }
        Refund failed = refunds.findByStripeRefundId(first.getId()).orElseThrow();
        refundService.handleWebhookStatusChange(first.getId(), RefundStatus.FAILED, "lost_or_stolen_card",
                "lost_or_stolen_card", order.getStripePaymentIntentId(), first.getCharge(), 1500L, null);
        assertThat(row(failed.getId()).getIdempotencyKey()).isEqualTo("failed:" + failed.getId());
        assertThat(claims(failed.getId())).isEmpty();

        String next = viaApprove
                ? field(approve(rr).andExpect(status().isOk())
                        .andExpect(jsonPath("$.refundStatus").value("pending")), "refundId")
                : field(refund(order, key, ticket.getId()).andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status").value("pending")), "id");
        assertThat(next).isNotEqualTo(failed.getId().toString());
        verify(stripeRefunds, times(2)).create(any(RefundCreateParams.class), any(RequestOptions.class));
    }

    enum Resolved { SUCCEEDED, FAILED }

    /** An approve whose refund Stripe has not answered stays PENDING until a later approve decides it. */
    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(Resolved.class)
    void approveWhileRefundInProgress_leavesTheRequestPending_untilTheRefundResolves(Resolved resolved)
            throws Exception {
        Order order = paidOrder(1500);
        ticket(order);
        RefundRequest rr = pendingRequest(order);
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                .thenThrow(new ApiConnectionException("read timeout"))
                .thenThrow(new InvalidRequestException("Charge has already been refunded.", null, "req_2",
                        "charge_already_refunded", 400, null))
                .thenReturn(stripeRefund("pending"));

        String inFlight = field(approve(rr)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFUND_IN_PROGRESS")), "refundId");
        approve(rr)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REFUND_IN_PROGRESS"))
                .andExpect(jsonPath("$.error.fields.refundId").value(inFlight));
        RefundRequest still = requests.findById(rr.getId()).orElseThrow();
        assertThat(still.getStatus()).isEqualTo(RefundRequestStatus.PENDING);
        assertThat(still.getRefundId()).isNull();
        assertThat(still.getPendingMarker()).isNotNull();
        verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));

        if (resolved == Resolved.SUCCEEDED) {
            com.stripe.model.Refund made = stripeRefund("succeeded");
            made.setMetadata(Map.of("imin_refund_id", inFlight));
            when(stripeRefunds.list(any(RefundListParams.class))).thenReturn(page(false, made));
        } else {
            when(stripeRefunds.list(any(RefundListParams.class))).thenReturn(page(false));
        }
        clock.advance(Duration.ofMinutes(6));
        reconcile();
        assertThat(row(UUID.fromString(inFlight)).getStatus()).isEqualTo(RefundStatus.valueOf(resolved.name()));

        String decidedId = field(approve(rr)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.refundStatus").value(resolved == Resolved.SUCCEEDED ? "succeeded" : "pending")),
                "refundId");
        if (resolved == Resolved.SUCCEEDED) {
            assertThat(decidedId).isEqualTo(inFlight);
            verify(stripeRefunds, times(1)).create(any(RefundCreateParams.class), any(RequestOptions.class));
        } else {
            assertThat(decidedId).isNotEqualTo(inFlight);
            verify(stripeRefunds, times(3)).create(any(RefundCreateParams.class), any(RequestOptions.class));
        }
        RefundRequest decided = requests.findById(rr.getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(RefundRequestStatus.APPROVED);
        assertThat(decided.getRefundId()).isEqualTo(UUID.fromString(decidedId));
    }

    /**
     * The platform-funded flag did not commit (a concurrent writer won, or the write failed), so no
     * platform refund is sent and the attempt stays for the reconciler.
     */
    @ParameterizedTest(name = "writeFails={0}")
    @ValueSource(booleans = {false, true})
    void platformSwitchNotCommitted_isInProgress_withoutAPlatformSend(boolean writeFails) throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = ticket(order);
        List<PgFaults.Fault> faults = new ArrayList<>();
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
            UUID rowId = UUID.fromString(rowIdOf(inv.getArgument(0)));
            faults.add(writeFails ? PgFaults.failWrites(jdbc, "refunds", "id", rowId)
                    : PgFaults.skipUpdates(jdbc, "refunds", "id", rowId));
            throw new InvalidRequestException("Insufficient funds", "amount", "req_1", "balance_insufficient", 400, null);
        });

        String id;
        try {
            id = field(refund(order, "k-" + UUID.randomUUID(), ticket.getId())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("REFUND_IN_PROGRESS")), "refundId");
        } finally {
            faults.forEach(PgFaults.Fault::close);
        }

        Refund after = row(UUID.fromString(id));
        assertThat(after.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(after.isPlatformFunded()).isFalse();
        assertThat(claims(after.getId())).containsExactly(ticket.getId());
        ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
        verify(stripeRefunds, times(1)).create(params.capture(), any(RequestOptions.class));
        assertThat(params.getValue().getReverseTransfer()).isTrue();
    }

    /** Stripe refused, but recording the refusal failed: the caller still hears the refusal. */
    @Test
    void refusalThatCannotBeRecorded_stillAnswersTheRefusal() throws Exception {
        Order order = paidOrder(1500);
        Ticket ticket = ticket(order);
        List<PgFaults.Fault> faults = new ArrayList<>();
        when(stripeRefunds.create(any(RefundCreateParams.class), any(RequestOptions.class))).thenAnswer(inv -> {
            faults.add(PgFaults.failWrites(jdbc, "refunds", "id", UUID.fromString(rowIdOf(inv.getArgument(0)))));
            throw new InvalidRequestException("Charge has already been refunded.", null, "req_1",
                    "charge_already_refunded", 400, null);
        });

        try {
            refund(order, "k-" + UUID.randomUUID(), ticket.getId())
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("STRIPE_REFUND_FAILED"))
                    .andExpect(jsonPath("$.error.fields.stripeCode").value("charge_already_refunded"));
        } finally {
            faults.forEach(PgFaults.Fault::close);
        }

        // Not recorded, so the claim stays until the reconciler meets the same refusal again.
        Refund kept = refunds.findByOrderIdOrderByCreatedAtDesc(order.getId()).get(0);
        assertThat(kept.getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(claims(kept.getId())).containsExactly(ticket.getId());
    }

    enum NotAdoptable { FAILED_ROW, CANCELED_ROW, OTHER_PAYMENT_INTENT, UNPARSABLE_ID }

    /** Metadata that cannot link a refund to its row never hides it: the refund is materialized instead. */
    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(NotAdoptable.class)
    void webhookMetadataThatCannotBeAdopted_materializesTheRefund(NotAdoptable c) {
        Order order = paidOrder(1500);
        ticket(order);
        Order other = paidOrder(1500);
        RefundStatus namedStatus = switch (c) {
            case FAILED_ROW -> RefundStatus.FAILED;
            case CANCELED_ROW -> RefundStatus.CANCELED;
            default -> RefundStatus.REQUESTED;
        };
        Refund named = new Refund();
        named.setOrderId(order.getId());
        named.setStripePaymentIntentId(order.getStripePaymentIntentId());
        named.setAmountMinor(1500);
        named.setCurrency(order.getCurrency());
        named.setReason(RefundReason.OTHER);
        named.setStatus(namedStatus);
        named.setIdempotencyKey("k-" + UUID.randomUUID());
        named.setStripeAttemptAt(clock.instant());
        named.setStripeAttempts(1);
        named = refunds.saveAndFlush(named);
        Order paidOn = c == NotAdoptable.OTHER_PAYMENT_INTENT ? other : order;
        String metadata = c == NotAdoptable.UNPARSABLE_ID ? "not-a-uuid" : named.getId().toString();
        String sid = "re_" + UUID.randomUUID().toString().replace("-", "");

        refundService.handleWebhookStatusChange(sid, RefundStatus.SUCCEEDED, null, null,
                paidOn.getStripePaymentIntentId(), "ch_x", 1500L, metadata);

        Refund untouched = row(named.getId());
        assertThat(untouched.getStripeRefundId()).isNull();
        assertThat(untouched.getStatus()).isEqualTo(namedStatus);
        assertThat(refunds.findByStripeRefundId(sid)).hasValueSatisfying(r -> {
            assertThat(r.getId()).isNotEqualTo(untouched.getId());
            assertThat(r.getOrderId()).isEqualTo(paidOn.getId());
        });
    }

    @SuppressWarnings("unchecked")
    private static String rowIdOf(RefundCreateParams p) {
        return ((Map<String, String>) p.getMetadata()).get("imin_refund_id");
    }

    // ── helpers ──

    private void webhook(com.stripe.model.Refund r) {
        refundService.handleWebhookStatusChange(r.getId(), RefundStatus.fromStripe(r.getStatus()), null, null,
                r.getPaymentIntent(), r.getCharge(), r.getAmount(), r.getMetadata().get("imin_refund_id"));
    }

    private void reconcile() {
        jdbc.update("UPDATE shedlock SET lock_until = locked_at WHERE name = 'refund_attempt_reconcile'");
        reconciler.reconcile();
    }

    private Order paidOrder(long totalMinor) {
        Order o = fx.order(event, fx.email("buyer"));
        // test_mode as checkout stamps it under the suite's sk_test key.
        jdbc.update("UPDATE orders SET stripe_payment_intent_id = ?, application_fee_minor = 0, total_minor = ?, "
                        + "test_mode = true WHERE id = ?",
                "pi_" + UUID.randomUUID().toString().replace("-", ""), totalMinor, o.getId());
        o.setStripePaymentIntentId(jdbc.queryForObject(
                "SELECT stripe_payment_intent_id FROM orders WHERE id = ?", String.class, o.getId()));
        return o;
    }

    private Ticket ticket(Order order) {
        Ticket t = fx.ticket(order, Ticket.STATE_ISSUED);
        jdbc.update("UPDATE tickets SET tier_id = ? WHERE id = ?", tier.getId(), t.getId());
        return t;
    }

    private RefundRequest pendingRequest(Order o) {
        RefundRequest rr = new RefundRequest();
        rr.setReference(references.next());
        rr.setOrderId(o.getId());
        rr.setOrgId(o.getOrgId());
        rr.setEventId(o.getEventId());
        rr.setBuyerEmail(o.getEmail());
        rr.setReason(RefundRequestReason.CANT_ATTEND);
        rr.setExplanation("can't make it");
        rr.setStatus(RefundRequestStatus.PENDING);
        rr.setPendingMarker(o.getId());
        return requests.save(rr);
    }

    private static com.stripe.model.Refund stripeRefund(String status) {
        com.stripe.model.Refund r = new com.stripe.model.Refund();
        r.setId("re_" + UUID.randomUUID().toString().replace("-", ""));
        r.setCharge("ch_" + UUID.randomUUID().toString().replace("-", ""));
        r.setStatus(status);
        return r;
    }

    private static StripeCollection<com.stripe.model.Refund> page(boolean hasMore, com.stripe.model.Refund... data) {
        StripeCollection<com.stripe.model.Refund> c = new StripeCollection<>();
        c.setData(List.of(data));
        c.setHasMore(hasMore);
        return c;
    }

    private Refund row(UUID id) {
        return refunds.findById(id).orElseThrow();
    }

    private List<UUID> claims(UUID refundId) {
        return jdbc.queryForList("SELECT ticket_id FROM refund_tickets WHERE refund_id = ?", UUID.class, refundId);
    }

    private String ticketState(Ticket t) {
        return jdbc.queryForObject("SELECT state FROM tickets WHERE id = ?", String.class, t.getId());
    }

    private int sold() {
        return jdbc.queryForObject("SELECT sold FROM ticket_tiers WHERE id = ?", Integer.class, tier.getId());
    }

    private ResultActions refund(Order o, String key, UUID... ticketIds) throws Exception {
        return mvc.perform(post("/api/v1/orders/{id}/refund", o.getId())
                .with(auth(principal))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of(
                        "ticketIds", List.of(ticketIds).stream().map(UUID::toString).toList(),
                        "reason", "other"))));
    }

    private ResultActions approve(RefundRequest rr) throws Exception {
        return mvc.perform(post("/api/v1/orgs/{orgId}/refund-requests/{id}/approve", rr.getOrgId(), rr.getId())
                .with(auth(principal))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirm\":true,\"note\":\"ok\"}"));
    }

    /** {@code $.error.fields.<name>} of an error, or {@code $.<name>} of a refund body. */
    private String field(ResultActions res, String name) throws Exception {
        JsonNode body = om.readTree(res.andReturn().getResponse().getContentAsString());
        JsonNode node = body.has("error") ? body.get("error").get("fields").get(name) : body.get(name);
        return node.asText();
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
