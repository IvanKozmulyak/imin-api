package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.DateResult.ScoreLine;
import com.imin.iminapi.predictor.rules.DateResult.Verdict;
import com.imin.iminapi.predictor.rules.Finding.Status;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.Thresholds;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Turns one date's findings into risk, opportunity, coverage and a verdict (spec §2.2). */
public final class Scorer {

    static final int MAX_SCORE = 10;
    static final int COVERAGE_SCALE = 3;

    /** Found rows by points descending, then question id, then source; shared with {@link ActionPicker}. */
    static Comparator<Finding> byPointsDesc(Thresholds t) {
        return Comparator.comparingInt((Finding f) -> points(f, t)).reversed()
                .thenComparing(Finding::questionId)
                .thenComparing(Finding::sourceKind);
    }

    private Scorer() {}

    /** Star ids are those flagged star on any of their rows. */
    public static DateResult score(List<Finding> findings, QuestionBank bank) {
        Set<String> starIds = bank.questions().stream().filter(Question::star).map(Question::id)
                .collect(Collectors.toSet());
        return score(findings, starIds, bank.thresholds());
    }

    public static DateResult score(List<Finding> findings, Set<String> starIds, Thresholds t) {
        int riskSum = 0;
        int oppSum = 0;
        boolean stop = false;
        for (Finding f : findings) {
            int p = points(f, t);
            if (f.kind() == Kind.RISK) riskSum += p; else oppSum += p;
            stop |= f.stopFactor();
        }
        int risk = Math.min(MAX_SCORE, riskSum);
        int opp = Math.min(MAX_SCORE, oppSum);

        List<ScoreLine> breakdown = findings.stream()
                .filter(f -> f.status() == Status.FOUND)
                .sorted(byPointsDesc(t))
                .map(f -> new ScoreLine(f.questionId(), f.kind(), f.sourceKind(), points(f, t)))
                .toList();

        BigDecimal coverage = coverage(findings, starIds);
        return new DateResult(verdict(stop, risk, coverage, t), risk, opp, coverage, breakdown);
    }

    /** {@code min(maxPointsPerFinding, strength * weight)} for a found row, else 0. */
    public static int points(Finding f, Thresholds t) {
        if (f.status() != Status.FOUND) return 0;
        return Math.min(t.maxPointsPerFinding(), f.strength() * f.weight());
    }

    /** Per star id: checked when any of its rows is found or clear; not_checked rows still count as applicable. */
    private static BigDecimal coverage(List<Finding> findings, Set<String> starIds) {
        Map<String, Boolean> checkedById = new HashMap<>();
        for (Finding f : findings) {
            if (!starIds.contains(f.questionId())) continue;
            checkedById.merge(f.questionId(), f.status() != Status.NOT_CHECKED, Boolean::logicalOr);
        }
        if (checkedById.isEmpty()) return BigDecimal.ZERO.setScale(COVERAGE_SCALE);
        long checked = checkedById.values().stream().filter(Boolean::booleanValue).count();
        return BigDecimal.valueOf(checked).divide(BigDecimal.valueOf(checkedById.size()), COVERAGE_SCALE,
                RoundingMode.DOWN);
    }

    /** MOVE is checked before coverage: unchecked questions can only add risk, so a confirmed MOVE stands. */
    private static Verdict verdict(boolean stop, int risk, BigDecimal coverage, Thresholds t) {
        if (stop || risk >= t.moveMinRisk()) return Verdict.MOVE;
        if (coverage.compareTo(BigDecimal.valueOf(t.minCoverage())) < 0) return Verdict.NOT_ENOUGH_DATA;
        if (risk >= t.adjustMinRisk()) return Verdict.ADJUST;
        return Verdict.GOOD;
    }
}
