package com.imin.iminapi.audience.service;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.ticket.TicketRedeemedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for {@link TicketRedeemedEvent} AFTER_COMMIT and recomputes the
 * membership's attended / no_show / last_attended from redeemed tickets (S1).
 *
 * <p>M1: loads Order by orderId to get orgId + buyer email. Does NOT use any
 * orgId from the event payload (none exists — Ticket has no orgId).
 * The gate redeemer may be a null-userId principal — this listener never touches auth.
 */
@Component
public class AudienceRedeemProjector {

    private static final Logger log = LoggerFactory.getLogger(AudienceRedeemProjector.class);

    private final OrderRepository orderRepo;
    private final AudienceOrderProjector orderProjector;

    public AudienceRedeemProjector(OrderRepository orderRepo,
                                    AudienceOrderProjector orderProjector) {
        this.orderRepo = orderRepo;
        this.orderProjector = orderProjector;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTicketRedeemed(TicketRedeemedEvent event) {
        try {
            Order order = orderRepo.findById(event.orderId()).orElse(null);
            if (order == null) {
                log.warn("AudienceRedeemProjector: Order {} not found — skipping", event.orderId());
                return;
            }
            String normalizedEmail = EmailNormalizer.normalize(order.getEmail());
            // Reuse upsertMembership which calls recompute() — derives attended from redeemed tickets (S1)
            orderProjector.upsertMembership(order.getOrgId(), normalizedEmail, order.getEmail());
        } catch (Exception e) {
            log.error("AudienceRedeemProjector failed for order {}: {}", event.orderId(), e.getMessage(), e);
        }
    }
}
