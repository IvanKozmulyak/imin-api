package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckoutStatusServiceTest {

    @Test
    void ready_when_order_with_session_id_exists() {
        OrderRepository orders = mock(OrderRepository.class);
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setToken("ORDTOK");
        when(orders.findByStripeSessionId("cs_test_x")).thenReturn(Optional.of(o));

        CheckoutStatusService.Result r = new CheckoutStatusService(orders).statusFor("cs_test_x");

        assertThat(r.status()).isEqualTo(CheckoutStatusService.Status.READY);
        assertThat(r.orderToken()).isEqualTo("ORDTOK");
    }

    @Test
    void pending_when_no_order_yet() {
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findByStripeSessionId("cs_pending")).thenReturn(Optional.empty());

        CheckoutStatusService.Result r = new CheckoutStatusService(orders).statusFor("cs_pending");

        assertThat(r.status()).isEqualTo(CheckoutStatusService.Status.PENDING);
        assertThat(r.orderToken()).isNull();
    }

    /**
     * BLOCKER regression. A native PaymentSheet purchase has no Checkout Session,
     * so {@code orders.stripe_session_id} is null for it and the app holds only a
     * {@code pi_…} id. Resolving by session id alone left the Success screen
     * polling forever with no way to reach the order it had just paid for.
     */
    @Test
    void ready_when_order_is_keyed_by_payment_intent_id() {
        OrderRepository orders = mock(OrderRepository.class);
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setToken("NATIVETOK");
        when(orders.findByStripePaymentIntentId("pi_test_native")).thenReturn(Optional.of(o));

        CheckoutStatusService.Result r = new CheckoutStatusService(orders).statusFor("pi_test_native");

        assertThat(r.status()).isEqualTo(CheckoutStatusService.Status.READY);
        assertThat(r.orderToken()).isEqualTo("NATIVETOK");
        // A pi_ id must never be looked up as a session id — that column is null
        // for native orders, so the query could only ever mis-resolve or miss.
        verify(orders, never()).findByStripeSessionId(any());
    }

    @Test
    void pending_when_payment_intent_has_no_order_yet() {
        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findByStripePaymentIntentId("pi_unknown")).thenReturn(Optional.empty());

        CheckoutStatusService.Result r = new CheckoutStatusService(orders).statusFor("pi_unknown");

        assertThat(r.status()).isEqualTo(CheckoutStatusService.Status.PENDING);
        assertThat(r.orderToken()).isNull();
    }

    @Test
    void pending_for_null_or_blank_session_id() {
        OrderRepository orders = mock(OrderRepository.class);
        CheckoutStatusService svc = new CheckoutStatusService(orders);

        assertThat(svc.statusFor(null).status()).isEqualTo(CheckoutStatusService.Status.PENDING);
        assertThat(svc.statusFor("").status()).isEqualTo(CheckoutStatusService.Status.PENDING);
        assertThat(svc.statusFor("   ").status()).isEqualTo(CheckoutStatusService.Status.PENDING);
    }
}
