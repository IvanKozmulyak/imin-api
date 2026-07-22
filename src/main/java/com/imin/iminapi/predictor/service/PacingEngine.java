package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.model.ProjectionBand;
import com.imin.iminapi.predictor.service.SalesTrajectoryService.NormalizedCurve;
import com.imin.iminapi.predictor.service.SalesTrajectoryService.NormalizedPoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The deterministic pacing engine (spec §7 Stage 1, task 86cav479d). PURE ARITHMETIC — no LLM,
 * no clock, no I/O. Same inputs → byte-identical output (unit-tested). This is the whole reason
 * live re-forecast numbers survive the kill switch: they are COMPUTED, never generated (spec
 * §4.2 "numbers are computed, not generated").
 *
 * <p>Two operations:
 * <ol>
 *   <li>{@link #buildCurve} — from the normalized trajectories of a segment's completed events,
 *       build a median + P25/P75 spread of "% of final sold by day-out". Called by the daily
 *       {@code PacingCurveService}; the result is persisted.</li>
 *   <li>{@link #project} — map an in-flight event's current position (sold, days-out) through a
 *       persisted curve band into a projected final-sold RANGE, a {@link ProjectionBand}, and a
 *       sell-out ETA range (null when even the slowest-pace projection stays under capacity).</li>
 * </ol>
 *
 * <p>Percentiles use numpy-style linear interpolation (type 7) so synthetic inputs have exact,
 * testable medians/quartiles.
 */
@Service
public class PacingEngine {

    /** One sampled point of a segment curve: the band of "% of final sold" at {@code daysOut}. */
    public record CurvePoint(int daysOut, double medianPct, double p25Pct, double p75Pct) {}

    /** A built segment curve, points sorted by {@code daysOut} DESC (early window first). */
    public record Curve(int eventsCount, List<CurvePoint> points) {}

    /**
     * A live projection off a curve. {@code insufficient} = the curve/position could not yield an
     * arithmetic projection (no sold yet, or no comparable sales at this horizon) — the caller
     * then falls back to the EXPLICITLY-LABELLED Stage 0 interim. {@code finalLow}/{@code finalHigh}
     * are capacity-clamped for display; {@code band} is classified on the RAW midpoint. Sell-out
     * ETA is in days-out (caller converts to a date via the event start); null when no pace in the
     * band reaches capacity.
     */
    public record Projection(boolean insufficient, int finalLow, int finalHigh, ProjectionBand band,
                             Integer sellOutEarliestDaysOut, Integer sellOutLatestDaysOut) {
        static Projection insufficientResult() {
            return new Projection(true, 0, 0, ProjectionBand.UNDER_60, null, null);
        }
    }

    // ---- curve building --------------------------------------------------------

    /**
     * Build a segment curve from completed events' normalized trajectories. Each trajectory
     * contributes its "% of final sold" at every integer day-out on the grid; the grid runs from
     * the segment's longest observed lead (capped at {@code maxDaysOut}) down to 0. Events with no
     * dated points are skipped (they cannot place a shape on the day-out axis).
     *
     * <p>Deterministic: the grid, the per-event step read ({@link #pctAtDaysOut}), and the
     * percentile method are all fixed functions of the inputs.
     */
    public Curve buildCurve(List<NormalizedCurve> events, int maxDaysOut) {
        List<NormalizedCurve> usable = new ArrayList<>();
        int maxD = 0;
        for (NormalizedCurve e : events) {
            boolean hasDated = false;
            for (NormalizedPoint p : e.points()) {
                if (p.daysToEvent() != null) {
                    hasDated = true;
                    maxD = Math.max(maxD, p.daysToEvent());
                }
            }
            if (hasDated) usable.add(e);
        }
        maxD = Math.min(maxD, maxDaysOut);

        List<CurvePoint> pts = new ArrayList<>();
        for (int d = maxD; d >= 0; d--) {
            double[] vals = new double[usable.size()];
            for (int i = 0; i < usable.size(); i++) {
                vals[i] = pctAtDaysOut(usable.get(i).points(), d);
            }
            java.util.Arrays.sort(vals);
            pts.add(new CurvePoint(d, percentile(vals, 50), percentile(vals, 25), percentile(vals, 75)));
        }
        return new Curve(usable.size(), pts);
    }

    /**
     * A single event's cumulative "% of final" as of {@code d} days before the event: the
     * cumulative pct at the LATEST sales day that is still &ge; d days out (step function; pct is
     * monotonic non-decreasing as the event nears). 0 when no sales had happened that early.
     */
    public static double pctAtDaysOut(List<NormalizedPoint> points, int d) {
        double best = 0.0;
        int bestDaysToEvent = Integer.MAX_VALUE;
        for (NormalizedPoint p : points) {
            Integer dte = p.daysToEvent();
            if (dte == null || dte < d) continue;
            if (dte < bestDaysToEvent) { // closest day-out that is still >= d = latest calendar time
                bestDaysToEvent = dte;
                best = p.pctOfFinal();
            }
        }
        return best;
    }

    // ---- projection ------------------------------------------------------------

    /**
     * Project an in-flight event through a curve band. {@code currentSold} at {@code daysOutNow}
     * is mapped through the median/P25/P75 pace at that horizon: a fast-pace (P75) read implies a
     * lower final, a slow-pace (P25) read a higher final. The displayed range is clamped to
     * {@code [currentSold, capacity]}; the band is classified on the raw midpoint.
     */
    public Projection project(Curve curve, int currentSold, int daysOutNow, int capacity) {
        if (currentSold <= 0 || curve == null || curve.points().isEmpty()) return Projection.insufficientResult();
        // No comparable coverage this early: the event is further out than any comparable's first
        // tracked sale. Extrapolating off the earliest curve point would be dishonest — the caller
        // falls back to the labelled Stage 0 interim instead.
        if (daysOutNow > curve.points().get(0).daysOut()) return Projection.insufficientResult();

        double medNow = interp(curve, daysOutNow, Pace.MEDIAN);
        double p25Now = interp(curve, daysOutNow, Pace.P25);
        double p75Now = interp(curve, daysOutNow, Pace.P75);
        if (medNow <= 0 && p25Now <= 0 && p75Now <= 0) return Projection.insufficientResult();

        // Fast pace (higher pct-of-final now) → smaller final; slow pace → larger final.
        double rawLow = p75Now > 0 ? currentSold / p75Now : currentSold;
        double rawHigh = p25Now > 0 ? currentSold / p25Now : capacity; // p25==0 → unbounded, cap at capacity
        if (rawHigh < rawLow) rawHigh = rawLow;

        int finalLow = clamp((int) Math.round(rawLow), currentSold, Math.max(currentSold, capacity));
        int finalHigh = clamp((int) Math.round(rawHigh), currentSold, Math.max(currentSold, capacity));

        ProjectionBand band = ProjectionBand.classify((rawLow + rawHigh) / 2.0, capacity);

        // Sell-out ETA: per pace curve that actually reaches capacity, the largest day-out where
        // the event's projected cumulative first hits capacity. Range = [earliest date, latest date]
        // = [max day-out, min day-out] across those paces. None reach → null (band top < capacity).
        Integer earliest = null, latest = null;
        for (Pace pace : Pace.values()) {
            double cNow = interp(curve, daysOutNow, pace);
            if (cNow <= 0) continue;
            if (currentSold / cNow < capacity) continue; // this pace never reaches capacity
            Integer eta = sellOutDaysOut(curve, pace, currentSold, cNow, daysOutNow, capacity);
            if (eta == null) continue;
            earliest = (earliest == null) ? eta : Math.max(earliest, eta);
            latest = (latest == null) ? eta : Math.min(latest, eta);
        }
        return new Projection(false, finalLow, finalHigh, band, earliest, latest);
    }

    /** Largest day-out (&le; now) at which projected cumulative first reaches capacity under {@code pace}. */
    private Integer sellOutDaysOut(Curve curve, Pace pace, int currentSold, double cNow, int daysOutNow, int capacity) {
        for (CurvePoint p : curve.points()) { // sorted daysOut DESC → first match is the largest day-out
            if (p.daysOut() > daysOutNow) continue;
            double projCum = currentSold * (value(p, pace) / cNow);
            if (projCum >= capacity) return p.daysOut();
        }
        return null;
    }

    private enum Pace { MEDIAN, P25, P75 }

    private static double value(CurvePoint p, Pace pace) {
        return switch (pace) {
            case MEDIAN -> p.medianPct();
            case P25 -> p.p25Pct();
            case P75 -> p.p75Pct();
        };
    }

    /**
     * Linear-interpolated curve value at an arbitrary {@code daysOut}. Points are sorted daysOut
     * DESC; between grid points we interpolate, and outside the grid we clamp to the nearest end
     * (an event further out than any curve point reads the earliest, lowest pct).
     */
    private static double interp(Curve curve, int daysOut, Pace pace) {
        List<CurvePoint> pts = curve.points();
        if (pts.isEmpty()) return 0.0;
        CurvePoint first = pts.get(0);              // largest daysOut
        CurvePoint last = pts.get(pts.size() - 1);  // daysOut 0
        if (daysOut >= first.daysOut()) return value(first, pace);
        if (daysOut <= last.daysOut()) return value(last, pace);
        for (int i = 0; i < pts.size() - 1; i++) {
            CurvePoint hi = pts.get(i);      // larger daysOut
            CurvePoint lo = pts.get(i + 1);  // smaller daysOut
            if (daysOut <= hi.daysOut() && daysOut >= lo.daysOut()) {
                int span = hi.daysOut() - lo.daysOut();
                if (span == 0) return value(hi, pace);
                double frac = (double) (daysOut - lo.daysOut()) / span;
                return value(lo, pace) * (1 - frac) + value(hi, pace) * frac;
            }
        }
        return value(last, pace);
    }

    /** numpy-style (type 7) percentile over a pre-sorted array. Empty → 0. */
    public static double percentile(double[] sorted, double p) {
        int n = sorted.length;
        if (n == 0) return 0.0;
        if (n == 1) return sorted[0];
        double rank = p / 100.0 * (n - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted[lo];
        double frac = rank - lo;
        return sorted[lo] * (1 - frac) + sorted[hi] * frac;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
