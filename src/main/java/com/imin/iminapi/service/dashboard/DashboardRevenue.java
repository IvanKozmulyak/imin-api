package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.dispute.DisputeWithholding;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The org home's organizer money: LIVE-mode order totals less succeeded refunds, the unrefunded
 * booking fee and the organizer share of live OPEN/LOST chargebacks. Sales keeps its gross figure.
 */
@Component
public class DashboardRevenue {

    public record Window(long netRevenueMinor, long ticketsSold) {}

    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final DisputeWithholding disputeWithholding;
    private final TicketRepository tickets;

    public DashboardRevenue(OrderRepository orders, RefundRepository refunds,
                            DisputeWithholding disputeWithholding, TicketRepository tickets) {
        this.orders = orders;
        this.refunds = refunds;
        this.disputeWithholding = disputeWithholding;
        this.tickets = tickets;
    }

    /**
     * The presentment-currency counterpart of the payout per-event net, applied to a whole window; the payout
     * itself is sized per order in what Stripe settled. {@code disputedShare} is the organizer share, fee excluded.
     */
    public static long net(long gross, long refunded, long appFee, long appFeeRefunded, long disputedShare) {
        return Math.max(0L, Math.max(0L, gross - refunded) - Math.max(0L, appFee - appFeeRefunded) - disputedShare);
    }

    /** Net and sold tickets of the org's orders created in {@code [since, until)}, with their own refunds and disputes. */
    public Window forOrgWindow(UUID orgId, Instant since, Instant until) {
        Object[] o = orders.sumTotalAndApplicationFeeByOrgInWindow(orgId, since, until).get(0);
        Object[] r = refunds.sumSucceededRefundAndFeeByOrgInWindow(orgId, since, until).get(0);
        long disputedShare = disputeWithholding.organizerShareMinorByOrgWindow(orgId, since, until);
        long net = net(num(o[0]), num(r[0]), num(o[1]), num(r[1]), disputedShare);
        return new Window(net, tickets.countSoldByOrgInWindow(orgId, since, until));
    }

    /** {@link #net} over every live order of one event. */
    public long netForEvent(UUID eventId) {
        return net(orders.sumTotalMinorByEventId(eventId),
                refunds.sumSucceededRefundMinorByEventId(eventId),
                orders.sumApplicationFeeMinorByEventId(eventId),
                refunds.sumSucceededRefundApplicationFeeMinorByEventId(eventId),
                disputeWithholding.organizerShareMinor(eventId));
    }

    /** Tickets on the event's live orders that are not refunded or revoked. */
    public long ticketsForEvent(UUID eventId) {
        return tickets.countSoldByEventId(eventId);
    }

    private static long num(Object v) {
        return ((Number) v).longValue();
    }
}
