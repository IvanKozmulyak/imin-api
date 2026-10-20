package com.imin.iminapi.marketing.model;

/**
 * Momentum trigger taxonomy (spec §6.1): four sales-curve triggers, first match wins, plus {@code clicked_no_buy}
 * (ticket-link clickers with no live ticket), checked on every pass after them. conversion(v2) deferred.
 */
public enum MomentumTriggerType {
    LAUNCH_PUSH, SLUMP, URGENCY_72H, SOLD_OUT, CLICKED_NO_BUY;

    public String wireValue() {
        return name().toLowerCase();
    }

    public static MomentumTriggerType fromWire(String s) {
        return valueOf(s.toUpperCase());
    }
}
