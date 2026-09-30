package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Questions answered from the form alone: 10.1, is there enough lead time to sell this night. */
@Component
public class InputEvaluator implements QuestionEvaluator {

    @Override
    public SourceKind source() { return SourceKind.INPUT; }

    @Override
    public Set<String> questionIds() { return Set.of("10.1"); }

    @Override
    public Finding evaluate(Question q, DateCheckInput in, LocalDate date) {
        long lead = ChronoUnit.DAYS.between(in.today(), date);
        boolean capacityKnown = in.capacity() != null;
        int min = capacityKnown && in.capacity() >= Params.of(q, "big_capacity_min").intValue()
                ? Params.of(q, "big_min_days").intValue()
                : Params.of(q, "small_min_days").intValue();
        if (lead >= min) return Finding.clear(q);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("leadDays", lead);
        facts.put("minDays", min);
        facts.put("capacityKnown", capacityKnown);
        return Finding.found(q, Kind.RISK, 2, facts, null);
    }
}
