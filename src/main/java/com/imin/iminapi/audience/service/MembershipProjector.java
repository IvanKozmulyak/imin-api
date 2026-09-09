package com.imin.iminapi.audience.service;

import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Recomputes membership aggregate columns from source rows (S1).
 * Called by both live ingestion and backfill to ensure replay produces identical rows.
 */
@Service
public class MembershipProjector {

    private final OrderRepository orderRepo;
    private final TicketRepository ticketRepo;
    private final EventRepository eventRepo;

    public MembershipProjector(OrderRepository orderRepo, TicketRepository ticketRepo,
                               EventRepository eventRepo) {
        this.orderRepo = orderRepo;
        this.ticketRepo = ticketRepo;
        this.eventRepo = eventRepo;
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

        // Redeemed tickets (attended) + missed events (no_show)
        List<UUID> orderIds = buyerOrders.stream().map(com.imin.iminapi.model.Order::getId).toList();
        Map<UUID, UUID> eventByOrderId = new HashMap<>();
        for (com.imin.iminapi.model.Order o : buyerOrders) {
            eventByOrderId.put(o.getId(), o.getEventId());
        }
        long attended = 0;
        Instant lastAttended = null;
        Set<UUID> attendedEventIds = new HashSet<>();
        Set<UUID> unscannedEventIds = new HashSet<>();
        if (!orderIds.isEmpty()) {
            List<com.imin.iminapi.model.Ticket> tickets = ticketRepo.findByOrderIdInOrderByOrderIdAscCreatedAtAsc(orderIds);
            for (com.imin.iminapi.model.Ticket t : tickets) {
                UUID ticketEventId = eventByOrderId.get(t.getOrderId());
                if (com.imin.iminapi.model.Ticket.STATE_REDEEMED.equals(t.getState())) {
                    attended++;
                    if (ticketEventId != null) attendedEventIds.add(ticketEventId);
                    if (lastAttended == null || t.getRedeemedAt() != null && t.getRedeemedAt().isAfter(lastAttended)) {
                        lastAttended = t.getRedeemedAt();
                    }
                } else if (com.imin.iminapi.model.Ticket.STATE_ISSUED.equals(t.getState())) {
                    if (ticketEventId != null) unscannedEventIds.add(ticketEventId);
                }
            }
        }
        // no_show is "events bought for but not attended", which is what the rule engine and
        // the AI segment prompt both promise. Three things follow, and none of them held
        // before: an unscanned ticket for an event that has not ENDED is not a miss (it used
        // to make every buyer holding an upcoming ticket a member of the prebuilt
        // "Bought-no-showed" campaign segment); turning up on any ticket for an event means
        // the event was attended; and four unscanned tickets to one gig are one miss, not four.
        unscannedEventIds.removeAll(attendedEventIds);
        long noShow = 0;
        if (!unscannedEventIds.isEmpty()) {
            Instant now = Instant.now();
            for (Event e : eventRepo.findAllById(unscannedEventIds)) {
                if (hasEnded(e, now)) noShow++;
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

    /**
     * Has this event finished? {@code ends_at} when we have one, otherwise {@code starts_at}.
     * An event carrying neither is never treated as past — we cannot prove it happened, and
     * a false no-show puts a real buyer in a "you missed us" campaign.
     */
    private static boolean hasEnded(Event e, Instant now) {
        Instant end = e.getEndsAt() != null ? e.getEndsAt() : e.getStartsAt();
        return end != null && end.isBefore(now);
    }
}
