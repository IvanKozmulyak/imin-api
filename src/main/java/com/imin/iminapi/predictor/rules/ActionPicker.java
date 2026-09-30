package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.Finding.Status;
import com.imin.iminapi.predictor.rules.QuestionBank.Action;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Up to {@link #MAX_ACTIONS} dated actions from the found rows, highest points first (spec §2.3). */
public final class ActionPicker {

    public static final int MAX_ACTIONS = 3;

    private ActionPicker() {}

    public static List<ActionItem> pick(List<Finding> findings, QuestionBank bank, LocalDate eventDate,
                                        LocalDate today) {
        List<Finding> found = findings.stream()
                .filter(f -> f.status() == Status.FOUND)
                .sorted(Scorer.byPointsDesc(bank.thresholds()))
                .toList();
        // First hit per key wins, so it carries the highest points; past-due actions never take a slot.
        Map<String, ActionItem> byKey = new LinkedHashMap<>();
        for (Finding f : found) {
            Question q = question(bank, f);
            for (Action a : q.actions()) {
                if (a.when() != f.kind()) continue;
                LocalDate due = eventDate.minusDays(a.dueDays());
                if (due.isBefore(today)) continue;
                byKey.putIfAbsent(a.key(), new ActionItem(a.key(), due, f.questionId(), f.facts()));
            }
        }
        return byKey.values().stream().limit(MAX_ACTIONS).toList();
    }

    private static Question question(QuestionBank bank, Finding f) {
        return bank.questions().stream()
                .filter(q -> q.id().equals(f.questionId()) && q.source() == f.sourceKind())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no bank question " + f.questionId() + " " + f.sourceKind()));
    }
}
