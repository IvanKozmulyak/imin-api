package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Experiments;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.TimingArm;
import com.imin.iminapi.audienceplan.engine.ActionPlanner.Action;
import com.imin.iminapi.audienceplan.engine.ActionPlanner.ActionType;
import com.imin.iminapi.audienceplan.engine.ActionPlanner.ArmDate;
import com.imin.iminapi.audienceplan.engine.ActionPlanner.Timing;
import com.imin.iminapi.audienceplan.engine.CoverageVerdict.Verdict;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.PlanSegment;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Tickets;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Confidence;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Fit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActionPlannerTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Experiments EXPERIMENTS = PlanFixtures.LOGIC.logic().experiments();
    private static final LocalDate EVENT = LocalDate.of(2026, 10, 24);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);
    private static final LocalDate LAUNCH = LocalDate.of(2026, 10, 1);
    private static final LocalDate D3 = LocalDate.of(2026, 10, 21);

    private static Instant paris(int y, int m, int d, int h, int min) {
        return ZonedDateTime.of(y, m, d, h, min, 0, 0, PARIS).toInstant();
    }

    private static final Instant NOW = paris(2026, 9, 26, 10, 0);
    private static final Instant START = paris(2026, 10, 24, 23, 0);

    private static PlanSegment segment(String classKey, Fit fit, int mailable) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < mailable; i++) ids.add(UUID.randomUUID());
        Band b = new Band(0.1, 0.2, 0.3);
        return new PlanSegment(classKey, fit, b, Confidence.PRIOR, 1.6, b, new Tickets(0, 0, 0), ids);
    }

    private static Timing timing(LocalDate today, LocalDate event) {
        return new Timing(today, event, today, false);
    }

    // ── timing ─────────────────────────────────────────────────────────────

    @Test
    void daysToEvent_26SeptemberTo24October2026_is28() {
        Timing t = ActionPlanner.timing(NOW, START, PARIS, null);
        assertThat(t.today()).isEqualTo(TODAY);
        assertThat(t.eventDate()).isEqualTo(EVENT);
        assertThat(t.daysToEvent()).isEqualTo(28);
    }

    @Test
    void datesAreTakenInTheEventTimezone() {
        // 23:30 UTC on 26.09 is already 27.09 in Paris; a 23:30 UTC start on 24.10 is 25.10 in Paris.
        Instant lateUtc = Instant.parse("2026-09-26T23:30:00Z");
        Instant startUtc = Instant.parse("2026-10-24T23:30:00Z");
        assertThat(ActionPlanner.timing(lateUtc, startUtc, PARIS, null).daysToEvent()).isEqualTo(28);
        assertThat(ActionPlanner.timing(lateUtc, startUtc, PARIS, null).today()).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(ActionPlanner.timing(lateUtc, START, PARIS, null).daysToEvent()).isEqualTo(27);
        assertThat(ActionPlanner.timing(lateUtc, START, ZoneId.of("UTC"), null).daysToEvent()).isEqualTo(28);
    }

    @Test
    void launch_isTheOnSaleDateWhenStillAhead() {
        Timing t = ActionPlanner.timing(NOW, START, PARIS, paris(2026, 10, 1, 12, 0));
        assertThat(t.launchDate()).isEqualTo(LAUNCH);
        assertThat(LAUNCH.getDayOfWeek()).hasToString("THURSDAY");
    }

    @Test
    void launch_isTodayWhenOnSaleHasPassedOrIsUnset() {
        assertThat(ActionPlanner.timing(NOW, START, PARIS, paris(2026, 9, 20, 12, 0)).launchDate()).isEqualTo(TODAY);
        assertThat(ActionPlanner.timing(NOW, START, PARIS, paris(2026, 9, 26, 20, 0)).launchDate()).isEqualTo(TODAY);
        assertThat(ActionPlanner.timing(NOW, START, PARIS, null).launchDate()).isEqualTo(TODAY);
    }

    @Test
    void d3For24October2026_is21October() {
        assertThat(timing(TODAY, EVENT).d3Date()).isEqualTo(D3);
        assertThat(D3.getDayOfWeek()).hasToString("WEDNESDAY");
        assertThat(EVENT.getDayOfWeek()).hasToString("SATURDAY");
    }

    @Test
    void d3_isDroppedAtThreeDaysOut_keptAtFour() {
        assertThat(timing(EVENT.minusDays(3), EVENT).d3Date()).isNull();
        assertThat(timing(EVENT.minusDays(4), EVENT).d3Date()).isEqualTo(D3);
    }

    @Test
    void d3_isDroppedWhenNotAfterTheLaunchDate() {
        assertThat(new Timing(TODAY, EVENT, D3, false).d3Date()).isNull();
        assertThat(new Timing(TODAY, EVENT, D3.minusDays(1), false).d3Date()).isEqualTo(D3);
    }

    // ── invites ────────────────────────────────────────────────────────────

    @Test
    void invite_perShownSegment_withLaunchAndD3Dates() {
        Timing t = new Timing(TODAY, EVENT, LAUNCH, false);
        List<Action> actions = ActionPlanner.plan(Verdict.MEDIUM,
                List.of(segment("loyal", Fit.SAME, 40), segment("first_timer", Fit.SAME, 235)), t, null, EXPERIMENTS);

        List<ArmDate> arms = List.of(new ArmDate(TimingArm.LAUNCH, LAUNCH), new ArmDate(TimingArm.D3, D3));
        assertThat(actions).containsExactly(
                new Action(ActionType.INVITE, "loyal", Fit.SAME, arms, 0),
                new Action(ActionType.INVITE, "first_timer", Fit.SAME, arms, 15));
    }

    @Test
    void holdout_onlyFrom60Mailable() {
        List<Action> actions = ActionPlanner.plan(Verdict.MEDIUM,
                List.of(segment("repeat", Fit.SAME, 59), segment("repeat", Fit.ADJACENT, 60)), timing(TODAY, EVENT), null,
                EXPERIMENTS);
        assertThat(actions).extracting(Action::holdoutPct).containsExactly(0, 15);
    }

    @Test
    void threeDaysOut_invitesWithTheLaunchArmOnly() {
        LocalDate today = EVENT.minusDays(3);
        List<Action> actions = ActionPlanner.plan(Verdict.MEDIUM, List.of(segment("loyal", Fit.SAME, 40)),
                timing(today, EVENT), null, EXPERIMENTS);
        assertThat(actions).singleElement().extracting(Action::arms)
                .isEqualTo(List.of(new ArmDate(TimingArm.LAUNCH, today)));
    }

    @Test
    void onTheEventDay_theLaunchArmIsToday() {
        List<Action> actions = ActionPlanner.plan(Verdict.MEDIUM, List.of(segment("loyal", Fit.SAME, 40)),
                timing(EVENT, EVENT), null, EXPERIMENTS);
        assertThat(actions).singleElement().extracting(Action::arms)
                .isEqualTo(List.of(new ArmDate(TimingArm.LAUNCH, EVENT)));
    }

    @Test
    void eventStarted_isNowAtOrAfterTheStartInstant() {
        assertThat(ActionPlanner.timing(paris(2026, 10, 24, 22, 59), START, PARIS, null).eventStarted()).isFalse();
        assertThat(ActionPlanner.timing(START, START, PARIS, null).eventStarted()).isTrue();
        assertThat(ActionPlanner.timing(paris(2026, 10, 24, 23, 30), START, PARIS, null).eventStarted()).isTrue();
    }

    @Test
    void onTheEventDay_afterTheStart_getsNoInvites() {
        Timing started = ActionPlanner.timing(paris(2026, 10, 24, 23, 30), START, PARIS, null);
        assertThat(started.daysToEvent()).isZero();
        assertThat(ActionPlanner.plan(Verdict.WEAK, List.of(segment("loyal", Fit.SAME, 40)), started, null, EXPERIMENTS))
                .extracting(Action::type).containsExactly(ActionType.IMPORT_WITH_PROOF);
    }

    @Test
    void onTheEventDay_beforeTheStart_stillInvites() {
        Timing notYet = ActionPlanner.timing(paris(2026, 10, 24, 22, 0), START, PARIS, null);
        assertThat(ActionPlanner.plan(Verdict.MEDIUM, List.of(segment("loyal", Fit.SAME, 40)), notYet, null, EXPERIMENTS))
                .singleElement().extracting(Action::arms).isEqualTo(List.of(new ArmDate(TimingArm.LAUNCH, EVENT)));
    }

    @Test
    void pastEvent_getsNoInvites() {
        List<Action> actions = ActionPlanner.plan(Verdict.MEDIUM, List.of(segment("loyal", Fit.SAME, 40)),
                timing(EVENT.plusDays(1), EVENT), null, EXPERIMENTS);
        assertThat(actions).isEmpty();
    }

    @Test
    void onSaleAfterTheEventDate_dropsEveryArmAndTheInvite() {
        List<Action> actions = ActionPlanner.plan(Verdict.MEDIUM, List.of(segment("loyal", Fit.SAME, 40)),
                new Timing(TODAY, EVENT, EVENT.plusDays(1), false), null, EXPERIMENTS);
        assertThat(actions).isEmpty();
    }

    @Test
    void unknownFitSegment_isInvitedLikeAnyOther() {
        List<Action> actions = ActionPlanner.plan(Verdict.MEDIUM, List.of(segment("imported", Fit.UNKNOWN, 20)),
                timing(TODAY, EVENT), null, EXPERIMENTS);
        assertThat(actions).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(ActionType.INVITE);
            assertThat(a.classKey()).isEqualTo("imported");
            assertThat(a.fit()).isEqualTo(Fit.UNKNOWN);
        });
    }

    // ── list building and realism ──────────────────────────────────────────

    @Test
    void weakOrCold_addsImportWithProof() {
        Action importAction = new Action(ActionType.IMPORT_WITH_PROOF, null, null, List.of(), null);
        assertThat(ActionPlanner.plan(Verdict.WEAK, List.of(), timing(TODAY, EVENT), null, EXPERIMENTS))
                .containsExactly(importAction);
        assertThat(ActionPlanner.plan(Verdict.COLD, List.of(), timing(TODAY, EVENT), null, EXPERIMENTS))
                .containsExactly(importAction);
    }

    @Test
    void mediumOrStrong_addsNoImport() {
        assertThat(ActionPlanner.plan(Verdict.MEDIUM, List.of(), timing(TODAY, EVENT), null, EXPERIMENTS)).isEmpty();
        assertThat(ActionPlanner.plan(Verdict.STRONG, List.of(), timing(TODAY, EVENT), null, EXPERIMENTS)).isEmpty();
    }

    @Test
    void gapExceedingTheTribe_addsRethinkTargetLast() {
        List<Action> actions = ActionPlanner.plan(Verdict.COLD, List.of(), timing(TODAY, EVENT), true, EXPERIMENTS);
        assertThat(actions).extracting(Action::type).containsExactly(ActionType.IMPORT_WITH_PROOF, ActionType.RETHINK_TARGET);
    }

    @Test
    void tribeUnknownOrLargeEnough_addsNoRethink() {
        assertThat(ActionPlanner.plan(Verdict.STRONG, List.of(), timing(TODAY, EVENT), null, EXPERIMENTS)).isEmpty();
        assertThat(ActionPlanner.plan(Verdict.STRONG, List.of(), timing(TODAY, EVENT), false, EXPERIMENTS)).isEmpty();
    }
    // ── step cap ───────────────────────────────────────────────────────────

    private static Action invite(String classKey) {
        return new Action(ActionType.INVITE, classKey, Fit.SAME, List.of(new ArmDate(TimingArm.LAUNCH, LAUNCH)), 15);
    }

    @Test
    void topSteps_atOrUnderTheCap_keepsEveryAction() {
        List<Action> actions = List.of(invite("loyal"), invite("repeat"), Action.of(ActionType.IMPORT_WITH_PROOF));
        assertThat(ActionPlanner.topSteps(actions, 3)).containsExactlyElementsOf(actions);
    }

    @Test
    void topSteps_overTheCap_keepsNonInvitesAndTheFirstInvitesInOrder() {
        Action importStep = Action.of(ActionType.IMPORT_WITH_PROOF);
        Action rethink = Action.of(ActionType.RETHINK_TARGET);
        List<Action> actions = List.of(invite("loyal"), invite("repeat"), invite("first_timer"), importStep, rethink);

        assertThat(ActionPlanner.topSteps(actions, 3)).containsExactly(invite("loyal"), importStep, rethink);
    }

    @Test
    void topSteps_onlyInvitesOverTheCap_keepsTheFirstOnes() {
        List<Action> actions = List.of(invite("loyal"), invite("repeat"), invite("first_timer"), invite("lapsing"));
        assertThat(ActionPlanner.topSteps(actions, 3)).containsExactly(invite("loyal"), invite("repeat"),
                invite("first_timer"));
    }

    @Test
    void topSteps_moreNonInvitesThanTheCap_keepsTheFirstNonInvites() {
        Action importStep = Action.of(ActionType.IMPORT_WITH_PROOF);
        Action rethink = Action.of(ActionType.RETHINK_TARGET);
        assertThat(ActionPlanner.topSteps(List.of(invite("loyal"), importStep, rethink), 1)).containsExactly(importStep);
    }

    @Test
    void topSteps_negativeCap_isRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ActionPlanner.topSteps(List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
