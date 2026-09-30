package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** One candidate date scored: verdict, capped risk and opportunity (0..10), coverage (0..1, scale 3), breakdown. */
public record DateResult(Verdict verdict, int riskScore, int oppScore, BigDecimal coverage, List<ScoreLine> breakdown) {

    public enum Verdict {
        GOOD, ADJUST, MOVE, NOT_ENOUGH_DATA;

        /** Matches V162 {@code ck_date_check_date_verdict}. */
        public String dbValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One found row's uncapped points; the UI shows the 10-point cap on the totals. */
    public record ScoreLine(String questionId, Kind kind, SourceKind sourceKind, int points) {}

    public DateResult {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(coverage, "coverage");
        breakdown = breakdown == null ? List.of() : List.copyOf(breakdown);
    }
}
