package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.DateCheckInput.KnownEvent;
import com.imin.iminapi.predictor.rules.Finding.Status;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.imin.iminapi.predictor.rules.RuleFixtures.TODAY;
import static com.imin.iminapi.predictor.rules.RuleFixtures.in;
import static com.imin.iminapi.predictor.rules.RuleFixtures.q;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizerEvaluatorTest {

    private static final LocalDate D = TODAY.plusDays(40);
    private final OrganizerEvaluator evaluator = new OrganizerEvaluator();
    private final Question night = q("2.1", SourceKind.ORGANIZER);
    private final Question week = q("2.2", SourceKind.ORGANIZER);

    @Test
    void nullListNotChecked() {
        Finding f = evaluator.evaluate(night, in().known(null).build(), D);

        assertThat(f.status()).isEqualTo(Status.NOT_CHECKED);
        assertThat(f.facts()).containsEntry("reason", "not_provided");
    }

    @Test
    void emptyListClear() {
        assertThat(evaluator.evaluate(night, in().known(List.of()).build(), D).status()).isEqualTo(Status.CLEAR);
        assertThat(evaluator.evaluate(week, in().known(List.of()).build(), D).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void sameNightFound21() {
        DateCheckInput in = in().known(List.of(new KnownEvent("Rex Club night", D.plusDays(1), "Rex", 2))).build();

        Finding f = evaluator.evaluate(night, in, D);

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.sourceKind()).isEqualTo(SourceKind.ORGANIZER);
        assertThat(f.facts()).containsEntry("date", D.plusDays(1).toString()).containsEntry("name", "Rex Club night")
                .containsEntry("venue", "Rex");
        assertThat(evaluator.evaluate(week, in, D).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void weekFound22Not21() {
        DateCheckInput in = in().known(List.of(new KnownEvent("Festival", D.minusDays(5), null, 1))).build();

        assertThat(evaluator.evaluate(night, in, D).status()).isEqualTo(Status.CLEAR);
        Finding f = evaluator.evaluate(week, in, D);
        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.facts()).doesNotContainKey("venue");
        assertThat(evaluator.evaluate(week, in().known(List.of(new KnownEvent("Far", D.plusDays(8), null, 2))).build(), D)
                .status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void knownEventNeverStopFactor() {
        assertThat(night.stopFactor()).isFalse();
        DateCheckInput in = in().known(List.of(new KnownEvent("Big one", D, null, 2))).build();

        Finding f = evaluator.evaluate(night, in, D);

        assertThat(f.stopFactor()).isFalse();
        assertThat(f.strength()).isEqualTo(2);
        assertThatThrownBy(() -> new KnownEvent("Big one", D, null, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void strengthIsMaxOfMatches() {
        DateCheckInput in = in().known(List.of(
                new KnownEvent("Weak", D, null, 1),
                new KnownEvent("Strong", D.minusDays(1), "Club", 2))).build();

        Finding f = evaluator.evaluate(night, in, D);

        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("name", "Strong");
    }
}
