package com.imin.iminapi.marketing.model;

/**
 * Why a stock alert stopped being true: the closed set stored as {@code withdrawn_reason} / {@code expired_reason}
 * and sent on the wire.
 */
public final class StockAlertReason {

    private StockAlertReason() {}

    /** Nothing left to buy in the tier. */
    public static final String SOLD_OUT = "sold_out";
    /** The tier is switched off, deleted or not on sale yet. */
    public static final String TIER_OFF = "tier_off";
    /** More tickets are left than the alert threshold. */
    public static final String MORE_LEFT = "more_left";
    /** The tier (or the event's sales) stopped selling. */
    public static final String DEADLINE_PASSED = "deadline_passed";
    /** The tier's last selling moment is no longer the one quoted, or no longer inside the lead time. */
    public static final String DEADLINE_MOVED = "deadline_moved";
    /** The cheapest tier on sale after the change costs the same or less all-in. */
    public static final String NEXT_NOT_HIGHER = "next_not_higher";
    /** No other tier is on sale when this one stops. */
    public static final String NO_NEXT_TIER = "no_next_tier";
    /** Two tiers on sale after the change share the cheapest price, so the next one cannot be named. */
    public static final String NEXT_TIER_UNCLEAR = "next_tier_unclear";
    /** The event is not public, not live, sales suspended or outside its sale window. */
    public static final String EVENT_NOT_ON_SALE = "event_not_on_sale";
    /** An all-in price the email quoted is no longer the price. */
    public static final String PRICE_CHANGED = "price_changed";
}
