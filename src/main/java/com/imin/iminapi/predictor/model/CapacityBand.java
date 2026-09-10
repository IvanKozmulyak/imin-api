package com.imin.iminapi.predictor.model;

/**
 * Capacity bands for comparable-corpus segmentation (spec §6.4):
 * ≤100 / 101–300 / 301–800 / 800+. The wire value is stored on
 * {@code event_outcomes.capacity_band} at publish-freeze so the corpus query
 * can group cheaply without recomputing from tier quantities.
 */
public enum CapacityBand {
    LE100, B101_300, B301_800, GT800;

    /**
     * Bucket a capacity (sum of tier quantities). Null OR NON-POSITIVE capacity → null band
     * (predictor-edge-4): a tier-less event's tier quantities sum to 0, which is an UNKNOWN
     * capacity, not a ≤100 one. Stamping LE100 on it made it a member of the ≤100 segment of
     * every other organizer's comparable corpus and of the LE100 pacing curves. Mirrors
     * {@code ProjectionBand.classify}.
     */
    public static CapacityBand of(Integer capacity) {
        if (capacity == null || capacity <= 0) return null;
        if (capacity <= 100) return LE100;
        if (capacity <= 300) return B101_300;
        if (capacity <= 800) return B301_800;
        return GT800;
    }
}
