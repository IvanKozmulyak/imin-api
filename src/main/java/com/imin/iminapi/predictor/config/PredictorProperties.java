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

    // ---- FORECAST phase (Stage 1 pacing / live re-forecast) -------------------

    /**
     * Minimum number of completed comparable events a segment needs before its pacing curve
     * (median + P25/P75 spread) is trusted for a live projection (spec §7 Stage 1: "~10–15
     * events complete"). HONEST GUESS at the low end of that range — below this the
     * re-forecast falls back to the EXPLICITLY-LABELLED Stage 0 interim rather than projecting
     * off a curve built from too few shapes. Config so it can be tuned against the ledger.
     */
    private int minCurveEvents = 12;

    /**
     * Upper bound (in days-to-event) for the pacing-curve sample grid. Curves are sampled at
     * every integer day-out from 0 up to the segment's own longest observed lead, capped here
     * so a single very-early outlier can't blow up the persisted points JSON. 90 days spans
     * the launch market's typical sell window.
     */
    private int pacingMaxDaysOut = 90;

    /**
     * Leading-edge debounce window (seconds) for milestone/tier/campaign re-forecast triggers:
     * the FIRST trigger for an event fires immediately, further triggers inside the window are
     * coalesced away so one sales burst does not recompute N times (spec §4.2 "recompute is
     * idempotent" / task "dedup within a short window"). The daily cron and later triggers pick
     * up any state the coalesced ones would have caught.
     */
    private int reforecastDebounceSeconds = 60;

    /**
     * ± window (days) around an event's date for the "competing nights on imin" signal — other
     * same-city platform events near the date. Real platform data, not an external calendar.
     */
    private int competingNightsWindowDays = 1;

    /**
     * Weather signal toggle (founder override of spec §6.3 "weather explicitly out" — lean, live
     * re-forecast only). Bound to {@code ${PREDICTOR_WEATHER_ENABLED:true}}.
     */
    private boolean weatherEnabled = true;

    /**
     * Horizon cap (days-to-event) for the weather signal: beyond this the forecast is not
     * reliable, so weather is null (never fabricated). Open-Meteo's free forecast spans ~16 days.
     */
    private int weatherMaxHorizonDays = 14;

    // ---- Organizer-action reactivity (system-initiated re-scores/re-forecasts, task scope B) ----

    /** ± debounce window (seconds) coalescing autosave bursts of organizer edits into one recompute. */
    private int actionDebounceSeconds = 120;

    /**
     * Daily cap on SYSTEM-initiated re-scores/re-forecasts per event (organizer-action triggers).
     * These bypass the per-organizer LLM quota but are capped here so a pathological edit loop
     * cannot burn budget; a WARN is logged when the cap is hit.
     */
    private int systemRescoresPerEventPerDay = 10;

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

    public int getMinCurveEvents() { return minCurveEvents; }
    public void setMinCurveEvents(int minCurveEvents) { this.minCurveEvents = minCurveEvents; }

    public int getPacingMaxDaysOut() { return pacingMaxDaysOut; }
    public void setPacingMaxDaysOut(int pacingMaxDaysOut) { this.pacingMaxDaysOut = pacingMaxDaysOut; }

    public int getReforecastDebounceSeconds() { return reforecastDebounceSeconds; }
    public void setReforecastDebounceSeconds(int reforecastDebounceSeconds) {
        this.reforecastDebounceSeconds = reforecastDebounceSeconds;
    }

    public int getCompetingNightsWindowDays() { return competingNightsWindowDays; }
    public void setCompetingNightsWindowDays(int competingNightsWindowDays) {
        this.competingNightsWindowDays = competingNightsWindowDays;
    }

    public boolean isWeatherEnabled() { return weatherEnabled; }
    public void setWeatherEnabled(boolean weatherEnabled) { this.weatherEnabled = weatherEnabled; }

    public int getWeatherMaxHorizonDays() { return weatherMaxHorizonDays; }
    public void setWeatherMaxHorizonDays(int weatherMaxHorizonDays) {
        this.weatherMaxHorizonDays = weatherMaxHorizonDays;
    }

    public int getActionDebounceSeconds() { return actionDebounceSeconds; }
    public void setActionDebounceSeconds(int actionDebounceSeconds) {
        this.actionDebounceSeconds = actionDebounceSeconds;
    }

    public int getSystemRescoresPerEventPerDay() { return systemRescoresPerEventPerDay; }
    public void setSystemRescoresPerEventPerDay(int systemRescoresPerEventPerDay) {
        this.systemRescoresPerEventPerDay = systemRescoresPerEventPerDay;
    }
}
