package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictorSegmentStatus;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.repository.PredictorSegmentStatusRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The founders-only calibration instrument panel (spec §5 "internal calibration view before
 * external users"; task 86cav476q). Renders accuracy FROM RECORDED DATA ALONE — the ledger's
 * scored renders, the outcome record's realized flags, and the V72 segment status. No live
 * model calls, no recomputation of predictions.
 *
 * <p>Cross-org by nature (the ledger is), which is exactly why this lives behind the static
 * internal token and NOT in the organizer dashboard.
 *
 * <p>Plain server-rendered HTML with inline CSS — an instrument panel, not a product surface.
 */
@Service
public class CalibrationViewService {

    /** Predicted sell-out-probability buckets for the calibration table (band midpoints, %). */
    private static final int[][] BUCKETS = {{0, 20}, {20, 40}, {40, 60}, {60, 80}, {80, 101}};

    private final PredictionLedgerRepository ledgerRepo;
    private final EventOutcomeRepository outcomeRepo;
    private final PredictorSegmentStatusRepository segmentRepo;

    public CalibrationViewService(PredictionLedgerRepository ledgerRepo, EventOutcomeRepository outcomeRepo,
                                  PredictorSegmentStatusRepository segmentRepo) {
        this.ledgerRepo = ledgerRepo;
        this.outcomeRepo = outcomeRepo;
        this.segmentRepo = segmentRepo;
    }

    /** One scored render with everything the panel needs, parsed once. */
    private record ScoredRender(PredictionLedger row, PredictionResult result, EventOutcome outcome) {}

    @Transactional(readOnly = true)
    public String render() {
        List<PredictionLedger> all = ledgerRepo.findAll();
        List<PredictionLedger> joinedRows = all.stream().filter(r -> r.getOutcomeJoinedAt() != null).toList();

        Map<UUID, EventOutcome> outcomes = new HashMap<>();
        for (PredictionLedger r : joinedRows) {
            outcomes.computeIfAbsent(r.getEventId(), id -> outcomeRepo.findById(id).orElse(null));
        }
        List<ScoredRender> scored = new ArrayList<>();
        for (PredictionLedger r : joinedRows) {
            EventOutcome o = outcomes.get(r.getEventId());
            if (o == null) continue;
            PredictionResult res = parse(r);
            if (res == null) continue;
            scored.add(new ScoredRender(r, res, o));
        }

        StringBuilder h = new StringBuilder();
        h.append("<html><head><title>predictor calibration</title>")
                .append("<style>body{font-family:ui-monospace,monospace;margin:2rem;color:#111;background:#fafafa}")
                .append("h1{font-size:1.2rem} h2{font-size:1rem;margin-top:2rem}")
                .append("table{border-collapse:collapse;margin-top:.5rem}")
                .append("td,th{border:1px solid #bbb;padding:.3rem .6rem;text-align:right;font-size:.85rem}")
                .append("th{background:#eee} td:first-child,th:first-child{text-align:left}")
                .append(".warn{color:#a40000;font-weight:bold} .muted{color:#777}")
                .append("</style></head><body>");
        h.append("<h1>AI Success Predictor — calibration (internal)</h1>");
        h.append("<p class=muted>rendered from the prediction ledger at ").append(Instant.now())
                .append(" · renders: ").append(all.size())
                .append(" · outcome-joined: ").append(joinedRows.size())
                .append(" · scored (parseable + outcome): ").append(scored.size()).append("</p>");

        calibrationTable(h, scored);
        coverage(h, scored);
        segments(h);

        h.append("</body></html>");
        return h.toString();
    }

    // ---- calibration: predicted band vs realized frequency -----------------------

    private void calibrationTable(StringBuilder h, List<ScoredRender> scored) {
        h.append("<h2>Sell-out calibration — predicted band midpoint vs realized frequency</h2>");
        h.append("<table><tr><th>predicted bucket</th><th>renders</th><th>mean predicted</th>")
                .append("<th>realized sell-out rate</th></tr>");
        for (int[] b : BUCKETS) {
            int n = 0, sellOuts = 0;
            double predictedSum = 0;
            for (ScoredRender s : scored) {
                PredictionResult.Band band = s.result().selloutBand();
                Boolean actual = s.outcome().getSellOut();
                if (band == null || actual == null) continue;
                double mid = (band.lowPct() + band.highPct()) / 2.0;
                if (mid >= b[0] && mid < b[1]) {
                    n++;
                    predictedSum += mid;
                    if (Boolean.TRUE.equals(actual)) sellOuts++;
                }
            }
            h.append("<tr><td>").append(b[0]).append("–").append(Math.min(b[1], 100)).append("%</td>");
            if (n == 0) {
                h.append("<td>0</td><td class=muted>—</td><td class=muted>no data yet</td></tr>");
            } else {
                h.append("<td>").append(n).append("</td><td>").append(pct(predictedSum / n))
                        .append("</td><td>").append(pct(100.0 * sellOuts / n)).append("</td></tr>");
            }
        }
        h.append("</table>");
        h.append("<p class=muted>a calibrated predictor's realized rate tracks its predicted bucket.</p>");
    }

    // ---- coverage (the honesty metric) + MAPE ------------------------------------

    private void coverage(StringBuilder h, List<ScoredRender> scored) {
        int attN = 0, attIn = 0, revN = 0, revIn = 0, apeN = 0;
        double apeSum = 0;
        for (ScoredRender s : scored) {
            PredictionResult.Range att = s.result().attendanceRange();
            Integer actualAtt = s.outcome().getAttendance();
            if (att != null && actualAtt != null) {
                attN++;
                if (actualAtt >= att.low() && actualAtt <= att.high()) attIn++;
            }
            PredictionResult.LongRange rev = s.result().revenueRangeMinor();
            Long actualRev = s.outcome().getGrossRevenueMinor();
            if (rev != null && actualRev != null) {
                revN++;
                if (actualRev >= rev.low() && actualRev <= rev.high()) revIn++;
            }
            if (s.row().getApe() != null) {
                apeN++;
                apeSum += s.row().getApe().doubleValue();
            }
        }
        h.append("<h2>Band coverage — % of outcomes inside the predicted range (the honesty metric)</h2>");
        h.append("<table><tr><th>range</th><th>scored</th><th>inside</th><th>coverage</th></tr>");
        coverageRow(h, "attendance", attN, attIn);
        coverageRow(h, "revenue", revN, revIn);
        h.append("</table>");
        h.append("<h2>Attendance MAPE (overall)</h2>");
        if (apeN == 0) {
            h.append("<p class=muted>no scored attendance claims yet</p>");
        } else {
            double mape = apeSum / apeN;
            h.append("<p>").append(pct(mape * 100)).append(" over ").append(apeN).append(" renders")
                    .append(mape > PredictionScoringJob.MAPE_TRIPWIRE
                            ? " <span class=warn>(above the 25% tripwire)</span>" : "")
                    .append("</p>");
        }
    }

    private static void coverageRow(StringBuilder h, String label, int n, int inside) {
        h.append("<tr><td>").append(label).append("</td><td>").append(n).append("</td><td>").append(inside)
                .append("</td><td>").append(n == 0 ? "<span class=muted>no data yet</span>" : pct(100.0 * inside / n))
                .append("</td></tr>");
    }

    // ---- per-segment status (Brier vs base rate, MAPE, tier overrides) -----------

    private void segments(StringBuilder h) {
        List<PredictorSegmentStatus> rows = segmentRepo.findAll();
        h.append("<h2>Per-segment status (genre × capacity band) — Brier vs base rate, tier overrides</h2>");
        if (rows.isEmpty()) {
            h.append("<p class=muted>no scored segments yet (monthly job fills this)</p>");
            return;
        }
        h.append("<table><tr><th>segment</th><th>scored</th><th>brier</th><th>base-rate brier</th>")
                .append("<th>mape</th><th>override</th><th>downgraded</th><th>reason</th></tr>");
        for (PredictorSegmentStatus s : rows) {
            boolean overridden = s.getLanguageTierOverride() != null;
            h.append("<tr><td>").append(esc(s.getSegmentKey())).append("</td>")
                    .append("<td>").append(s.getScoredCount()).append("</td>")
                    .append("<td>").append(numOrDash(s.getBrier())).append("</td>")
                    .append("<td>").append(numOrDash(s.getBaseRateBrier())).append("</td>")
                    .append("<td>").append(numOrDash(s.getMape())).append("</td>")
                    .append("<td>").append(overridden
                            ? "<span class=warn>" + esc(s.getLanguageTierOverride()) + "</span>" : "—").append("</td>")
                    .append("<td>").append(s.getDowngradedAt() == null ? "—" : s.getDowngradedAt()).append("</td>")
                    .append("<td>").append(s.getReason() == null ? "—" : esc(s.getReason())).append("</td></tr>");
        }
        h.append("</table>");
        h.append("<p class=muted>downgrades are automatic; upgrades are manual only (V72 header has the SQL).</p>");
    }

    // ---- helpers -----------------------------------------------------------------

    private PredictionResult parse(PredictionLedger row) {
        try {
            return PredictorJson.MAPPER.readValue(row.getOutputJson(), PredictionResult.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String pct(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", v);
    }

    private static String numOrDash(Object v) {
        return v == null ? "—" : esc(v.toString());
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
