package com.imin.iminapi.predictor.model;

/**
 * Which predictor surface produced a ledger row (spec §4). Stored on
 * {@code prediction_ledger.surface}.
 */
public enum PredictionSurface {
    PRE_PUBLISH,   // §4.1 pre-publish draft score
    REFORECAST,    // §4.2 live re-forecast
    // §4.3 prescriptive actions. NOTHING WRITES THIS ROW TODAY: prescriptive actions ship inside
    // the PRE_PUBLISH result's `recommendations` list, so filtering the ledger's surface column
    // for ACTIONS returns an empty set — the constant is a reserved name, not a promise.
    ACTIONS;

    public String wire() { return name().toLowerCase(); }
}
