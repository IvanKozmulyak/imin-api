package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.MetaAds;
import com.imin.iminapi.audienceplan.engine.GapCalculator.CountRange;
import com.imin.iminapi.audienceplan.engine.GapCalculator.Gap;
import com.imin.iminapi.audienceplan.engine.GapCalculator.Reach;
import com.imin.iminapi.audienceplan.engine.GapCalculator.ReachNeeded;
import com.imin.iminapi.audienceplan.engine.GapCalculator.ReachStatus;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Tickets;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GapCalculatorTest {

    private static final Gap FIXTURE_GAP = new Gap(162, 229);
    private static final CountRange METZ_REGULARS = new CountRange(462, 606);

    @Test
    void gap_isTargetMinusTheRoundedTotals() {
        assertThat(GapCalculator.gap(255, new Tickets(26, 52, 93))).isEqualTo(FIXTURE_GAP);
    }

    @Test
    void gap_isNeverNegative() {
        assertThat(GapCalculator.gap(5, new Tickets(8, 16, 26))).isEqualTo(new Gap(0, 0));
        assertThat(GapCalculator.gap(20, new Tickets(8, 16, 26))).isEqualTo(new Gap(0, 12));
    }

    @Test
    void shippedPriors_metaUnverified_instagramUnknown() {
        ReachNeeded r = GapCalculator.reachNeeded(FIXTURE_GAP, PlanFixtures.LOGIC.priors());
        assertThat(r.metaAds()).isEqualTo(new Reach(ReachStatus.UNVERIFIED, null, null));
        assertThat(r.instagramOrganic()).isEqualTo(new Reach(ReachStatus.UNKNOWN, null, null));
    }

    @Test
    void verifiedMeta_isGapOverCtrTimesLandingToTicket() {
        AudiencePlanLogic logic = PlanFixtures.withReach(
                new MetaAds(0.027, new Band(0.02, 0.05, 0.08), true, "test"), Optional.empty());
        Reach meta = GapCalculator.reachNeeded(FIXTURE_GAP, logic.priors()).metaAds();
        // 162 / (0.027 × 0.08) = 75,000; 229 / (0.027 × 0.02) = 424,074.07
        assertThat(meta).isEqualTo(new Reach(ReachStatus.ESTIMATED, 75000, 424074));
    }

    @Test
    void instagramWithARate_isARange() {
        AudiencePlanLogic logic = PlanFixtures.withReach(PlanFixtures.LOGIC.priors().metaAds(),
                Optional.of(new Band(0.001, 0.002, 0.004)));
        assertThat(GapCalculator.reachNeeded(FIXTURE_GAP, logic.priors()).instagramOrganic())
                .isEqualTo(new Reach(ReachStatus.ESTIMATED, 40500, 229000));
    }

    @Test
    void zeroRateBound_leavesThatBoundUnknown() {
        assertThat(GapCalculator.reach(FIXTURE_GAP, new Band(0, 0.002, 0.004)))
                .isEqualTo(new Reach(ReachStatus.ESTIMATED, 40500, null));
    }

    @Test
    void reachBeyondIntRange_isUnknownNotAnError() {
        // 229 / 1e-9 = 2.29e11 people does not fit an int.
        assertThat(GapCalculator.reach(FIXTURE_GAP, new Band(1e-9, 0.002, 0.004)))
                .isEqualTo(new Reach(ReachStatus.ESTIMATED, 40500, null));
        assertThat(GapCalculator.reach(FIXTURE_GAP, new Band(Double.MIN_VALUE, 1e-9, 1e-9)))
                .isEqualTo(new Reach(ReachStatus.ESTIMATED, null, null));
    }

    @Test
    void coldGap_isTheWholeTarget() {
        assertThat(GapCalculator.gap(255, null)).isEqualTo(new Gap(255, 255));
    }

    @Test
    void zeroGap_needsNoReach() {
        assertThat(GapCalculator.reach(new Gap(0, 0), new Band(0, 0.002, 0.004)))
                .isEqualTo(new Reach(ReachStatus.ESTIMATED, 0, 0));
    }

    @Test
    void exceedsTribe_nullWhileTheTribeIsUnknown() {
        assertThat(GapCalculator.exceedsTribe(FIXTURE_GAP, null)).isNull();
        assertThat(GapCalculator.exceedsTribe(FIXTURE_GAP, new CountRange(null, null))).isNull();
    }

    @Test
    void exceedsTribe_700Vs606_isTrue() {
        assertThat(GapCalculator.exceedsTribe(new Gap(700, 700), METZ_REGULARS)).isTrue();
    }

    @Test
    void exceedsTribe_162Vs606_isFalse() {
        assertThat(GapCalculator.exceedsTribe(FIXTURE_GAP, METZ_REGULARS)).isFalse();
    }

    @Test
    void exceedsTribe_equalToTheTribe_isFalse() {
        assertThat(GapCalculator.exceedsTribe(new Gap(606, 700), METZ_REGULARS)).isFalse();
    }
}
