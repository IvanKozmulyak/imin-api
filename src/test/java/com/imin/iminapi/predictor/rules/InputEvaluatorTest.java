package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.Finding.Status;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import org.junit.jupiter.api.Test;

import static com.imin.iminapi.predictor.rules.RuleFixtures.TODAY;
import static com.imin.iminapi.predictor.rules.RuleFixtures.in;
import static com.imin.iminapi.predictor.rules.RuleFixtures.q;
import static org.assertj.core.api.Assertions.assertThat;

class InputEvaluatorTest {

    private final InputEvaluator evaluator = new InputEvaluator();
    private final Question q = q("10.1", SourceKind.INPUT);

    @Test
    void shortLeadSmallEventFound() {
        Finding f = evaluator.evaluate(q, in().capacity(300).build(), TODAY.plusDays(14));

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.kind()).isEqualTo(Kind.RISK);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.facts()).containsEntry("leadDays", 14L).containsEntry("minDays", 21)
                .containsEntry("capacityKnown", true);
    }

    @Test
    void veryShortLeadStaysAtStrength2() {
        Finding f = evaluator.evaluate(q, in().capacity(300).build(), TODAY.plusDays(10));

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.stopFactor()).isFalse();
    }

    @Test
    void bigCapacityUses60() {
        Finding f = evaluator.evaluate(q, in().capacity(800).build(), TODAY.plusDays(45));

        assertThat(f.status()).isEqualTo(Status.FOUND);
        assertThat(f.facts()).containsEntry("minDays", 60);
        assertThat(evaluator.evaluate(q, in().capacity(799).build(), TODAY.plusDays(45)).status()).isEqualTo(Status.CLEAR);
    }

    @Test
    void nullCapacityUsesSmallThreshold() {
        Finding f = evaluator.evaluate(q, in().capacity(null).build(), TODAY.plusDays(14));

        assertThat(f.facts()).containsEntry("minDays", 21).containsEntry("capacityKnown", false);
    }

    @Test
    void enoughLeadClear() {
        Finding f = evaluator.evaluate(q, in().capacity(300).build(), TODAY.plusDays(21));

        assertThat(f.status()).isEqualTo(Status.CLEAR);
        assertThat(f.strength()).isZero();
    }
}
