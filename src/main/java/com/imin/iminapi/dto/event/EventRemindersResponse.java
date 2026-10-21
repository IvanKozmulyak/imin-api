package com.imin.iminapi.dto.event;

import java.time.Instant;

/**
 * Reminder settings of one event with what happened so far. Every count is read from recorded
 * outcomes or live rows, never estimated.
 */
public record EventRemindersResponse(
        boolean enabled,
        boolean shareLinkEnabled,
        String doorNote,
        boolean platformOn,
        long ticketHolderOrders,
        Window dayBefore,
        Window doorsSoon,
        Share share,
        Wallet wallet) {

    /**
     * One reminder window. {@code state}: {@code no_date} (event has no start), {@code sent} (at least
     * one email went out), {@code cancelled} (the event is cancelled, nothing sent, no time promised),
     * {@code passed} (window over, nothing sent), {@code sending} (inside the window, nothing recorded yet)
     * or {@code upcoming}.
     */
    public record Window(Instant sendsFrom, String state, long sent, long optedOut, long bounced, Instant firstSentAt) {}

    /**
     * Friend-link results for this event only. {@code eligible} is false when the event has no public page;
     * {@code friendBlockEligible} is whether the day-before email would carry the friend block right now
     * (switch on, public, sales not suspended, a tier on sale), before any single buyer's opt-out.
     */
    public record Share(boolean eligible, boolean friendBlockEligible, long visitors, long orders, long revenueMinor, String currency) {}

    /** Whether IMIN can mint each wallet pass today. */
    public record Wallet(boolean apple, boolean google) {}
}
