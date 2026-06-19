package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.service.ticket.TicketsIssuedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Listens for {@link TicketsIssuedEvent} AFTER_COMMIT and upserts the
 * Consumer + Membership rows, then recomputes all derived aggregates from source.
 *
 * <p>S3: first_touch_src = 'organic' unconditionally in Tier C (no anon_id on Order).
 * <p>S5: no marketing-consent row is written — gate ships CLOSED.
 * <p>S1: aggregates are DERIVED from source rows (not incremented).
 */
@Component
public class AudienceOrderProjector {

    private static final Logger log = LoggerFactory.getLogger(AudienceOrderProjector.class);

    private final OrderRepository orderRepo;
    private final ConsumerRepository consumerRepo;
    private final MembershipRepository membershipRepo;
    private final MembershipProjector projector;

    public AudienceOrderProjector(OrderRepository orderRepo,
                                   ConsumerRepository consumerRepo,
                                   MembershipRepository membershipRepo,
                                   MembershipProjector projector) {
        this.orderRepo = orderRepo;
        this.consumerRepo = consumerRepo;
        this.membershipRepo = membershipRepo;
        this.projector = projector;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTicketsIssued(TicketsIssuedEvent event) {
        try {
            Order order = orderRepo.findById(event.orderId()).orElse(null);
            if (order == null) {
                log.warn("AudienceOrderProjector: Order {} not found — skipping", event.orderId());
                return;
            }
            String normalizedEmail = EmailNormalizer.normalize(order.getEmail());
            upsertMembership(order.getOrgId(), normalizedEmail, order.getEmail());
        } catch (Exception e) {
            log.error("AudienceOrderProjector failed for order {}: {}", event.orderId(), e.getMessage(), e);
        }
    }

    /**
     * Upsert Consumer then Membership, recompute projection.
     * Package-visible so the backfill job can call it directly.
     */
    @Transactional
    public void upsertMembership(java.util.UUID orgId, String normalizedEmail, String displayName) {
        // 1. Upsert Consumer (INSERT-first, catch DuplicateKeyException — idempotent like WebhookEventDedupService)
        Consumer consumer = consumerRepo.findByNormalizedEmail(normalizedEmail).orElse(null);
        if (consumer == null) {
            Consumer newC = new Consumer();
            newC.setNormalizedEmail(normalizedEmail);
            newC.setDisplayName(displayName);
            try {
                consumer = consumerRepo.save(newC);
            } catch (DataIntegrityViolationException dup) {
                // Race: another thread inserted first
                consumer = consumerRepo.findByNormalizedEmail(normalizedEmail)
                        .orElseThrow(() -> new IllegalStateException("Consumer insert race but still not found: " + normalizedEmail));
            }
        }

        // 2. Upsert Membership
        Optional<Membership> existing = membershipRepo.findByOrgIdAndConsumerId(orgId, consumer.getConsumerId());
        Membership m;
        if (existing.isPresent()) {
            m = existing.get();
        } else {
            m = new Membership();
            m.setOrgId(orgId);
            m.setConsumerId(consumer.getConsumerId());
            m.setDisplayName(displayName);
            m.setFirstTouchSrc("organic"); // S3
        }

        // 3. Recompute aggregates from source (S1)
        projector.recompute(m, normalizedEmail);

        membershipRepo.save(m);
    }
}
