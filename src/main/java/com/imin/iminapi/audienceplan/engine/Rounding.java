package com.imin.iminapi.audienceplan.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Plan rounding: counts are {@code Math.round} of the raw value; shown ratios are 2 decimals, rounded down so a
 * shown coverage never reaches a verdict threshold the exact ratio has not.
 */
public final class Rounding {

    private Rounding() {}

    /** A raw expected-ticket or people value as the whole number the organizer sees. */
    public static int count(double raw) {
        if (!(raw >= 0) || raw > Integer.MAX_VALUE) throw new IllegalArgumentException("count out of range: " + raw);
        return (int) Math.round(raw);
    }

    /** {@code numerator / denominator} truncated to 2 decimals; callers pass already rounded counts. */
    public static double ratio2(int numerator, int denominator) {
        if (denominator <= 0) throw new IllegalArgumentException("denominator must be > 0: " + denominator);
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 2, RoundingMode.DOWN)
                .doubleValue();
    }

    /** {@code round(value × pct / 100)}, half-up in integer arithmetic so 300 × 85% is exactly 255. */
    public static int percentOf(int value, int pct) {
        if (value < 0 || pct < 0) throw new IllegalArgumentException("negative percent-of: " + value + " × " + pct);
        return (int) ((value * (long) pct + 50) / 100);
    }
}
