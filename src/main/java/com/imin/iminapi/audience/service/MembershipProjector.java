package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Recomputes membership aggregate columns from source rows (S1).
 * Called by both live ingestion and backfill to ensure replay produces identical rows.
 */
@Service
public class MembershipProjector {

    private final OrderRepository orderRepo;
    private final TicketRepository ticketRepo;

    public MembershipProjector(OrderRepository orderRepo, TicketRepository ticketRepo) {
        this.orderRepo = orderRepo;
        this.ticketRepo = ticketRepo;
    }

    /**
     * Recompute all derived columns on {@code m} from source Orders and Tickets.
     * Mutates the passed membership — caller is responsible for saving.
     *
     * @param m         the membership to recompute (must have orgId + consumerId + normalizedEmail set)
     * @param buyerEmail normalized email to query orders
     */
    public void recompute(Membership m, String buyerEmail) {
        UUID orgId = m.getOrgId();

        // (S1) Derive counts from source Orders
        List<Object[]> orderRows = orderRepo.orderCountsByEmailSince(orgId, Instant.EPOCH);
        long orderCount = 0;
        long totalSpend = 0;
        Instant lastPurchase = null;
        Instant firstSeen = null;

        // Full org-scoped orders for this buyer
        List<com.imin.iminapi.model.Order> buyerOrders = orderRepo.findByOrgIdAndNormalizedEmail(orgId, buyerEmail);

        orderCount = buyerOrders.size();
        for (com.imin.iminapi.model.Order o : buyerOrders) {
            totalSpend += o.getTotalMinor();
            if (lastPurchase == null || o.getCreatedAt().isAfter(lastPurchase)) {
                lastPurchase = o.getCreatedAt();
            }
            if (firstSeen == null || o.getCreatedAt().isBefore(firstSeen)) {
                firstSeen = o.getCreatedAt();
            }
        }

        // Distinct events (by eventId)
        long distinctEvents = buyerOrders.stream().map(com.imin.iminapi.model.Order::getEventId).distinct().count();

        // Redeemed tickets (attended)
        List<UUID> orderIds = buyerOrders.stream().map(com.imin.iminapi.model.Order::getId).toList();
        long attended = 0;
        long noShow = 0;
        Instant lastAttended = null;
        if (!orderIds.isEmpty()) {
            List<com.imin.iminapi.model.Ticket> tickets = ticketRepo.findByOrderIdInOrderByOrderIdAscCreatedAtAsc(orderIds);
            for (com.imin.iminapi.model.Ticket t : tickets) {
                if (com.imin.iminapi.model.Ticket.STATE_REDEEMED.equals(t.getState())) {
                    attended++;
                    if (lastAttended == null || t.getRedeemedAt() != null && t.getRedeemedAt().isAfter(lastAttended)) {
                        lastAttended = t.getRedeemedAt();
                    }
                } else if (com.imin.iminapi.model.Ticket.STATE_ISSUED.equals(t.getState())) {
                    noShow++;
                }
            }
        }

        m.setOrders((int) orderCount);
        m.setSpendMinor(totalSpend);
        m.setAovMinor(orderCount > 0 ? totalSpend / orderCount : 0);
        m.setEvents((int) distinctEvents);
        m.setAttended((int) attended);
        m.setNoShow((int) noShow);
        m.setFirstSeen(firstSeen);
        m.setLastPurchase(lastPurchase);
        m.setLastAttended(lastAttended);

        // Recency in days from last purchase
        Integer recencyDays = null;
        if (lastPurchase != null) {
            recencyDays = (int) ChronoUnit.DAYS.between(lastPurchase, Instant.now());
        }
        m.setRecencyDays(recencyDays);

        // RFM bands
        m.setRfmR(RfmBander.recency(recencyDays));
        m.setRfmF(RfmBander.frequency((int) orderCount));
        m.setRfmM(RfmBander.monetary(totalSpend));

        // Lifecycle
        m.setLifecycle(LifecycleClassifier.classify(m));
    }
}
