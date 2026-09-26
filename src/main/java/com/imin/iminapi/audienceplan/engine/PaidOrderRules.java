package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;

import java.util.Collection;

/**
 * Which orders count as paid purchases for the audience plan: Stripe, non-zero, live mode,
 * and at least one ticket that was neither refunded nor revoked.
 */
public final class PaidOrderRules {

    static final String PAYMENT_METHOD_STRIPE = "stripe";

    private PaidOrderRules() {}

    public static boolean isPaid(Order order, Collection<Ticket> ticketsOfOrder) {
        if (!PAYMENT_METHOD_STRIPE.equals(order.getPaymentMethod())) return false;
        if (order.getTotalMinor() <= 0) return false;
        if (order.isTestMode()) return false;
        return ticketsOfOrder.stream().anyMatch(PaidOrderRules::countable);
    }

    /** A ticket still held by the buyer: not refunded and not revoked. */
    public static boolean countable(Ticket ticket) {
        String state = ticket.getState();
        return !Ticket.STATE_REFUNDED.equals(state) && !Ticket.STATE_REVOKED.equals(state);
    }
}
