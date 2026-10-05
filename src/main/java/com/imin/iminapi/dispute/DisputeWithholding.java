package com.imin.iminapi.dispute;

import com.imin.iminapi.refund.RefundOrderSums;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What an event's chargebacks take off the organizer-facing totals. One definition of the
 * withholding set — {@link DisputeStatus#OPEN} (money at risk) and {@link DisputeStatus#LOST}
 * (money gone) — shared by every readout an organizer sees, so the payout, Overview, Sales and
 * the org home cannot drift into telling different stories about the same event.
 *
 * <p>Per order, {@link DisputeShare}: gross figures lose the disputed amount capped at what was
 * not refunded, net figures only the organizer share; a dispute with no order counts in full.
 *
 * <p>WON and WITHDRAWN_REINSTATED give the money back by construction: they are simply not in
 * the set, so the amount stops being subtracted.
 */
@Component
public class DisputeWithholding {

    /**
     * The withholding set itself, for the queries and the row renderer that need the statuses
     * rather than an amount. Every {@code OPEN or LOST} test in the codebase reads this list —
     * a second copy is how the three readouts start disagreeing.
     */
    public static final List<DisputeStatus> STATUSES =
            List.of(DisputeStatus.OPEN, DisputeStatus.LOST);

    private final DisputeRepository disputes;
    private final TicketRepository tickets;
    private final RefundRepository refunds;

    public DisputeWithholding(DisputeRepository disputes, TicketRepository tickets,
                              RefundRepository refunds) {
        this.disputes = disputes;
        this.tickets = tickets;
        this.refunds = refunds;
    }

    /** Disputed amount withheld from this event's gross, capped per order at what was not refunded. All modes. */
    public long withheldMinor(UUID eventId) {
        return totals(disputes.withholdingRowsByEventIds(List.of(eventId), STATUSES))
                .getOrDefault(eventId, Totals.ZERO).grossWithheld();
    }

    /** The organizer's share of this event's chargebacks, i.e. what comes off its net. All modes. */
    public long organizerShareMinor(UUID eventId) {
        return totals(disputes.withholdingRowsByEventIds(List.of(eventId), STATUSES))
                .getOrDefault(eventId, Totals.ZERO).organizerShare();
    }

    /** {@link #organizerShareMinor}, LIVE-mode disputes only (V130): the payout net's figure. */
    public long organizerShareLiveMinor(UUID eventId) {
        return totals(disputes.liveWithholdingRowsByEventId(eventId, STATUSES))
                .getOrDefault(eventId, Totals.ZERO).organizerShare();
    }

    /** The organizer's share over the org's orders created in {@code [since, until)}, all modes. */
    public long organizerShareMinorByOrgWindow(UUID orgId, Instant since, Instant until) {
        return totals(disputes.withholdingRowsByOrgOrderWindow(orgId, since, until, STATUSES))
                .values().stream().mapToLong(Totals::organizerShare).sum();
    }

    /** Charged-back ORDERS on this event, one per order however many disputes it collected. */
    public int disputedOrderCount(UUID eventId) {
        return (int) disputes.countOpenOrLostOrdersByEventId(eventId);
    }

    /**
     * Tickets revoked by those chargebacks. {@code TicketTier.sold} is deliberately left
     * untouched by dispute ingest, so a sold figure read from that column has to subtract
     * this rather than expect the counter to have moved.
     */
    public int disputedTicketCount(UUID eventId) {
        return (int) tickets.countRevokedInDisputedOrders(eventId, STATUSES);
    }

    /** {@link #disputedTicketCount} for a page of events; an event with none has no entry. */
    public Map<UUID, Integer> disputedTicketCounts(Collection<UUID> eventIds) {
        Map<UUID, Integer> out = new HashMap<>();
        for (Object[] row : tickets.countRevokedInDisputedOrdersByEventIds(eventIds, STATUSES)) {
            out.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return out;
    }

    /** {@link #withheldMinor} for a page of events; an event with none has no entry. */
    public Map<UUID, Long> withheldMinorByEvent(Collection<UUID> eventIds) {
        Map<UUID, Long> out = new HashMap<>();
        totals(disputes.withholdingRowsByEventIds(eventIds, STATUSES))
                .forEach((eventId, t) -> out.put(eventId, t.grossWithheld()));
        return out;
    }

    private record Totals(long grossWithheld, long organizerShare) {
        static final Totals ZERO = new Totals(0L, 0L);

        Totals plus(DisputeShare s) {
            return new Totals(grossWithheld + s.grossWithheldMinor(), organizerShare + s.organizerShareMinor());
        }
    }

    /** Splits each order row and sums per event; one refunds query for all the rows' orders. */
    private Map<UUID, Totals> totals(List<DisputeOrderRow> rows) {
        Set<UUID> orderIds = rows.stream().map(DisputeOrderRow::orderId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        // Hibernate cannot bind an empty IN list.
        Map<UUID, RefundOrderSums> refunded = orderIds.isEmpty() ? Map.of()
                : refunds.sumSucceededAmountAndFeeByOrderIds(orderIds).stream()
                        .collect(Collectors.toMap(RefundOrderSums::orderId, r -> r));
        Map<UUID, Totals> out = new HashMap<>();
        for (DisputeOrderRow row : rows) {
            long disputed = nz(row.disputedMinor());
            DisputeShare share;
            if (row.orderId() == null || row.totalMinor() == null) {
                share = DisputeShare.unattributed(disputed);
            } else {
                RefundOrderSums r = refunded.get(row.orderId());
                share = DisputeShare.of(row.totalMinor(), nz(row.feeMinor()),
                        r == null ? 0L : nz(r.refundedMinor()),
                        r == null ? 0L : nz(r.feeRefundedMinor()), disputed);
            }
            out.merge(row.eventId(), Totals.ZERO.plus(share),
                    (a, b) -> new Totals(a.grossWithheld() + b.grossWithheld(),
                            a.organizerShare() + b.organizerShare()));
        }
        return out;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
