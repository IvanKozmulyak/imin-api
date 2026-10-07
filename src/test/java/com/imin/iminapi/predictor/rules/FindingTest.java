package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.Finding.Status;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.rules.QuestionBank.Window;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.imin.iminapi.predictor.rules.RuleFixtures.q;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FindingTest {

    private static Question question(SourceKind source, boolean stop, int maxStrength) {
        return new Question("9.9", "test", false, source, EnumSet.of(Kind.RISK, Kind.OPPORTUNITY), 2, maxStrength,
                Window.NIGHT, stop, Set.of("FR"), Set.of(), Map.of(), "predictor.q.9_9", List.of());
    }

    @Test
    void clearAndNotCheckedHaveZeroStrength() {
        Question q = question(SourceKind.STRUCTURED, true, 3);

        Finding clear = Finding.clear(q);
        Finding notChecked = Finding.notChecked(q, "no_data");

        assertThat(clear.status()).isEqualTo(Status.CLEAR);
        assertThat(clear.strength()).isZero();
        assertThat(clear.stopFactor()).isFalse();
        assertThat(clear.kind()).isEqualTo(Kind.RISK);
        assertThat(notChecked.strength()).isZero();
        assertThat(notChecked.stopFactor()).isFalse();
        assertThat(notChecked.facts()).containsEntry("reason", "no_data");
        assertThatThrownBy(() -> new Finding("9.9", Kind.RISK, Status.CLEAR, 1, 2, SourceKind.STRUCTURED, Window.NIGHT,
                false, Map.of(), null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void strengthClampedToMaxStrength() {
        Finding f = Finding.found(q("2.1", SourceKind.ORGANIZER), Kind.RISK, 3, Map.of(), null);

        assertThat(f.strength()).isEqualTo(2);
        assertThat(f.weight()).isEqualTo(3);
        assertThat(f.window()).isEqualTo(Window.NIGHT);
        assertThat(Finding.found(question(SourceKind.STRUCTURED, false, 3), Kind.RISK, 0, Map.of(), null).strength())
                .isEqualTo(1);
        assertThat(Finding.found(question(SourceKind.INPUT, false, 3), Kind.RISK, 3, Map.of(), null).strength())
                .isEqualTo(2);
        assertThat(Finding.found(question(SourceKind.INTERNAL, false, 3), Kind.RISK, 3, Map.of(), null).strength())
                .isEqualTo(3);
    }

    @Test
    void stopFactorNeverOnOrganizerOrWeb() {
        assertThat(Finding.found(question(SourceKind.INTERNAL, true, 3), Kind.RISK, 3, Map.of(), null).stopFactor())
                .isTrue();
        assertThatThrownBy(() -> new Finding("2.1", Kind.RISK, Status.FOUND, 2, 3, SourceKind.ORGANIZER, Window.NIGHT,
                true, Map.of(), null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Finding("9.9", Kind.RISK, Status.FOUND, 3, 2, SourceKind.WEB, Window.NIGHT,
                false, Map.of(), null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singleOpportunityQuestionClearsAsOpportunity() {
        assertThat(Finding.clear(q("4.5", SourceKind.STRUCTURED)).kind()).isEqualTo(Kind.OPPORTUNITY);
    }

    @Test
    void foundRejectsAKindTheQuestionDoesNotHave() {
        assertThatThrownBy(() -> Finding.found(q("4.1", SourceKind.STRUCTURED), Kind.OPPORTUNITY, 2, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
