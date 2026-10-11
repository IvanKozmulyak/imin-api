package com.imin.iminapi.service.event;

import com.imin.iminapi.dispute.DisputeWithholding;
import com.imin.iminapi.dto.event.EventSalesFigures;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sold, capacity and revenue for the event list and detail, the Overview and the org home, LIVE-mode
 * orders only: one grouped query per figure for a whole page, never one per event. {@link #soldForEvent}
 * is the same sold figure for the predictor and Momentum reads.
 */
@Component
public class EventSalesTotals {

    private final TicketTierRepository tiers;
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final DisputeWithholding disputeWithholding;
    private final TicketRepository tickets;

    public EventSalesTotals(TicketTierRepository tiers, OrderRepository orders, RefundRepository refunds,
                            DisputeWithholding disputeWithholding, TicketRepository tickets) {
        this.tiers = tiers;
        this.orders = orders;
        this.refunds = refunds;
        this.disputeWithholding = disputeWithholding;
        this.tickets = tickets;
    }

    /** Figures for every id; an id with no sales rows gets zeros and a null capacity. */
    public Map<UUID, EventSalesFigures> forEvents(Collection<UUID> eventIds) {
        Map<UUID, EventSalesFigures> out = new HashMap<>();
        // Hibernate cannot bind an empty IN list, and an empty page needs no queries.
        if (eventIds == null || eventIds.isEmpty()) return out;

        Map<UUID, long[]> tierSums = tierSums(eventIds);
        Map<UUID, Integer> sold = liveSold(eventIds, tierSums);
        Map<UUID, Long> gross = sumsById(orders.sumTotalMinorByEventIds(eventIds));
        Map<UUID, Long> refunded = sumsById(refunds.sumSucceededRefundMinorByEventIds(eventIds));
        Map<UUID, Long> withheld = disputeWithholding.withheldMinorByEvent(eventIds);

        for (UUID id : eventIds) {
            long quantity = tierSums.getOrDefault(id, new long[] {0L, 0L})[1];
            Integer capacity = quantity > 0 ? (int) quantity : null;
            long revenue = Math.max(0L, gross.getOrDefault(id, 0L)
                    - refunded.getOrDefault(id, 0L)
                    - withheld.getOrDefault(id, 0L));
            out.put(id, new EventSalesFigures(sold.get(id), capacity, revenue));
        }
        return out;
    }

    /** Figures for one event (the detail and patch responses). */
    public EventSalesFigures forEvent(UUID eventId) {
        return forEvents(List.of(eventId)).get(eventId);
    }

    /** {@link EventSalesFigures#sold} for one event, without the money queries. */
    public int soldForEvent(UUID eventId) {
        List<UUID> ids = List.of(eventId);
        return liveSold(ids, tierSums(ids)).get(eventId);
    }

    /** Per event: [SUM(sold), SUM(quantity)]; an event with no tiers has no entry. */
    private Map<UUID, long[]> tierSums(Collection<UUID> eventIds) {
        Map<UUID, long[]> out = new HashMap<>();
        for (Object[] row : tiers.sumSoldAndQuantityByEventIds(eventIds)) {
            out.put((UUID) row[0], new long[] {
                    ((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }
        return out;
    }

    /** tier.sold counts both modes, so the test-era tickets it still holds come off, then live chargebacks. */
    private Map<UUID, Integer> liveSold(Collection<UUID> eventIds, Map<UUID, long[]> tierSums) {
        Map<UUID, Long> testHeld = sumsById(tickets.countHeldOnTestOrdersByEventIds(eventIds));
        Map<UUID, Integer> disputedTickets = disputeWithholding.disputedTicketCounts(eventIds);
        Map<UUID, Integer> out = new HashMap<>();
        for (UUID id : eventIds) {
            long sold = tierSums.getOrDefault(id, new long[] {0L, 0L})[0];
            out.put(id, (int) Math.max(0L, sold - testHeld.getOrDefault(id, 0L)
                    - disputedTickets.getOrDefault(id, 0)));
        }
        return out;
    }

    private static Map<UUID, Long> sumsById(List<Object[]> rows) {
        Map<UUID, Long> out = new HashMap<>();
        for (Object[] row : rows) out.put((UUID) row[0], ((Number) row[1]).longValue());
        return out;
    }
}
