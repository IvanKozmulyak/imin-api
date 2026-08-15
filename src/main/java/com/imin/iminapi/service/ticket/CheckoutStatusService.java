package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
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

    public CheckoutStatusService(OrderRepository orders) {
        this.orders = orders;
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
        return o.map(order -> new Result(Status.READY, order.getToken()))
                .orElse(new Result(Status.PENDING, null));
    }
}
