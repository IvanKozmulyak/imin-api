package com.imin.iminapi.dispute;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.refund.RefundOrderSums;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.settlement.SettlementRate;
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
 *
 * <p>Also sizes the LOST-dispute recovery ({@link #owedOnOrder}) and the hold the payout keeps
 * back for debts not reversed yet ({@link #unrecoveredLostShareLiveMinorByOrg}). Those payout-only methods,
 * and {@link #organizerShareLiveMinor}, answer in settlement minor units ({@link SettlementRate}); the
 * organizer readouts stay in the event's presentment currency.
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

    /**
     * {@link #organizerShareMinor}, LIVE-mode disputes only (V130), in settlement minor units: the payout
     * net's figure. A dispute with no order, or on an order not stamped yet, counts its amount in full.
     */
    public long organizerShareLiveMinor(UUID eventId) {
        List<DisputeSettlementRow> rows = disputes.liveWithholdingRowsByEventId(eventId, STATUSES);
        Map<UUID, RefundOrderSums> refunded = refundsByOrder(rows.stream().map(DisputeSettlementRow::orderId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        long sum = 0L;
        for (DisputeSettlementRow row : rows) {
            long disputed = nz(row.disputedMinor());
            if (row.orderId() == null || row.totalMinor() == null || row.settlementCurrency() == null) {
                sum += disputed;
                continue;
            }
            RefundOrderSums r = refunded.get(row.orderId());
            long ref = r == null ? 0L : nz(r.refundedMinor());
            long feeRef = r == null ? 0L : nz(r.feeRefundedMinor());
            SettlementRate rate = rateOf(row);
            sum += rate.organizerShare(DisputeShare.of(row.totalMinor(), nz(row.feeMinor()), ref, feeRef, disputed),
                    ref, feeRef);
        }
        return sum;
    }

    /** The organizer's share over the org's orders created in {@code [since, until)}, all modes. */
    public long organizerShareMinorByOrgWindow(UUID orgId, Instant since, Instant until) {
        return totals(disputes.withholdingRowsByOrgOrderWindow(orgId, since, until, STATUSES))
                .values().stream().mapToLong(Totals::organizerShare).sum();
    }

    /**
     * What is still to reverse for this order's LOST disputes, in settlement minor units: their organizer
     * share less what earlier reversals on the order still hold. An OPEN sibling is not owed yet.
     * Throws when the order is not stamped.
     */
    public long owedOnOrder(Order order) {
        return owed(order.getId(), SettlementRate.of(order));
    }

    private long owed(UUID orderId, SettlementRate rate) {
        long share = lostShare(orderId, rate);
        if (share <= 0L) return 0L;
        return Math.max(0L, share - disputes.sumHeldByOrderId(orderId));
    }

    /**
     * What the order's reversals still hold beyond the organizer share of its LOST disputes, in settlement
     * minor units: owed back. Throws when the order is not stamped.
     */
    public long returnableOnOrder(Order order) {
        return Math.max(0L, disputes.sumHeldByOrderId(order.getId()) - lostShare(order.getId(), SettlementRate.of(order)));
    }

    private long lostShare(UUID orderId, SettlementRate rate) {
        long lost = disputes.sumLostAmountByOrderId(orderId, DisputeStatus.LOST);
        if (lost <= 0L) return 0L;
        RefundOrderSums r = refunds.sumSucceededAmountAndFeeByOrderIds(List.of(orderId)).stream()
                .findFirst().orElse(null);
        long ref = r == null ? 0L : nz(r.refundedMinor());
        long feeRef = r == null ? 0L : nz(r.feeRefundedMinor());
        return rate.organizerShare(DisputeShare.of(rate.totalMinor(), rate.feeMinor(), ref, feeRef, lost), ref, feeRef);
    }

    /**
     * What is still owed, in settlement minor units, on the org's live orders that settled in
     * {@code settlementCurrency} with an open LOST debt, on events other than {@code excludeEventId}:
     * {@link #owedOnOrder} per order, so a capped reversal counts what it took.
     */
    public long unrecoveredLostShareLiveMinorByOrg(UUID orgId, UUID excludeEventId, String settlementCurrency) {
        Map<UUID, DisputeSettlementRow> byOrder = new HashMap<>();
        for (DisputeSettlementRow row : disputes.unrecoveredLostRowsByOrgExcludingEvent(orgId, DisputeStatus.LOST,
                excludeEventId, settlementCurrency.toLowerCase(java.util.Locale.ROOT))) {
            byOrder.putIfAbsent(row.orderId(), row);
        }
        long sum = 0L;
        for (DisputeSettlementRow row : byOrder.values()) {
            sum += owed(row.orderId(), rateOf(row));
        }
        return sum;
    }

    private static SettlementRate rateOf(DisputeSettlementRow row) {
        return new SettlementRate(nz(row.totalMinor()), nz(row.feeMinor()),
                nz(row.settlementGrossMinor()), nz(row.settlementFeeMinor()));
    }

    private Map<UUID, RefundOrderSums> refundsByOrder(Set<UUID> orderIds) {
        // Hibernate cannot bind an empty IN list.
        return orderIds.isEmpty() ? Map.of()
                : refunds.sumSucceededAmountAndFeeByOrderIds(orderIds).stream()
                        .collect(Collectors.toMap(RefundOrderSums::orderId, r -> r));
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
