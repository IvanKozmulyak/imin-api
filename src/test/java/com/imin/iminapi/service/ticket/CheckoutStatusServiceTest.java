package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.ReservationStatus;
import com.imin.iminapi.model.TicketReservation;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketReservationRepository;
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
        TicketReservationRepository reservations = mock(TicketReservationRepository.class);
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setToken("ORDTOK");
        when(orders.findByStripeSessionId("cs_test_x")).thenReturn(Optional.of(o));

        CheckoutStatusService.Result r = new CheckoutStatusService(orders, reservations).statusFor("cs_test_x");

        assertThat(r.status()).isEqualTo(CheckoutStatusService.Status.READY);
        assertThat(r.orderToken()).isEqualTo("ORDTOK");
    }

    @Test
    void pending_when_no_order_yet() {
        OrderRepository orders = mock(OrderRepository.class);
        TicketReservationRepository reservations = mock(TicketReservationRepository.class);
        when(orders.findByStripeSessionId("cs_pending")).thenReturn(Optional.empty());

        CheckoutStatusService.Result r = new CheckoutStatusService(orders, reservations).statusFor("cs_pending");

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
        TicketReservationRepository reservations = mock(TicketReservationRepository.class);
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setToken("NATIVETOK");
        when(orders.findByStripePaymentIntentId("pi_test_native")).thenReturn(Optional.of(o));

        CheckoutStatusService.Result r = new CheckoutStatusService(orders, reservations).statusFor("pi_test_native");

        assertThat(r.status()).isEqualTo(CheckoutStatusService.Status.READY);
        assertThat(r.orderToken()).isEqualTo("NATIVETOK");
        // A pi_ id must never be looked up as a session id — that column is null
        // for native orders, so the query could only ever mis-resolve or miss.
        verify(orders, never()).findByStripeSessionId(any());
    }

    @Test
    void pending_when_payment_intent_has_no_order_yet() {
        OrderRepository orders = mock(OrderRepository.class);
        TicketReservationRepository reservations = mock(TicketReservationRepository.class);
        when(orders.findByStripePaymentIntentId("pi_unknown")).thenReturn(Optional.empty());

        CheckoutStatusService.Result r = new CheckoutStatusService(orders, reservations).statusFor("pi_unknown");

        assertThat(r.status()).isEqualTo(CheckoutStatusService.Status.PENDING);
        assertThat(r.orderToken()).isNull();
    }

    /**
     * {@code Status.FAILED} was declared, serialised by
     * {@code PublicCheckoutController} as {@code status: "failed"} — and produced by
     * nothing. A buyer whose card was declined or who abandoned the session got
     * {@code pending} forever: the success page meta-refreshes until READY, so the
     * poll simply never terminated. A RELEASED hold with no Order is exactly that
     * outcome, and it is the only signal we have for it.
     */
    @Test
    void failed_when_the_hold_was_released_and_no_order_exists() {
        OrderRepository orders = mock(OrderRepository.class);
        TicketReservationRepository reservations = mock(TicketReservationRepository.class);
        when(orders.findByStripeSessionId("cs_declined")).thenReturn(Optional.empty());
        when(reservations.findByStripeSessionId("cs_declined"))
                .thenReturn(Optional.of(reservation(ReservationStatus.RELEASED)));

        CheckoutStatusService.Result r =
                new CheckoutStatusService(orders, reservations).statusFor("cs_declined");

        assertThat(r.status()).isEqualTo(CheckoutStatusService.Status.FAILED);
        assertThat(r.orderToken()).isNull();
    }

    /**
     * The native shape reaches the same answer: {@code StripePaymentIntentService}
     * stamps the PaymentIntent id onto the reservation through
     * {@code attachSessionId}, so one lookup covers both id shapes.
     */
    @Test
    void failed_for_a_payment_intent_whose_hold_was_released() {
        OrderRepository orders = mock(OrderRepository.class);
        TicketReservationRepository reservations = mock(TicketReservationRepository.class);
        when(orders.findByStripePaymentIntentId("pi_declined")).thenReturn(Optional.empty());
        when(reservations.findByStripeSessionId("pi_declined"))
                .thenReturn(Optional.of(reservation(ReservationStatus.RELEASED)));

        assertThat(new CheckoutStatusService(orders, reservations).statusFor("pi_declined").status())
                .isEqualTo(CheckoutStatusService.Status.FAILED);
    }

    /** Still held — the webhook race is the normal case and must stay PENDING. */
    @Test
    void pending_while_the_hold_is_still_held() {
        OrderRepository orders = mock(OrderRepository.class);
        TicketReservationRepository reservations = mock(TicketReservationRepository.class);
        when(orders.findByStripeSessionId("cs_racing")).thenReturn(Optional.empty());
        when(reservations.findByStripeSessionId("cs_racing"))
                .thenReturn(Optional.of(reservation(ReservationStatus.HELD)));

        assertThat(new CheckoutStatusService(orders, reservations).statusFor("cs_racing").status())
                .isEqualTo(CheckoutStatusService.Status.PENDING);
    }

    /**
     * CONFIRMED with no Order yet is the reconciler/webhook window — money moved,
     * issuance has not landed. Calling that failed would be a lie to a paying buyer.
     */
    @Test
    void pending_when_the_hold_is_confirmed_but_issuance_has_not_landed() {
        OrderRepository orders = mock(OrderRepository.class);
        TicketReservationRepository reservations = mock(TicketReservationRepository.class);
        when(orders.findByStripeSessionId("cs_confirmed")).thenReturn(Optional.empty());
        when(reservations.findByStripeSessionId("cs_confirmed"))
                .thenReturn(Optional.of(reservation(ReservationStatus.CONFIRMED)));

        assertThat(new CheckoutStatusService(orders, reservations).statusFor("cs_confirmed").status())
                .isEqualTo(CheckoutStatusService.Status.PENDING);
    }

    private static TicketReservation reservation(ReservationStatus status) {
        TicketReservation r = new TicketReservation();
        r.setStatus(status);
        return r;
    }

    @Test
    void pending_for_null_or_blank_session_id() {
        OrderRepository orders = mock(OrderRepository.class);
        TicketReservationRepository reservations = mock(TicketReservationRepository.class);
        CheckoutStatusService svc = new CheckoutStatusService(orders, reservations);

        assertThat(svc.statusFor(null).status()).isEqualTo(CheckoutStatusService.Status.PENDING);
        assertThat(svc.statusFor("").status()).isEqualTo(CheckoutStatusService.Status.PENDING);
        assertThat(svc.statusFor("   ").status()).isEqualTo(CheckoutStatusService.Status.PENDING);
    }
}
