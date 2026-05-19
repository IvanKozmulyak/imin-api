package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

    @Test
    void pending_for_null_or_blank_session_id() {
        OrderRepository orders = mock(OrderRepository.class);
        CheckoutStatusService svc = new CheckoutStatusService(orders);

        assertThat(svc.statusFor(null).status()).isEqualTo(CheckoutStatusService.Status.PENDING);
        assertThat(svc.statusFor("").status()).isEqualTo(CheckoutStatusService.Status.PENDING);
        assertThat(svc.statusFor("   ").status()).isEqualTo(CheckoutStatusService.Status.PENDING);
    }
}
