package com.imin.iminapi.service.event;

import com.imin.iminapi.model.ReservationStatus;
import com.imin.iminapi.model.TicketReservation;
import com.imin.iminapi.repository.TicketReservationRepository;
import com.stripe.StripeClient;
import com.stripe.service.PaymentIntentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sweeper's second job, added with the native PaymentIntent surface.
 *
 * <p>On the hosted path a released hold automatically stops being payable: the
 * reservation's {@code expires_at} and the Checkout Session's {@code expires_at}
 * are the same instant. <b>A PaymentIntent has no {@code expires_at}.</b> Left
 * alone, a native intent stays payable indefinitely — mint intents on a hot tier,
 * wait out the TTL, let the seats resell, then confirm. {@code confirmSold} on a
 * RELEASED row deliberately credits {@code sold} anyway and logs {@code [OVERSOLD]},
 * so that becomes a real, fully-charged, fully-transferred oversold sale.
 */
class ReservationSweeperTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    private TicketReservationRepository reservations;
    private InventoryService inventory;
    private StripeClient stripeClient;
    private PaymentIntentService paymentIntents;
    private ReservationSweeper sweeper;

    @BeforeEach
    void setUp() {
        reservations = mock(TicketReservationRepository.class);
        inventory = mock(InventoryService.class);
        stripeClient = mock(StripeClient.class);
        paymentIntents = mock(PaymentIntentService.class);
        when(stripeClient.paymentIntents()).thenReturn(paymentIntents);
        sweeper = new ReservationSweeper(reservations, inventory,
                Clock.fixed(NOW, ZoneOffset.UTC), stripeClient);
    }

    @Test
    void releasesAndCancelsAnExpiredNativeIntent() throws Exception {
        TicketReservation r = held("pi_test_native_1");
        when(reservations.findHeldExpiredBefore(eq(NOW), any())).thenReturn(List.of(r));

        sweeper.sweep();

        verify(inventory).releaseReservation(r.getId(), "SWEEPER");
        verify(paymentIntents).cancel("pi_test_native_1");
    }

    @Test
    void neverCancelsForAHostedSessionHold() throws Exception {
        TicketReservation r = held("cs_test_hosted_1");
        when(reservations.findHeldExpiredBefore(eq(NOW), any())).thenReturn(List.of(r));

        sweeper.sweep();

        verify(inventory).releaseReservation(r.getId(), "SWEEPER");
        // The Session's own expires_at already made this unpayable — and cancelling
        // a Session id through the PaymentIntent API would just error.
        verify(paymentIntents, never()).cancel(anyString());
    }

    @Test
    void freeHoldWithNoStripeIdIsJustReleased() throws Exception {
        TicketReservation r = held(null);
        when(reservations.findHeldExpiredBefore(eq(NOW), any())).thenReturn(List.of(r));

        sweeper.sweep();

        verify(inventory).releaseReservation(r.getId(), "SWEEPER");
        verify(paymentIntents, never()).cancel(anyString());
    }

    /**
     * Stripe refuses to cancel an intent that already succeeded — which is correct,
     * that one is a real sale the webhook will fulfil. A cancel failure must never
     * stop the sweep or un-release the seats.
     */
    @Test
    void aCancelFailureDoesNotBreakTheSweep() throws Exception {
        TicketReservation ok = held("pi_test_ok");
        TicketReservation boom = held("pi_test_already_succeeded");
        when(reservations.findHeldExpiredBefore(eq(NOW), any())).thenReturn(List.of(boom, ok));
        when(paymentIntents.cancel("pi_test_already_succeeded"))
                .thenThrow(new com.stripe.exception.InvalidRequestException(
                        "already succeeded", null, null, null, 400, null));

        sweeper.sweep();

        verify(inventory).releaseReservation(boom.getId(), "SWEEPER");
        verify(inventory).releaseReservation(ok.getId(), "SWEEPER");
        verify(paymentIntents).cancel("pi_test_ok");
    }

    /** A release failure must not leave a still-payable intent cancelled behind it. */
    @Test
    void doesNotCancelWhenTheReleaseItselfFailed() throws Exception {
        TicketReservation r = held("pi_test_release_fails");
        when(reservations.findHeldExpiredBefore(eq(NOW), any())).thenReturn(List.of(r));
        org.mockito.Mockito.doThrow(new IllegalStateException("row gone"))
                .when(inventory).releaseReservation(r.getId(), "SWEEPER");

        sweeper.sweep();

        // The hold is still HELD and the next tick will retry, so the intent must
        // stay payable — cancelling it here would strand a buyer mid-payment on a
        // hold that was never actually released.
        verify(paymentIntents, never()).cancel(anyString());
    }

    @Test
    void emptyBatchTouchesNothing() throws Exception {
        when(reservations.findHeldExpiredBefore(eq(NOW), any())).thenReturn(List.of());

        sweeper.sweep();

        verify(inventory, never()).releaseReservation(any(), anyString());
        verify(paymentIntents, never()).cancel(anyString());
    }

    private TicketReservation held(String stripeId) {
        TicketReservation r = new TicketReservation();
        r.setId(UUID.randomUUID());
        r.setTierId(UUID.randomUUID());
        r.setQty(1);
        r.setStatus(ReservationStatus.HELD);
        r.setExpiresAt(NOW.minusSeconds(60));
        r.setStripeSessionId(stripeId);
        return r;
    }
}
