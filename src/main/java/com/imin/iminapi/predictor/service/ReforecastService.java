package com.imin.iminapi.predictor.service;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.dto.PredictionResult;
import com.imin.iminapi.predictor.dto.ReforecastResult;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.PredictionLedger;
import com.imin.iminapi.predictor.model.PredictionSurface;
import com.imin.iminapi.predictor.model.ProjectionBand;
import com.imin.iminapi.predictor.model.ReforecastTrigger;
import com.imin.iminapi.predictor.model.Season;
import com.imin.iminapi.predictor.repository.PredictionLedgerRepository;
import com.imin.iminapi.predictor.service.PacingCurveService.CurveMatch;
import com.imin.iminapi.predictor.service.PacingEngine.Projection;
import com.imin.iminapi.predictor.service.SalesTrajectoryService.NormalizedCurve;
import com.imin.iminapi.predictor.service.SalesTrajectoryService.NormalizedPoint;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.EventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The live re-forecast engine (spec §4.2, tasks 86cav479j/86cav479m). {@link #recompute} is
 * idempotent — same live inputs → same projection — and writes ONE {@code prediction_ledger}
 * row (surface=reforecast) BEFORE the result is servable (write-before-render, §5).
 *
 * <p><b>Numbers are computed, not generated (§4.2/§7.1).</b> Stage 1 projections come from the
 * deterministic {@link PacingEngine} over persisted curves; the LLM {@link ReforecastNarrator}
 * only narrates, and ONLY when the projection band changed vs the previous re-forecast row
 * (otherwise the prior narration is reused verbatim). Below the curve threshold the projection
 * is the EXPLICITLY-LABELLED Stage 0 interim (the pre-publish estimate, {@code stage:0}), never
 * blended.
 *
 * <p><b>Kill switch (§5).</b> Benchmark-only mode suppresses ONLY narration — the pacing
 * arithmetic still computes and serves, because those numbers are arithmetic, not generation,
 * and must survive the switch.
 *
 * <p>Privacy (§6.4): the pacing percentages are cross-org aggregates of normalized %-shapes over
 * &ge; {@code min-curve-events} completed events — no figure is attributable to any single
 * foreign event. See {@link PacingCurveService} for the full argument.
 */
@Service
public class ReforecastService {

    private static final Logger log = LoggerFactory.getLogger(ReforecastService.class);

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final PacingCurveService pacingCurves;
    private final PacingEngine engine;
    private final SalesTrajectoryService trajectories;
    private final PredictionLedgerService ledgerService;
    private final PredictionLedgerRepository ledgerRepo;
    private final ReforecastNarrator narrator;
    private final ReforecastAlertNotifier alertNotifier;
    private final PredictorProperties props;
    private final Clock clock;

    public ReforecastService(EventRepository events, TicketTierRepository tiers,
                             PacingCurveService pacingCurves, PacingEngine engine,
                             SalesTrajectoryService trajectories, PredictionLedgerService ledgerService,
                             PredictionLedgerRepository ledgerRepo, ReforecastNarrator narrator,
                             ReforecastAlertNotifier alertNotifier, PredictorProperties props, Clock clock) {
        this.events = events;
        this.tiers = tiers;
        this.pacingCurves = pacingCurves;
        this.engine = engine;
        this.trajectories = trajectories;
        this.ledgerService = ledgerService;
        this.ledgerRepo = ledgerRepo;
        this.narrator = narrator;
        this.alertNotifier = alertNotifier;
        this.props = props;
        this.clock = clock;
    }

    /** The previous re-forecast's carried state, read before a new row is written. */
    private record Prior(ProjectionBand band, String narration, ReforecastResult.Alert alert, int soldNow) {}

    /**
     * Recompute the live re-forecast for an event and ledger it. Returns the fresh result (with
     * ledger stamp) — used by the manual endpoint; the daily cron and triggers discard it.
     */
    public ReforecastResult recompute(UUID eventId, ReforecastTrigger trigger) {
        Event e = events.findActive(eventId).orElse(null);
        if (e == null) return ReforecastResult.none(clock.instant());

        int capacity = tiers.sumQuantityByEventId(eventId);
        int currentSold = tiers.sumSoldByEventId(eventId);
        ZoneId zone = resolveZone(e.getTimezone());
        Instant startsAt = e.getStartsAt();
        Integer daysOut = daysOut(startsAt, zone);

        // Pacing projection when a curve exists and the horizon is projectable.
        CurveMatch match = null;
        Projection projection = null;
        if (capacity > 0 && startsAt != null && daysOut != null && daysOut >= 0) {
            CapacityBand band = CapacityBand.of(capacity);
            Season season = Season.of(startsAt, zone);
            match = pacingCurves.lookup(nullBlank(e.getVenueCity()), nullBlank(e.getVenueCountry()),
                    nullBlank(e.getGenre()), band, season).orElse(null);
            if (match != null) {
                projection = engine.project(match.curve(), currentSold, daysOut, capacity);
            }
        }

        Prior prior = latestReforecast(eventId);
        Double velocity = velocity(eventId, startsAt, zone, currentSold);

        ReforecastResult result;
        ProjectionBand newBand;
        if (match != null && projection != null && !projection.insufficient()) {
            newBand = projection.band();
            result = buildStage1(e, match, projection, currentSold, capacity, daysOut, startsAt, velocity, prior);
        } else {
            result = buildInterim(eventId, currentSold, capacity, velocity, prior);
            newBand = result.band() == null ? null : ProjectionBand.valueOf(result.band());
        }

        // Write-before-serve: ledger the render, then stamp the row id back onto the result.
        UUID ledgerId = ledgerWrite(e, result, currentSold, daysOut, match, trigger);
        result = result.withLedger(new ReforecastResult.Ledger(
                ledgerId.toString(), result.stage(), narrator.modelId(),
                ReforecastNarrator.PROMPT_VERSION, hash(eventId, result, currentSold, daysOut)));

        // Trajectory alert: exactly once per crossing (prior band read above; a new row is now the
        // reference for the next recompute). Only when both bands are real projection bands.
        if (prior != null && prior.band() != null && newBand != null
                && !prior.band().equals(newBand) && "ready".equals(result.status())) {
            alertNotifier.notifyBandChange(e, prior.band(), newBand, result);
        }
        return result;
    }

    // ---- stage 1 (pacing) ------------------------------------------------------

    private ReforecastResult buildStage1(Event e, CurveMatch match, Projection p, int currentSold,
                                         int capacity, int daysOut, Instant startsAt, Double velocity, Prior prior) {
        ProjectionBand newBand = p.band();
        ReforecastResult.Range range = new ReforecastResult.Range(p.finalLow(), p.finalHigh());
        ReforecastResult.RevenueRange revenue = revenueRange(e.getId(), currentSold, range);
        ReforecastResult.SellOutEta eta = sellOutEta(p, startsAt);
        ReforecastResult.Pacing pacing = new ReforecastResult.Pacing(
                curvePoints(match), eventCurvePoints(e.getId()), match.curve().eventsCount(),
                match.relaxation().name());

        // Narration regenerates ONLY on a band change; otherwise reuse the prior verbatim. The kill
        // switch suppresses narration entirely — the numbers above are arithmetic and survive it.
        String narration;
        if (prior != null && newBand.equals(prior.band())) {
            narration = prior.narration();
        } else if (props.isBenchmarkOnly()) {
            narration = null;
        } else {
            narration = safeNarrate(new ReforecastNarrator.Context(
                    newBand, prior == null ? null : prior.band(), p.finalLow(), p.finalHigh(), capacity,
                    currentSold, match.curve().eventsCount(), match.relaxation().name(),
                    newBand == ProjectionBand.SELL_OUT_LIKELY));
        }

        ReforecastResult.Alert alert = alertFor(prior, newBand, p.finalLow(), p.finalHigh());
        return new ReforecastResult("ready", 1, newBand.wire(), range, revenue, velocity, eta, pacing,
                narration, alert, null, clock.instant());
    }

    // ---- stage 0 interim / insufficient ----------------------------------------

    /**
     * Below the curve threshold: lean on the latest pre-publish Stage 0 estimate as the interim,
     * EXPLICITLY labelled {@code stage:0} (no blending). No prior score → {@code insufficient_data}.
     */
    private ReforecastResult buildInterim(UUID eventId, int currentSold, int capacity, Double velocity, Prior prior) {
        PredictionResult pre = latestPrePublish(eventId);
        Instant at = clock.instant();
        if (pre == null || pre.attendanceRange() == null) {
            // Nothing forward-looking to lean on — say so rather than widen silently (§5).
            return new ReforecastResult("insufficient_data", 0, null, null, null, velocity, null, null,
                    null, prior == null ? null : prior.alert(), null, at);
        }
        int rawLow = pre.attendanceRange().low();
        int rawHigh = pre.attendanceRange().high();
        int cap = capacity > 0 ? capacity : rawHigh;
        int low = clamp(rawLow, 0, cap);
        int high = clamp(rawHigh, low, cap);
        ProjectionBand band = ProjectionBand.classify((rawLow + rawHigh) / 2.0, capacity);
        ReforecastResult.Range range = new ReforecastResult.Range(low, high);
        ReforecastResult.RevenueRange revenue = revenueRange(eventId, currentSold, range);
        ReforecastResult.Alert alert = alertFor(prior, band, low, high);
        return new ReforecastResult("ready", 0, band.wire(), range, revenue, velocity, null, null,
                null, alert, null, at);
    }

    // ---- derived arithmetic fields ---------------------------------------------

    /**
     * Projected gross-revenue range = projected sold range × realized average ticket price, where
     * realized avg price = Σ(tier.sold × tier.priceMinor) / Σ(tier.sold). Null when nothing has
     * sold yet (no realized price to project from). Arithmetic only — never generated.
     */
    private ReforecastResult.RevenueRange revenueRange(UUID eventId, int currentSold, ReforecastResult.Range range) {
        if (currentSold <= 0 || range == null) return null;
        long weighted = 0;
        int sold = 0;
        for (TicketTier t : tiers.findByEventIdOrderBySortOrderAsc(eventId)) {
            weighted += (long) t.getSold() * t.getPriceMinor();
            sold += t.getSold();
        }
        if (sold <= 0) return null;
        double avg = (double) weighted / sold;
        return new ReforecastResult.RevenueRange(
                Math.round(range.low() * avg), Math.round(range.high() * avg));
    }

    private Double velocity(UUID eventId, Instant startsAt, ZoneId zone, int currentSold) {
        if (currentSold <= 0) return 0.0;
        LocalDate asOf = LocalDate.ofInstant(clock.instant(), zone);
        return trajectories.velocityPerDayLast7(eventId, asOf);
    }

    private ReforecastResult.SellOutEta sellOutEta(Projection p, Instant startsAt) {
        if (p.sellOutEarliestDaysOut() == null || startsAt == null) return null;
        String earliest = startsAt.minus(p.sellOutEarliestDaysOut(), ChronoUnit.DAYS).toString();
        String latest = p.sellOutLatestDaysOut() == null ? null
                : startsAt.minus(p.sellOutLatestDaysOut(), ChronoUnit.DAYS).toString();
        return new ReforecastResult.SellOutEta(earliest, latest);
    }

    private List<ReforecastResult.CurvePoint> curvePoints(CurveMatch match) {
        List<ReforecastResult.CurvePoint> out = new ArrayList<>();
        for (PacingEngine.CurvePoint c : match.curve().points()) {
            out.add(new ReforecastResult.CurvePoint(c.daysOut(), c.medianPct(), c.p25Pct(), c.p75Pct()));
        }
        return out;
    }

    private List<ReforecastResult.EventPoint> eventCurvePoints(UUID eventId) {
        NormalizedCurve nc = trajectories.normalizedCurve(eventId);
        List<ReforecastResult.EventPoint> out = new ArrayList<>();
        for (NormalizedPoint p : nc.points()) {
            if (p.daysToEvent() != null) out.add(new ReforecastResult.EventPoint(p.daysToEvent(), p.cumulativeSold()));
        }
        return out;
    }

    /** Carry the prior alert forward unless this recompute crossed a band, in which case replace it. */
    private ReforecastResult.Alert alertFor(Prior prior, ProjectionBand newBand, int low, int high) {
        if (prior != null && prior.band() != null && newBand != null && !prior.band().equals(newBand)) {
            String tone = newBand.ordinal() > prior.band().ordinal() ? "up" : "down";
            return new ReforecastResult.Alert(tone, prior.band().phrase(), newBand.phrase(), clock.instant().toString());
        }
        return prior == null ? null : prior.alert();
    }

    private String safeNarrate(ReforecastNarrator.Context ctx) {
        try {
            return narrator.narrate(ctx);
        } catch (Exception ex) {
            log.warn("Reforecast narration failed (numbers still served): {}", ex.getMessage());
            return null; // numbers are computed and survive; narration is best-effort
        }
    }

    // ---- ledger + prior reads --------------------------------------------------

    private UUID ledgerWrite(Event e, ReforecastResult result, int currentSold, Integer daysOut,
                             CurveMatch match, ReforecastTrigger trigger) {
        Map<String, Object> internal = new LinkedHashMap<>();
        internal.put("soldNow", currentSold);
        internal.put("daysOut", daysOut);
        internal.put("stage", result.stage());
        internal.put("band", result.band());
        internal.put("trigger", trigger == null ? null : trigger.name().toLowerCase());
        if (match != null) {
            internal.put("relaxation", match.relaxation().name());
            internal.put("comparableEventsCount", match.curve().eventsCount());
        }
        String comparablesJson = writeJson(internal);
        String outputJson = writeJson(result);
        return ledgerService.record(new PredictionLedgerService.RecordCommand(
                e.getId(), e.getOrgId(), PredictionSurface.REFORECAST, result.stage(),
                narrator.modelId(), ReforecastNarrator.PROMPT_VERSION,
                hash(e.getId(), result, currentSold, daysOut), comparablesJson, outputJson));
    }

    private Prior latestReforecast(UUID eventId) {
        for (PredictionLedger row : ledgerRepo.findByEventIdOrderByCreatedAtDesc(eventId)) {
            if (row.getSurface() != PredictionSurface.REFORECAST) continue;
            ReforecastResult r = parseReforecast(row.getOutputJson());
            if (r == null) return null;
            ProjectionBand band = r.band() == null ? null : ProjectionBand.valueOf(r.band());
            int soldNow = readSoldNow(row.getComparablesJson());
            return new Prior(band, r.narration(), r.alert(), soldNow);
        }
        return null;
    }

    private PredictionResult latestPrePublish(UUID eventId) {
        for (PredictionLedger row : ledgerRepo.findByEventIdOrderByCreatedAtDesc(eventId)) {
            if (row.getSurface() != PredictionSurface.PRE_PUBLISH) continue;
            try {
                return PredictorJson.MAPPER.readValue(row.getOutputJson(), PredictionResult.class);
            } catch (Exception ex) {
                return null;
            }
        }
        return null;
    }

    /** The sold count at the most recent re-forecast (0 if none) — the milestone-trigger reference. */
    public int lastForecastSold(UUID eventId) {
        Prior p = latestReforecast(eventId);
        return p == null ? 0 : p.soldNow();
    }

    /** True once at least one re-forecast row exists for the event. */
    public boolean hasReforecast(UUID eventId) {
        for (PredictionLedger row : ledgerRepo.findByEventIdOrderByCreatedAtDesc(eventId)) {
            if (row.getSurface() == PredictionSurface.REFORECAST) return true;
        }
        return false;
    }

    /**
     * Serve the latest re-forecast for an event (GET path): parse the newest reforecast ledger
     * row and stamp its ledger identity on. {@code none} when the event was never re-forecast.
     */
    public ReforecastResult latestServable(UUID eventId) {
        for (PredictionLedger row : ledgerRepo.findByEventIdOrderByCreatedAtDesc(eventId)) {
            if (row.getSurface() != PredictionSurface.REFORECAST) continue;
            ReforecastResult r = parseReforecast(row.getOutputJson());
            if (r == null) return ReforecastResult.none(clock.instant());
            return r.withLedger(new ReforecastResult.Ledger(row.getId().toString(), row.getStage(),
                    row.getModelId(), row.getPromptVersion(), row.getInputSnapshotHash()));
        }
        return ReforecastResult.none(clock.instant());
    }

    // ---- helpers ---------------------------------------------------------------

    private static ReforecastResult parseReforecast(String json) {
        try {
            return PredictorJson.MAPPER.readValue(json, ReforecastResult.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private static int readSoldNow(String comparablesJson) {
        try {
            JsonNode n = PredictorJson.MAPPER.readTree(comparablesJson).get("soldNow");
            return n == null || n.isNull() ? 0 : n.asInt();
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String writeJson(Object o) {
        try {
            return PredictorJson.MAPPER.writeValueAsString(o);
        } catch (Exception ex) {
            throw new IllegalStateException("Reforecast JSON serialization failed", ex);
        }
    }

    private static String hash(UUID eventId, ReforecastResult r, int sold, Integer daysOut) {
        String canonical = "reforecast|" + eventId + "|" + r.stage() + "|" + sold + "|" + daysOut + "|" + r.band();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Integer daysOut(Instant startsAt, ZoneId zone) {
        if (startsAt == null) return null;
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate eventDay = startsAt.atZone(zone).toLocalDate();
        return (int) ChronoUnit.DAYS.between(today, eventDay);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(Math.max(lo, hi), v));
    }

    private static String nullBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static ZoneId resolveZone(String raw) {
        if (raw == null || raw.isBlank()) return ZoneId.of("UTC");
        try {
            return ZoneId.of(raw);
        } catch (DateTimeException ex) {
            return ZoneId.of("UTC");
        }
    }
}
