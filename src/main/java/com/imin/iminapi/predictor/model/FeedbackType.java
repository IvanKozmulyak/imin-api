package com.imin.iminapi.predictor.model;

/**
 * Feedback signal on the prediction feedback endpoint. DISMISSED, EXECUTED and RESTORED target one
 * recommendation (spec §4.3): a dismissed recommendation does not return unless materially changed, an executed
 * one should visibly change subsequent forecasts, a restored one clears a prior dismissal; all three are logged
 * to the ledger ({@code prediction_feedback}). DATE_VERDICT_MATCH is the organizer's after-event answer to the
 * date-check verdict; it is stored in {@code date_verdict_feedback} and never in {@code prediction_feedback}.
 */
public enum FeedbackType {
    DISMISSED, EXECUTED, RESTORED, DATE_VERDICT_MATCH;

    public String wire() { return name().toLowerCase(); }

    public static FeedbackType fromWire(String s) {
        return valueOf(s.toUpperCase());
    }
}
