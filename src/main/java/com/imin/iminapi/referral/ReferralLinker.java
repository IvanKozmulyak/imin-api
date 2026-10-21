package com.imin.iminapi.referral;

import com.imin.iminapi.audience.service.EmailNormalizer;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Links a new order to the order whose crew link it was bought with. Called by both checkout writers after the
 * attribution is applied and before the order is saved, because {@code referred_by_order_id} is insert-only.
 *
 * <p>A code credits only an order of the same event, in the same Stripe mode, bought by a different normalized
 * email. Anything else leaves the order unlinked, and so does an exception caught here; the lookup shares the
 * caller's transaction, so a database error in it still fails that fulfilment attempt.
 */
@Component
public class ReferralLinker {

    private static final Logger log = LoggerFactory.getLogger(ReferralLinker.class);

    private final OrderRepository orders;

    public ReferralLinker(OrderRepository orders) {
        this.orders = orders;
    }

    public void link(Order order, String code) {
        if (order == null || !ReferralCode.isWellFormed(code)) return;
        try {
            Order referrer = orders.findByReferralCode(code).orElse(null);
            if (referrer == null) return;
            if (!referrer.getEventId().equals(order.getEventId())) return;
            if (referrer.isTestMode() != order.isTestMode()) return;
            if (Objects.equals(EmailNormalizer.normalize(referrer.getEmail()), EmailNormalizer.normalize(order.getEmail()))) {
                return;
            }
            order.setReferredByOrderId(referrer.getId());
        } catch (RuntimeException e) {
            // No throwable and no email: the order is kept, unlinked.
            log.warn("Crew link not applied to an order of event {}: {}", order.getEventId(), e.getClass().getSimpleName());
        }
    }
}
