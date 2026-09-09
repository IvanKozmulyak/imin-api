package com.imin.iminapi.refund;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeRefundService;
import com.stripe.exception.ApiConnectionException;
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
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    RefundService service;

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
        service = new RefundService(orders, tickets, refunds, refundTickets, stripeRefunds, tierRepo, publisher);
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
                eq(RefundReason.OTHER), eq(300L), anyString())).thenReturn(stripeStub);

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
     * refund-2: the refund_tickets INSERTs must reach the database BEFORE the Stripe call,
     * so UNIQUE(ticket_id) rejects the loser of a race while the money is still ours.
     * With a plain saveAll() the INSERT is only queued (RefundTicket has an
     * application-assigned @IdClass id, so save() merges), the violation surfaces at commit
     * — after Stripe already refunded — and this catch is unreachable.
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
        when(refunds.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });
        // The other request won the UNIQUE(ticket_id) index a moment earlier.
        when(refundTickets.saveAllAndFlush(any()))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "refund_tickets_ticket_id_unique"));

        ApiException ex = (ApiException) assertThatThrownBy(() ->
            service.createRefund(orderId, principal, "k", ids, RefundReason.OTHER))
            .isInstanceOf(ApiException.class).actual();

        assertThat(ex.code()).isEqualTo(ErrorCode.TICKET_ALREADY_REFUNDED);
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
                "expired_or_canceled_card", "The card has expired.");

            org.mockito.Mockito.verify(refundTickets).deleteByRefundId(r.getId());
            assertThat(r.getFailureCode()).isEqualTo("expired_or_canceled_card");
        }

        @Test
        void canceled_webhook_releases_the_refund_tickets() {
            Refund r = pendingRefund();
            when(refunds.findByStripeRefundId("re_fail")).thenReturn(Optional.of(r));
            when(refunds.updateStatusIfCurrent(r.getId(), RefundStatus.PENDING, RefundStatus.CANCELED))
                .thenReturn(1);

            service.handleWebhookStatusChange("re_fail", RefundStatus.CANCELED, null, null);

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

            service.handleWebhookStatusChange("re_fail", RefundStatus.SUCCEEDED, null, null);

            org.mockito.Mockito.verify(refundTickets, org.mockito.Mockito.never())
                .deleteByRefundId(any());
        }
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
            when(stripeRefunds.create(eq("pi_x"), eq(8000L), eq("eur"), any(), eq(400L), anyString()))
                .thenReturn(stripeRefund);

            service.createRefund(orderId, principal, "idem-1", ids, RefundReason.OTHER);

            ArgumentCaptor<Long> amount = ArgumentCaptor.forClass(Long.class);
            ArgumentCaptor<Long> fee = ArgumentCaptor.forClass(Long.class);
            org.mockito.Mockito.verify(stripeRefunds)
                .create(eq("pi_x"), amount.capture(), eq("eur"), any(), fee.capture(), anyString());
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
            when(stripeRefunds.create(eq("pi_x"), eq(4000L), eq("eur"), any(), eq(200L), anyString()))
                .thenReturn(stripeRefund);

            service.createRefund(orderId, principal, "idem-1", ids, RefundReason.OTHER);

            org.mockito.Mockito.verify(stripeRefunds)
                .create(eq("pi_x"), eq(4000L), eq("eur"), any(), eq(200L), anyString());
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
            when(stripeRefunds.create(eq("pi_x"), eq(4000L), eq("eur"), any(), eq(200L), anyString()))
                .thenReturn(stripeRefund);

            service.createRefund(orderId, principal, "idem-final", ids, RefundReason.OTHER);

            org.mockito.Mockito.verify(stripeRefunds)
                .create(eq("pi_x"), eq(4000L), eq("eur"), any(), eq(200L), anyString());
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
     * refund-1: the Stripe idempotency key must be a function of durable inputs. The whole
     * createRefund transaction rolls back when the Stripe call fails, so the refund row id
     * is NOT durable — deriving the key from it makes every retry a brand-new Stripe request.
     *
     * <p>Worked example used throughout: order total 9000 minor (3 x 3000 face tickets),
     * applicationFeeMinor 450. Refunding ONE 3000 ticket = round(9000 * 3000 / 9000) = 3000;
     * app-fee refund = round(450 * 3000 / 9000) = 150.
     */
    @org.junit.jupiter.api.Nested
    class StripeIdempotencyKeyStability {

        private Order order9000() {
            Order o = new Order();
            o.setId(orderId);
            o.setOrgId(orgId);
            o.setStripePaymentIntentId("pi_x");
            o.setTotalMinor(9000);
            o.setCurrency("eur");
            o.setApplicationFeeMinor(450);
            return o;
        }

        private void stubOneTicketRefund(Order o, Ticket t1, Ticket t2, Ticket t3, List<UUID> ids) {
            when(orders.findById(orderId)).thenReturn(Optional.of(o));
            when(refunds.findByOrderIdAndIdempotencyKey(eq(orderId), anyString()))
                .thenReturn(Optional.empty());
            when(tickets.findByIdInAndOrderId(any(), eq(orderId))).thenAnswer(inv -> {
                List<UUID> asked = inv.getArgument(0);
                return List.of(t1, t2, t3).stream().filter(t -> asked.contains(t.getId())).toList();
            });
            when(tickets.findByOrderId(orderId)).thenReturn(List.of(t1, t2, t3));
            when(refundTickets.findRefundedTicketIds(any())).thenReturn(Set.of());
            when(refunds.sumActiveAmountByOrderId(orderId)).thenReturn(0L);
            // Each attempt mints a fresh in-memory row id, exactly as @GeneratedValue does.
            when(refunds.save(any(Refund.class))).thenAnswer(inv -> {
                Refund r = inv.getArgument(0);
                if (r.getId() == null) r.setId(UUID.randomUUID());
                return r;
            });
        }

        @Test
        void retry_after_a_rolled_back_attempt_reuses_the_same_stripe_key() throws Exception {
            Order o = order9000();
            Ticket t1 = ticket(3000);
            Ticket t2 = ticket(3000);
            Ticket t3 = ticket(3000);
            List<UUID> ids = List.of(t1.getId());
            stubOneTicketRefund(o, t1, t2, t3, ids);
            // Stripe created the refund but the response was lost -> our tx rolls back.
            when(stripeRefunds.create(eq("pi_x"), eq(3000L), eq("eur"), any(), eq(150L), anyString()))
                .thenThrow(new ApiConnectionException("connection reset"));

            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() ->
                    service.createRefund(orderId, principal, "idem-retry", ids, RefundReason.OTHER))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code())
                    .isEqualTo(ErrorCode.STRIPE_REFUND_FAILED);
            }

            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(stripeRefunds, org.mockito.Mockito.times(2))
                .create(eq("pi_x"), eq(3000L), eq("eur"), any(), eq(150L), keys.capture());
            assertThat(keys.getAllValues().get(0))
                .as("a retry must replay the SAME Stripe idempotency key, or Stripe refunds 3000 twice")
                .isEqualTo(keys.getAllValues().get(1));
        }

        @Test
        void a_different_ticket_selection_gets_a_different_stripe_key() throws Exception {
            Order o = order9000();
            Ticket t1 = ticket(3000);
            Ticket t2 = ticket(3000);
            Ticket t3 = ticket(3000);
            stubOneTicketRefund(o, t1, t2, t3, List.of(t1.getId()));
            when(stripeRefunds.create(eq("pi_x"), eq(3000L), eq("eur"), any(), eq(150L), anyString()))
                .thenThrow(new ApiConnectionException("connection reset"));

            assertThatThrownBy(() -> service.createRefund(
                orderId, principal, "idem-same", List.of(t1.getId()), RefundReason.OTHER))
                .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> service.createRefund(
                orderId, principal, "idem-same", List.of(t2.getId()), RefundReason.OTHER))
                .isInstanceOf(ApiException.class);

            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            org.mockito.Mockito.verify(stripeRefunds, org.mockito.Mockito.times(2))
                .create(eq("pi_x"), eq(3000L), eq("eur"), any(), eq(150L), keys.capture());
            assertThat(keys.getAllValues().get(0))
                .as("refunding a different ticket is a different refund and must not replay")
                .isNotEqualTo(keys.getAllValues().get(1));
        }
    }
}
