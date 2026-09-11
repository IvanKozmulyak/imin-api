package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.ReservationStatus;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketReservationRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Translates a Stripe Checkout Session id — or, for the native app, a
 * PaymentIntent id — into the "is the issuance webhook done yet?" answer the
 * imin-public success page polls.
 *
 * <p>The page lands at {@code /e/{eventId}/success?session_id=cs_…} immediately
 * after Stripe redirects the buyer. The webhook race typically completes
 * within ~1 second, but to be safe the page polls this endpoint with a meta
 * refresh until it sees {@code READY}, then server-side redirects to
 * {@code /order/{orderToken}}.
 */
@Service
public class CheckoutStatusService {

    public enum Status { READY, PENDING, FAILED }

    public record Result(Status status, String orderToken) {}

    private final OrderRepository orders;
    private final TicketReservationRepository reservations;

    public CheckoutStatusService(OrderRepository orders, TicketReservationRepository reservations) {
        this.orders = orders;
        this.reservations = reservations;
    }

    /**
     * Resolves a just-paid checkout to its order token.
     *
     * <p>Two id shapes reach here. The web sends a Stripe Checkout Session id
     * ({@code cs_…}), which lands on {@code orders.stripe_session_id}. The app sends
     * a PaymentIntent id ({@code pi_…}), because a native PaymentSheet purchase has
     * no Session at all — and {@code orders.stripe_session_id} is therefore null for
     * it. Dispatching on the prefix keeps one endpoint and one polling contract for
     * both clients.
     *
     * <p>The id in the URL is the authorization, in both shapes: only the buyer's own
     * client ever holds it, and the response is {@code private, no-store}.
     */
    public Result statusFor(String id) {
        if (id == null || id.isBlank()) {
            return new Result(Status.PENDING, null);
        }
        Optional<Order> o = id.startsWith("pi_")
                ? orders.findByStripePaymentIntentId(id)
                : orders.findByStripeSessionId(id);
        if (o.isPresent()) {
            return new Result(Status.READY, o.get().getToken());
        }
        return terminalFailure(id) ? new Result(Status.FAILED, null)
                                    : new Result(Status.PENDING, null);
    }

    /**
     * {@code FAILED} exists so the poll can stop. A declined card or an abandoned
     * session produces no Order, ever, and the success page meta-refreshes until it
     * sees something other than {@code PENDING} — so without this the buyer sat on a
     * spinner forever. (The constant was declared and serialised as
     * {@code status: "failed"} but produced by nothing, which is how that went
     * unnoticed.)
     *
     * <p>A RELEASED hold is the signal, and only the terminal paths reach it:
     * {@code checkout.session.expired}, {@code checkout.session.async_payment_failed},
     * {@code payment_intent.canceled} and the {@code ReservationSweeper}. A
     * {@code payment_intent.payment_failed} no longer releases a card hold — a declined or
     * 3DS-failed intent is still payable inside its session, so the poller answers PENDING
     * until the session expires, the sweeper collects it, or Stripe cancels it. HELD
     * (the normal webhook race) and CONFIRMED (money moved, issuance not landed yet
     * — the case the fulfilment reconciler covers) both stay PENDING; calling either
     * failed would be a lie to someone who has paid.
     *
     * <p>One lookup covers both id shapes: the web flow attaches the Checkout Session
     * id to the reservation, and {@code StripePaymentIntentService} attaches the
     * PaymentIntent id to the same column for native purchases.
     */
    private boolean terminalFailure(String id) {
        return reservations.findByStripeSessionId(id)
                .map(r -> r.getStatus() == ReservationStatus.RELEASED)
                .orElse(false);
    }
}
