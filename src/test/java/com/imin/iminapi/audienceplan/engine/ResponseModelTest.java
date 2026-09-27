package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanConfig;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import com.imin.iminapi.audienceplan.engine.CalibrationSource.Counts;
import com.imin.iminapi.audienceplan.engine.CalibrationSource.Observations;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Confidence;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Fit;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Rate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class ResponseModelTest {

    private static final AudiencePlanLogic LOGIC = shipped();
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final double EPS = 1e-12;

    @Test
    void priorOnly_loyalSame_isYamlBandExactly() {
        Rate rate = model(CalibrationSource.NONE).rate(ORG, "loyal", Fit.SAME, 0);
        assertThat(rate.band()).isEqualTo(new Band(0.12, 0.25, 0.40));
        assertThat(rate.confidence()).isEqualTo(Confidence.PRIOR);
    }

    @Test
    void adjacentFit_halvesAllThree() {
        assertBand(model(CalibrationSource.NONE).rate(ORG, "loyal", Fit.ADJACENT, 0).band(), 0.06, 0.125, 0.20);
    }

    @Test
    void otherFit_timesPointTwo() {
        assertBand(model(CalibrationSource.NONE).rate(ORG, "loyal", Fit.OTHER, 0).band(), 0.024, 0.05, 0.08);
    }

    @Test
    void unknownFit_isNeutral_theClassPriorExactly() {
        assertBand(model(CalibrationSource.NONE).rate(ORG, "imported", Fit.UNKNOWN, 0).band(), 0.002, 0.006, 0.015);
    }

    @Test
    void noShowBefore_appliesBandwise() {
        assertBand(model(CalibrationSource.NONE).rate(ORG, "loyal", Fit.SAME, 1).band(), 0.048, 0.175, 0.40);
    }

    @Test
    void noShowAfterCalibration_multipliesTheBlendedBand() {
        Band blended = model(own(10, 4)).rate(ORG, "loyal", Fit.SAME, 0).band();
        Band withNoShow = model(own(10, 4)).rate(ORG, "loyal", Fit.SAME, 2).band();
        assertBand(withNoShow, blended.low() * 0.4, blended.mid() * 0.7, blended.high());
    }

    @Test
    void ownTenInvitedFourBought_givesFixtureBand() {
        Band band = model(own(10, 4)).rate(ORG, "loyal", Fit.SAME, 0).band();
        assertThat(band.low()).isCloseTo(0.146, within(0.001));
        assertThat(band.mid()).isCloseTo(0.300, within(0.001));
        assertThat(band.high()).isCloseTo(0.403, within(0.001));
    }

    @Test
    void iminAndOwnObservations_areAddedTogether() {
        CalibrationSource split = (o, c, f) -> new Observations(new Counts(6, 2), new Counts(4, 2));
        assertThat(model(split).rate(ORG, "loyal", Fit.SAME, 0).band())
                .isEqualTo(model(own(10, 4)).rate(ORG, "loyal", Fit.SAME, 0).band());
    }

    @Test
    void calibration_startsFromTheFitModifiedPrior() {
        // adjacent loyal prior mid 0.125 → α0 = 2.5, β0 = 17.5; + 10 invited / 4 bought.
        Band band = model(own(10, 4)).rate(ORG, "loyal", Fit.ADJACENT, 0).band();
        assertThat(band.mid()).isCloseTo(6.5 / 30, within(EPS));
    }

    @Test
    void calibrationSource_isAskedForOrgClassAndFit() {
        List<Object> seen = new ArrayList<>();
        CalibrationSource spy = (o, c, f) -> {
            seen.addAll(List.of(o, c, f));
            return Observations.NONE;
        };
        model(spy).rate(ORG, "repeat", Fit.OTHER, 0);
        assertThat(seen).containsExactly(ORG, "repeat", Fit.OTHER);
    }

    @Test
    void confidence_own19_iminNone_isPrior() {
        assertThat(confidence(0, 19)).isEqualTo(Confidence.PRIOR);
    }

    @Test
    void confidence_own19_imin20_isImin() {
        assertThat(confidence(20, 19)).isEqualTo(Confidence.IMIN);
    }

    @Test
    void confidence_own20_isOwn() {
        assertThat(confidence(0, 20)).isEqualTo(Confidence.OWN);
    }

    @Test
    void confidence_imin20_ownNone_isImin() {
        assertThat(confidence(20, 0)).isEqualTo(Confidence.IMIN);
    }

    @Test
    void confidence_imin19_ownNone_isPrior() {
        assertThat(confidence(19, 0)).isEqualTo(Confidence.PRIOR);
    }

    @Test
    void confidence_bothAtThreshold_isOwn() {
        assertThat(confidence(20, 20)).isEqualTo(Confidence.OWN);
    }

    @Test
    void classWithoutPrior_isRejected() {
        assertThatThrownBy(() -> model(CalibrationSource.NONE).rate(ORG, "none", Fit.SAME, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no purchase-rate prior for class: none");
    }

    @Test
    void springBeans_bindEmptyCalibrationAndModel() {
        new ApplicationContextRunner()
                .withUserConfiguration(AudiencePlanConfig.class)
                .run(ctx -> {
                    assertThat(ctx.getBean(CalibrationSource.class)).isSameAs(CalibrationSource.NONE);
                    Rate rate = ctx.getBean(ResponseModel.class).rate(ORG, "loyal", Fit.SAME, 0);
                    assertThat(rate.band()).isEqualTo(new Band(0.12, 0.25, 0.40));
                    assertThat(rate.confidence()).isEqualTo(Confidence.PRIOR);
                });
    }

    // ---- helpers ----

    private static Confidence confidence(int iminInvited, int ownInvited) {
        CalibrationSource source = (o, c, f) ->
                new Observations(new Counts(iminInvited, 0), new Counts(ownInvited, 0));
        return model(source).rate(ORG, "loyal", Fit.SAME, 0).confidence();
    }

    private static CalibrationSource own(int invited, int bought) {
        return (o, c, f) -> new Observations(Counts.ZERO, new Counts(invited, bought));
    }

    private static ResponseModel model(CalibrationSource source) {
        return new ResponseModel(LOGIC, source);
    }

    private static void assertBand(Band band, double low, double mid, double high) {
        assertThat(band.low()).isCloseTo(low, within(EPS));
        assertThat(band.mid()).isCloseTo(mid, within(EPS));
        assertThat(band.high()).isCloseTo(high, within(EPS));
    }

    private static AudiencePlanLogic shipped() {
        try (InputStream logic = resource("audienceplan/logic-v1.yaml");
             InputStream priors = resource("audienceplan/priors-v1.yaml");
             InputStream genres = resource("audienceplan/genres-v1.yaml")) {
            return LogicLoader.parse(logic, priors, genres);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static InputStream resource(String path) {
        return ResponseModelTest.class.getClassLoader().getResourceAsStream(path);
    }
}
