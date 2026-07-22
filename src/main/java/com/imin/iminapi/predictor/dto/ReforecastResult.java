package com.imin.iminapi.predictor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * THE frozen live re-forecast wire contract (spec §4.2, task 86cav479j/86cav479m) — an FE agent
 * codes the pacing view against exactly this shape; do not rename or remove fields.
 *
 * <p>{@code status}: {@code none} (never forecast) | {@code insufficient_data} (no pacing curve
 * AND no Stage 0 interim to lean on) | {@code ready}. {@code stage}: 1 = deterministic pacing
 * arithmetic, 0 = LLM interim before curves exist (EXPLICITLY labelled so the FE renders the
 * label — never blended). {@code band} and {@code generatedAt} are present on every response
 * (band may be null only for {@code none}). Trust rules (spec §5): {@code projectedFinalRange}
 * is a RANGE, never a point; the numbers are COMPUTED by the pacing engine (they survive the
 * kill switch), while {@code narration} is the only generated field and regenerates ONLY on a
 * band change.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReforecastResult(
        String status,                    // none | insufficient_data | ready
        int stage,                        // 0 (LLM interim) | 1 (pacing)
        String band,                      // ProjectionBand wire code (null only when status=none)
        Range projectedFinalRange,        // nullable {low, high}, capacity-bounded
        RevenueRange revenueRangeMinor,   // nullable — projectedFinalRange × realized avg ticket price
        Double velocity,                  // nullable — tickets/day over the last 7 days (arithmetic)
        SellOutEta sellOutEta,            // nullable {earliest, latest} ISO-8601 dates
        Pacing pacing,                    // nullable — present only at stage 1
        String narration,                 // nullable — generated only on band change
        Alert alert,                      // nullable — most recent band-crossing alert for this event
        Ledger ledger,                    // assembled at serve time from the backing ledger row
        Instant generatedAt
) {

    /** Projected final sold range (integers), bounded by capacity. */
    public record Range(int low, int high) {}

    /** Projected gross-revenue range in minor units (projected sold × realized avg ticket price). */
    public record RevenueRange(long low, long high) {}

    /**
     * The most recent band-crossing alert (spec §4.2, task 86cav479r). {@code tone}: "up" when
     * the band strengthened, "down" when it weakened. {@code was}/{@code now} are honest band
     * phrases. Carried forward on unchanged recomputes so the served result always shows the
     * last crossing; null until one fires.
     */
    public record Alert(String tone, String was, String now, String firedAt) {}

    /**
     * The ledger stamp (spec §5 write-before-render): the row backing the served result. Assembled
     * at serve time from the {@code prediction_ledger} row — not persisted inside {@code output_json}
     * (it would be self-referential). Powers the FE's visible "logged before render" stamp.
     */
    public record Ledger(String id, int stage, String modelId, String promptVersion, String inputHash) {}

    /** Sell-out ETA window as ISO-8601 instants; null-fielded when a bound is open. */
    public record SellOutEta(String earliest, String latest) {}

    /** One comparable-band point: median + P25/P75 % of final sold at {@code daysOut}. */
    public record CurvePoint(int daysOut, double medianPct, double p25Pct, double p75Pct) {}

    /** One point of the event's own cumulative trajectory. */
    public record EventPoint(int daysOut, int cumulativeSold) {}

    /**
     * The pacing overlay (spec §4.2): the comparable band curve, the event's own curve, the
     * count of comparable completed events the band was built from, and the relaxation applied
     * to find them. The percentages are privacy-safe aggregates — see {@code PacingCurveService}.
     */
    public record Pacing(List<CurvePoint> curve, List<EventPoint> eventCurve,
                         int comparableEventsCount, String relaxation) {}

    /** A terminal "never forecast" result — band null, but generatedAt always present. */
    public static ReforecastResult none(Instant at) {
        return new ReforecastResult("none", 0, null, null, null, null, null, null, null, null, null, at);
    }

    /** Return a copy with the ledger stamp attached (called by the serving layer). */
    public ReforecastResult withLedger(Ledger stamp) {
        return new ReforecastResult(status, stage, band, projectedFinalRange, revenueRangeMinor, velocity,
                sellOutEta, pacing, narration, alert, stamp, generatedAt);
    }
}
