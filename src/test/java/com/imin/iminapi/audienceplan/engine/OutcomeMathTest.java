package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.engine.OutcomeMath.LiftStatus;
import com.imin.iminapi.audienceplan.engine.OutcomeMath.Phase;
import com.imin.iminapi.audienceplan.engine.OutcomeMath.Range;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeMathTest {

    private static final Instant START = Instant.parse("2026-10-24T18:00:00Z");

    @Test
    void rate_isAWilson80Range_aroundTheObservedShare() {
        assertThat(OutcomeMath.rate(10, 100)).isEqualTo(new Range(0.0678, 0.1, 0.1451));
    }

    @Test
    void rate_clampsToZeroAndOne() {
        assertThat(OutcomeMath.rate(0, 60)).isEqualTo(new Range(0.0, 0.0, 0.0266));
        assertThat(OutcomeMath.rate(60, 60)).isEqualTo(new Range(0.9734, 1.0, 1.0));
    }

    @Test
    void rate_ofAnEmptyGroup_isNull() {
        assertThat(OutcomeMath.rate(0, 0)).isNull();
    }

    @Test
    void lift_isANewcombe80Range_ofArmMinusHoldout() {
        assertThat(OutcomeMath.lift(20, 100, 6, 60)).isEqualTo(new Range(0.0236, 0.1, 0.1684));
    }

    @Test
    void lift_canBeNegative_andIsSymmetricWhenTheGroupsSwap() {
        assertThat(OutcomeMath.lift(6, 60, 20, 100)).isEqualTo(new Range(-0.1684, -0.1, -0.0236));
    }

    @Test
    void lift_withAnEmptyGroup_isNull() {
        assertThat(OutcomeMath.lift(1, 10, 0, 0)).isNull();
        assertThat(OutcomeMath.lift(0, 0, 1, 10)).isNull();
    }

    @Test
    void liftStatus_holdoutRow_isBaseline() {
        assertThat(OutcomeMath.liftStatus(true, 100, 100, 60)).isEqualTo(LiftStatus.BASELINE);
    }

    @Test
    void liftStatus_withoutHoldout_isNoHoldout() {
        assertThat(OutcomeMath.liftStatus(false, null, 100, 60)).isEqualTo(LiftStatus.NO_HOLDOUT);
    }

    @Test
    void liftStatus_armOrHoldoutUnderTheMinimum_isTooFew() {
        assertThat(OutcomeMath.liftStatus(false, 59, 100, 60)).isEqualTo(LiftStatus.TOO_FEW);
        assertThat(OutcomeMath.liftStatus(false, 100, 59, 60)).isEqualTo(LiftStatus.TOO_FEW);
    }

    @Test
    void liftStatus_bothAtTheMinimum_isOk() {
        assertThat(OutcomeMath.liftStatus(false, 60, 60, 60)).isEqualTo(LiftStatus.OK);
    }

    @Test
    void doorClose_isTheEnd_whenSet() {
        Instant end = START.plus(Duration.ofHours(6));
        assertThat(OutcomeMath.doorClose(START, end)).isEqualTo(end);
    }

    @Test
    void doorClose_isStartPlus12h_withoutAnEnd() {
        assertThat(OutcomeMath.doorClose(START, null)).isEqualTo(START.plus(Duration.ofHours(12)));
    }

    @Test
    void doorClose_withoutStartOrEnd_isNull() {
        assertThat(OutcomeMath.doorClose(null, null)).isNull();
    }

    @Test
    void due_isNothingBeforeOneDay_d1FromOneDay_d7FromSevenDays() {
        assertThat(OutcomeMath.due(START, START.plus(Duration.ofDays(1)).minusSeconds(1))).isEmpty();
        assertThat(OutcomeMath.due(START, START.plus(Duration.ofDays(1)))).contains(Phase.D1);
        assertThat(OutcomeMath.due(START, START.plus(Duration.ofDays(7)).minusSeconds(1))).contains(Phase.D1);
        assertThat(OutcomeMath.due(START, START.plus(Duration.ofDays(7)))).contains(Phase.D7);
        assertThat(OutcomeMath.due(null, START)).isEmpty();
    }

    @Test
    void storedPhases_haveTheirWindow_andKeysAreLowerCase() {
        assertThat(Phase.D1.window()).isEqualTo(Duration.ofDays(1));
        assertThat(Phase.D7.window()).isEqualTo(Duration.ofDays(7));
        assertThat(Phase.PENDING.key()).isEqualTo("pending");
        assertThat(LiftStatus.TOO_FEW.key()).isEqualTo("too_few");
    }
}
