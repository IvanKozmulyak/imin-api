package com.imin.iminapi.predictor.model;

/**
 * The projection band a live re-forecast falls into, relative to configured capacity
 * (spec §4.2 trajectory-change alerts, task 86cav479r). A band CROSSING between two
 * consecutive re-forecasts is what fires exactly one dashboard notification.
 *
 * <p>Boundaries (code constants, deliberately coarse — a band is a story, not a number):
 * <ul>
 *   <li>{@link #UNDER_60} — projected final below 60% of capacity.</li>
 *   <li>{@link #TRACKING_60_85} — 60% to 85%.</li>
 *   <li>{@link #TRACKING_85_100} — 85% up to (but not at) capacity.</li>
 *   <li>{@link #SELL_OUT_LIKELY} — projected final at or above capacity.</li>
 * </ul>
 *
 * <p>Classification reads the RAW (un-capacity-clamped) projected-final midpoint, so a
 * strong event can reach {@link #SELL_OUT_LIKELY} even though the DISPLAYED range is clamped
 * to capacity. Ordinal order is ascending in strength ({@code UNDER_60} < ... <
 * {@code SELL_OUT_LIKELY}) so a caller can tell an up-crossing from a recovery.
 */
public enum ProjectionBand {
    UNDER_60("tracking below 60% of capacity"),
    TRACKING_60_85("tracking 60–85% of capacity"),
    TRACKING_85_100("tracking 85–100% of capacity"),
    SELL_OUT_LIKELY("tracking toward a sell-out");

    private final String phrase;

    ProjectionBand(String phrase) { this.phrase = phrase; }

    /** Honest, range-worded phrase for alert copy — no "will", no bare integer. */
    public String phrase() { return phrase; }

    public String wire() { return name(); }

    /**
     * Classify a RAW (unclamped) projected-final midpoint against capacity. Capacity &le; 0
     * (no tiers) has no meaningful band → {@link #UNDER_60}.
     */
    public static ProjectionBand classify(double rawProjectedFinalMidpoint, int capacity) {
        if (capacity <= 0) return UNDER_60;
        double ratio = rawProjectedFinalMidpoint / capacity;
        if (ratio >= 1.0) return SELL_OUT_LIKELY;
        if (ratio >= 0.85) return TRACKING_85_100;
        if (ratio >= 0.60) return TRACKING_60_85;
        return UNDER_60;
    }
}
