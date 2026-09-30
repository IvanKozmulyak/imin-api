package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.DateCheckInput.KnownEvent;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Competition the organizer told us about: 2.1 (same night, ±1) and 2.2 (same week, 2..7 days away). */
@Component
public class OrganizerEvaluator implements QuestionEvaluator {

    @Override
    public SourceKind source() { return SourceKind.ORGANIZER; }

    @Override
    public Set<String> questionIds() { return Set.of("2.1", "2.2"); }

    @Override
    public Finding evaluate(Question q, DateCheckInput in, LocalDate date) {
        if (in.knownEvents() == null) return Finding.notChecked(q, "not_provided");
        boolean night = q.id().equals("2.1");
        List<KnownEvent> matches = in.knownEvents().stream()
                .filter(e -> {
                    long delta = Math.abs(ChronoUnit.DAYS.between(date, e.date()));
                    return night ? delta <= 1 : delta > 1 && delta <= 7;
                })
                .toList();
        if (matches.isEmpty()) return Finding.clear(q);
        KnownEvent strongest = matches.stream()
                .max(Comparator.comparingInt(KnownEvent::strength)
                        .thenComparing(e -> -Math.abs(ChronoUnit.DAYS.between(date, e.date()))))
                .orElseThrow();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("date", strongest.date().toString());
        facts.put("name", strongest.name());
        facts.put("venue", strongest.venue());
        return Finding.found(q, Kind.RISK, Math.min(strongest.strength(), 2), facts, null);
    }
}
