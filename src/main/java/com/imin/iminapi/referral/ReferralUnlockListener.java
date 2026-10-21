package com.imin.iminapi.referral;

import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * After a friend's order commits, re-counts its referrer's crew tickets and writes the unlock when the threshold is
 * reached. Off-thread and swallowed: a failure here never touches the order, and the board and the door re-evaluate.
 */
@Component
public class ReferralUnlockListener {

    private static final Logger log = LoggerFactory.getLogger(ReferralUnlockListener.class);

    private final OrderRepository orders;
    private final ReferralPerkService perks;

    public ReferralUnlockListener(OrderRepository orders, ReferralPerkService perks) {
        this.orders = orders;
        this.perks = perks;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTicketsIssued(TicketsIssuedEvent event) {
        try {
            if (!perks.perksEnabled()) return;
            UUID referrerId = orders.findById(event.orderId()).map(o -> o.getReferredByOrderId()).orElse(null);
            // evaluate runs in its own transaction, which commits before this try ends.
            if (referrerId != null) perks.evaluate(referrerId);
        } catch (Exception e) {
            log.error("Crew perk evaluation failed for order {}: {}", event.orderId(), e.getClass().getSimpleName());
        }
    }
}
