package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.QuestionBank.Action;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.rules.QuestionBank.Thresholds;
import com.imin.iminapi.predictor.rules.QuestionBank.Window;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionPickerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final String PROMO = "predictor.a.holiday_early_promo";

    private static Question q(String id, int weight, Set<Kind> kinds, Action... actions) {
        return new Question(id, "test", true, SourceKind.STRUCTURED, kinds, weight, 3, Window.NIGHT, false,
                Set.of("FR"), Set.of(), Map.of(), "predictor.q.t", List.of(actions));
    }

    private static Question risk(String id, int weight, Action... actions) {
        return q(id, weight, EnumSet.of(Kind.RISK), actions);
    }

    private static QuestionBank bank(Question... qs) {
        return new QuestionBank(2, 1, new Thresholds(3, 7, 0.6, 4), List.of(qs), Map.of());
    }

    private static Action a(String key, Kind when, int due) {
        return new Action(key, when, due);
    }

    @Test
    void actionsDedupedAndPastDueDropped() {
        Question q41 = risk("4.1", 2, a(PROMO, Kind.RISK, 28));
        Question q43 = risk("4.3", 3, a(PROMO, Kind.RISK, 28));
        Question q31 = risk("3.1", 1, a("predictor.a.book_early", Kind.RISK, 60));
        LocalDate event = TODAY.plusDays(40);
        List<Finding> fs = List.of(Finding.found(q41, Kind.RISK, 1, Map.of("name", "low"), null),
                Finding.found(q43, Kind.RISK, 3, Map.of("name", "high"), null),
                Finding.found(q31, Kind.RISK, 1, Map.of(), null));

        List<ActionItem> out = ActionPicker.pick(fs, bank(q41, q43, q31), event, TODAY);

        assertThat(out).containsExactly(new ActionItem(PROMO, event.minusDays(28), "4.3", Map.of("name", "high")));
    }

    @Test
    void pastDueDroppedBeforeTopThreeCap() {
        Question top = risk("1.1", 3, a("predictor.a.too_late", Kind.RISK, 90));
        Question b = risk("1.2", 1, a("predictor.a.b", Kind.RISK, 7));
        Question c = risk("1.3", 1, a("predictor.a.c", Kind.RISK, 7));
        Question d = risk("1.4", 1, a("predictor.a.d", Kind.RISK, 7));
        Question e = risk("1.5", 1, a("predictor.a.e", Kind.RISK, 7));
        List<Finding> fs = List.of(Finding.found(top, Kind.RISK, 3, Map.of(), null),
                Finding.found(b, Kind.RISK, 3, Map.of(), null), Finding.found(c, Kind.RISK, 2, Map.of(), null),
                Finding.found(d, Kind.RISK, 1, Map.of(), null), Finding.found(e, Kind.RISK, 1, Map.of(), null));

        List<ActionItem> out = ActionPicker.pick(fs, bank(top, b, c, d, e), TODAY.plusDays(30), TODAY);

        assertThat(out).extracting(ActionItem::key)
                .containsExactly("predictor.a.b", "predictor.a.c", "predictor.a.d");
        assertThat(ActionPicker.MAX_ACTIONS).isEqualTo(3);
    }

    @Test
    void dueTodayKeptAndDueDateIsEventMinusDays() {
        Question q = risk("4.1", 2, a(PROMO, Kind.RISK, 28), a("predictor.a.late", Kind.RISK, 7),
                a("predictor.a.missed", Kind.RISK, 29));
        LocalDate event = TODAY.plusDays(28);

        List<ActionItem> out = ActionPicker.pick(List.of(Finding.found(q, Kind.RISK, 2, Map.of(), null)), bank(q),
                event, TODAY);

        assertThat(out).extracting(ActionItem::key).containsExactly(PROMO, "predictor.a.late");
        assertThat(out).extracting(ActionItem::dueDate).containsExactly(TODAY, event.minusDays(7));
        assertThat(out).extracting(ActionItem::questionId).containsOnly("4.1");
    }

    @Test
    void onlyActionsMatchingFindingKind() {
        Question q42 = q("4.2", 2, EnumSet.of(Kind.RISK, Kind.OPPORTUNITY),
                a("predictor.a.eve_of_day_off_theme", Kind.OPPORTUNITY, 21), a(PROMO, Kind.RISK, 28));

        List<ActionItem> opp = ActionPicker.pick(List.of(Finding.found(q42, Kind.OPPORTUNITY, 2, Map.of(), null)),
                bank(q42), TODAY.plusDays(60), TODAY);
        List<ActionItem> risk = ActionPicker.pick(List.of(Finding.found(q42, Kind.RISK, 2, Map.of(), null)),
                bank(q42), TODAY.plusDays(60), TODAY);

        assertThat(opp).extracting(ActionItem::key).containsExactly("predictor.a.eve_of_day_off_theme");
        assertThat(risk).extracting(ActionItem::key).containsExactly(PROMO);
    }

    @Test
    void clearAndNotCheckedGiveNoActions() {
        Question q = risk("4.1", 2, a(PROMO, Kind.RISK, 28));

        List<ActionItem> out = ActionPicker.pick(List.of(Finding.clear(q), Finding.notChecked(q, "no_data")),
                bank(q), TODAY.plusDays(60), TODAY);

        assertThat(out).isEmpty();
    }

    @Test
    void unknownQuestionRejected() {
        Question inBank = risk("4.1", 2, a(PROMO, Kind.RISK, 28));
        Question otherSource = new Question("4.1", "test", true, SourceKind.ORGANIZER, EnumSet.of(Kind.RISK), 2, 2,
                Window.NIGHT, false, Set.of("FR"), Set.of(), Map.of(), "predictor.q.t", List.of());

        assertThatThrownBy(() -> ActionPicker.pick(List.of(Finding.found(otherSource, Kind.RISK, 1, Map.of(), null)),
                bank(inBank), TODAY.plusDays(60), TODAY)).isInstanceOf(IllegalArgumentException.class);
    }
}
