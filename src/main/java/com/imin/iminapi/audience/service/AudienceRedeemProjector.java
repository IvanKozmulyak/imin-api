package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.repository.ErasedAddressRepository;
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
    private final ErasedAddressRepository erasedAddressRepo;

    public AudienceRedeemProjector(OrderRepository orderRepo,
                                    AudienceOrderProjector orderProjector,
                                    ErasedAddressRepository erasedAddressRepo) {
        this.orderRepo = orderRepo;
        this.orderProjector = orderProjector;
        this.erasedAddressRepo = erasedAddressRepo;
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
            // Erasure ledger (V99). A door scan replays an OLD order — it is not new
            // data — so for an erased address it would silently rebuild the Consumer +
            // Membership that Art.17 removed. The ticket itself still scans and admits
            // the holder; only the audience profile is not resurrected.
            if (erasedAddressRepo.existsPlatformWide(normalizedEmail)
                    || erasedAddressRepo.existsForOrg(order.getOrgId(), normalizedEmail)) {
                log.info("AudienceRedeemProjector: address erased — skipping projection for order {}",
                        event.orderId());
                return;
            }
            // Reuse upsertMembership which calls recompute() — derives attended from redeemed tickets (S1)
            orderProjector.upsertMembership(order.getOrgId(), normalizedEmail, order.getEmail());
        } catch (Exception e) {
            log.error("AudienceRedeemProjector failed for order {}: {}", event.orderId(), e.getMessage(), e);
        }
    }
}
