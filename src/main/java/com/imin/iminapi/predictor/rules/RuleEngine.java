package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.util.EventNormalization;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Answers every non-web bank question for one candidate date: one {@link Finding} per (question, source),
 * in bank order. Deterministic, no LLM. Fails at boot when a question has no evaluator or two.
 */
@Service
public class RuleEngine {

    private record Key(SourceKind source, String id) {}

    private final QuestionBank bank;
    private final Map<Key, QuestionEvaluator> evaluators = new HashMap<>();

    public RuleEngine(QuestionBank bank, List<QuestionEvaluator> evaluators) {
        this.bank = bank;
        for (QuestionEvaluator e : evaluators) {
            for (String id : e.questionIds()) {
                QuestionEvaluator prev = this.evaluators.put(new Key(e.source(), id), e);
                if (prev != null) {
                    throw new IllegalStateException("predictor rule engine: question " + id + " (" + e.source()
                            + ") claimed by " + prev.getClass().getSimpleName() + " and " + e.getClass().getSimpleName());
                }
            }
        }
        for (Question q : bank.questions()) {
            if (q.source() != SourceKind.WEB && !this.evaluators.containsKey(new Key(q.source(), q.id()))) {
                throw new IllegalStateException("predictor rule engine: no evaluator for question " + q.id()
                        + " (" + q.source() + ")");
            }
        }
    }

    public List<Finding> evaluate(DateCheckInput in, LocalDate date) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(date, "date");
        if (date.isBefore(in.today())) {
            throw new IllegalArgumentException("candidate date " + date + " is before today " + in.today());
        }
        String cityKey = in.cityKey();
        List<Question> applicable = new ArrayList<>();
        for (Question q : bank.questionsFor(in.country())) {
            if (q.source() == SourceKind.WEB) continue;
            if (!q.cities().isEmpty()
                    && q.cities().stream().map(EventNormalization::cityKey).noneMatch(cityKey::equals)) continue;
            applicable.add(q);
        }
        // Group per evaluator so one can share a fetch across its questions, then restore bank order.
        Map<QuestionEvaluator, List<Question>> byEvaluator = new LinkedHashMap<>();
        for (Question q : applicable) {
            byEvaluator.computeIfAbsent(evaluators.get(new Key(q.source(), q.id())), e -> new ArrayList<>()).add(q);
        }
        Map<Question, Finding> answers = new IdentityHashMap<>();
        byEvaluator.forEach((e, qs) -> {
            List<Finding> out = e.evaluateAll(qs, in, date);
            if (out.size() != qs.size()) {
                throw new IllegalStateException(e.getClass().getSimpleName() + " answered " + out.size()
                        + " of " + qs.size() + " questions");
            }
            for (int i = 0; i < qs.size(); i++) answers.put(qs.get(i), out.get(i));
        });
        return applicable.stream().map(answers::get).toList();
    }
}
