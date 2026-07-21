package com.imin.iminapi.predictor.model;

/**
 * Which predictor surface produced a ledger row (spec §4). Stored on
 * {@code prediction_ledger.surface}.
 */
public enum PredictionSurface {
    PRE_PUBLISH,   // §4.1 pre-publish draft score
    REFORECAST,    // §4.2 live re-forecast
    ACTIONS;       // §4.3 prescriptive actions

    public String wire() { return name().toLowerCase(); }

    public static PredictionSurface fromWire(String s) {
        return valueOf(s.toUpperCase());
    }
}
