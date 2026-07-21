package com.imin.iminapi.predictor.model;

/**
 * The fixed comparable-corpus relaxation ladder (spec §6.4). Retrieval starts at
 * {@link #NONE} (city × genre family × capacity band × season) and steps DOWN this
 * ladder, in this exact order, whenever the cluster has fewer than the minimum
 * comparables — widening the net one rung at a time. The applied level is carried
 * in the corpus result so the surface can be honest about how far the net was cast
 * ("based on 11 events in this genre across NL").
 *
 * <p>Ladder order (spec, verbatim): city → country, exact genre → genre family,
 * season dropped LAST.
 *
 * <p><b>Genre rung is currently identity.</b> {@code event_outcomes.genre_family}
 * stores ONLY the fixed 8-bucket family (events carry no finer sub-genre — V32
 * collapsed the vocabulary), so {@link #GENRE_TO_FAMILY} widens nothing today. It is
 * kept as an explicit rung, in the spec's position, so the ladder order is preserved
 * and the rung activates for free if a sub-genre attribute is ever captured. Until
 * then it yields the same cluster as the rung above it and retrieval falls straight
 * through to {@link #DROP_SEASON}.
 */
public enum RelaxationLevel {
    /** city × genre family × capacity band × season. */
    NONE,
    /** city → country. country × genre family × capacity band × season. */
    CITY_TO_COUNTRY,
    /** exact genre → genre family (identity today, see class doc). */
    GENRE_TO_FAMILY,
    /** season dropped (last resort). country × genre family × capacity band. */
    DROP_SEASON;

    /** The next wider rung, or {@code null} at the widest. */
    public RelaxationLevel next() {
        return switch (this) {
            case NONE -> CITY_TO_COUNTRY;
            case CITY_TO_COUNTRY -> GENRE_TO_FAMILY;
            case GENRE_TO_FAMILY -> DROP_SEASON;
            case DROP_SEASON -> null;
        };
    }

    /** True once geography has been widened from the requesting city to its country. */
    public boolean isCountryWide() { return this != NONE; }

    /** True once season has been dropped from the segment. */
    public boolean isSeasonDropped() { return this == DROP_SEASON; }
}
