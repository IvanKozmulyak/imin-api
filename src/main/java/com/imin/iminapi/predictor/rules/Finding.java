package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.rules.QuestionBank.Window;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One answer to one bank question (per source) for one candidate date. Build through the factories;
 * the constructor enforces the same rules as the V162 {@code date_check_finding} CHECKs.
 *
 * <p>Facts keys (template params later): {@code date, name, venue, count, leadDays, minDays,
 * capacityKnown, sharedArtists, sellOutRate, n, relaxation, soldShareBefore, approximate, reason, endDate, country,
 * community, competition, kickoff, estimate}.
 */
public record Finding(String questionId, Kind kind, Status status, int strength, int weight, SourceKind sourceKind,
                      Window window, boolean stopFactor, Map<String, Object> facts, String url, String quote,
                      Instant fetchedAt) {

    public enum Status { FOUND, CLEAR, NOT_CHECKED }

    /** V162 {@code ck_date_check_finding_soft_strength}: web, organizer and input rows stop at 2. */
    static final int SOFT_MAX_STRENGTH = 2;

    public Finding {
        Objects.requireNonNull(questionId, "questionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(window, "window");
        if (strength < 0 || strength > 3) throw new IllegalArgumentException("strength out of 0..3: " + strength);
        if (status == Status.FOUND && strength == 0) throw new IllegalArgumentException("a found row needs strength");
        if (status != Status.FOUND && strength != 0) throw new IllegalArgumentException(status + " must have strength 0");
        if (!firsthand(sourceKind) && strength > SOFT_MAX_STRENGTH) {
            throw new IllegalArgumentException(sourceKind + " strength capped at 2, was " + strength);
        }
        if (stopFactor && (status != Status.FOUND || !firsthand(sourceKind))) {
            throw new IllegalArgumentException("stop factor only on a found structured or internal row");
        }
        facts = facts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }

    /** Strength is clamped to [1, q.maxStrength()], and to 2 off a structured or internal source; null facts are dropped. */
    public static Finding found(Question q, Kind kind, int strength, Map<String, Object> facts, String url) {
        if (!q.kinds().contains(kind)) {
            throw new IllegalArgumentException("question " + q.id() + " has no kind " + kind);
        }
        int cap = firsthand(q.source()) ? q.maxStrength() : Math.min(q.maxStrength(), SOFT_MAX_STRENGTH);
        int s = Math.max(1, Math.min(strength, cap));
        boolean stop = q.stopFactor() && firsthand(q.source());
        return new Finding(q.id(), kind, Status.FOUND, s, q.weight(), q.source(), q.window(), stop,
                withoutNulls(facts), url, null, null);
    }

    public static Finding clear(Question q) {
        return new Finding(q.id(), defaultKind(q), Status.CLEAR, 0, q.weight(), q.source(), q.window(), false,
                Map.of(), null, null, null);
    }

    public static Finding notChecked(Question q, String reason) {
        return new Finding(q.id(), defaultKind(q), Status.NOT_CHECKED, 0, q.weight(), q.source(), q.window(), false,
                Map.of("reason", reason), null, null, null);
    }

    /** RISK for a two-kind question; otherwise its only kind. */
    static Kind defaultKind(Question q) {
        return q.kinds().contains(Kind.RISK) ? Kind.RISK : q.kinds().iterator().next();
    }

    private static boolean firsthand(SourceKind s) {
        return s == SourceKind.STRUCTURED || s == SourceKind.INTERNAL;
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> facts) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (facts != null) facts.forEach((k, v) -> { if (v != null) out.put(k, v); });
        return out;
    }
}
