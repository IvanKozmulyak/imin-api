package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Band;
import com.imin.iminapi.audienceplan.engine.CalibrationSource.Counts;
import org.apache.commons.math3.distribution.BetaDistribution;

/**
 * Turns a YAML prior band plus observations into a band that moves smoothly from the YAML bounds toward the
 * Beta posterior's 80% interval, so the first observation never makes the range jump.
 */
public final class BetaBand {

    static final double P_LOW = 0.10;
    static final double P_HIGH = 0.90;

    private BetaBand() {}

    /**
     * n = 0 returns {@code prior} unchanged. Otherwise, with w = n / (n + strength), each bound is
     * (1 − w)·YAML + w·posterior quantile, mid is the posterior mean, and the bounds are clamped around mid.
     */
    public static Band blend(Band prior, int priorStrength, Counts observed) {
        if (priorStrength <= 0) throw new IllegalArgumentException("prior strength must be > 0: " + priorStrength);
        int n = observed.invited();
        if (n == 0) return prior;

        double alpha = prior.mid() * priorStrength + observed.bought();
        double beta = (1 - prior.mid()) * priorStrength + (n - observed.bought());
        double w = (double) n / (n + priorStrength);

        double mid = alpha / (alpha + beta);
        double low = (1 - w) * prior.low() + w * quantile(alpha, beta, P_LOW);
        double high = (1 - w) * prior.high() + w * quantile(alpha, beta, P_HIGH);
        return new Band(Math.min(low, mid), mid, Math.max(high, mid));
    }

    /** A zero shape parameter is a point mass (prior mid 0 or 1 with no opposing observation). */
    static double quantile(double alpha, double beta, double p) {
        if (alpha == 0) return 0;
        if (beta == 0) return 1;
        return new BetaDistribution(null, alpha, beta, BetaDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY)
                .inverseCumulativeProbability(p);
    }
}
