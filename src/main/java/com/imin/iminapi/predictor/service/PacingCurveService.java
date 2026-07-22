package com.imin.iminapi.predictor.service;

import com.imin.iminapi.predictor.config.PredictorProperties;
import com.imin.iminapi.predictor.model.CapacityBand;
import com.imin.iminapi.predictor.model.EventOutcome;
import com.imin.iminapi.predictor.model.PacingCurve;
import com.imin.iminapi.predictor.model.RelaxationLevel;
import com.imin.iminapi.predictor.model.Season;
import com.imin.iminapi.predictor.repository.EventOutcomeRepository;
import com.imin.iminapi.predictor.repository.PacingCurveRepository;
import com.imin.iminapi.predictor.service.PacingEngine.Curve;
import com.imin.iminapi.predictor.service.PacingEngine.CurvePoint;
import com.imin.iminapi.predictor.service.SalesTrajectoryService.NormalizedCurve;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists and serves the pacing curves (spec §7 Stage 1, task 86cav479d). The daily
 * {@link #rebuildAll()} groups every completed event by its comparable segment at three
 * relaxation granularities, builds a curve for each group at or above the
 * {@code imin.predictor.min-curve-events} floor (default 12), and REPLACES the whole table.
 * The re-forecast reads a target event's curve by walking the relaxation ladder with
 * {@link #lookup}.
 *
 * <p><b>Privacy reasoning (spec §6.4, gate item 5) — documented here because this is where the
 * cross-org aggregate is produced.</b> A curve is built ONLY from segments with &ge;
 * {@code min-curve-events} (12) completed events, and it stores nothing but the median/P25/P75
 * of NORMALIZED %-of-final SHAPES per day-out. It carries no attendance counts, no revenue, no
 * event ids, and no organizer identity — a %-shape over &ge;12 events is not attributable to
 * any single foreign event. This is a stronger floor than the &ge;5 comparable-cluster rule
 * that governs figures, and it is why the curve percentages are safe to render cross-org.
 */
@Service
public class PacingCurveService {

    private static final Logger log = LoggerFactory.getLogger(PacingCurveService.class);
    private static final TypeReference<List<CurvePoint>> POINTS = new TypeReference<>() {};

    private final EventOutcomeRepository outcomes;
    private final SalesTrajectoryService trajectories;
    private final PacingCurveRepository curves;
    private final PacingEngine engine;
    private final PredictorProperties props;

    public PacingCurveService(EventOutcomeRepository outcomes, SalesTrajectoryService trajectories,
                              PacingCurveRepository curves, PacingEngine engine, PredictorProperties props) {
        this.outcomes = outcomes;
        this.trajectories = trajectories;
        this.curves = curves;
        this.engine = engine;
        this.props = props;
    }

    /** A resolved curve for a target event's segment: the relaxation it was found at + the parsed curve. */
    public record CurveMatch(RelaxationLevel relaxation, Curve curve) {}

    // ---- rebuild (daily job body) ----------------------------------------------

    /**
     * Recompute every segment curve from the finalized outcome record + materialized
     * trajectories, and replace the whole {@code pacing_curves} table. Idempotent: re-running
     * yields the same rows, and a segment that falls below the floor simply disappears. Runs in
     * one transaction so a re-forecast never sees a half-rebuilt table.
     */
    @Transactional
    public int rebuildAll() {
        int minEvents = props.getMinCurveEvents();
        int maxDaysOut = props.getPacingMaxDaysOut();

        // key -> the completed events' normalized shapes that fall in that segment.
        Map<String, List<NormalizedCurve>> groups = new LinkedHashMap<>();
        for (EventOutcome o : outcomes.findAll()) {
            if (o.getFinalizedAt() == null) continue; // a comparable is an event whose result is known
            String genre = o.getGenreFamily();
            CapacityBand band = o.getCapacityBand();
            Season season = o.getSeason();
            if (genre == null || band == null) continue;

            NormalizedCurve nc = trajectories.normalizedCurve(o.getEventId());
            if (nc.finalTotal() <= 0 || !hasDatedPoint(nc)) continue; // no placeable shape

            if (o.getCity() != null && season != null) {
                groups.computeIfAbsent(keyNone(o.getCity(), genre, band, season), k -> new ArrayList<>()).add(nc);
            }
            if (o.getCountry() != null && season != null) {
                groups.computeIfAbsent(keyCountry(o.getCountry(), genre, band, season), k -> new ArrayList<>()).add(nc);
            }
            if (o.getCountry() != null) {
                groups.computeIfAbsent(keyDropSeason(o.getCountry(), genre, band), k -> new ArrayList<>()).add(nc);
            }
        }

        List<PacingCurve> rows = new ArrayList<>();
        for (Map.Entry<String, List<NormalizedCurve>> g : groups.entrySet()) {
            if (g.getValue().size() < minEvents) continue;
            Curve curve = engine.buildCurve(g.getValue(), maxDaysOut);
            if (curve.eventsCount() < minEvents) continue; // undated shapes dropped it below the floor
            PacingCurve row = new PacingCurve();
            row.setSegmentKey(g.getKey());
            row.setRelaxation(relaxationOf(g.getKey()).name());
            row.setEventsCount(curve.eventsCount());
            row.setPointsJson(writePoints(curve.points()));
            rows.add(row);
        }

        curves.deleteAllInBatch();
        curves.saveAll(rows);
        log.info("PacingCurveService: rebuilt {} segment curve(s) from {} grouped segment(s)", rows.size(), groups.size());
        return rows.size();
    }

    // ---- lookup (re-forecast path) ---------------------------------------------

    /**
     * Resolve the least-relaxed persisted curve for a target event's segment, walking the same
     * ladder as {@code ComparableCorpusService}: city segment → country segment → season-dropped.
     * (GENRE_TO_FAMILY is identity today, so it is not a separate rung.) Empty when no rung has a
     * persisted curve — the caller falls back to the Stage 0 interim.
     */
    @Transactional(readOnly = true)
    public Optional<CurveMatch> lookup(String city, String country, String genre, CapacityBand band, Season season) {
        if (genre == null || band == null) return Optional.empty();
        if (city != null && season != null) {
            Optional<CurveMatch> m = read(keyNone(city, genre, band, season), RelaxationLevel.NONE);
            if (m.isPresent()) return m;
        }
        if (country != null && season != null) {
            Optional<CurveMatch> m = read(keyCountry(country, genre, band, season), RelaxationLevel.CITY_TO_COUNTRY);
            if (m.isPresent()) return m;
        }
        if (country != null) {
            return read(keyDropSeason(country, genre, band), RelaxationLevel.DROP_SEASON);
        }
        return Optional.empty();
    }

    private Optional<CurveMatch> read(String key, RelaxationLevel level) {
        return curves.findById(key).map(row ->
                new CurveMatch(level, new Curve(row.getEventsCount(), readPoints(row.getPointsJson()))));
    }

    // ---- segment keys ----------------------------------------------------------

    static String keyNone(String city, String genre, CapacityBand band, Season season) {
        return "NONE|" + city + "|" + genre + "|" + band.name() + "|" + season.name();
    }

    static String keyCountry(String country, String genre, CapacityBand band, Season season) {
        return "CITY_TO_COUNTRY|" + country + "|" + genre + "|" + band.name() + "|" + season.name();
    }

    static String keyDropSeason(String country, String genre, CapacityBand band) {
        return "DROP_SEASON|" + country + "|" + genre + "|" + band.name();
    }

    private static RelaxationLevel relaxationOf(String key) {
        int bar = key.indexOf('|');
        return RelaxationLevel.valueOf(key.substring(0, bar));
    }

    private static boolean hasDatedPoint(NormalizedCurve nc) {
        return nc.points().stream().anyMatch(p -> p.daysToEvent() != null);
    }

    private static String writePoints(List<CurvePoint> points) {
        try {
            return PredictorJson.MAPPER.writeValueAsString(points);
        } catch (Exception e) {
            throw new IllegalStateException("Pacing curve points serialization failed", e);
        }
    }

    private static List<CurvePoint> readPoints(String json) {
        try {
            return PredictorJson.MAPPER.readValue(json, POINTS);
        } catch (Exception e) {
            throw new IllegalStateException("Pacing curve points parse failed", e);
        }
    }
}
