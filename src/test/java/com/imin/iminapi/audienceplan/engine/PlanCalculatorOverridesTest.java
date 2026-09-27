package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.engine.ActionPlanner.Action;
import com.imin.iminapi.audienceplan.engine.ActionPlanner.ActionType;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder.Person;
import com.imin.iminapi.audienceplan.engine.CoverageVerdict.Coverage;
import com.imin.iminapi.audienceplan.engine.CoverageVerdict.Verdict;
import com.imin.iminapi.audienceplan.engine.GapCalculator.Gap;
import com.imin.iminapi.audienceplan.engine.ModeSelector.Mode;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Input;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Plan;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.PlanSegment;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Tickets;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Tier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The organizer's left-out classes and the top-segment cap. */
class PlanCalculatorOverridesTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final String HOUSE = "house & techno";
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Instant NOW = ZonedDateTime.of(2026, 9, 26, 10, 0, 0, 0, PARIS).toInstant();
    private static final Instant START = ZonedDateTime.of(2026, 10, 24, 23, 0, 0, 0, PARIS).toInstant();
    private static final List<Tier> THREE_HUNDRED = List.of(new Tier(300, true));

    private static final PlanCalculator CALC =
            new PlanCalculator(PlanFixtures.LOGIC, new ResponseModel(PlanFixtures.LOGIC, CalibrationSource.NONE));

    private static List<Person> people(String classKey, int n) {
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Person(UUID.randomUUID(), classKey, Map.of(HOUSE, 1.0), 0, false, false, 0, 0));
        return out;
    }

    private static List<Person> fixture() {
        List<Person> all = new ArrayList<>();
        all.addAll(people("loyal", 40));
        all.addAll(people("repeat", 70));
        all.addAll(people("first_timer", 235));
        return all;
    }

    private static Input input(List<Person> mailable, Set<String> excluded, int maxSegments) {
        return new Input(ORG, HOUSE, THREE_HUNDRED, 85, 1.6, Map.of("legacy_unproven", 4), mailable, NOW, START, PARIS,
                null, null, excluded, maxSegments);
    }

    private static List<String> invitedClasses(Plan p) {
        return p.actions().stream().filter(a -> a.type() == ActionType.INVITE).map(Action::classKey).toList();
    }

    // ── left-out classes ────────────────────────────────────────────────────

    @Test
    void excludedClasses_leaveTheSegments_butStayInMailableAndMode() {
        Plan p = CALC.calculate(input(fixture(), Set.of("repeat", "first_timer"), 0));

        assertThat(p.mailable()).isEqualTo(345);
        assertThat(p.mode()).isEqualTo(Mode.WARM);
        assertThat(p.segments()).extracting(PlanSegment::classKey).containsExactly("loyal");
        assertThat(p.expected()).isEqualTo(new Tickets(8, 16, 26));
        assertThat(p.gap()).isEqualTo(new Gap(229, 247));
        assertThat(p.exclusions()).containsEntry(Exclusions.EXCLUDED_SEGMENT, 305).containsEntry(Exclusions.SEGMENT_CAP, 0)
                .containsEntry("legacy_unproven", 4);
        assertThat(invitedClasses(p)).containsExactly("loyal");
    }

    @Test
    void excludedClasses_inColdMode_areCounted() {
        List<Person> few = new ArrayList<>(people("loyal", 20));
        few.addAll(people("first_timer", 10));

        Plan p = CALC.calculate(input(few, Set.of("first_timer"), 3));

        assertThat(p.mode()).isEqualTo(Mode.COLD);
        assertThat(p.mailable()).isEqualTo(30);
        assertThat(p.exclusions()).containsEntry(Exclusions.EXCLUDED_SEGMENT, 10).containsEntry(Exclusions.SEGMENT_CAP, 0);
    }

    @Test
    void aMemberWithoutClass_isNoClass_notExcludedSegment() {
        List<Person> all = fixture();
        all.addAll(people(null, 5));

        Plan p = CALC.calculate(input(all, Set.of("first_timer"), 0));

        assertThat(p.exclusions()).containsEntry(Exclusions.NO_CLASS, 5).containsEntry(Exclusions.EXCLUDED_SEGMENT, 235);
    }

    @Test
    void duplicatedPerson_isExcludedOnce() {
        List<Person> all = fixture();
        all.add(all.get(0));

        Plan p = CALC.calculate(input(all, Set.of("loyal"), 0));

        assertThat(p.mailable()).isEqualTo(345);
        assertThat(p.exclusions()).containsEntry(Exclusions.EXCLUDED_SEGMENT, 40);
    }

    // ── top-segment cap ─────────────────────────────────────────────────────

    @Test
    void cap_keepsTheHighestRateSegments_andTotalsCoverOnlyThose() {
        List<Person> all = fixture();
        all.addAll(people("lapsing", 20));

        Plan p = CALC.calculate(input(all, Set.of(), 3));

        assertThat(p.segments()).extracting(PlanSegment::classKey).containsExactly("loyal", "repeat", "first_timer");
        assertThat(p.expected()).isEqualTo(new Tickets(26, 52, 93));
        assertThat(p.coverage()).isEqualTo(new Coverage(0.10, 0.20, 0.36, Verdict.MEDIUM));
        assertThat(p.gap()).isEqualTo(new Gap(162, 229));
        assertThat(p.mailable()).isEqualTo(365);
        assertThat(p.exclusions()).containsEntry(Exclusions.SEGMENT_CAP, 20).containsEntry(Exclusions.EXCLUDED_SEGMENT, 0);
        assertThat(invitedClasses(p)).containsExactly("loyal", "repeat", "first_timer");
    }

    @Test
    void capZero_keepsEverySegment() {
        List<Person> all = fixture();
        all.addAll(people("lapsing", 20));

        Plan p = CALC.calculate(input(all, Set.of(), 0));

        // lapsing 20 × .01/.025/.05 × 1.6 adds 0.32 / 0.80 / 1.60 to 25.68 / 52.00 / 93.12.
        assertThat(p.segments()).extracting(PlanSegment::classKey)
                .containsExactly("loyal", "repeat", "first_timer", "lapsing");
        assertThat(p.expected()).isEqualTo(new Tickets(26, 53, 95));
        assertThat(p.exclusions()).containsEntry(Exclusions.SEGMENT_CAP, 0);
    }

    @Test
    void capAtOrAboveTheSegmentCount_dropsNothing() {
        Plan p = CALC.calculate(input(fixture(), Set.of(), 3));

        assertThat(p.segments()).hasSize(3);
        assertThat(p.exclusions()).containsEntry(Exclusions.SEGMENT_CAP, 0);
    }

    // ── input shape ─────────────────────────────────────────────────────────

    @Test
    void theOverrideFreeConstructor_reportsBothPlanReasonsAsZero() {
        Plan p = CALC.calculate(new Input(ORG, HOUSE, THREE_HUNDRED, 85, 1.6, Map.of(), fixture(), NOW, START, PARIS,
                null, null));

        assertThat(p.segments()).hasSize(3);
        assertThat(p.exclusions()).containsEntry(Exclusions.EXCLUDED_SEGMENT, 0).containsEntry(Exclusions.SEGMENT_CAP, 0);
    }

    @Test
    void nullExcludedClasses_meansNone() {
        assertThat(input(fixture(), null, 0).excludedClasses()).isEmpty();
    }

    @Test
    void negativeCap_isRefused() {
        assertThatThrownBy(() -> input(fixture(), Set.of(), -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
