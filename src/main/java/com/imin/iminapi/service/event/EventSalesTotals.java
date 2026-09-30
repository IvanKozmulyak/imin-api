package com.imin.iminapi.service.event;

import com.imin.iminapi.dispute.DisputeWithholding;
import com.imin.iminapi.dto.event.EventSalesFigures;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sold, capacity and revenue for the event list and detail, with the same formulas as the
 * Overview and the org home: one grouped query per figure for a whole page, never one per event.
 */
@Component
public class EventSalesTotals {

    private final TicketTierRepository tiers;
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final DisputeWithholding disputeWithholding;

    public EventSalesTotals(TicketTierRepository tiers, OrderRepository orders,
                            RefundRepository refunds, DisputeWithholding disputeWithholding) {
        this.tiers = tiers;
        this.orders = orders;
        this.refunds = refunds;
        this.disputeWithholding = disputeWithholding;
    }

    /** Figures for every id; an id with no sales rows gets zeros and a null capacity. */
    public Map<UUID, EventSalesFigures> forEvents(Collection<UUID> eventIds) {
        Map<UUID, EventSalesFigures> out = new HashMap<>();
        // Hibernate cannot bind an empty IN list, and an empty page needs no queries.
        if (eventIds == null || eventIds.isEmpty()) return out;

        Map<UUID, long[]> tierSums = new HashMap<>();
        for (Object[] row : tiers.sumSoldAndQuantityByEventIds(eventIds)) {
            tierSums.put((UUID) row[0], new long[] {
                    ((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        Map<UUID, Long> gross = sumsById(orders.sumTotalMinorByEventIds(eventIds));
        Map<UUID, Long> refunded = sumsById(refunds.sumSucceededRefundMinorByEventIds(eventIds));
        Map<UUID, Integer> disputedTickets = disputeWithholding.disputedTicketCounts(eventIds);
        Map<UUID, Long> withheld = disputeWithholding.withheldMinorByEvent(eventIds);

        for (UUID id : eventIds) {
            long[] t = tierSums.getOrDefault(id, new long[] {0L, 0L});
            int sold = (int) Math.max(0L, t[0] - disputedTickets.getOrDefault(id, 0));
            Integer capacity = t[1] > 0 ? (int) t[1] : null;
            long revenue = Math.max(0L, gross.getOrDefault(id, 0L)
                    - refunded.getOrDefault(id, 0L)
                    - withheld.getOrDefault(id, 0L));
            out.put(id, new EventSalesFigures(sold, capacity, revenue));
        }
        return out;
    }

    /** Figures for one event (the detail and patch responses). */
    public EventSalesFigures forEvent(UUID eventId) {
        return forEvents(List.of(eventId)).get(eventId);
    }

    private static Map<UUID, Long> sumsById(List<Object[]> rows) {
        Map<UUID, Long> out = new HashMap<>();
        for (Object[] row : rows) out.put((UUID) row[0], ((Number) row[1]).longValue());
        return out;
    }
}
