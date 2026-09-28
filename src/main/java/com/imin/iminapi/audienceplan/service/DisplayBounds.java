package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.dto.AudiencePlanResponse;

import java.util.List;

/**
 * Range bounds as the webapp's {@code displayRange} shows them (imin-webapp src/shared/lib/rangeDisplay.ts), so text
 * written from them matches the card: {@code prior} rounds outward to 5, {@code own} and {@code imin} round to whole.
 */
final class DisplayBounds {

    static final int STEP = 5;
    static final String PRIOR = "prior";
    /** Least sure first, as the webapp's {@code planConfidence}. */
    private static final List<String> SURENESS = List.of(PRIOR, "imin", "own");

    private DisplayBounds() {}

    /** Shown low and high; either is null when its input is. */
    record Range(Integer low, Integer high) {}

    static Range count(Number low, Number high, String confidence) {
        return shape(value(low), value(high), confidence, false);
    }

    /** A 0..1 ratio pair as whole percents ({@code round(v × 1000) / 10}, then the same rule). */
    static Range percent(Number low, Number high, String confidence) {
        return shape(pct(value(low)), pct(value(high)), confidence, true);
    }

    /** The gap as the card shows it; the cold panel keeps equal bounds (the whole target) unwidened. */
    static Range gap(AudiencePlanResponse p, String planConfidence) {
        if ("cold".equals(p.mode()) && p.gap().low() == p.gap().high()) {
            return new Range(p.gap().low(), p.gap().high());
        }
        return count(p.gap().low(), p.gap().high(), planConfidence);
    }

    /** Confidence of a plan-level range: the least sure segment; prior when there are none. */
    static String planConfidence(List<AudiencePlanResponse.Segment> segments) {
        for (String level : SURENESS) {
            for (AudiencePlanResponse.Segment s : segments) {
                if (level.equals(s.confidence())) return level;
            }
        }
        return PRIOR;
    }

    private static Range shape(Double low, Double high, String confidence, boolean percent) {
        boolean approximate = PRIOR.equals(confidence);
        Integer lo = low == null ? null : approximate ? (int) (Math.floor(low / STEP) * STEP) : (int) Math.round(low);
        Integer hi = high == null ? null : approximate ? (int) (Math.ceil(high / STEP) * STEP) : (int) Math.round(high);
        if (approximate && lo != null && lo.equals(hi)) {
            hi = lo + STEP;
            if (percent && hi > 100) {
                hi = 100;
                lo = Math.max(0, hi - STEP);
            }
        }
        return new Range(lo, hi);
    }

    private static Double value(Number n) {
        return n == null || !Double.isFinite(n.doubleValue()) ? null : n.doubleValue();
    }

    private static Double pct(Double ratio) {
        return ratio == null ? null : Math.round(ratio * 1000) / 10.0;
    }
}
