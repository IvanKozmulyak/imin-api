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
import java.util.regex.Pattern;

/**
 * One {@link Finding} per applicable (question, source) for a date, in bank order: filtered by country, then postcode
 * prefix (city when no valid postcode) or city. Deterministic; fails at boot unless each question has one evaluator.
 */
@Service
public class RuleEngine {

    private record Key(SourceKind source, String id) {}

    /** Same postcode shape as {@code CalendarRegions}: five digits once whitespace is removed. */
    private static final Pattern FR_POSTCODE = Pattern.compile("\\d{5}");

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
            if (!q.postcodePrefixes().isEmpty()) {
                if (!inRegion(q, in.postalCode(), cityKey)) continue;
            } else if (!q.cities().isEmpty() && !listsCity(q, cityKey)) {
                continue;
            }
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

    /** The postcode decides when it is valid; only a missing or malformed one falls back to the city list. */
    private static boolean inRegion(Question q, String postalCode, String cityKey) {
        String pc = postalCode == null ? "" : postalCode.replaceAll("\\s+", "");
        if (FR_POSTCODE.matcher(pc).matches()) return q.postcodePrefixes().contains(pc.substring(0, 2));
        return listsCity(q, cityKey);
    }

    private static boolean listsCity(Question q, String cityKey) {
        return q.cities().stream().map(EventNormalization::cityKey).anyMatch(cityKey::equals);
    }
}
