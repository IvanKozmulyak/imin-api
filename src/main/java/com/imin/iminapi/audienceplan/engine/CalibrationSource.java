package com.imin.iminapi.audienceplan.engine;

import java.util.UUID;

/**
 * Observed invitations and purchases that calibrate a response prior. {@code imin} must exclude the org's own
 * counts, since the model adds both. Arms are summed by the source.
 */
public interface CalibrationSource {

    /** No observations anywhere: every band stays the YAML prior. */
    CalibrationSource NONE = (orgId, classKey, fit) -> Observations.NONE;

    Observations observations(UUID orgId, String classKey, ResponseModel.Fit fit);

    /** Changes whenever the observations do; 0 means none are stored. Plans hash it. */
    default int version() {
        return 0;
    }

    record Counts(int invited, int bought) {

        public static final Counts ZERO = new Counts(0, 0);

        public Counts {
            if (invited < 0) throw new IllegalArgumentException("invited must be >= 0: " + invited);
            if (bought < 0) throw new IllegalArgumentException("bought must be >= 0: " + bought);
            if (bought > invited) {
                throw new IllegalArgumentException("bought " + bought + " exceeds invited " + invited);
            }
        }

        public Counts plus(Counts other) {
            return new Counts(invited + other.invited, bought + other.bought);
        }
    }

    record Observations(Counts imin, Counts own) {

        public static final Observations NONE = new Observations(Counts.ZERO, Counts.ZERO);

        public Counts total() {
            return imin.plus(own);
        }
    }
}
