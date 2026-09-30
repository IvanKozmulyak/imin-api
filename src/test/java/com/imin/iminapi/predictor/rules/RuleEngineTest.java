package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.calendar.CalendarRegions;
import com.imin.iminapi.predictor.calendar.ReferenceCalendarService;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.imin.iminapi.predictor.rules.RuleFixtures.BANK;
import static com.imin.iminapi.predictor.rules.RuleFixtures.TODAY;
import static com.imin.iminapi.predictor.rules.RuleFixtures.in;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RuleEngineTest {

    private static final LocalDate D = TODAY.plusDays(40);

    /** Answers every claimed question clear and records what it was asked. */
    private static class Stub implements QuestionEvaluator {
        final SourceKind source;
        final Set<String> ids;
        final List<String> asked = new ArrayList<>();

        Stub(SourceKind source, String... ids) {
            this.source = source;
            this.ids = Set.of(ids);
        }

        @Override public SourceKind source() { return source; }
        @Override public Set<String> questionIds() { return ids; }
        @Override public Finding evaluate(Question q, DateCheckInput in, LocalDate date) {
            asked.add(q.id());
            return Finding.clear(q);
        }
    }

    private static List<QuestionEvaluator> stubsForShippedBank() {
        return List.of(
                new Stub(SourceKind.STRUCTURED, "4.1", "4.2", "4.3", "4.4", "4.5", "4.7", "5.1", "5.2", "7.1", "3.2", "10.3"),
                new Stub(SourceKind.INTERNAL, "2.1", "2.2", "2.7", "2.9", "10.2"),
                new Stub(SourceKind.ORGANIZER, "2.1", "2.2"),
                new Stub(SourceKind.INPUT, "10.1"));
    }

    private static QuestionBank bankWithWeb() {
        String bank = """
                version: 3
                thresholds: { adjust_min_risk: 3, move_min_risk: 7, min_coverage: 0.6, max_points_per_finding: 4 }
                questions:
                  - { id: "4.1", family: holidays, source: structured, kinds: [risk], weight: 2, window: week,
                      applies_when: { countries: [FR] }, template: predictor.q.4_1 }
                  - { id: "3.1", family: sport, source: web, kinds: [risk], weight: 2, max_strength: 2, window: night,
                      applies_when: { countries: [FR] }, template: predictor.q.3_1 }
                """;
        return QuestionBankLoader.parse(new ByteArrayInputStream(bank.getBytes(StandardCharsets.UTF_8)),
                RuleEngineTest.class.getResourceAsStream("/predictor/genre-profiles-v1.yaml"));
    }

    @Test
    void webQuestionsSkipped() {
        Stub structured = new Stub(SourceKind.STRUCTURED, "4.1");
        RuleEngine engine = new RuleEngine(bankWithWeb(), List.of(structured));

        List<Finding> out = engine.evaluate(in().build(), D);

        assertThat(out).extracting(Finding::questionId).containsExactly("4.1");
    }

    @Test
    void cityScopedQuestionOnlyForListedCity() {
        RuleEngine engine = new RuleEngine(BANK, stubsForShippedBank());

        assertThat(engine.evaluate(in().city("METZ", "FR", "57000").build(), D))
                .extracting(Finding::questionId).contains("4.5");
        assertThat(engine.evaluate(in().build(), D)).extracting(Finding::questionId).doesNotContain("4.5");
    }

    @Test
    void countryFilterApplied() {
        RuleEngine engine = new RuleEngine(BANK, stubsForShippedBank());

        List<String> ua = engine.evaluate(in().city("Kyiv", "UA", null).build(), D).stream()
                .map(Finding::questionId).toList();

        assertThat(ua).doesNotContain("4.3", "4.4", "3.2").contains("4.1", "7.1", "10.1");
    }

    @Test
    void missingEvaluatorFailsConstruction() {
        List<QuestionEvaluator> evaluators = new ArrayList<>(stubsForShippedBank());
        evaluators.remove(3);

        assertThatThrownBy(() -> new RuleEngine(BANK, evaluators))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10.1");
    }

    @Test
    void duplicateEvaluatorFailsConstruction() {
        List<QuestionEvaluator> evaluators = new ArrayList<>(stubsForShippedBank());
        evaluators.add(new Stub(SourceKind.INPUT, "10.1"));

        assertThatThrownBy(() -> new RuleEngine(BANK, evaluators))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claimed by");
    }

    @Test
    void everyShippedQuestionHasAnEvaluator() {
        List<QuestionEvaluator> real = List.of(
                new CalendarEvaluator(mock(ReferenceCalendarService.class), mock(CalendarRegions.class)),
                new InternalEvaluator(null, null, null, null),
                new OrganizerEvaluator(),
                new InputEvaluator());

        new RuleEngine(BANK, real);
    }

    @Test
    void pastCandidateRejected() {
        RuleEngine engine = new RuleEngine(BANK, stubsForShippedBank());

        assertThatThrownBy(() -> engine.evaluate(in().build(), TODAY.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(engine.evaluate(in().build(), TODAY)).isNotEmpty();
    }

    @Test
    void oneRowPerIdAndSource() {
        RuleEngine engine = new RuleEngine(BANK, stubsForShippedBank());

        List<Finding> out = engine.evaluate(in().build(), D);

        assertThat(out.stream().filter(f -> f.questionId().equals("2.1")).map(Finding::sourceKind))
                .containsExactly(SourceKind.INTERNAL, SourceKind.ORGANIZER);
        assertThat(out.stream().map(f -> f.questionId() + "|" + f.sourceKind()).distinct()).hasSize(out.size());
        // bank order kept even though evaluators answer in groups
        List<String> expected = BANK.questionsFor("FR").stream()
                .filter(q -> q.cities().isEmpty()).map(q -> q.id() + "|" + q.source()).toList();
        assertThat(out.stream().map(f -> f.questionId() + "|" + f.sourceKind()).toList()).isEqualTo(expected);
        assertThatThrownBy(() -> out.add(out.get(0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void evaluatorAnsweringTheWrongNumberFails() {
        QuestionEvaluator broken = new Stub(SourceKind.STRUCTURED, "4.1") {
            @Override public List<Finding> evaluateAll(List<Question> qs, DateCheckInput in, LocalDate date) {
                return List.of();
            }
        };
        RuleEngine engine = new RuleEngine(bankWithWeb(), List.of(broken));

        assertThatThrownBy(() -> engine.evaluate(in().build(), D)).isInstanceOf(IllegalStateException.class);
    }
}
