package com.imin.iminapi.predictor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the AI Success Predictor's LEDGER phase (data foundation).
 * Prefix {@code imin.predictor}. Only operational knobs live here — the hard
 * privacy invariants (≥5 comparable cluster, attendance→10 / revenue→€100 rounding)
 * are compile-time constants in {@code ComparableCorpusService}, NOT config, so they
 * can never be misconfigured below the legal floor (spec §5/§6.4, gate item 5).
 */
@ConfigurationProperties(prefix = "imin.predictor")
public class PredictorProperties {

    /**
     * Days after an event's {@code endsAt} before the finalize job records its outcome.
     * Lets late refunds settle before the result is frozen (mirrors the payout buffer).
     */
    private int finalizeGraceDays = 3;

    /** Page size for the one-shot outcome/trajectory backfill scans. */
    private int backfillPageSize = 500;

    // ---- SCORE phase (Stage 0) ------------------------------------------------

    /**
     * Model id for the Stage 0 scoring call. Blank = fall back to the platform-wide
     * {@code openrouter.model}. Stamped verbatim on every ledger row (§7.3 versioning).
     */
    private String model = "";

    /**
     * GLOBAL KILL SWITCH (spec §5, house pattern): when true, every scoring request returns a
     * benchmark-only result — comparable-segment stats, NO forward-looking numbers, no LLM
     * call, no quota burn. Checked at request time, so flipping the env var takes effect on
     * the next score without a deploy. Pre-authorized cut §9.4: invoking it needs no meeting.
     */
    private boolean benchmarkOnly = false;

    /**
     * Static bearer secret for the founders-only calibration view. BLANK (the default) keeps
     * the endpoint completely dark (404). Compared constant-time.
     */
    private String internalToken = "";

    // Language-ladder thresholds (§5). HONEST GUESSES: these starting values are the spec's
    // starting values, expected to be re-tuned against the ledger once real segments have
    // scored events — that is why they are config and not constants.

    /** Comparable density below which a segment speaks at Tier C ("assessment"). */
    private int tierBMinDensity = 8;

    /** Comparable density from which Tier A ("forecast") becomes reachable. */
    private int tierAMinDensity = 25;

    /** Tier A additionally requires at least this many of the organizer's OWN completed events. */
    private int tierAOwnMin = 3;

    public int getFinalizeGraceDays() { return finalizeGraceDays; }
    public void setFinalizeGraceDays(int finalizeGraceDays) { this.finalizeGraceDays = finalizeGraceDays; }

    public int getBackfillPageSize() { return backfillPageSize; }
    public void setBackfillPageSize(int backfillPageSize) { this.backfillPageSize = backfillPageSize; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public boolean isBenchmarkOnly() { return benchmarkOnly; }
    public void setBenchmarkOnly(boolean benchmarkOnly) { this.benchmarkOnly = benchmarkOnly; }

    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) { this.internalToken = internalToken; }

    public int getTierBMinDensity() { return tierBMinDensity; }
    public void setTierBMinDensity(int tierBMinDensity) { this.tierBMinDensity = tierBMinDensity; }

    public int getTierAMinDensity() { return tierAMinDensity; }
    public void setTierAMinDensity(int tierAMinDensity) { this.tierAMinDensity = tierAMinDensity; }

    public int getTierAOwnMin() { return tierAOwnMin; }
    public void setTierAOwnMin(int tierAOwnMin) { this.tierAOwnMin = tierAOwnMin; }
}
