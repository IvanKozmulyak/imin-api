package com.imin.iminapi.predictor.service;

import com.imin.iminapi.util.EventNormalization;

/**
 * The comparable-corpus segment keys (spec §6.4) — ONE seam that every writer and every reader
 * of a segment goes through, so a segment can never be split by spelling.
 *
 * <p><b>Why (predictor-edge-3).</b> {@code event_outcomes.city} / {@code genre_family} used to be
 * frozen from the event's DISPLAY strings, and all three segment queries in
 * {@code EventOutcomeRepository} match them by equality. {@link EventNormalization} states the
 * rule for exactly those two fields: whitespace is collapsed but <b>case is PRESERVED</b>, because
 * both frontends print the stored string verbatim — "merging the case variants is the job of
 * {@code cityKey}/{@code genreKey}". V82 added {@code events.venue_city_key} /
 * {@code events.genre_key} for precisely this reason. Copying the un-merged half meant
 * {@code Techno} and {@code techno}, {@code Metz} and {@code METZ} were separate segments here —
 * reproducing the fragmentation V82 fixed for the buyer facets, which shrinks {@code clusterSize},
 * which is what picks the §5 language tier and what the ≥5 privacy floor tests.
 *
 * <p>These are INTERNAL keys: nothing renders {@code event_outcomes}, and the display spelling
 * stays on the event row (and in {@code PredictionResult.Comparables.filters}, which is built from
 * the snapshot's display fields). Country needs no helper — {@code EventNormalization.country}
 * already upper-cases or nulls it on write, so it is a merge key by construction.
 */
public final class PredictorSegmentKeys {

    private PredictorSegmentKeys() {}

    /**
     * Merge key for a segment's city, or {@code null} when the event has none. Lower-cased and
     * whitespace-collapsed — identical to {@code events.venue_city_key}.
     */
    public static String cityKey(String displayCity) {
        return blankToNull(EventNormalization.cityKey(displayCity));
    }

    /**
     * Merge key for a segment's genre family, or {@code null} when the event has none.
     * Lower-cased and whitespace-collapsed — identical to {@code events.genre_key}.
     */
    public static String genreKey(String displayGenre) {
        return blankToNull(EventNormalization.genreKey(displayGenre));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
