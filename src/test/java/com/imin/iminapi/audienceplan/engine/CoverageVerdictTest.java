package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.BandPoint;
import com.imin.iminapi.audienceplan.engine.CoverageVerdict.Coverage;
import com.imin.iminapi.audienceplan.engine.CoverageVerdict.Verdict;
import com.imin.iminapi.audienceplan.engine.ModeSelector.Mode;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Tickets;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoverageVerdictTest {

    private static final AudiencePlanLogic.CoverageVerdict RULE = PlanFixtures.LOGIC.logic().coverageVerdict();

    private static Verdict onMid(int mid, int target) {
        return CoverageVerdict.of(Mode.WARM, new Tickets(0, mid, mid), target, RULE).verdict();
    }

    @Test
    void shippedRuleReadsTheMidAt30And15() {
        assertThat(RULE).isEqualTo(new AudiencePlanLogic.CoverageVerdict(BandPoint.MID, 0.30, 0.15));
    }

    @Test
    void fixture_26_52_93Of255() {
        Coverage c = CoverageVerdict.of(Mode.WARM, new Tickets(26, 52, 93), 255, RULE);
        assertThat(c).isEqualTo(new Coverage(0.10, 0.20, 0.36, Verdict.MEDIUM));
    }

    @Test
    void verdictBoundaries() {
        assertThat(onMid(3000, 10000)).isEqualTo(Verdict.STRONG);
        assertThat(onMid(2999, 10000)).isEqualTo(Verdict.MEDIUM);
        assertThat(onMid(1500, 10000)).isEqualTo(Verdict.MEDIUM);
        assertThat(onMid(1499, 10000)).isEqualTo(Verdict.WEAK);
    }

    @Test
    void shownCoverageNeverCrossesAThresholdTheVerdictHasNot() {
        Coverage weak = CoverageVerdict.of(Mode.WARM, new Tickets(0, 1496, 1496), 10000, RULE);
        assertThat(weak.mid()).isEqualTo(0.14);
        assertThat(weak.verdict()).isEqualTo(Verdict.WEAK);
        Coverage medium = CoverageVerdict.of(Mode.WARM, new Tickets(0, 2996, 2996), 10000, RULE);
        assertThat(medium.mid()).isEqualTo(0.29);
        assertThat(medium.verdict()).isEqualTo(Verdict.MEDIUM);
    }

    @Test
    void coverageMidSitsNextToTheVerdict_149And150Of1000() {
        Coverage at149 = CoverageVerdict.of(Mode.WARM, new Tickets(0, 149, 149), 1000, RULE);
        assertThat(at149.mid()).isEqualTo(0.14);
        assertThat(at149.verdict()).isEqualTo(Verdict.WEAK);
        Coverage at150 = CoverageVerdict.of(Mode.WARM, new Tickets(0, 150, 150), 1000, RULE);
        assertThat(at150.mid()).isEqualTo(0.15);
        assertThat(at150.verdict()).isEqualTo(Verdict.MEDIUM);
    }

    @Test
    void verdictMatchesTheOtherGenreGate_149And150Of1000() {
        double gate = PlanFixtures.LOGIC.logic().inviteOtherGenreOnlyIfCoverageBelow();
        assertThat(onMid(149, 1000)).isEqualTo(Verdict.WEAK);
        assertThat(149.0 / 1000 < gate).isTrue();
        assertThat(onMid(150, 1000)).isEqualTo(Verdict.MEDIUM);
        assertThat(150.0 / 1000 < gate).isFalse();
    }

    @Test
    void coldMode_isColdWithNoCoverageComputed() {
        assertThat(CoverageVerdict.of(Mode.COLD, null, 255, RULE))
                .isEqualTo(new Coverage(null, null, null, Verdict.COLD));
    }

    @Test
    void hotMode_getsAVerdictLikeWarm() {
        assertThat(CoverageVerdict.of(Mode.HOT, new Tickets(80, 90, 100), 255, RULE).verdict()).isEqualTo(Verdict.STRONG);
    }

    @Test
    void verdictOnLowOrHigh_readsThatPoint() {
        Tickets t = new Tickets(10, 40, 80);
        assertThat(CoverageVerdict.verdict(t, 100, new AudiencePlanLogic.CoverageVerdict(BandPoint.LOW, 0.30, 0.15)))
                .isEqualTo(Verdict.WEAK);
        assertThat(CoverageVerdict.verdict(t, 100, new AudiencePlanLogic.CoverageVerdict(BandPoint.HIGH, 0.30, 0.15)))
                .isEqualTo(Verdict.STRONG);
    }

    @Test
    void zeroTarget_isRefused() {
        assertThatThrownBy(() -> CoverageVerdict.of(Mode.WARM, new Tickets(1, 2, 3), 0, RULE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
