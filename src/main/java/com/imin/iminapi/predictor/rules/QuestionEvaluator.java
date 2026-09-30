package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Answers the bank questions of one source kind. {@link RuleEngine} refuses to start if a question has none. */
public interface QuestionEvaluator {

    SourceKind source();

    Set<String> questionIds();

    Finding evaluate(Question q, DateCheckInput in, LocalDate date);

    /** Answers several of this evaluator's questions for one date, in order; override to share one fetch. */
    default List<Finding> evaluateAll(List<Question> questions, DateCheckInput in, LocalDate date) {
        return questions.stream().map(q -> evaluate(q, in, date)).toList();
    }
}
