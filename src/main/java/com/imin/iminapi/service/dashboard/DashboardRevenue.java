package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * The org home's organizer money: order totals less succeeded refunds, the unrefunded booking
 * fee and OPEN/LOST chargebacks, all modes. Overview and Sales keep their gross-based figures.
 */
@Component
public class DashboardRevenue {

    public record Window(long netRevenueMinor, long ticketsSold) {}

    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final DisputeRepository disputes;
    private final TicketRepository tickets;

    public DashboardRevenue(OrderRepository orders, RefundRepository refunds,
                            DisputeRepository disputes, TicketRepository tickets) {
        this.orders = orders;
        this.refunds = refunds;
        this.disputes = disputes;
        this.tickets = tickets;
    }

    /** Same expression as the payout per-event net in PostEventPayoutService.payOneEvent, applied to a whole window. */
    public static long net(long gross, long refunded, long appFee, long appFeeRefunded, long disputed) {
        return Math.max(0L, Math.max(0L, gross - refunded) - Math.max(0L, appFee - appFeeRefunded) - disputed);
    }

    /** Net and sold tickets of the org's orders created in {@code [since, until)}, with their own refunds and disputes. */
    public Window forOrgWindow(UUID orgId, Instant since, Instant until) {
        Object[] o = orders.sumTotalAndApplicationFeeByOrgInWindow(orgId, since, until).get(0);
        Object[] r = refunds.sumSucceededRefundAndFeeByOrgInWindow(orgId, since, until).get(0);
        long disputed = disputes.sumOpenOrLostMinorByOrgOrderWindow(orgId, since, until);
        long net = net(num(o[0]), num(r[0]), num(o[1]), num(r[1]), disputed);
        return new Window(net, tickets.countSoldByOrgInWindow(orgId, since, until));
    }

    /** {@link #net} over every order of one event. */
    public long netForEvent(UUID eventId) {
        return net(orders.sumTotalMinorByEventId(eventId),
                refunds.sumSucceededRefundMinorByEventId(eventId),
                orders.sumApplicationFeeMinorByEventId(eventId),
                refunds.sumSucceededRefundApplicationFeeMinorByEventId(eventId),
                disputes.sumOpenOrLostMinorByEventId(eventId));
    }

    /** Tickets on the event that are not refunded or revoked. */
    public long ticketsForEvent(UUID eventId) {
        return tickets.countSoldByEventId(eventId);
    }

    private static long num(Object v) {
        return ((Number) v).longValue();
    }
}
