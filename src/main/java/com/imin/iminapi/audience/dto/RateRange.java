package com.imin.iminapi.audience.dto;

import org.apache.commons.math3.distribution.BetaDistribution;

/**
 * A share in percent (0-100) as a range: mid = k/n, low/high = the 80% interval of Beta(k+1, n-k+1).
 * {@code n} is the denominator, so a reader can tell a small sample from a large one.
 */
public record RateRange(double low, double mid, double high, long n) {

    /** Null when there is nothing to divide by; a rate of an empty set is unknown, not 0. */
    public static RateRange of(long k, long n) {
        if (n <= 0) return null;
        long hits = Math.max(0, Math.min(k, n));
        BetaDistribution beta = new BetaDistribution(null, hits + 1d, n - hits + 1d,
                BetaDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
        double mid = (double) hits / n;
        double low = Math.min(beta.inverseCumulativeProbability(0.10), mid);
        double high = Math.max(beta.inverseCumulativeProbability(0.90), mid);
        return new RateRange(pct(low), pct(mid), pct(high), n);
    }

    private static double pct(double share) {
        return Math.round(share * 1000d) / 10d;
    }
}
