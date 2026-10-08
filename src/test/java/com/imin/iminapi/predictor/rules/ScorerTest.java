package com.imin.iminapi.predictor.rules;

import com.imin.iminapi.predictor.rules.DateResult.ScoreLine;
import com.imin.iminapi.predictor.rules.DateResult.Verdict;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.rules.QuestionBank.Thresholds;
import com.imin.iminapi.predictor.rules.QuestionBank.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScorerTest {

    private static final Thresholds T = new Thresholds(3, 7, 0.6, 4);

    static Question q(String id, boolean star, SourceKind source, int weight, boolean stop, Kind... kinds) {
        return new Question(id, "test", star, source, EnumSet.of(kinds[0], kinds), weight, 3, Window.NIGHT, stop,
                Set.of("FR"), Set.of(), Map.of(), "predictor.q.t", List.of(), Set.of());
    }

    static Question risk(String id, int weight) {
        return q(id, true, SourceKind.STRUCTURED, weight, false, Kind.RISK);
    }

    static Question opp(String id, int weight) {
        return q(id, true, SourceKind.STRUCTURED, weight, false, Kind.OPPORTUNITY);
    }

    private static Finding foundRisk(String id, int weight, int strength) {
        return Finding.found(risk(id, weight), Kind.RISK, strength, Map.of(), null);
    }

    private static Finding foundOpp(String id, int weight, int strength) {
        return Finding.found(opp(id, weight), Kind.OPPORTUNITY, strength, Map.of(), null);
    }

    private static Set<String> stars(List<Finding> fs) {
        Set<String> out = new java.util.HashSet<>();
        fs.forEach(f -> out.add(f.questionId()));
        return out;
    }

    private static DateResult score(List<Finding> fs) {
        return Scorer.score(fs, stars(fs), T);
    }

    @Test
    void singleFindingCappedAt4() {
        DateResult r = score(List.of(foundRisk("1.1", 2, 3)));

        assertThat(r.riskScore()).isEqualTo(4);
        assertThat(r.oppScore()).isZero();
        assertThat(r.breakdown()).containsExactly(new ScoreLine("1.1", Kind.RISK, SourceKind.STRUCTURED, 4));
        assertThat(r.verdict()).isEqualTo(Verdict.ADJUST);
    }

    @Test
    void pointsZeroForRowsThatAreNotFound() {
        assertThat(Scorer.points(Finding.clear(risk("1.1", 3)), T)).isZero();
        assertThat(Scorer.points(Finding.notChecked(risk("1.1", 3), "no_data"), T)).isZero();
        assertThat(Scorer.points(foundRisk("1.1", 1, 3), T)).isEqualTo(3);
    }

    @Test
    void riskAndOppEachCappedAt10() {
        List<Finding> fs = List.of(foundRisk("1.1", 2, 3), foundRisk("1.2", 2, 3), foundRisk("1.3", 2, 3),
                foundOpp("2.1", 2, 3), foundOpp("2.2", 2, 3), foundOpp("2.3", 2, 3));

        DateResult r = score(fs);

        assertThat(r.riskScore()).isEqualTo(10);
        assertThat(r.oppScore()).isEqualTo(10);
        assertThat(r.breakdown()).hasSize(6);
        assertThat(r.breakdown().stream().mapToInt(ScoreLine::points).sum()).isEqualTo(24);
        assertThat(r.verdict()).isEqualTo(Verdict.MOVE);
    }

    @Test
    void opportunitiesNeverReduceRisk() {
        DateResult r = score(List.of(foundRisk("1.1", 2, 2), foundOpp("2.1", 2, 2), foundOpp("2.2", 2, 2)));

        assertThat(r.riskScore()).isEqualTo(4);
        assertThat(r.oppScore()).isEqualTo(8);
        assertThat(r.verdict()).isEqualTo(Verdict.ADJUST);
    }

    @Test
    void breakdownSortedByPointsThenQuestionId() {
        DateResult r = score(List.of(foundRisk("1.2", 1, 1), foundOpp("2.1", 1, 3), foundRisk("1.1", 1, 1),
                Finding.clear(risk("3.1", 2))));

        assertThat(r.breakdown()).extracting(ScoreLine::questionId).containsExactly("2.1", "1.1", "1.2");
        assertThat(r.breakdown()).extracting(ScoreLine::points).containsExactly(3, 1, 1);
    }

    @Test
    void stopFactorForcesMove() {
        Question stopQ = q("4.3", true, SourceKind.STRUCTURED, 1, true, Kind.RISK);
        DateResult r = score(List.of(Finding.found(stopQ, Kind.RISK, 2, Map.of(), null)));

        assertThat(r.riskScore()).isEqualTo(2);
        assertThat(r.verdict()).isEqualTo(Verdict.MOVE);
    }

    @Test
    void stopFactorBeatsLowCoverage() {
        Question stopQ = q("4.3", true, SourceKind.STRUCTURED, 1, true, Kind.RISK);
        List<Finding> fs = List.of(Finding.found(stopQ, Kind.RISK, 1, Map.of(), null),
                Finding.notChecked(risk("5.1", 2), "no_data"), Finding.notChecked(risk("7.1", 2), "no_data"));

        DateResult r = score(fs);

        assertThat(r.coverage()).isEqualByComparingTo("0.333");
        assertThat(r.verdict()).isEqualTo(Verdict.MOVE);
    }

    @Test
    void riskAt7BeatsLowCoverage() {
        List<Finding> fs = List.of(foundRisk("1.1", 2, 2), foundRisk("1.2", 1, 3),
                Finding.notChecked(risk("5.1", 2), "no_data"), Finding.notChecked(risk("7.1", 2), "no_data"),
                Finding.notChecked(risk("3.2", 2), "no_data"));

        DateResult r = score(fs);

        assertThat(r.riskScore()).isEqualTo(7);
        assertThat(r.coverage()).isEqualByComparingTo("0.400");
        assertThat(r.verdict()).isEqualTo(Verdict.MOVE);
    }

    @Test
    void coverageBelow06IsNotEnoughData() {
        List<Finding> fs = new ArrayList<>();
        fs.add(Finding.notChecked(risk("7.1", 2), "no_data"));
        fs.add(Finding.notChecked(risk("5.1", 2), "no_data"));
        fs.add(Finding.notChecked(risk("3.2", 2), "no_data"));
        fs.add(Finding.notChecked(risk("6.1", 2), "no_data"));
        fs.add(Finding.notChecked(risk("6.2", 2), "no_data"));
        fs.add(Finding.clear(risk("4.1", 2)));
        fs.add(Finding.clear(risk("4.2", 2)));
        fs.add(Finding.clear(risk("1.1", 2)));

        DateResult r = score(fs);

        assertThat(r.riskScore()).isZero();
        assertThat(r.coverage()).isEqualByComparingTo("0.375");
        assertThat(r.verdict()).isEqualTo(Verdict.NOT_ENOUGH_DATA);
    }

    @Test
    void coverageCountsIdOnceWhenAnySourceChecked() {
        Question internal = q("2.1", true, SourceKind.INTERNAL, 3, false, Kind.RISK);
        Question organizer = q("2.1", true, SourceKind.ORGANIZER, 3, false, Kind.RISK);
        List<Finding> fs = List.of(Finding.notChecked(internal, "no_data"), Finding.clear(organizer),
                Finding.clear(risk("4.1", 2)));

        DateResult r = score(fs);

        assertThat(r.coverage()).isEqualByComparingTo("1.000");
        assertThat(r.verdict()).isEqualTo(Verdict.GOOD);
    }

    @Test
    void nonStarQuestionsIgnoredInCoverage() {
        List<Finding> fs = List.of(Finding.clear(risk("4.1", 2)),
                Finding.notChecked(q("4.4", false, SourceKind.STRUCTURED, 2, false, Kind.RISK), "no_data"),
                Finding.notChecked(q("8.1", false, SourceKind.STRUCTURED, 2, false, Kind.RISK), "no_data"));

        DateResult r = Scorer.score(fs, Set.of("4.1"), T);

        assertThat(r.coverage()).isEqualByComparingTo("1.000");
        assertThat(r.coverage().scale()).isEqualTo(3);
        assertThat(r.verdict()).isEqualTo(Verdict.GOOD);
    }

    @Test
    void coverageRoundsDownAndExactly06Scores() {
        DateResult twoThirds = score(List.of(Finding.clear(risk("1.1", 1)), Finding.clear(risk("1.2", 1)),
                Finding.notChecked(risk("1.3", 1), "no_data")));
        DateResult threeFifths = score(List.of(Finding.clear(risk("1.1", 1)), Finding.clear(risk("1.2", 1)),
                Finding.clear(risk("1.3", 1)), Finding.notChecked(risk("1.4", 1), "no_data"),
                Finding.notChecked(risk("1.5", 1), "no_data")));

        assertThat(twoThirds.coverage()).isEqualTo(new BigDecimal("0.666"));
        assertThat(threeFifths.coverage()).isEqualTo(new BigDecimal("0.600"));
        assertThat(threeFifths.verdict()).isEqualTo(Verdict.GOOD);
    }

    @Test
    void noStarRowsIsNotEnoughData() {
        DateResult nonStar = Scorer.score(List.of(Finding.clear(risk("4.4", 2))), Set.of("4.1"), T);
        DateResult empty = Scorer.score(List.of(), Set.of("4.1"), T);

        assertThat(nonStar.coverage()).isEqualTo(new BigDecimal("0.000"));
        assertThat(nonStar.verdict()).isEqualTo(Verdict.NOT_ENOUGH_DATA);
        assertThat(empty.coverage()).isEqualTo(new BigDecimal("0.000"));
        assertThat(empty.verdict()).isEqualTo(Verdict.NOT_ENOUGH_DATA);
        assertThat(empty.breakdown()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"2,GOOD", "3,ADJUST", "6,ADJUST", "7,MOVE"})
    void thresholdEdges3And7(int risk, Verdict expected) {
        List<Finding> fs = new ArrayList<>();
        int left = risk;
        for (int i = 1; left > 0; i++) {
            int s = Math.min(3, left);
            fs.add(foundRisk("1." + i, 1, s));
            left -= s;
        }

        DateResult r = score(fs);

        assertThat(r.riskScore()).isEqualTo(risk);
        assertThat(r.coverage()).isEqualByComparingTo("1.000");
        assertThat(r.verdict()).isEqualTo(expected);
    }

    @Test
    void bankOverloadUsesStarFlagsAndBankThresholds() {
        Question star = risk("4.1", 2);
        Question starOrganizer = q("4.1", false, SourceKind.ORGANIZER, 2, false, Kind.RISK);
        Question nonStar = q("4.4", false, SourceKind.STRUCTURED, 2, false, Kind.RISK);
        QuestionBank bank = new QuestionBank(2, 1, new Thresholds(1, 9, 0.6, 2), List.of(star, starOrganizer, nonStar),
                Map.of());

        DateResult r = Scorer.score(List.of(Finding.found(starOrganizer, Kind.RISK, 2, Map.of(), null),
                Finding.notChecked(nonStar, "no_data")), bank);

        assertThat(r.riskScore()).isEqualTo(2);
        assertThat(r.coverage()).isEqualByComparingTo("1.000");
        assertThat(r.verdict()).isEqualTo(Verdict.ADJUST);
    }

    @Test
    void verdictDbValuesAreLowerCase() {
        assertThat(Verdict.GOOD.dbValue()).isEqualTo("good");
        assertThat(Verdict.NOT_ENOUGH_DATA.dbValue()).isEqualTo("not_enough_data");
    }
}
