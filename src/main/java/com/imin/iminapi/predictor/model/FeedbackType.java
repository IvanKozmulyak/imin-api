package com.imin.iminapi.predictor.model;

/**
 * Recommendation feedback signal (spec §4.3). A dismissed recommendation does not return
 * unless materially changed; an executed one should visibly change subsequent forecasts.
 * Both are logged to the ledger as signal.
 */
public enum FeedbackType {
    DISMISSED, EXECUTED;

    public String wire() { return name().toLowerCase(); }

    public static FeedbackType fromWire(String s) {
        return valueOf(s.toUpperCase());
    }
}
