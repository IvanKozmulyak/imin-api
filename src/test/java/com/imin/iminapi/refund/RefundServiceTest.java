package com.imin.iminapi.refund;

import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeRefundService;
import com.stripe.exception.InvalidRequestException;
import com.imin.iminapi.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RefundServiceTest {

    OrderRepository orders = mock(OrderRepository.class);
    TicketRepository tickets = mock(TicketRepository.class);
    RefundRepository refunds = mock(RefundRepository.class);
    RefundTicketRepository refundTickets = mock(RefundTicketRepository.class);
    StripeRefundService stripeRefunds = mock(StripeRefundService.class);
    TicketTierRepository tierRepo = mock(TicketTierRepository.class);
    DisputeRepository disputes = mock(DisputeRepository.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    RefundAttemptStore store = mock(RefundAttemptStore.class);
    // Real: the webhook tests below assert the tickets and tier it writes.
    RefundInventoryRelease release = new RefundInventoryRelease(refundTickets, tickets, tierRepo, publisher);
    RefundService service;
    /** The row the last {@code store.open} committed, as the store would return it. */
    Refund opened;

    UUID orgId;
    UUID userId;
    UUID orderId;
    AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        userId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        principal = new AuthPrincipal(userId, orgId, UserRole.OWNER, UUID.randomUUID());
        service = new RefundService(orders, tickets, refunds, refundTickets, stripeRefunds,
            disputes, publisher, liveKey(), store, release, java.time.Clock.systemUTC());
        when(store.open(any(), any(), any())).thenAnswer(inv -> {
            opened = inv.getArgument(0);
            opened.setId(UUID.randomUUID());
            opened.setStatus(RefundStatus.REQUESTED);
            return opened;
        });
        when(store.recordOutcome(any(), any())).thenAnswer(inv -> {
            com.stripe.model.Refund sr = inv.getArgument(1);
            opened.setStripeRefundId(sr.getId());
            opened.setStripeChargeId(sr.getCharge());
            opened.setStatus(RefundStatus.fromStripe(sr.getStatus()));
            return opened;
        });
    }

    /** Live key: these orders keep the default testMode=false, so their mode matches. */
    private static com.imin.iminapi.stripe.StripeProperties liveKey() {
        com.imin.iminapi.stripe.StripeProperties p = new com.imin.iminapi.stripe.StripeProperties();
        p.setSecretKey("sk_live_unit");
        return p;
    }

    private Order paidOrder() {
        Order o = new Order();
        o.setId(orderId);
        o.setOrgId(orgId);
        o.setStripePaymentIntentId("pi_x");
        o.setTotalMinor(10000);
        o.setCurrency("eur");
        o.setApplicationFeeMinor(599);
        return o;
    }

    private Ticket ticket(int price) {
        Ticket t = new Ticket();
        t.setId(UUID.randomUUID());
        t.setOrderId(orderId);
        t.setTierId(UUID.randomUUID());
        t.setPriceMinor(price);
        t.setState(Ticket.STATE_ISSUED);
        return t;
    }

    @Test
    void missing_idempotency_key_returns_400() {
        ApiException ex = (ApiException) assertThatThrownBy(() ->
            service.createRefund(orderId, principal, null, List.of(UUID.randomUUID()), RefundReason.OTHER))
            .isInstanceOf(ApiException.class).actual();
        assertThat(ex.code()).isEqualTo(ErrorCode.MISSING_IDEMPOTENCY_KEY);
    }

    @Test
    void idempotency_key_returns_existing_refund_without_calling_stripe() {
        Refund existing = new Refund();
        existing.setId(UUID.randomUUID());
        when(orders.findById(orderId)).thenReturn(Optional.of(paidOrder()));
        when(refunds.findByOrderIdAndIdempotencyKey(orderId, "idem-1"))
            .thenReturn(Optional.of(existing));

        Refund out = service.createRefund(orderId, principal, "idem-1",
            List.of(UUID.randomUUID()), RefundReason.OTHER);

        assertThat(out).isSameAs(existing);
        verifyNoInteractions(stripeRefunds);
    }

    /**
     * refund-6: the replay short-circuit must not answer before the org check. The approve
     * path builds the key as "refund-request-" + requestId and the buyer holds both that id
     * and the order id, so a guessable key must not read another org's refund row.
     */
    @Test
    void idempotent_replay_for_another_org_is_404_not_a_refund_row() {
        Order foreign = paidOrder();
        foreign.setOrgId(UUID.randomUUID());
        Refund existing = new Refund();
        existing.setId(UUID.randomUUID());
        when(orders.findById(orderId)).thenReturn(Optional.of(foreign));
        when(refunds.findByOrderIdAndIdempotencyKey(orderId, "refund-request-guessed"))
            .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createRefund(orderId, principal,
            "refund-request-guessed", List.of(UUID.randomUUID()), RefundReason.OTHER))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).code())
            .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void duplicate_ticket_ids_in_input_400() {
        // The order is loaded and org-checked before the body is validated (refund-6).
        when(orders.findById(orderId)).thenReturn(Optional.of(paidOrder()));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        UUID dup = UUID.randomUUID();
        assertThatThrownBy(() ->
            service.createRefund(orderId, principal, "k", List.of(dup, dup), RefundReason.OTHER))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).code())
            .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void cross_org_returns_404() {
        Order o = paidOrder();
        o.setOrgId(UUID.randomUUID());
        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            service.createRefund(orderId, principal, "k", List.of(UUID.randomUUID()), RefundReason.OTHER))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).code())
            .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void order_without_payment_intent_409_ORDER_NOT_REFUNDABLE() {
        Order o = paidOrder();
        o.setStripePaymentIntentId(null);
        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            service.createRefund(orderId, principal, "k", List.of(UUID.randomUUID()), RefundReason.OTHER))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).code())
            .isEqualTo(ErrorCode.ORDER_NOT_REFUNDABLE);
    }

    @Test
    void ticket_not_in_order_400() {
        Order o = paidOrder();
        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(tickets.findByIdInAndOrderId(any(), eq(orderId))).thenReturn(List.of());   // 0 returned for 1 requested

        assertThatThrownBy(() ->
            service.createRefund(orderId, principal, "k", List.of(UUID.randomUUID()), RefundReason.OTHER))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).code())
            .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void already_refunded_ticket_409() {
        Order o = paidOrder();
        Ticket t = ticket(2500);
        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(tickets.findByIdInAndOrderId(any(), eq(orderId))).thenReturn(List.of(t));
        when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of(t.getId()));

        assertThatThrownBy(() ->
            service.createRefund(orderId, principal, "k", List.of(t.getId()), RefundReason.OTHER))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).code())
            .isEqualTo(ErrorCode.TICKET_ALREADY_REFUNDED);
    }

    @Test
    void redeemed_ticket_409() {
        Order o = paidOrder();
        Ticket t = ticket(2500);
        t.setState(Ticket.STATE_REDEEMED);
        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(tickets.findByIdInAndOrderId(any(), eq(orderId))).thenReturn(List.of(t));
        when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());

        assertThatThrownBy(() ->
            service.createRefund(orderId, principal, "k", List.of(t.getId()), RefundReason.OTHER))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).code())
            .isEqualTo(ErrorCode.TICKET_REDEEMED);
    }

    /**
     * A charged-back order must never reach Stripe: the money is already being pulled
     * back, and Stripe answers a refund on it with charge_disputed.
     */
    @Test
    void open_dispute_blocks_the_refund_before_stripe() throws Exception {
        Order o = paidOrder();
        Ticket t1 = ticket(2500);
        Ticket t2 = ticket(2500);
        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(tickets.findByIdInAndOrderId(any(), eq(orderId))).thenReturn(List.of(t1));
        when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2));
        when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(0L);
        when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
        com.stripe.model.Refund stripeStub = new com.stripe.model.Refund();
        stripeStub.setId("re_1");
        stripeStub.setStatus("pending");
        when(stripeRefunds.create(anyString(), anyLong(), anyString(), any(), anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(), anyString(), anyString())).thenReturn(stripeStub);
        when(refunds.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        when(disputes.hasOpenOrLostByOrderId(orderId)).thenReturn(true);

        assertThatThrownBy(() ->
            service.createRefund(orderId, principal, "k", List.of(t1.getId()), RefundReason.OTHER))
            .isInstanceOf(ApiException.class)
            .extracting(e -> ((ApiException) e).code())
            .isEqualTo(ErrorCode.ORDER_DISPUTED);
        verifyNoInteractions(stripeRefunds);
    }

    /**
     * The guard sits AFTER the replay short-circuit: a refund taken before the chargeback
     * keeps returning its own row, rather than 409ing a caller retrying a settled request.
     */
    @Test
    void idempotent_replay_still_returns_its_row_on_a_disputed_order() {
        Refund existing = new Refund();
        existing.setId(UUID.randomUUID());
        when(orders.findById(orderId)).thenReturn(Optional.of(paidOrder()));
        when(refunds.findByOrderIdAndIdempotencyKey(orderId, "pre-dispute"))
            .thenReturn(Optional.of(existing));
        when(disputes.hasOpenOrLostByOrderId(orderId)).thenReturn(true);

        Refund out = service.createRefund(orderId, principal, "pre-dispute",
            List.of(UUID.randomUUID()), RefundReason.OTHER);

        assertThat(out).isSameAs(existing);
        verifyNoInteractions(stripeRefunds);
    }

    @Test
    void happy_path_persists_refund_and_calls_stripe_with_proportional_fee() throws Exception {
        Order o = paidOrder();
        Ticket t1 = ticket(2500);
        Ticket t2 = ticket(2500);
        Ticket t3 = ticket(2500);
        Ticket t4 = ticket(2500);
        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(tickets.findByIdInAndOrderId(any(), eq(orderId))).thenReturn(List.of(t1, t2));
        when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2, t3, t4));
        when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(0L);
        when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());

        com.stripe.model.Refund stripeStub = new com.stripe.model.Refund();
        stripeStub.setId("re_1");
        stripeStub.setCharge("ch_1");
        stripeStub.setStatus("pending");
        when(stripeRefunds.create(eq("pi_x"), eq(5000L), eq("eur"),
                eq(RefundReason.OTHER), eq(300L), eq(true), anyString(), anyString())).thenReturn(stripeStub);

        // Capture saved Refund so we can verify computed fields. JPA save() returns the input.
        when(refunds.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        Refund out = service.createRefund(orderId, principal, "k",
            List.of(t1.getId(), t2.getId()), RefundReason.OTHER);

        // 599 × 5000 / 10000 = 299.5 → rounds HALF_UP to 300
        assertThat(out.getApplicationFeeRefundMinor()).isEqualTo(300L);
        assertThat(out.getAmountMinor()).isEqualTo(5000L);
        assertThat(out.getStripeRefundId()).isEqualTo("re_1");
        assertThat(out.getStripeChargeId()).isEqualTo("ch_1");
        assertThat(out.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(out.getInitiatedByUserId()).isEqualTo(userId);
    }

    /**
     * The ticket claims commit BEFORE the Stripe call, so UNIQUE(ticket_id) rejects the loser of
     * a race while the money is still ours.
     */
    @Test
    void concurrent_claim_on_a_ticket_is_rejected_before_stripe_is_called() throws Exception {
        Order o = paidOrder();
        Ticket t1 = ticket(2500);
        Ticket t2 = ticket(2500);
        List<UUID> ids = List.of(t1.getId());
        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(tickets.findByIdInAndOrderId(any(), eq(orderId))).thenReturn(List.of(t1));
        when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2));
        when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
        when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(0L);
        // The other request won the UNIQUE(ticket_id) index a moment earlier.
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException(
                "refund_tickets_ticket_id_unique"))
            .when(store).open(any(), any(), any());

        ApiException ex = (ApiException) assertThatThrownBy(() ->
            service.createRefund(orderId, principal, "k", ids, RefundReason.OTHER))
            .isInstanceOf(ApiException.class).actual();

        assertThat(ex.code()).isEqualTo(ErrorCode.TICKET_ALREADY_REFUNDED);
        verifyNoInteractions(stripeRefunds);
    }

    /** A double-clicked submit: the twin committed the same key first, so its row is the answer. */
    @Test
    void idempotencyKeyRaceOnOpen_replaysTheWinner() throws Exception {
        Order o = paidOrder();
        Ticket t1 = ticket(2500);
        Ticket t2 = ticket(2500);
        Refund winner = new Refund();
        winner.setId(UUID.randomUUID());
        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(orderId, "k"))
            .thenReturn(Optional.empty(), Optional.of(winner));
        when(tickets.findByIdInAndOrderId(any(), eq(orderId))).thenReturn(List.of(t1));
        when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2));
        when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
        when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(0L);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException(
                "insert into refunds", new org.hibernate.exception.ConstraintViolationException(
                    "duplicate key", new java.sql.SQLException("23505"), "refunds_order_idem_unique")))
            .when(store).open(any(), any(), any());

        Refund out = service.createRefund(orderId, principal, "k", List.of(t1.getId()), RefundReason.OTHER);

        assertThat(out).isSameAs(winner);
        verifyNoInteractions(stripeRefunds);
    }

    /**
     * refund-3: a refund that Stripe ends at FAILED/CANCELED moved no money, so its tickets
     * must become refundable again. The refund_tickets rows are what makes them refundable,
     * and UNIQUE(ticket_id) means a stale row blocks every retry with 409
     * TICKET_ALREADY_REFUNDED — permanently, since nothing else ever deletes it.
     */
    @org.junit.jupiter.api.Nested
    class WebhookTerminalFailure {

        private Refund pendingRefund() {
            Refund r = new Refund();
            r.setId(UUID.randomUUID());
            r.setOrderId(orderId);
            r.setStripeRefundId("re_fail");
            r.setAmountMinor(3000);
            r.setStatus(RefundStatus.PENDING);
            return r;
        }

        @Test
        void failed_webhook_releases_the_refund_tickets() {
            Refund r = pendingRefund();
            when(refunds.findByStripeRefundId("re_fail")).thenReturn(Optional.of(r));
            when(refunds.updateStatusIfCurrent(r.getId(), RefundStatus.PENDING, RefundStatus.FAILED))
                .thenReturn(1);
            when(refunds.findById(r.getId())).thenReturn(Optional.of(r));

            service.handleWebhookStatusChange("re_fail", RefundStatus.FAILED,
                "expired_or_canceled_card", "The card has expired.", "pi_x", "ch_x", 3000L, null);

            org.mockito.Mockito.verify(refundTickets).deleteByRefundId(r.getId());
            assertThat(r.getFailureCode()).isEqualTo("expired_or_canceled_card");
        }

        @Test
        void canceled_webhook_releases_the_refund_tickets() {
            Refund r = pendingRefund();
            when(refunds.findByStripeRefundId("re_fail")).thenReturn(Optional.of(r));
            when(refunds.updateStatusIfCurrent(r.getId(), RefundStatus.PENDING, RefundStatus.CANCELED))
                .thenReturn(1);

            service.handleWebhookStatusChange("re_fail", RefundStatus.CANCELED, null, null, "pi_x", "ch_x", 3000L, null);

            org.mockito.Mockito.verify(refundTickets).deleteByRefundId(r.getId());
        }

        @Test
        void succeeded_webhook_keeps_the_refund_tickets() {
            Refund r = pendingRefund();
            when(refunds.findByStripeRefundId("re_fail")).thenReturn(Optional.of(r));
            when(refunds.updateStatusIfCurrent(r.getId(), RefundStatus.PENDING, RefundStatus.SUCCEEDED))
                .thenReturn(1);
            when(refundTickets.findTicketIdsByRefundId(r.getId())).thenReturn(List.of());
            when(tickets.findAllById(List.of())).thenReturn(List.of());

            service.handleWebhookStatusChange("re_fail", RefundStatus.SUCCEEDED, null, null, "pi_x", "ch_x", 3000L, null);

            org.mockito.Mockito.verify(refundTickets, org.mockito.Mockito.never())
                .deleteByRefundId(any());
        }
    }

    // ── stripe-15 — the fee refunds must never sum above the original fee ─────────
    @Test
    void app_fee_refunds_are_clamped_to_the_remaining_unrefunded_fee() {
        // Three equal tickets on a 1000 total with a 149 fee. Each ticket refunds
        // round(1000 × f / 3f) = 333, and each fee share rounds to
        // round(149 × 333 / 1000) = round(49.617) = 50. Unclamped that is 50+50+50 = 150
        // against a 149 fee, so the third applicationFees().refunds().create asks Stripe for
        // 50 when only 49 is unrefunded and Stripe rejects it with an invalid_request_error —
        // deterministically, on every retry.
        Order o = paidOrder();
        o.setTotalMinor(1000);
        o.setApplicationFeeMinor(149);

        when(refunds.sumActiveApplicationFeeRefundMinorByOrderId(orderId)).thenReturn(0L);
        long first = service.computeAppFeeRefundMinor(o, 333);
        assertThat(first).isEqualTo(50L);

        when(refunds.sumActiveApplicationFeeRefundMinorByOrderId(orderId)).thenReturn(50L);
        long second = service.computeAppFeeRefundMinor(o, 333);
        assertThat(second).isEqualTo(50L);

        when(refunds.sumActiveApplicationFeeRefundMinorByOrderId(orderId)).thenReturn(100L);
        long third = service.computeAppFeeRefundMinor(o, 333);
        assertThat(third)
                .as("only 149 − 100 = 49 of the fee is still unrefunded")
                .isEqualTo(49L);

        assertThat(first + second + third)
                .as("the three fee refunds sum to EXACTLY the original fee")
                .isEqualTo(149L);
    }

    @Test
    void app_fee_refund_is_zero_once_the_whole_fee_is_already_refunded() {
        Order o = paidOrder();
        o.setTotalMinor(1000);
        o.setApplicationFeeMinor(149);
        when(refunds.sumActiveApplicationFeeRefundMinorByOrderId(orderId)).thenReturn(149L);

        assertThat(service.computeAppFeeRefundMinor(o, 333))
                .as("never a negative fee refund, and never a request Stripe must reject")
                .isZero();
    }

    @org.junit.jupiter.api.Nested
    class PromoCodeAmountAllocation {

        @Test
        void full_order_with_promo_refunds_total_minor_not_face_price() throws Exception {
            // Order total 8000 (promo applied), face price sums to 10000.
            // A full-order refund must call Stripe with 8000, not 10000.
            Order o = paidOrder();
            o.setTotalMinor(8000);
            o.setApplicationFeeMinor(400);
            when(orders.findById(orderId)).thenReturn(Optional.of(o));

            Ticket t1 = ticket(5000);
            Ticket t2 = ticket(5000);
            List<UUID> ids = List.of(t1.getId(), t2.getId());
            when(tickets.findByIdInAndOrderId(ids, orderId)).thenReturn(List.of(t1, t2));
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2));
            when(refundTickets.findRefundedTicketIds(ids)).thenReturn(Set.of());
            when(refunds.findByOrderIdAndIdempotencyKey(orderId, "idem-1")).thenReturn(Optional.empty());
            when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(0L);
            when(refunds.save(any())).thenAnswer(inv -> inv.getArgument(0));

            com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
            stripeRefund.setId("re_1");
            stripeRefund.setStatus("pending");
            when(stripeRefunds.create(eq("pi_x"), eq(8000L), eq("eur"), any(), eq(400L), eq(true), anyString(), anyString()))
                .thenReturn(stripeRefund);

            service.createRefund(orderId, principal, "idem-1", ids, RefundReason.OTHER);

            ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> fee = ArgumentCaptor.forClass(Long.class);
            org.mockito.Mockito.verify(stripeRefunds)
                .create(eq("pi_x"), amount.capture(), eq("eur"), any(), fee.capture(), eq(true), anyString(), anyString());
            assertThat(amount.getValue()).isEqualTo(8000L);
            assertThat(fee.getValue()).isEqualTo(400L);
        }

        @Test
        void partial_with_promo_uses_proportional_total_minor_allocation() throws Exception {
            // Order total 8000, face total 10000, refunding one of two 5000-face tickets.
            // Proportional amount = round(8000 * 5000 / 10000) = 4000.
            Order o = paidOrder();
            o.setTotalMinor(8000);
            o.setApplicationFeeMinor(400);
            when(orders.findById(orderId)).thenReturn(Optional.of(o));

            Ticket t1 = ticket(5000);
            Ticket t2 = ticket(5000);
            List<UUID> ids = List.of(t1.getId());
            when(tickets.findByIdInAndOrderId(ids, orderId)).thenReturn(List.of(t1));
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2));
            when(refundTickets.findRefundedTicketIds(ids)).thenReturn(Set.of());
            when(refunds.findByOrderIdAndIdempotencyKey(orderId, "idem-1")).thenReturn(Optional.empty());
            when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(0L);
            when(refunds.save(any())).thenAnswer(inv -> inv.getArgument(0));

            com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
            stripeRefund.setId("re_2");
            stripeRefund.setStatus("pending");
            when(stripeRefunds.create(eq("pi_x"), eq(4000L), eq("eur"), any(), eq(200L), eq(true), anyString(), anyString()))
                .thenReturn(stripeRefund);

            service.createRefund(orderId, principal, "idem-1", ids, RefundReason.OTHER);

            org.mockito.Mockito.verify(stripeRefunds)
                .create(eq("pi_x"), eq(4000L), eq("eur"), any(), eq(200L), eq(true), anyString(), anyString());
        }

        @Test
        void last_remaining_refund_clamps_to_total_minor_minus_prior_refunds() throws Exception {
            // Prior refunded 4000 of 8000. This refund's proportional amount might
            // overshoot due to rounding; clamp to remaining (4000).
            Order o = paidOrder();
            o.setTotalMinor(8000);
            o.setApplicationFeeMinor(400);
            when(orders.findById(orderId)).thenReturn(Optional.of(o));

            Ticket t1 = ticket(5000);
            Ticket t2 = ticket(5001);  // quirky face to provoke rounding
            List<UUID> ids = List.of(t2.getId());
            when(tickets.findByIdInAndOrderId(ids, orderId)).thenReturn(List.of(t2));
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2));
            when(refundTickets.findRefundedTicketIds(ids)).thenReturn(Set.of());
            when(refunds.findByOrderIdAndIdempotencyKey(orderId, "idem-final")).thenReturn(Optional.empty());
            when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(4000L);
            when(refunds.save(any())).thenAnswer(inv -> inv.getArgument(0));

            com.stripe.model.Refund stripeRefund = new com.stripe.model.Refund();
            stripeRefund.setId("re_3");
            stripeRefund.setStatus("pending");
            when(stripeRefunds.create(eq("pi_x"), eq(4000L), eq("eur"), any(), eq(200L), eq(true), anyString(), anyString()))
                .thenReturn(stripeRefund);

            service.createRefund(orderId, principal, "idem-final", ids, RefundReason.OTHER);

            org.mockito.Mockito.verify(stripeRefunds)
                .create(eq("pi_x"), eq(4000L), eq("eur"), any(), eq(200L), eq(true), anyString(), anyString());
        }

        @Test
        void zero_remaining_returns_409() {
            // Prior refunds already cover the full order total.
            Order o = paidOrder();
            o.setTotalMinor(8000);
            when(orders.findById(orderId)).thenReturn(Optional.of(o));

            Ticket t1 = ticket(5000);
            List<UUID> ids = List.of(t1.getId());
            when(tickets.findByIdInAndOrderId(ids, orderId)).thenReturn(List.of(t1));
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1));
            when(refundTickets.findRefundedTicketIds(ids)).thenReturn(Set.of());
            when(refunds.findByOrderIdAndIdempotencyKey(orderId, "idem-z")).thenReturn(Optional.empty());
            when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(8000L);

            ApiException ex = (ApiException) assertThatThrownBy(() ->
                service.createRefund(orderId, principal, "idem-z", ids, RefundReason.OTHER))
                .isInstanceOf(ApiException.class).actual();
            assertThat(ex.code()).isEqualTo(ErrorCode.ORDER_NOT_REFUNDABLE);
            verifyNoInteractions(stripeRefunds);
        }
    }

    /**
     * P1-16 + P1-14: what the caller is told when Stripe misbehaves, and the platform-funded
     * second attempt when the connected balance cannot fund the reversal.
     */
    @org.junit.jupiter.api.Nested
    class StripeFailureHandling {

        private List<UUID> stubRefundable() {
            Order o = paidOrder();
            Ticket t1 = ticket(2500);
            Ticket t2 = ticket(2500);
            Ticket t3 = ticket(2500);
            Ticket t4 = ticket(2500);
            when(orders.findById(orderId)).thenReturn(Optional.of(o));
            when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
            when(tickets.findByIdInAndOrderId(any(), eq(orderId))).thenReturn(List.of(t1, t2));
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2, t3, t4));
            when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(0L);
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(refunds.save(any(Refund.class))).thenAnswer(inv -> {
                Refund r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });
            return List.of(t1.getId(), t2.getId());
        }

        @Test
        void balanceInsufficientRetriesPlatformFunded() throws Exception {
            List<UUID> ids = stubRefundable();
            when(store.switchToPlatform(any(), any())).thenReturn(true);
            com.stripe.model.Refund funded = new com.stripe.model.Refund();
            funded.setId("re_platform");
            funded.setCharge("ch_platform");
            funded.setStatus("pending");
            when(stripeRefunds.create(any(), anyLong(), any(), any(), anyLong(), eq(true), anyString(), anyString()))
                .thenThrow(new InvalidRequestException(
                    "Insufficient funds", "amount", null, "balance_insufficient", 400, null));
            when(stripeRefunds.create(any(), anyLong(), any(), any(), anyLong(), eq(false), anyString(), anyString()))
                .thenReturn(funded);

            Refund out = service.createRefund(orderId, principal, "k", ids, RefundReason.OTHER);

            assertThat(out.getStripeRefundId()).isEqualTo("re_platform");
            org.mockito.Mockito.verify(store).switchToPlatform(eq(opened.getId()), any());
            org.mockito.Mockito.verify(stripeRefunds).create(any(), anyLong(), any(), any(), anyLong(), eq(true),
                eq(RefundService.stripeKeyFor(opened.getId(), false)), eq(opened.getId().toString()));
            org.mockito.Mockito.verify(stripeRefunds).create(any(), anyLong(), any(), any(), anyLong(), eq(false),
                eq(RefundService.stripeKeyFor(opened.getId(), true)), eq(opened.getId().toString()));
            assertThat(RefundService.stripeKeyFor(opened.getId(), true))
                .as("Stripe rejects a replayed key whose params differ, and reverse_transfer differs")
                .isNotEqualTo(RefundService.stripeKeyFor(opened.getId(), false));
        }

        @Test
        void balanceInsufficientTwiceStillThrows409() throws Exception {
            List<UUID> ids = stubRefundable();
            when(store.switchToPlatform(any(), any())).thenReturn(true);
            when(stripeRefunds.create(any(), anyLong(), any(), any(), anyLong(), eq(true), anyString(), anyString()))
                .thenThrow(new InvalidRequestException(
                    "Insufficient funds", "amount", null, "balance_insufficient", 400, null));
            when(stripeRefunds.create(any(), anyLong(), any(), any(), anyLong(), eq(false), anyString(), anyString()))
                .thenThrow(new InvalidRequestException(
                    "Platform balance too low", "amount", null, "balance_insufficient", 400, null));

            ApiException ex = (ApiException) assertThatThrownBy(() ->
                service.createRefund(orderId, principal, "k", ids, RefundReason.OTHER))
                .isInstanceOf(ApiException.class).actual();

            assertThat(ex.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
            assertThat(ex.code()).isEqualTo(ErrorCode.ORDER_NOT_REFUNDABLE);
            org.mockito.Mockito.verify(stripeRefunds).create(any(), anyLong(), any(), any(), anyLong(), eq(false),
                eq(RefundService.stripeKeyFor(opened.getId(), true)), anyString());
            org.mockito.Mockito.verify(store).recordRefusal(eq(opened.getId()), eq("balance_insufficient"), any());
        }
    }

    /**
     * P1-15: a refund Stripe reports that we never created (organizer refunded from the Stripe
     * Dashboard). Today's no-op leaves the money invisible and the tickets valid.
     */
    @org.junit.jupiter.api.Nested
    class UnknownStripeRefund {

        private Order backResolvableOrder() {
            Order o = paidOrder();
            when(refunds.findByStripeRefundId("re_dash")).thenReturn(Optional.empty());
            when(orders.findByStripePaymentIntentId("pi_x")).thenReturn(Optional.of(o));
            when(refunds.save(any(Refund.class))).thenAnswer(inv -> {
                Refund r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });
            return o;
        }

        @Test
        void unknownStripeRefundFullAmountMaterializesAndRevokes() {
            backResolvableOrder();
            Ticket t1 = ticket(5000);
            Ticket t2 = ticket(5000);
            when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(0L);
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(refunds.updateStatusIfCurrent(any(), eq(RefundStatus.REQUESTED), eq(RefundStatus.SUCCEEDED)))
                .thenReturn(1);
            when(refundTickets.findTicketIdsByRefundId(any())).thenReturn(List.of(t1.getId(), t2.getId()));
            when(tickets.findAllById(any())).thenReturn(List.of(t1, t2));
            com.imin.iminapi.model.TicketTier tier = new com.imin.iminapi.model.TicketTier();
            tier.setId(t1.getTierId());
            tier.setSold(5);
            when(tierRepo.findByIdForUpdate(any())).thenReturn(Optional.of(tier));

            service.handleWebhookStatusChange("re_dash", RefundStatus.SUCCEEDED, null, null,
                "pi_x", "ch_dash", 10000L, null);

            ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
            org.mockito.Mockito.verify(refunds).save(saved.capture());
            assertThat(saved.getValue().getAmountMinor()).isEqualTo(10000L);
            assertThat(saved.getValue().getStripeRefundId()).isEqualTo("re_dash");
            assertThat(saved.getValue().getInitiatedByUserId())
                .as("no imin actor initiated a Dashboard refund — never invent one")
                .isNull();

            org.mockito.Mockito.verify(refundTickets).saveAllAndFlush(any());
            assertThat(t1.getState()).isEqualTo(Ticket.STATE_REFUNDED);
            assertThat(t2.getState()).isEqualTo(Ticket.STATE_REFUNDED);
            assertThat(tier.getSold()).as("sold must come back down by the revoked tickets").isEqualTo(3);
        }

        @Test
        void unknownStripeRefundPartialAmountMaterializesWithoutRevoking() {
            backResolvableOrder();
            Ticket t1 = ticket(5000);
            Ticket t2 = ticket(5000);
            when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(0L);
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2));
            when(refunds.updateStatusIfCurrent(any(), eq(RefundStatus.REQUESTED), eq(RefundStatus.SUCCEEDED)))
                .thenReturn(1);
            when(refundTickets.findTicketIdsByRefundId(any())).thenReturn(List.of());
            when(tickets.findAllById(any())).thenReturn(List.of());

            service.handleWebhookStatusChange("re_dash", RefundStatus.SUCCEEDED, null, null,
                "pi_x", "ch_dash", 2500L, null);

            ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
            org.mockito.Mockito.verify(refunds).save(saved.capture());
            assertThat(saved.getValue().getAmountMinor()).isEqualTo(2500L);

            org.mockito.Mockito.verify(refundTickets, org.mockito.Mockito.never()).saveAllAndFlush(any());
            assertThat(t1.getState()).isEqualTo(Ticket.STATE_ISSUED);
            assertThat(t2.getState()).isEqualTo(Ticket.STATE_ISSUED);
        }

        @Test
        void unknownStripeRefundWithNoOrderIsStillANoOp() {
            when(refunds.findByStripeRefundId("re_dash")).thenReturn(Optional.empty());
            when(orders.findByStripePaymentIntentId("pi_other")).thenReturn(Optional.empty());

            service.handleWebhookStatusChange("re_dash", RefundStatus.SUCCEEDED, null, null,
                "pi_other", "ch_other", 2500L, null);

            verifyNoInteractions(refundTickets);
            org.mockito.Mockito.verify(refunds, org.mockito.Mockito.never()).save(any(Refund.class));
        }
    }
}
