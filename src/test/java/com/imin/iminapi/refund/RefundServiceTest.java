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
        when(refunds.findByOrderIdAndIdempotencyKey(orderId, "idem-1"))
            .thenReturn(Optional.of(existing));

        Refund out = service.createRefund(orderId, principal, "idem-1",
            List.of(UUID.randomUUID()), RefundReason.OTHER);

        assertThat(out).isSameAs(existing);
        verifyNoInteractions(stripeRefunds);
    }

    @Test
    void duplicate_ticket_ids_in_input_400() {
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
        when(orders.findById(orderId)).thenReturn(Optional.of(o));
        when(refunds.findByOrderIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        when(tickets.findByIdInAndOrderId(any(), eq(orderId))).thenReturn(List.of(t1, t2));
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
}
