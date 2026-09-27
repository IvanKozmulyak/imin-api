package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.engine.ModeSelector.Mode;
import com.imin.iminapi.audienceplan.engine.PlanCalculator.Tickets;

import java.util.Objects;

/**
 * Coverage = the rounded ticket totals over the target, truncated to 2 decimals (93/255 is 0.36, from 93 not 93.12).
 * The verdict reads the exact ratio, like CandidateBuilder's other-genre gate; truncation keeps the two consistent.
 * The verdict counts every shown segment, {@code other} and {@code unknown} fits included, while the other-genre
 * gate counts only same + adjacent, so the two can read different ratios for one plan.
 */
public final class CoverageVerdict {

    public enum Verdict { STRONG, MEDIUM, WEAK, COLD }

    /** Ratios of the target, 2 decimals; all three are null in cold mode, where nothing is computed. */
    public record Coverage(Double low, Double mid, Double high, Verdict verdict) {
        static final Coverage COLD = new Coverage(null, null, null, Verdict.COLD);
    }

    private CoverageVerdict() {}

    /** {@code expected} is ignored (and may be null) in cold mode. */
    public static Coverage of(Mode mode, Tickets expected, int targetTickets, AudiencePlanLogic.CoverageVerdict rule) {
        if (targetTickets <= 0) throw new IllegalArgumentException("target must be > 0: " + targetTickets);
        if (mode == Mode.COLD) return Coverage.COLD;
        Objects.requireNonNull(expected, "expected");
        return new Coverage(Rounding.ratio2(expected.low(), targetTickets), Rounding.ratio2(expected.mid(), targetTickets),
                Rounding.ratio2(expected.high(), targetTickets), verdict(expected, targetTickets, rule));
    }

    static Verdict verdict(Tickets expected, int targetTickets, AudiencePlanLogic.CoverageVerdict rule) {
        int point = switch (rule.verdictOn()) {
            case LOW -> expected.low();
            case MID -> expected.mid();
            case HIGH -> expected.high();
        };
        double ratio = (double) point / targetTickets;
        if (ratio >= rule.strong()) return Verdict.STRONG;
        if (ratio >= rule.medium()) return Verdict.MEDIUM;
        return Verdict.WEAK;
    }
}
