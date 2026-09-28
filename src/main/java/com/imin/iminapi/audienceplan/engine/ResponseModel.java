package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.ClassPrior;
import com.imin.iminapi.audienceplan.engine.CalibrationSource.Observations;

import java.util.Objects;
import java.util.UUID;

/**
 * Purchase-rate band for one invitee: class prior × genre-fit modifier, calibrated Beta-Binomial on
 * IMIN-wide then own observations, then × the no-show band. Never a single number.
 */
public final class ResponseModel {

    /** Invitations of a class needed before its band is labelled as own or IMIN data. */
    public static final int CONFIDENT_INVITATIONS = 20;

    /** {@code UNKNOWN}: the person has no taste yet, so no genre comparison is possible. */
    public enum Fit { SAME, ADJACENT, OTHER, UNKNOWN }

    public enum Confidence { OWN, IMIN, PRIOR }

    public record Rate(Band band, Confidence confidence) {}

    private final AudiencePlanLogic logic;
    private final CalibrationSource calibration;

    public ResponseModel(AudiencePlanLogic logic, CalibrationSource calibration) {
        this.logic = Objects.requireNonNull(logic);
        this.calibration = Objects.requireNonNull(calibration);
    }

    /** The calibration the bands are built from; part of a plan's inputs. */
    public int calibrationVersion() {
        return calibration.version();
    }

    public Rate rate(UUID orgId, String classKey, Fit fit, int noShowN) {
        ClassPrior classPrior = logic.priors().classes().get(classKey);
        if (classPrior == null) throw new IllegalArgumentException("no purchase-rate prior for class: " + classKey);

        Band prior = scale(classPrior.purchaseRate(), modifier(fit));
        Observations observed = calibration.observations(orgId, classKey, fit);
        Band band = BetaBand.blend(prior, logic.priors().priorStrengthInvitations(), observed.total());
        if (noShowN > 0) band = times(band, logic.priors().noShowBefore());
        return new Rate(band, confidence(observed));
    }

    private double modifier(Fit fit) {
        AudiencePlanLogic.GenreFit m = logic.priors().genreFit();
        return switch (fit) {
            case SAME -> m.same();
            case ADJACENT -> m.adjacent();
            case OTHER -> m.other();
            case UNKNOWN -> m.unknown();
        };
    }

    private static Confidence confidence(Observations observed) {
        if (observed.own().invited() >= CONFIDENT_INVITATIONS) return Confidence.OWN;
        if (observed.imin().invited() >= CONFIDENT_INVITATIONS) return Confidence.IMIN;
        return Confidence.PRIOR;
    }

    private static Band scale(Band b, double factor) {
        return new Band(b.low() * factor, b.mid() * factor, b.high() * factor);
    }

    /** Bandwise product; order is kept because both bands are non-negative and sorted. */
    private static Band times(Band a, Band b) {
        return new Band(a.low() * b.low(), a.mid() * b.mid(), a.high() * b.high());
    }
}
