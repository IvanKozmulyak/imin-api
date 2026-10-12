package com.imin.iminapi.service;

import java.util.List;
import java.util.Optional;

/** Medians of comparable events' listed tier prices, in minor units. */
public final class ComparablePrices {

    /** Below this many events the recommendation falls back to the default range. */
    public static final int MIN_EVENTS = 3;

    private ComparablePrices() {}

    /**
     * {@code {median of mins, median of maxes}} over one {@code {min, max}} pair per event; an even
     * count takes the half-up mean of the two middles. Empty below {@link #MIN_EVENTS} events.
     */
    public static Optional<long[]> medians(List<long[]> perEventMinMax) {
        if (perEventMinMax.size() < MIN_EVENTS) return Optional.empty();
        long[] mins = perEventMinMax.stream().mapToLong(p -> p[0]).sorted().toArray();
        long[] maxes = perEventMinMax.stream().mapToLong(p -> p[1]).sorted().toArray();
        return Optional.of(new long[]{median(mins), median(maxes)});
    }

    private static long median(long[] sorted) {
        int n = sorted.length;
        if (n % 2 == 1) return sorted[n / 2];
        return Math.floorDiv(Math.addExact(Math.addExact(sorted[n / 2 - 1], sorted[n / 2]), 1L), 2L);
    }
}
