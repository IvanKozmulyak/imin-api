package com.imin.iminapi.audienceplan.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * After-event arithmetic: 80% ranges for a response rate (Wilson) and for arm − holdout lift (Newcombe hybrid score),
 * door close and which stored phase is due. Never a single number: every rate comes back as low / mid / high.
 */
public final class OutcomeMath {

    /** Same width as the plan bands (p10-p90). */
    public static final double INTERVAL_LEVEL = 0.8;
    /** Two-sided z for {@link #INTERVAL_LEVEL}. */
    static final double Z = 1.2815515655446004;
    /** An event without an end time closes its door this long after the start. */
    public static final Duration DOOR_OPEN_WITHOUT_END = Duration.ofHours(12);
    static final Duration D1 = Duration.ofDays(1);
    static final Duration D7 = Duration.ofDays(7);

    private OutcomeMath() {}

    public record Range(double low, double mid, double high) {}

    public enum Phase {
        LIVE, PENDING, D1, D7;

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** How long after door close unsubscribes and complaints still count for a stored phase. */
        public Duration window() {
            return switch (this) {
                case D1 -> OutcomeMath.D1;
                case D7 -> OutcomeMath.D7;
                default -> throw new IllegalStateException("no stored window for " + this);
            };
        }
    }

    public enum LiftStatus {
        /** Arm and holdout both reach the minimum: {@code lift} is a range. */
        OK,
        /** Arm or holdout under the minimum: too few to tell. */
        TOO_FEW,
        /** The segment was under the holdout minimum, so nothing to compare with. */
        NO_HOLDOUT,
        /** This row is the holdout itself. */
        BASELINE;

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** Wilson score interval around {@code k / n}; null when nobody is in the group. */
    public static Range rate(int k, int n) {
        if (n <= 0) return null;
        double[] w = wilson(k, n);
        return round(w[0], (double) k / n, w[1]);
    }

    /** Newcombe's hybrid score interval for {@code k1/n1 − k2/n2}; null when either group is empty. */
    public static Range lift(int k1, int n1, int k2, int n2) {
        if (n1 <= 0 || n2 <= 0) return null;
        double p1 = (double) k1 / n1;
        double p2 = (double) k2 / n2;
        double[] w1 = wilson(k1, n1);
        double[] w2 = wilson(k2, n2);
        double d = p1 - p2;
        double low = d - Math.sqrt(sq(p1 - w1[0]) + sq(w2[1] - p2));
        double high = d + Math.sqrt(sq(w1[1] - p1) + sq(p2 - w2[0]));
        return round(low, d, high);
    }

    /** {@code holdoutMembers} is null when the segment has no holdout row. */
    public static LiftStatus liftStatus(boolean holdoutRow, Integer holdoutMembers, int armMembers, int minimum) {
        if (holdoutRow) return LiftStatus.BASELINE;
        if (holdoutMembers == null) return LiftStatus.NO_HOLDOUT;
        return armMembers >= minimum && holdoutMembers >= minimum ? LiftStatus.OK : LiftStatus.TOO_FEW;
    }

    /** {@code ends_at}, else {@code starts_at} + 12 h; null when the event has neither. */
    public static Instant doorClose(Instant startsAt, Instant endsAt) {
        if (endsAt != null) return endsAt;
        return startsAt == null ? null : startsAt.plus(DOOR_OPEN_WITHOUT_END);
    }

    /** The stored phase due at {@code now}: d7 from 7 days after door close, d1 from 1 day, else none. */
    public static Optional<Phase> due(Instant doorClose, Instant now) {
        if (doorClose == null) return Optional.empty();
        if (!now.isBefore(doorClose.plus(D7))) return Optional.of(Phase.D7);
        if (!now.isBefore(doorClose.plus(D1))) return Optional.of(Phase.D1);
        return Optional.empty();
    }

    private static double[] wilson(int k, int n) {
        double p = (double) k / n;
        double z2 = Z * Z;
        double denom = 1 + z2 / n;
        double centre = (p + z2 / (2.0 * n)) / denom;
        double half = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denom;
        return new double[] {Math.max(0, centre - half), Math.min(1, centre + half)};
    }

    private static double sq(double x) {
        return x * x;
    }

    /** Four decimals; rounding is monotonic, so low ≤ mid ≤ high survives it. */
    private static Range round(double low, double mid, double high) {
        return new Range(r4(low), r4(mid), r4(high));
    }

    private static double r4(double x) {
        return Math.round(x * 10_000d) / 10_000d;
    }
}
