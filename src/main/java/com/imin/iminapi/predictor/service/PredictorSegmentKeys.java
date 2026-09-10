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
     * Lower-cased and whitespace-collapsed — like {@code events.genre_key} — and CLAMPED to
     * {@link #MAX_GENRE_CHARS}.
     *
     * <p><b>Why the clamp (predictor-edge-5).</b> {@code event_outcomes.genre_family} is
     * VARCHAR(64) and {@code EventService.publish} runs the freeze INSIDE its own transaction, so
     * an over-long genre would fail that INSERT and roll the WHOLE publish back with only a
     * generic "Request violates a data constraint" to explain it. Today {@code events.genre} is
     * itself VARCHAR(64) (V6), so the freeze cannot be handed a longer value — this is belt and
     * braces for the day that column is widened, because a snapshot must never be able to fail a
     * publish. Same treatment {@code PacingCurveService} already gives its VARCHAR(200) segment
     * key, with the same digest suffix so two different long genres keep two different keys.
     */
    public static String genreKey(String displayGenre) {
        return clamp(blankToNull(EventNormalization.genreKey(displayGenre)), MAX_GENRE_CHARS);
    }

    /** {@code event_outcomes.genre_family} is VARCHAR(64) — see {@link #genreKey}. */
    public static final int MAX_GENRE_CHARS = 64;

    /**
     * Keep a readable prefix plus a digest of the full value, so distinct long values stay
     * distinct. Mirrors {@code PacingCurveService.clamp} (writer and reader both come through
     * here, so a clamped key still resolves on lookup).
     */
    private static String clamp(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max - 9) + "~" + digestPrefix(value);
    }

    private static String digestPrefix(String value) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex); // every JVM ships it
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
