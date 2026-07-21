package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictorSegmentStatus;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.repository.PredictorSegmentStatusRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Monthly scoring + downgrade tripwires (spec §5, §7.3; task 86cav477c). Two passes:
 *
 * <p><b>1. Outcome join + per-render scoring.</b> For every un-joined ledger render whose
 * event outcome is finalized: fill actual sold/attendance and compute
 * <ul>
 *   <li>{@code brier_component} = (p − o)² where p = the render's sell-out band MIDPOINT as a
 *       probability and o = the realized sell-out flag (1/0). Null when the render made no
 *       sell-out claim (benchmark-only) or the outcome has no flag — a render that claimed
 *       nothing is not scored on nothing.</li>
 *   <li>{@code ape} = |attendance-range midpoint − actual| / actual (fraction; only when the
 *       render carried a numeric range and actual &gt; 0).</li>
 * </ul>
 *
 * <p><b>2. Per-segment aggregation + tripwires.</b> Segment = genre_family × capacity_band
 * from the event's outcome row (kept simple per the Score-phase brief). Once a metric has
 * ≥ {@link #TRIPWIRE_MIN_SCORED} scored renders in a segment:
 * <ul>
 *   <li>mean Brier ≥ base-rate Brier (the constant predictor answering the segment's
 *       empirical sell-out rate) → {@code DROP_ONE}: segment speaks one language tier lower;</li>
 *   <li>mean APE &gt; {@link #MAPE_TRIPWIRE} → {@code QUALITATIVE}: numeric attendance/revenue
 *       ranges drop to qualitative for the segment.</li>
 * </ul>
 * DOWNGRADES ARE AUTOMATIC (WARN-logged). UPGRADES ARE MANUAL ONLY: there is deliberately no
 * code path that clears or weakens an override — when metrics recover the job WARNs for
 * founder review instead. The manual-upgrade SQL is documented in the V72 migration header.
 */
@Component
public class PredictionScoringJob {

    private static final Logger log = LoggerFactory.getLogger(PredictionScoringJob.class);
    private static final int PAGE = 500;

    /** Tripwires evaluate only at/after this many scored renders per metric (spec §5: 20). */
    static final int TRIPWIRE_MIN_SCORED = 20;

    /** MAPE above this fraction drops the segment's numeric ranges to qualitative (spec §5: 25%). */
    static final double MAPE_TRIPWIRE = 0.25;

    private final PredictionLedgerRepository ledger;
    private final EventOutcomeRepository outcomes;
    private final PredictionLedgerService service;
    private final PredictorSegmentStatusRepository segments;
    private final Clock clock;

    public PredictionScoringJob(PredictionLedgerRepository ledger, EventOutcomeRepository outcomes,
                                PredictionLedgerService service, PredictorSegmentStatusRepository segments,
                                Clock clock) {
        this.ledger = ledger;
        this.outcomes = outcomes;
        this.service = service;
        this.segments = segments;
        this.clock = clock;
    }

    /** 05:00 UTC on the 1st of each month. */
    @Scheduled(cron = "0 0 5 1 * *")
    @SchedulerLock(name = "predictor_scoring", lockAtMostFor = "PT1H", lockAtLeastFor = "PT10S")
    public void run() {
        Instant now = clock.instant();
        joinAndScore(now);
        aggregateSegments(now);
    }

    // ---- pass 1: outcome join + per-render Brier / APE ---------------------------

    private void joinAndScore(Instant now) {
        List<PredictionLedger> unjoined = ledger.findByOutcomeJoinedAtIsNull(PageRequest.of(0, PAGE));
        int joined = 0;
        for (PredictionLedger row : unjoined) {
            EventOutcome o = outcomes.findById(row.getEventId()).orElse(null);
            if (o == null || o.getFinalizedAt() == null) continue; // outcome not finalized yet
            PredictionResult r = parse(row);
            service.joinOutcome(row.getId(), o.getSoldTotal(), o.getAttendance(), now,
                    brierComponent(r, o), ape(r, o));
            joined++;
        }
        if (joined > 0) {
            log.info("PredictionScoringJob: joined+scored {} ledger render(s)", joined);
        } else {
            log.debug("PredictionScoringJob: nothing to join");
        }
    }

    /** (bandMidpoint/100 − sellOut)², or null when either side made/has no claim. */
    public static BigDecimal brierComponent(PredictionResult r, EventOutcome o) {
        if (r == null || r.selloutBand() == null || o.getSellOut() == null) return null;
        double p = (r.selloutBand().lowPct() + r.selloutBand().highPct()) / 2.0 / 100.0;
        double actual = Boolean.TRUE.equals(o.getSellOut()) ? 1.0 : 0.0;
        return BigDecimal.valueOf((p - actual) * (p - actual)).setScale(6, RoundingMode.HALF_UP);
    }

    /** |attendance midpoint − actual| / actual, or null without a numeric claim / positive actual. */
    public static BigDecimal ape(PredictionResult r, EventOutcome o) {
        if (r == null || r.attendanceRange() == null) return null;
        Integer actual = o.getAttendance();
        if (actual == null || actual <= 0) return null;
        double mid = (r.attendanceRange().low() + r.attendanceRange().high()) / 2.0;
        return BigDecimal.valueOf(Math.abs(mid - actual) / actual).setScale(6, RoundingMode.HALF_UP);
    }

    private PredictionResult parse(PredictionLedger row) {
        try {
            return PredictorJson.MAPPER.readValue(row.getOutputJson(), PredictionResult.class);
        } catch (Exception ex) {
            return null; // unreadable output scores nothing — never invent a claim to score
        }
    }

    // ---- pass 2: segment aggregation + tripwires ---------------------------------

    private void aggregateSegments(Instant now) {
        List<PredictionLedger> scored = ledger.findByOutcomeJoinedAtIsNotNull();
        if (scored == null || scored.isEmpty()) return;

        Map<String, List<PredictionLedger>> bySegment = new HashMap<>();
        Map<UUID, Boolean> sellOutByEvent = new HashMap<>();
        for (PredictionLedger row : scored) {
            EventOutcome o = outcomes.findById(row.getEventId()).orElse(null);
            if (o == null) continue;
            String key = PredictorSegmentStatus.key(o.getGenreFamily(), o.getCapacityBand());
            bySegment.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            if (o.getSellOut() != null) sellOutByEvent.put(row.getEventId(), o.getSellOut());
        }

        for (Map.Entry<String, List<PredictionLedger>> entry : bySegment.entrySet()) {
            updateSegment(entry.getKey(), entry.getValue(), sellOutByEvent, now);
        }
    }

    private void updateSegment(String key, List<PredictionLedger> rows,
                               Map<UUID, Boolean> sellOutByEvent, Instant now) {
        List<PredictionLedger> brierRows = rows.stream().filter(r -> r.getBrierComponent() != null).toList();
        List<PredictionLedger> apeRows = rows.stream().filter(r -> r.getApe() != null).toList();

        BigDecimal meanBrier = mean(brierRows.stream().map(PredictionLedger::getBrierComponent).toList());
        BigDecimal meanApe = mean(apeRows.stream().map(PredictionLedger::getApe).toList());

        // Base-rate Brier: the constant predictor that always answers the segment's empirical
        // sell-out rate, scored over the SAME outcome set (empirically = rate·(1−rate)).
        BigDecimal baseRateBrier = null;
        if (!brierRows.isEmpty()) {
            double rate = brierRows.stream()
                    .mapToDouble(r -> Boolean.TRUE.equals(sellOutByEvent.get(r.getEventId())) ? 1.0 : 0.0)
                    .average().orElse(0.0);
            double b = brierRows.stream()
                    .mapToDouble(r -> {
                        double o = Boolean.TRUE.equals(sellOutByEvent.get(r.getEventId())) ? 1.0 : 0.0;
                        return (rate - o) * (rate - o);
                    })
                    .average().orElse(0.0);
            baseRateBrier = BigDecimal.valueOf(b).setScale(6, RoundingMode.HALF_UP);
        }

        PredictorSegmentStatus status = segments.findById(key).orElseGet(() -> {
            PredictorSegmentStatus s = new PredictorSegmentStatus();
            s.setSegmentKey(key);
            return s;
        });
        status.setScoredCount(rows.size());
        status.setBrier(meanBrier);
        status.setBaseRateBrier(baseRateBrier);
        status.setMape(meanApe);
        status.setUpdatedAt(now);

        boolean brierTrip = brierRows.size() >= TRIPWIRE_MIN_SCORED
                && meanBrier != null && baseRateBrier != null
                && meanBrier.compareTo(baseRateBrier) >= 0;
        boolean mapeTrip = apeRows.size() >= TRIPWIRE_MIN_SCORED
                && meanApe != null && meanApe.doubleValue() > MAPE_TRIPWIRE;

        String existing = status.getLanguageTierOverride();
        String desired = merge(existing, brierTrip, mapeTrip);
        if (desired != null && !Objects.equals(desired, existing)) {
            // AUTOMATIC DOWNGRADE — merge() only ever ADDS severity, never removes it.
            status.setLanguageTierOverride(desired);
            status.setDowngradedAt(now);
            status.setReason(reason(brierTrip, mapeTrip, meanBrier, baseRateBrier, meanApe,
                    brierRows.size(), apeRows.size()));
            log.warn("Predictor segment '{}' DOWNGRADED to {} — {}", key, desired, status.getReason());
        } else if (existing != null && !brierTrip && !mapeTrip) {
            // Metrics recovered but the override stays: UPGRADES ARE MANUAL ONLY (spec §5).
            log.warn("Predictor segment '{}' metrics recovered (brier={} vs base={}, mape={}) but override "
                            + "'{}' is retained — manual review required (V72 header has the upgrade SQL)",
                    key, meanBrier, baseRateBrier, meanApe, existing);
        }
        segments.save(status);
    }

    /**
     * Merge tripwire results into the existing override, adding severity only. There is
     * deliberately NO transition from any override back to null or to a weaker value —
     * that is the manual-upgrade path (a human UPDATE, spec §5).
     */
    static String merge(String existing, boolean brierTrip, boolean mapeTrip) {
        boolean hasDrop = brierTrip
                || PredictorSegmentStatus.OVERRIDE_DROP_ONE.equals(existing)
                || PredictorSegmentStatus.OVERRIDE_DROP_ONE_QUALITATIVE.equals(existing);
        boolean hasQual = mapeTrip
                || PredictorSegmentStatus.OVERRIDE_QUALITATIVE.equals(existing)
                || PredictorSegmentStatus.OVERRIDE_DROP_ONE_QUALITATIVE.equals(existing);
        if (hasDrop && hasQual) return PredictorSegmentStatus.OVERRIDE_DROP_ONE_QUALITATIVE;
        if (hasDrop) return PredictorSegmentStatus.OVERRIDE_DROP_ONE;
        if (hasQual) return PredictorSegmentStatus.OVERRIDE_QUALITATIVE;
        return null;
    }

    private static String reason(boolean brierTrip, boolean mapeTrip, BigDecimal brier,
                                 BigDecimal baseRateBrier, BigDecimal mape, int brierN, int apeN) {
        StringBuilder sb = new StringBuilder();
        if (brierTrip) {
            sb.append("Brier ").append(brier).append(" not beating base-rate ").append(baseRateBrier)
                    .append(" over ").append(brierN).append(" scored renders");
        }
        if (mapeTrip) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("MAPE ").append(mape).append(" > ").append(MAPE_TRIPWIRE)
                    .append(" over ").append(apeN).append(" scored renders");
        }
        return sb.toString();
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        List<BigDecimal> nonNull = values.stream().filter(Objects::nonNull).toList();
        if (nonNull.isEmpty()) return null;
        BigDecimal sum = nonNull.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(nonNull.size()), 6, RoundingMode.HALF_UP);
    }
}
