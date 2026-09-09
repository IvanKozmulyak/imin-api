package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.model.ProjectionBand;
import com.imin.iminapi.predictor.service.PacingEngine;
import com.imin.iminapi.predictor.service.PacingEngine.Curve;
import com.imin.iminapi.predictor.service.PacingEngine.Projection;
import com.imin.iminapi.predictor.service.SalesTrajectoryService.NormalizedCurve;
import com.imin.iminapi.predictor.service.SalesTrajectoryService.NormalizedPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Task 1 (pacing engine, 86cav479d): pure-arithmetic determinism, normalization correctness
 * (synthetic trajectories with known medians/quartiles), projection mapping and sell-out ETA.
 */
class PacingEngineTest {

    private final PacingEngine engine = new PacingEngine();

    private NormalizedCurve ev(double p10, double p5) {
        // Three dated points: 10 days out, 5 days out, event day (always 1.0 of final).
        return new NormalizedCurve(UUID.randomUUID(), 100, List.of(
                new NormalizedPoint(LocalDate.parse("2026-03-01"), 10, 20, p10),
                new NormalizedPoint(LocalDate.parse("2026-03-06"), 5, 50, p5),
                new NormalizedPoint(LocalDate.parse("2026-03-11"), 0, 100, 1.0)));
    }

    /** Curve of three events whose per-day-out quartiles are hand-computable. */
    private Curve curve() {
        return engine.buildCurve(List.of(ev(0.2, 0.5), ev(0.3, 0.6), ev(0.4, 0.7)), 90);
    }

    @Test
    void percentileIsNumpyLinearType7() {
        double[] xs = {0.2, 0.3, 0.4};
        assertThat(PacingEngine.percentile(xs, 50)).isEqualTo(0.3);
        assertThat(PacingEngine.percentile(xs, 25)).isCloseTo(0.25, within(1e-9));
        assertThat(PacingEngine.percentile(xs, 75)).isCloseTo(0.35, within(1e-9));
        assertThat(PacingEngine.percentile(new double[0], 50)).isEqualTo(0.0);
        assertThat(PacingEngine.percentile(new double[]{0.7}, 25)).isEqualTo(0.7);
    }

    @Test
    void pctAtDaysOutIsAStepFunctionOfLatestSaleAtLeastDOut() {
        List<NormalizedPoint> pts = ev(0.2, 0.5).points();
        assertThat(PacingEngine.pctAtDaysOut(pts, 20)).isEqualTo(0.0);  // nothing sold that early
        assertThat(PacingEngine.pctAtDaysOut(pts, 10)).isEqualTo(0.2);
        assertThat(PacingEngine.pctAtDaysOut(pts, 7)).isEqualTo(0.2);   // last point >= 7 is day-out 10
        assertThat(PacingEngine.pctAtDaysOut(pts, 5)).isEqualTo(0.5);
        assertThat(PacingEngine.pctAtDaysOut(pts, 0)).isEqualTo(1.0);
    }

    @Test
    void buildCurveProducesKnownMedianAndSpread() {
        Curve c = curve();
        assertThat(c.eventsCount()).isEqualTo(3);
        assertThat(c.points()).isNotEmpty();
        assertThat(c.points().get(0).daysOut()).isEqualTo(10);              // sorted DESC
        assertThat(c.points().get(c.points().size() - 1).daysOut()).isEqualTo(0);

        PacingEngine.CurvePoint at10 = pointAt(c, 10);
        assertThat(at10.medianPct()).isEqualTo(0.3);
        assertThat(at10.p25Pct()).isCloseTo(0.25, within(1e-9));
        assertThat(at10.p75Pct()).isCloseTo(0.35, within(1e-9));

        PacingEngine.CurvePoint at5 = pointAt(c, 5);
        assertThat(at5.medianPct()).isEqualTo(0.6);

        assertThat(pointAt(c, 0).medianPct()).isEqualTo(1.0);
    }

    @Test
    void buildCurveIsDeterministic() {
        assertThat(curve()).isEqualTo(curve());
    }

    @Test
    void projectMapsCurrentPositionToClampedFinalRange() {
        // sold 30 at day-out 5: p25=0.55, median=0.6, p75=0.65 → final ~[46, 55], no sell-out at cap 100.
        Projection p = engine.project(curve(), 30, 5, 100);
        assertThat(p.insufficient()).isFalse();
        assertThat(p.finalLow()).isEqualTo(46);   // 30 / 0.65
        assertThat(p.finalHigh()).isEqualTo(55);  // 30 / 0.55
        assertThat(p.band()).isEqualTo(ProjectionBand.UNDER_60);
        assertThat(p.sellOutEarliestDaysOut()).isNull(); // band top < capacity → null
        assertThat(p.sellOutLatestDaysOut()).isNull();
    }

    @Test
    void projectYieldsSellOutEtaWhenBandTopReachesCapacity() {
        // Same position, tighter capacity 40: all paces project past capacity → ETA range present.
        Projection p = engine.project(curve(), 30, 5, 40);
        assertThat(p.insufficient()).isFalse();
        assertThat(p.band()).isEqualTo(ProjectionBand.SELL_OUT_LIKELY);
        assertThat(p.sellOutEarliestDaysOut()).isNotNull();
        assertThat(p.sellOutLatestDaysOut()).isNotNull();
        // earliest date = largest day-out; latest date = smallest day-out; both within [0, now].
        assertThat(p.sellOutEarliestDaysOut()).isGreaterThanOrEqualTo(p.sellOutLatestDaysOut());
        assertThat(p.sellOutLatestDaysOut()).isBetween(0, 5);
        assertThat(p.sellOutEarliestDaysOut()).isBetween(0, 5);
    }

    @Test
    void projectIsInsufficientWithNoSalesOrNoComparableSalesAtHorizon() {
        assertThat(engine.project(curve(), 0, 5, 100).insufficient()).isTrue();   // nothing to project from
        // day-out 40 is beyond the curve's earliest point (day-out 10) where pct is 0 → insufficient.
        assertThat(engine.project(curve(), 10, 40, 100).insufficient()).isTrue();
    }

    @Test
    void projectIsInsufficientWhenMostComparablesHadNoSalesAtThisHorizon() {
        // 12 comparables at day-out 40: 8 had not sold a thing that early, 4 had sold {0.5, 1, 2, 5}%
        // of their final. Percentiles: median 0, p25 0, p75 0.00625. Dividing 3 sold tickets by
        // 0.00625 "projects" 480 against a capacity of 300 — a sell-out call off three tickets.
        List<NormalizedCurve> comparables = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) comparables.add(noSalesBy40());
        for (double pct : new double[]{0.005, 0.01, 0.02, 0.05}) comparables.add(tinyPctBy40(pct));
        Curve c = engine.buildCurve(comparables, 90);
        assertThat(c.eventsCount()).isEqualTo(12);
        assertThat(pointAt(c, 40).medianPct()).isEqualTo(0.0);
        assertThat(pointAt(c, 40).p75Pct()).isCloseTo(0.00625, within(1e-9));

        Projection p = engine.project(c, 3, 40, 300);
        assertThat(p.insufficient()).isTrue();          // no range, no band, no ETA
        assertThat(p.sellOutEarliestDaysOut()).isNull();
        assertThat(p.sellOutLatestDaysOut()).isNull();
    }

    @Test
    void projectIsInsufficientWhenTheSlowPaceEndIsUnknown() {
        // Healthy median (0.4) and fast end (p75 0.5), but a quarter of the comparables had no sale
        // at this horizon so p25 is 0 — the SLOW-pace end, i.e. the top of the range, is unknown.
        // Capacity is not an observation, so it must not stand in for it: no range at all.
        Curve c = new Curve(12, List.of(
                new PacingEngine.CurvePoint(10, 0.4, 0.0, 0.5),
                new PacingEngine.CurvePoint(0, 1.0, 1.0, 1.0)));

        Projection p = engine.project(c, 100, 10, 300);
        assertThat(p.insufficient()).isTrue();
        assertThat(p.finalHigh()).isNotEqualTo(300);   // the capacity ceiling was never a projection
        assertThat(p.sellOutEarliestDaysOut()).isNull();
    }

    @Test
    void projectIsDeterministic() {
        assertThat(engine.project(curve(), 30, 5, 40)).isEqualTo(engine.project(curve(), 30, 5, 40));
    }

    /** A comparable whose first tracked sale is 20 days out — nothing at all at day-out 40. */
    private NormalizedCurve noSalesBy40() {
        return new NormalizedCurve(UUID.randomUUID(), 100, List.of(
                new NormalizedPoint(LocalDate.parse("2026-03-01"), 20, 50, 0.5),
                new NormalizedPoint(LocalDate.parse("2026-03-21"), 0, 100, 1.0)));
    }

    /** A comparable that had sold {@code pct} of its final 40 days out, the rest later. */
    private NormalizedCurve tinyPctBy40(double pct) {
        return new NormalizedCurve(UUID.randomUUID(), 100, List.of(
                new NormalizedPoint(LocalDate.parse("2026-02-09"), 40, (int) Math.round(pct * 100), pct),
                new NormalizedPoint(LocalDate.parse("2026-03-21"), 0, 100, 1.0)));
    }

    private static PacingEngine.CurvePoint pointAt(Curve c, int daysOut) {
        return c.points().stream().filter(p -> p.daysOut() == daysOut).findFirst().orElseThrow();
    }
}
