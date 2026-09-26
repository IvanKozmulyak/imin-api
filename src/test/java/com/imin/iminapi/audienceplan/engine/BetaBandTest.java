package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.engine.CalibrationSource.Counts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class BetaBandTest {

    private static final Band LOYAL = new Band(0.12, 0.25, 0.40);
    private static final int STRENGTH = 20;

    @Test
    void noObservations_returnsYamlBandExactly() {
        assertThat(BetaBand.blend(LOYAL, STRENGTH, Counts.ZERO)).isSameAs(LOYAL);
    }

    @Test
    void loyalPriorBeta_5_15_quantiles() {
        assertThat(BetaBand.quantile(5, 15, BetaBand.P_LOW)).isCloseTo(0.134, within(0.001));
        assertThat(BetaBand.quantile(5, 15, BetaBand.P_HIGH)).isCloseTo(0.378, within(0.001));
    }

    @Test
    void posteriorBeta_9_21_quantiles() {
        assertThat(BetaBand.quantile(9, 21, BetaBand.P_LOW)).isCloseTo(0.197, within(0.001));
        assertThat(BetaBand.quantile(9, 21, BetaBand.P_HIGH)).isCloseTo(0.409, within(0.001));
    }

    @Test
    void tenInvitedFourBought_blendsAtOneThird() {
        Band band = BetaBand.blend(LOYAL, STRENGTH, new Counts(10, 4));
        assertThat(band.low()).isCloseTo(0.146, within(0.001));
        assertThat(band.mid()).isCloseTo(0.300, within(0.001));
        assertThat(band.high()).isCloseTo(0.403, within(0.001));
    }

    @Test
    void oneObservation_boundsStayWithinOneHundredthOfYaml_midIsPosteriorMean() {
        Band none = BetaBand.blend(LOYAL, STRENGTH, new Counts(1, 0));
        Band one = BetaBand.blend(LOYAL, STRENGTH, new Counts(1, 1));

        assertThat(none.low()).isCloseTo(LOYAL.low(), within(0.01));
        assertThat(none.high()).isCloseTo(LOYAL.high(), within(0.01));
        assertThat(one.low()).isCloseTo(LOYAL.low(), within(0.01));
        assertThat(one.high()).isCloseTo(LOYAL.high(), within(0.01));
        assertThat(none.mid()).isCloseTo(5.0 / 21, within(1e-12));
        assertThat(one.mid()).isCloseTo(6.0 / 21, within(1e-12));
    }

    @Test
    void manyObservations_convergeToPosteriorInterval() {
        Band band = BetaBand.blend(LOYAL, STRENGTH, new Counts(10_000, 2_500));
        double alpha = 5 + 2_500;
        double beta = 15 + 7_500;
        assertThat(band.low()).isCloseTo(BetaBand.quantile(alpha, beta, 0.10), within(0.005));
        assertThat(band.high()).isCloseTo(BetaBand.quantile(alpha, beta, 0.90), within(0.005));
        assertThat(band.mid()).isCloseTo(alpha / (alpha + beta), within(1e-12));
    }

    @Test
    void lowAboveMid_isClampedToMid() {
        Band narrow = new Band(0.25, 0.25, 0.25);
        Band band = BetaBand.blend(narrow, STRENGTH, new Counts(1, 0));
        assertThat(band.mid()).isCloseTo(5.0 / 21, within(1e-12));
        assertThat(band.low()).isEqualTo(band.mid());
        assertThat(band.high()).isGreaterThan(band.mid());
    }

    @Test
    void highBelowMid_isClampedToMid() {
        Band narrow = new Band(0.25, 0.25, 0.25);
        Band band = BetaBand.blend(narrow, STRENGTH, new Counts(1, 1));
        assertThat(band.mid()).isCloseTo(6.0 / 21, within(1e-12));
        assertThat(band.high()).isEqualTo(band.mid());
        assertThat(band.low()).isLessThan(band.mid());
    }

    @Test
    void zeroAlpha_isPointMassAtZero() {
        assertThat(BetaBand.quantile(0, 25, 0.9)).isZero();
        Band band = BetaBand.blend(new Band(0, 0, 0.10), STRENGTH, new Counts(5, 0));
        assertThat(band.low()).isZero();
        assertThat(band.mid()).isZero();
        assertThat(band.high()).isCloseTo(0.08, within(1e-12));
    }

    @Test
    void zeroBeta_isPointMassAtOne() {
        assertThat(BetaBand.quantile(25, 0, 0.1)).isEqualTo(1.0);
        Band band = BetaBand.blend(new Band(0.90, 1, 1), STRENGTH, new Counts(5, 5));
        assertThat(band.low()).isCloseTo(0.92, within(1e-12));
        assertThat(band.mid()).isEqualTo(1.0);
        assertThat(band.high()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void nonPositivePriorStrength_isRejected() {
        assertThatThrownBy(() -> BetaBand.blend(LOYAL, 0, Counts.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("prior strength must be > 0: 0");
    }
}
