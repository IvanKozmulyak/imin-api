package com.imin.iminapi.cancellation;

import java.util.Locale;

/**
 * Where one order of a cancelled event stands, re-derived from live facts on every pass: the refund
 * rows are the money marker, so a lost state write can never make imin refund an order twice.
 */
public enum CancellationItemState {
    /** Owes a refund and none is in flight: refund now. */
    PENDING,
    /** A refund attempt with an unknown Stripe outcome; RefundAttemptReconciler resolves it. */
    IN_PROGRESS,
    /** Nothing left owed and a refund is still PENDING at Stripe. */
    TRIGGERED,
    /** Nothing left owed and every refund has settled. */
    REFUNDED,
    /** An OPEN dispute: the bank decides first, imin refunds after a win. */
    WAITING_DISPUTE,
    /** A LOST dispute already returned what is owed. */
    DISPUTE_LOST,
    /** A free order. */
    NOTHING_TO_REFUND,
    /** Paid in the other Stripe mode; the running key cannot refund it. */
    OTHER_MODE,
    /** imin cannot settle it alone; see {@code last_error}. */
    NEEDS_ATTENTION;

    /** Refused refund attempts before an order needs a human. */
    public static final int MAX_ATTEMPTS = 3;

    public boolean isTerminal() {
        return this == REFUNDED || this == DISPUTE_LOST || this == NOTHING_TO_REFUND || this == OTHER_MODE;
    }

    /** The {@code event_cancellation_orders.state} value. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CancellationItemState fromWire(String s) {
        return valueOf(s.toUpperCase(Locale.ROOT));
    }

    /** Live facts of one order. {@code remainingMinor} = total minus REQUESTED, PENDING and SUCCEEDED refunds. */
    public record Facts(boolean modeMatches, long totalMinor, boolean hasPaymentIntent, long openDisputes,
                        long lostDisputedMinor, long remainingMinor, boolean hasRequestedRefund,
                        boolean hasPendingRefund, int attempts) {}

    /** A state, plus why it needs attention ({@code no_payment}, {@code lost_partial}, {@code refused}). */
    public record Derived(CancellationItemState state, String reason) {
        static Derived of(CancellationItemState s) {
            return new Derived(s, null);
        }
    }

    /** First matching rule wins; the order is load-bearing. */
    public static Derived derive(Facts f) {
        if (!f.modeMatches()) return Derived.of(OTHER_MODE);
        if (f.totalMinor() == 0) return Derived.of(NOTHING_TO_REFUND);
        if (!f.hasPaymentIntent()) return new Derived(NEEDS_ATTENTION, "no_payment");
        // Any open claim wins: a WON dispute never releases the refund while another is still open.
        if (f.openDisputes() > 0) return Derived.of(WAITING_DISPUTE);
        if (f.lostDisputedMinor() > 0) {
            return f.lostDisputedMinor() >= f.remainingMinor()
                    ? Derived.of(DISPUTE_LOST)
                    : new Derived(NEEDS_ATTENTION, "lost_partial");
        }
        if (f.hasRequestedRefund()) return Derived.of(IN_PROGRESS);
        if (f.remainingMinor() <= 0 && f.hasPendingRefund()) return Derived.of(TRIGGERED);
        if (f.remainingMinor() <= 0) return Derived.of(REFUNDED);
        if (f.attempts() >= MAX_ATTEMPTS) return new Derived(NEEDS_ATTENTION, "refused");
        return Derived.of(PENDING);
    }
}
