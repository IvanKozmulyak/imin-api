package com.imin.iminapi.buyer.controller;

import com.imin.iminapi.buyer.model.BuyerAccountEmail;
import com.imin.iminapi.buyer.repository.BuyerAccountEmailRepository;
import com.imin.iminapi.buyer.security.BuyerPrincipal;
import com.imin.iminapi.buyer.security.CurrentBuyer;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
import com.imin.iminapi.service.ticket.TicketIssuanceEmailer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Actions a signed-in buyer can take on one of their own orders (spec §4.6).
 *
 * <p><b>Why this lives under {@code /buyer} and not {@code /public}.</b> The
 * order token alone would be enough to identify the order — but not to identify
 * the <i>caller</i>, and the caller is what the rate limit has to bind to.
 * Anyone who has ever seen an order link could otherwise use it to send mail to
 * that buyer's inbox on demand. Requiring a session, and checking the order's
 * address is one the account has verified, makes the resend an action the owner
 * takes rather than one anybody can trigger on their behalf.
 */
@RestController
public class BuyerOrderActionsController {

    private static final String NO_STORE = "private, no-store";

    private final OrderRepository orders;
    private final BuyerAccountEmailRepository emails;
    private final TicketIssuanceEmailer emailer;
    private final RateLimiter rateLimiter;

    public BuyerOrderActionsController(OrderRepository orders,
                                       BuyerAccountEmailRepository emails,
                                       TicketIssuanceEmailer emailer,
                                       RateLimiter rateLimiter) {
        this.orders = orders;
        this.emails = emails;
        this.emailer = emailer;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Re-sends the ticket email to the order's own address — never to an
     * address supplied by the caller. A 429 is surfaced, not swallowed: a
     * buyer who pressed the button twice deserves to be told the mail is
     * already on its way.
     */
    @PostMapping("/api/v1/buyer/orders/{token}/resend")
    @Transactional(readOnly = true)
    public ResponseEntity<Void> resend(@CurrentBuyer BuyerPrincipal buyer, @PathVariable String token) {
        rateLimiter.consume("buyer-order-resend", "account:" + buyer.accountId());

        Order order = orders.findByToken(token).orElseThrow(() -> ApiException.notFound("Order"));

        List<String> verified = emails.findByBuyerAccountIdOrderByCreatedAtAsc(buyer.accountId()).stream()
                .filter(e -> e.getVerifiedAt() != null)
                .map(BuyerAccountEmail::getEmailNormalized)
                .toList();

        // 404, not 403: an order the caller does not own must be indistinguishable
        // from one that does not exist, or the endpoint becomes a way to ask
        // whether a given token is real.
        if (order.getEmailNormalized() == null || !verified.contains(order.getEmailNormalized())) {
            throw ApiException.notFound("Order");
        }

        emailer.send(order.getId());

        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, NO_STORE).build();
    }
}
