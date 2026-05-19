package com.imin.iminapi.service.ticket;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Translates a Stripe Checkout Session id into the "is the issuance webhook
 * done yet?" answer the imin-public success page polls.
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

    public Result statusFor(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new Result(Status.PENDING, null);
        }
        Optional<Order> o = orders.findByStripeSessionId(sessionId);
        return o.map(order -> new Result(Status.READY, order.getToken()))
                .orElse(new Result(Status.PENDING, null));
    }
}
