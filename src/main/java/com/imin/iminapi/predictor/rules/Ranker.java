package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.DateResult.Verdict;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Ranks candidate dates 1..n inside each coverage bucket; buckets are never compared with each other. */
public final class Ranker {

    static final BigDecimal HIGH_MIN = new BigDecimal("0.800");
    static final BigDecimal MID_MIN = new BigDecimal("0.600");

    public record Candidate(LocalDate date, DateResult result, int leadDays) {
        public Candidate {
            Objects.requireNonNull(date, "date");
            Objects.requireNonNull(result, "result");
        }
    }

    public enum Bucket { HIGH, MID, NONE }

    public record Ranked(LocalDate date, Bucket bucket, Integer rank) {}

    private static final Comparator<Candidate> ORDER = Comparator
            .comparingInt((Candidate c) -> c.result().riskScore())
            .thenComparing(Comparator.comparingInt((Candidate c) -> c.result().oppScore()).reversed())
            .thenComparing(Comparator.comparingInt(Candidate::leadDays).reversed())
            .thenComparing(Candidate::date);

    private Ranker() {}

    /** One entry per candidate, in input order; NONE-bucket and not_enough_data dates get a null rank. */
    public static List<Ranked> rank(List<Candidate> candidates) {
        Map<Bucket, List<Candidate>> byBucket = new EnumMap<>(Bucket.class);
        for (Candidate c : candidates) {
            Bucket b = bucket(c.result().coverage());
            if (b != Bucket.NONE && c.result().verdict() != Verdict.NOT_ENOUGH_DATA) {
                byBucket.computeIfAbsent(b, k -> new ArrayList<>()).add(c);
            }
        }
        Map<Candidate, Integer> ranks = new IdentityHashMap<>();
        byBucket.values().forEach(list -> {
            list.sort(ORDER);
            for (int i = 0; i < list.size(); i++) ranks.put(list.get(i), i + 1);
        });
        return candidates.stream()
                .map(c -> new Ranked(c.date(), bucket(c.result().coverage()), ranks.get(c)))
                .toList();
    }

    public static Bucket bucket(BigDecimal coverage) {
        if (coverage.compareTo(HIGH_MIN) >= 0) return Bucket.HIGH;
        if (coverage.compareTo(MID_MIN) >= 0) return Bucket.MID;
        return Bucket.NONE;
    }
}
