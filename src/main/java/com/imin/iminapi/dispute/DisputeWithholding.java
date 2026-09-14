package com.imin.iminapi.dispute;

import com.imin.iminapi.repository.TicketRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * What an event's chargebacks take off the organizer-facing totals. One definition of the
 * withholding set — {@link DisputeStatus#OPEN} (money at risk) and {@link DisputeStatus#LOST}
 * (money gone) — shared by every readout an organizer sees, so Overview, Sales and the org
 * home cannot drift into telling three stories about the same event.
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

    public DisputeWithholding(DisputeRepository disputes, TicketRepository tickets) {
        this.disputes = disputes;
        this.tickets = tickets;
    }

    /** Face value withheld from this event, in minor units. All modes — payouts keep a LIVE-only variant. */
    public long withheldMinor(UUID eventId) {
        return disputes.sumOpenOrLostMinorByEventId(eventId);
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
}
