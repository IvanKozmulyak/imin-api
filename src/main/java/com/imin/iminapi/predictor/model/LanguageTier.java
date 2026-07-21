package com.imin.iminapi.predictor.model;

/**
 * The §5 language ladder — confidence wording EARNED by comparable-corpus density, never
 * chosen by the LLM:
 * <ul>
 *   <li><b>C</b> — "assessment": qualitative wording, wide bands, explicit "based on market
 *       priors, not yet on imin outcome data" label. Density below the Tier-B floor.</li>
 *   <li><b>B</b> — "estimate": density in the middle band.</li>
 *   <li><b>A</b> — "forecast": high density INCLUDING a minimum of the organizer's own
 *       completed events.</li>
 * </ul>
 * Thresholds live in {@code PredictorProperties} (config, tuned against the ledger — the
 * starting values are honest guesses and say so there). Downgrade tripwires drop a segment
 * one tier via {@link #dropOne()}; upgrades are MANUAL only (spec §5).
 */
public enum LanguageTier {
    A, B, C;

    /** One tier down (the §5 automatic-downgrade step). C is the floor. */
    public LanguageTier dropOne() {
        return switch (this) {
            case A -> B;
            case B, C -> C;
        };
    }

    public String wire() { return name(); }
}
