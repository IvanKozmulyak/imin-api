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

    public int getFinalizeGraceDays() { return finalizeGraceDays; }
    public void setFinalizeGraceDays(int finalizeGraceDays) { this.finalizeGraceDays = finalizeGraceDays; }

    public int getBackfillPageSize() { return backfillPageSize; }
    public void setBackfillPageSize(int backfillPageSize) { this.backfillPageSize = backfillPageSize; }
}
