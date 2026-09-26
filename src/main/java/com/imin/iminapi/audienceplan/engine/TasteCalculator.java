package com.imin.iminapi.audienceplan.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Taste = per whitelisted genre bucket, the sum of {@code 0.5^(age_days / half_life)} over bought events,
 * normalized to sum to 1. Buckets outside the whitelist contribute nothing; no whitelisted purchase gives an empty map.
 */
public final class TasteCalculator {

    private static final double SECONDS_PER_DAY = 86_400d;

    /** One bought event: its genre bucket key and when it took place. */
    public record Purchase(String genreKey, Instant at) {}

    private TasteCalculator() {}

    public static Map<String, Double> taste(Collection<Purchase> purchases, Set<String> whitelist,
                                            int halfLifeDays, Instant asOf) {
        Map<String, Double> raw = new TreeMap<>();
        for (Purchase p : purchases) {
            if (p.genreKey() == null || !whitelist.contains(p.genreKey()) || p.at() == null) continue;
            raw.merge(p.genreKey(), decay(p.at(), asOf, halfLifeDays), Double::sum);
        }
        double total = raw.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return Map.of();
        raw.replaceAll((k, w) -> w / total);
        return raw;
    }

    /** Weight of one purchase; a future event has age 0 (weight 1). */
    static double decay(Instant at, Instant asOf, int halfLifeDays) {
        double ageDays = Math.max(0, Duration.between(at, asOf).getSeconds() / SECONDS_PER_DAY);
        return Math.pow(0.5, ageDays / halfLifeDays);
    }
}
