package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.TimingArm;
import com.imin.iminapi.audienceplan.engine.ActionPlanner.Action;
import com.imin.iminapi.audienceplan.engine.ActionPlanner.ActionType;
import com.imin.iminapi.audienceplan.engine.ActionPlanner.ArmDate;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Person;
import com.imin.iminapi.audienceplan.engine.CoverageVerdict.Coverage;
import com.imin.iminapi.audienceplan.engine.CoverageVerdict.Verdict;
import com.imin.iminapi.audienceplan.engine.GapCalculator.CountRange;
import com.imin.iminapi.audienceplan.engine.GapCalculator.Gap;
import com.imin.iminapi.audienceplan.engine.GapCalculator.Reach;
import com.imin.iminapi.audienceplan.engine.GapCalculator.ReachStatus;
import com.imin.iminapi.audienceplan.engine.ModeSelector.Mode;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Input;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.NoCapacityException;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Plan;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.PlanSegment;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Tickets;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Tier;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Confidence;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Fit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PlanCalculatorTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final String HOUSE = "house & techno";
    private static final String JAZZ = "jazz & acoustic";
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Instant NOW = ZonedDateTime.of(2026, 9, 26, 10, 0, 0, 0, PARIS).toInstant();
    private static final Instant START = ZonedDateTime.of(2026, 10, 24, 23, 0, 0, 0, PARIS).toInstant();
    private static final Instant ON_SALE = ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, PARIS).toInstant();
    private static final LocalDate LAUNCH = LocalDate.of(2026, 10, 1);
    private static final LocalDate D3 = LocalDate.of(2026, 10, 21);
    private static final List<Tier> THREE_HUNDRED = List.of(new Tier(200, true), new Tier(100, true));
    private static final double EPS = 1e-9;

    private static final PlanCalculator CALC =
            new PlanCalculator(PlanFixtures.LOGIC, new ResponseModel(PlanFixtures.LOGIC, CalibrationSource.NONE));

    private static List<Person> people(String classKey, Map<String, Double> taste, int n) {
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Person(UUID.randomUUID(), classKey, taste, 0, false, false, 0, 0));
        return out;
    }

    private static List<Person> fixturePeople() {
        List<Person> all = new ArrayList<>();
        all.addAll(people("loyal", Map.of(HOUSE, 1.0), 40));
        all.addAll(people("repeat", Map.of(HOUSE, 1.0), 70));
        all.addAll(people("first_timer", Map.of(HOUSE, 1.0), 235));
        return all;
    }

    private static Input input(List<Tier> tiers, int pct, double tpo, List<Person> mailable, CountRange tribe) {
        return new Input(ORG, HOUSE, tiers, pct, tpo, gateCounts(), mailable, NOW, START, PARIS, ON_SALE, tribe);
    }

    private static Map<String, Integer> gateCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("legacy_unproven", 12);
        m.put("unsubscribed", 3);
        return m;
    }

    private static void assertSegment(PlanSegment s, String classKey, int mailable, Band rate, Band raw, Tickets shown) {
        assertThat(s.classKey()).isEqualTo(classKey);
        assertThat(s.fit()).isEqualTo(Fit.SAME);
        assertThat(s.mailable()).isEqualTo(mailable);
        assertThat(s.confidence()).isEqualTo(Confidence.PRIOR);
        assertThat(s.ticketsPerOrder()).isEqualTo(1.6);
        assertThat(s.rate().low()).isCloseTo(rate.low(), within(EPS));
        assertThat(s.rate().mid()).isCloseTo(rate.mid(), within(EPS));
        assertThat(s.rate().high()).isCloseTo(rate.high(), within(EPS));
        assertThat(s.rawExpected().low()).isCloseTo(raw.low(), within(EPS));
        assertThat(s.rawExpected().mid()).isCloseTo(raw.mid(), within(EPS));
        assertThat(s.rawExpected().high()).isCloseTo(raw.high(), within(EPS));
        assertThat(s.expected()).isEqualTo(shown);
    }

    // ── checked fixtures ────────────────────────────────────────────────────────

    @Test
    void warmFixture_endToEnd() {
        Plan p = CALC.calculate(input(THREE_HUNDRED, 85, 1.6, fixturePeople(), null));

        assertThat(p.mode()).isEqualTo(Mode.WARM);
        assertThat(p.capacity()).isEqualTo(300);
        assertThat(p.targetTickets()).isEqualTo(255);
        assertThat(p.mailable()).isEqualTo(345);
        assertThat(p.segments()).hasSize(3);
        assertSegment(p.segments().get(0), "loyal", 40, new Band(.12, .25, .40), new Band(7.68, 16.00, 25.60),
                new Tickets(8, 16, 26));
        assertSegment(p.segments().get(1), "repeat", 70, new Band(.06, .12, .20), new Band(6.72, 13.44, 22.40),
                new Tickets(7, 13, 22));
        assertSegment(p.segments().get(2), "first_timer", 235, new Band(.03, .06, .12), new Band(11.28, 22.56, 45.12),
                new Tickets(11, 23, 45));
        assertThat(p.expected()).isEqualTo(new Tickets(26, 52, 93));
        assertThat(p.coverage()).isEqualTo(new Coverage(0.10, 0.20, 0.36, Verdict.MEDIUM));
        assertThat(p.gap()).isEqualTo(new Gap(162, 229));
        assertThat(p.reachNeeded().metaAds()).isEqualTo(new Reach(ReachStatus.UNVERIFIED, null, null));
        assertThat(p.reachNeeded().instagramOrganic()).isEqualTo(new Reach(ReachStatus.UNKNOWN, null, null));
        assertThat(p.gapExceedsTribe()).isNull();
        assertThat(p.smallGroupsNotShown()).isZero();
        assertThat(p.otherGenreInvited()).isFalse();
        assertThat(p.otherGenreHeldBack()).isZero();
        assertThat(p.exclusions()).containsEntry("legacy_unproven", 12).containsEntry("unsubscribed", 3)
                .containsEntry(Exclusions.BOUGHT_THIS_EVENT, 0).containsEntry(Exclusions.SMALL_GROUP, 0);
        assertThat(p.timing().daysToEvent()).isEqualTo(28);
        assertThat(p.timing().launchDate()).isEqualTo(LAUNCH);
        assertThat(p.timing().d3Date()).isEqualTo(D3);
        List<ArmDate> arms = List.of(new ArmDate(TimingArm.LAUNCH, LAUNCH), new ArmDate(TimingArm.D3, D3));
        assertThat(p.actions()).containsExactly(
                new Action(ActionType.INVITE, "loyal", Fit.SAME, arms, 0),
                new Action(ActionType.INVITE, "repeat", Fit.SAME, arms, 15),
                new Action(ActionType.INVITE, "first_timer", Fit.SAME, arms, 15));
        assertThat(p.assumptions()).isEqualTo(new PlanCalculator.Assumptions(85, 1.6));
        assertThat(p.versions()).isEqualTo(new PlanCalculator.Versions(1, 1));
    }

    @Test
    void coverageHigh_isReadFrom93_not93point12() {
        Plan p = CALC.calculate(input(THREE_HUNDRED, 85, 1.6, fixturePeople(), null));

        double rawHigh = p.segments().stream().mapToDouble(s -> s.rawExpected().high()).sum();
        assertThat(rawHigh).isCloseTo(93.12, within(EPS));
        assertThat(p.expected()).isEqualTo(new Tickets(26, 52, 93));
        assertThat(p.coverage().high()).isEqualTo(0.36);
    }

    @Test
    void coverageComesFromTheRoundedTotals_notTheRawSum() {
        // 59 loyal same at 1 ticket per order: raw mid 14.75 would show 0.14; the rounded total 15 shows 0.15.
        Plan p = CALC.calculate(input(List.of(new Tier(100, true)), 100, 1.0, people("loyal", Map.of(HOUSE, 1.0), 59),
                null));
        double rawMid = p.segments().stream().mapToDouble(s -> s.rawExpected().mid()).sum();
        assertThat(rawMid).isCloseTo(14.75, within(EPS));
        assertThat(new BigDecimal(rawMid / 100).setScale(2, RoundingMode.DOWN).doubleValue()).isEqualTo(0.14);
        assertThat(p.expected().mid()).isEqualTo(15);
        assertThat(p.coverage().mid()).isEqualTo(0.15);
        assertThat(p.coverage().verdict()).isEqualTo(Verdict.MEDIUM);
    }

    @Test
    void coldFixture_noMailable() {
        Plan p = CALC.calculate(input(THREE_HUNDRED, 85, 1.6, List.of(), null));

        assertThat(p.mode()).isEqualTo(Mode.COLD);
        assertThat(p.mailable()).isZero();
        assertThat(p.expected()).isNull();
        assertThat(p.coverage()).isEqualTo(new Coverage(null, null, null, Verdict.COLD));
        assertThat(p.gap()).isEqualTo(new Gap(255, 255));
        assertThat(p.segments()).isEmpty();
        assertThat(p.actions()).containsExactly(new Action(ActionType.IMPORT_WITH_PROOF, null, null, List.of(), null));
        assertThat(p.exclusions()).containsEntry("legacy_unproven", 12).containsEntry(Exclusions.NO_CLASS, 0);
    }

    @Test
    void coldWithSomeMailable_showsNoSegments_butCountsThem() {
        Plan p = CALC.calculate(input(THREE_HUNDRED, 85, 1.6, people("loyal", Map.of(HOUSE, 1.0), 49), null));

        assertThat(p.mode()).isEqualTo(Mode.COLD);
        assertThat(p.mailable()).isEqualTo(49);
        assertThat(p.segments()).isEmpty();
        assertThat(p.expected()).isNull();
        assertThat(p.coverage()).isEqualTo(new Coverage(null, null, null, Verdict.COLD));
        assertThat(p.gap()).isEqualTo(new Gap(255, 255));
        assertThat(p.otherGenreInvited()).isFalse();
    }

    @Test
    void duplicatedPeople_countOnceTowardTheMode() {
        List<Person> people = people("loyal", Map.of(HOUSE, 1.0), 49);
        people.add(people.get(0));
        assertThat(CALC.calculate(input(THREE_HUNDRED, 85, 1.6, people, null)).mode()).isEqualTo(Mode.COLD);
    }

    @Test
    void hotMode_from500Mailable() {
        Plan p = CALC.calculate(input(THREE_HUNDRED, 85, 1.6, people("first_timer", Map.of(HOUSE, 1.0), 500), null));
        assertThat(p.mode()).isEqualTo(Mode.HOT);
        assertThat(p.segments()).singleElement().extracting(PlanSegment::mailable).isEqualTo(500);
    }

    // ── capacity and target ────────────────────────────────────────────────

    @Test
    void capacity_sumsEnabledTiersOnly() {
        assertThat(PlanCalculator.capacity(List.of(new Tier(200, true), new Tier(50, false), new Tier(100, true))))
                .isEqualTo(300);
    }

    @Test
    void capacityZero_isRefused() {
        assertThatThrownBy(() -> CALC.calculate(input(List.of(new Tier(300, false)), 85, 1.6, fixturePeople(), null)))
                .isInstanceOf(NoCapacityException.class);
        assertThatThrownBy(() -> CALC.calculate(input(List.of(), 85, 1.6, fixturePeople(), null)))
                .isInstanceOf(NoCapacityException.class);
    }

    @Test
    void targetRoundingToZero_isRefused() {
        assertThatThrownBy(() -> CALC.calculate(input(List.of(new Tier(1, true)), 1, 1.6, List.of(), null)))
                .isInstanceOf(NoCapacityException.class);
    }

    @Test
    void target_300Is255_301Is256() {
        assertThat(PlanCalculator.target(300, 85)).isEqualTo(255);
        assertThat(PlanCalculator.target(301, 85)).isEqualTo(256);
        assertThat(CALC.calculate(input(List.of(new Tier(301, true)), 85, 1.6, List.of(), null)).targetTickets())
                .isEqualTo(256);
    }

    @Test
    void targetPctOutside1To100_isRefused() {
        assertThatThrownBy(() -> PlanCalculator.target(300, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanCalculator.target(300, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PlanCalculator.target(300, 100)).isEqualTo(300);
    }

    @Test
    void gapIsNeverNegative_whenTheListCoversTheTarget() {
        Plan p = CALC.calculate(input(List.of(new Tier(5, true)), 100, 1.6, fixturePeople(), null));
        assertThat(p.targetTickets()).isEqualTo(5);
        assertThat(p.expected()).isEqualTo(new Tickets(26, 52, 93));
        assertThat(p.gap()).isEqualTo(new Gap(0, 0));
        assertThat(p.coverage().verdict()).isEqualTo(Verdict.STRONG);
    }

    // ── verdict and the other-genre gate share one rule ────────────────────

    @Test
    void verdictAndOtherGenreGate_149Of1000_isWeakAndInvitesOther() {
        // loyal same at 1 ticket per order: 0.25 mid each, 596 → 149.
        Plan p = CALC.calculate(input(List.of(new Tier(1000, true)), 100, 1.0, people("loyal", Map.of(HOUSE, 1.0), 596),
                null));
        assertThat(p.expected().mid()).isEqualTo(149);
        assertThat(p.coverage().mid()).isEqualTo(0.14);
        assertThat(p.coverage().verdict()).isEqualTo(Verdict.WEAK);
        assertThat(p.otherGenreInvited()).isTrue();
        assertThat(p.actions()).extracting(Action::type).contains(ActionType.IMPORT_WITH_PROOF);
    }

    @Test
    void verdictAndOtherGenreGate_150Of1000_isMediumAndHoldsOtherBack() {
        List<Person> people = people("loyal", Map.of(HOUSE, 1.0), 600);
        people.addAll(people("loyal", Map.of(JAZZ, 1.0), 10));
        Plan p = CALC.calculate(input(List.of(new Tier(1000, true)), 100, 1.0, people, null));
        assertThat(p.expected().mid()).isEqualTo(150);
        assertThat(p.coverage().mid()).isEqualTo(0.15);
        assertThat(p.coverage().verdict()).isEqualTo(Verdict.MEDIUM);
        assertThat(p.otherGenreInvited()).isFalse();
        assertThat(p.otherGenreHeldBack()).isEqualTo(10);
        assertThat(p.actions()).extracting(Action::type).doesNotContain(ActionType.IMPORT_WITH_PROOF);
    }

    // ── unknown genre fit (members without taste) ──────────────────────────

    @Test
    void unknownFitSegment_isCarriedIntoTotalsAndInvites() {
        List<Person> people = fixturePeople();
        people.addAll(people("imported", Map.of(), 20));

        Plan p = CALC.calculate(input(THREE_HUNDRED, 85, 1.6, people, null));

        PlanSegment imported = p.segments().stream().filter(s -> s.classKey().equals("imported")).findFirst().orElseThrow();
        assertThat(imported.fit()).isEqualTo(Fit.UNKNOWN);
        // 20 × [0.002, 0.006, 0.015] × 1.0 (unknown) × 1.6 = 0.064 / 0.192 / 0.48
        assertThat(imported.rawExpected().mid()).isCloseTo(0.192, within(EPS));
        assertThat(imported.expected()).isEqualTo(new Tickets(0, 0, 0));
        // 25.744 / 52.192 / 93.60 → 26 / 52 / 94
        assertThat(p.expected()).isEqualTo(new Tickets(26, 52, 94));
        assertThat(p.mailable()).isEqualTo(365);
        assertThat(p.actions()).anySatisfy(a -> {
            assertThat(a.classKey()).isEqualTo("imported");
            assertThat(a.fit()).isEqualTo(Fit.UNKNOWN);
        });
    }

    // ── target realism ─────────────────────────────────────────────────────

    @Test
    void gapOf700AgainstMetzRegulars606_isUnrealistic() {
        Plan p = CALC.calculate(input(List.of(new Tier(824, true)), 85, 1.6, List.of(), new CountRange(462, 606)));
        assertThat(p.gap()).isEqualTo(new Gap(700, 700));
        assertThat(p.gapExceedsTribe()).isTrue();
        assertThat(p.actions()).extracting(Action::type)
                .containsExactly(ActionType.IMPORT_WITH_PROOF, ActionType.RETHINK_TARGET);
    }

    @Test
    void gapOf162AgainstMetzRegulars606_isRealistic() {
        Plan p = CALC.calculate(input(THREE_HUNDRED, 85, 1.6, fixturePeople(), new CountRange(462, 606)));
        assertThat(p.gapExceedsTribe()).isFalse();
        assertThat(p.actions()).extracting(Action::type).doesNotContain(ActionType.RETHINK_TARGET);
    }
}
