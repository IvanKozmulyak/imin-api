package com.imin.iminapi.predictor.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.predictor.dto.PublicDataSourcesResponse.PublicDataSource;
import com.imin.iminapi.predictor.model.TransitDisruption;
import com.imin.iminapi.predictor.model.TransitSyncState;
import com.imin.iminapi.predictor.repository.TransitDisruptionRepository;
import com.imin.iminapi.predictor.repository.TransitSyncStateRepository;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.sources.DataSourceCatalog;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.prim.PrimClassifier;
import com.imin.iminapi.predictor.sources.prim.PrimProperties;
import com.imin.iminapi.predictor.sources.prim.TransitStopStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Questions 6.1 (strike) and 6.2 (works cutting service) on the night, from stored IDFM messages: line-level rail
 * objects anywhere in the network, and, when the check's event has a venue point and the stops reference has synced,
 * stop-level objects of any mode (buses and Noctilien included) within {@code stopRadiusM} of the venue. Facts carry
 * {@code scope} ({@code network} or {@code near_venue}); a near-venue match adds {@code radiusM} and the stops
 * reference's {@code stopsUrl}, {@code stopsLicence}, {@code stopsLicenceUrl} and {@code stopsUpdated} (UTC date of its
 * last ok sync). A source that is off, never loaded or out of date answers not checked, never clear.
 */
@Component
public class TransitEvaluator implements QuestionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TransitEvaluator.class);
    static final String STRIKE = "6.1";
    static final String WORKS = "6.2";
    static final String GATE = "prim";
    static final String BLOQUANTE = "BLOQUANTE";
    static final int MAX_LISTED_LINES = 5;
    private static final Set<String> PARAM_KEYS =
            Set.of("clear_max_ahead_days", "night_start_hour", "night_end_hour", "wide_min_lines");
    private static final Map<String, Integer> SEVERITY_RANK = Map.of("BLOQUANTE", 3, "PERTURBEE", 2, "INFORMATION", 1);
    private static final ObjectMapper JSON = new ObjectMapper();

    private record QParams(int clearMaxAheadDays, int nightStartHour, int nightEndHour, int wideMinLines) {}

    /** {@code stop}: the stop ref of a stop-level object, else null. */
    private record Line(String label, String mode, String level, String stop) {}

    private record Period(Instant begin, Instant end) {}

    private record Row(String kind, String severity, List<Line> lines, List<Period> periods) {}

    private record Window(Instant start, Instant end) {}

    /** Stop refs near the venue, the radius used, and the UTC date of the stops sync; empty without a venue point. */
    private record Near(Set<String> refs, int radiusM, String updated) {
        static final Near NONE = new Near(Set.of(), 0, null);
    }

    private final TransitDisruptionRepository disruptions;
    private final TransitSyncStateRepository states;
    private final TransitStopStore stops;
    private final SourceGates gates;
    private final PrimProperties props;
    private final Clock clock;
    private final Map<String, QParams> params = new HashMap<>();
    private final String url;
    private final String licence;
    private final String licenceUrl;
    private final String credit;
    private final String stopsUrl;
    private final String stopsLicence;
    private final String stopsLicenceUrl;

    public TransitEvaluator(QuestionBank bank, TransitDisruptionRepository disruptions,
                            TransitSyncStateRepository states, TransitStopStore stops, SourceGates gates,
                            DataSourceCatalog catalog, PrimProperties props, Clock clock) {
        this.disruptions = disruptions;
        this.states = states;
        this.stops = stops;
        this.gates = gates;
        this.props = props;
        this.clock = clock;
        for (String id : List.of(STRIKE, WORKS)) {
            Question q = bank.questions().stream()
                    .filter(x -> x.id().equals(id) && x.source() == SourceKind.STRUCTURED).findFirst()
                    .orElseThrow(() -> new IllegalStateException("predictor question " + id + ": not in the bank"));
            for (String key : q.params().keySet()) {
                if (!PARAM_KEYS.contains(key)) {
                    throw new IllegalStateException("predictor question " + id + ": unknown params." + key);
                }
            }
            params.put(id, new QParams(whole(q, "clear_max_ahead_days", 0, 120), whole(q, "night_start_hour", 0, 23),
                    whole(q, "night_end_hour", 0, 23), whole(q, "wide_min_lines", 1, 20)));
        }
        PublicDataSource source = catalog.byId(TransitSyncState.IDFM_PRIM).orElseThrow(() ->
                new IllegalStateException("predictor questions 6.1/6.2: sources.yaml has no " + TransitSyncState.IDFM_PRIM));
        this.url = source.url();
        this.licence = source.licence();
        this.licenceUrl = source.licenceUrl();
        this.credit = source.creditLine();
        PublicDataSource stopsSource = catalog.byId(TransitSyncState.IDFM_STOPS).orElseThrow(() ->
                new IllegalStateException("predictor questions 6.1/6.2: sources.yaml has no " + TransitSyncState.IDFM_STOPS));
        this.stopsUrl = stopsSource.url();
        this.stopsLicence = stopsSource.licence();
        this.stopsLicenceUrl = stopsSource.licenceUrl();
    }

    private static int whole(Question q, String key, int min, int max) {
        double v = Params.of(q, key).doubleValue();
        if (v != Math.rint(v) || v < min || v > max) {
            throw new IllegalStateException("predictor question " + q.id() + ": params." + key + " must be a whole "
                    + min + ".." + max + ", was " + v);
        }
        return (int) v;
    }

    @Override
    public SourceKind source() { return SourceKind.STRUCTURED; }

    @Override
    public Set<String> questionIds() { return Set.of(STRIKE, WORKS); }

    @Override
    public Finding evaluate(Question q, DateCheckInput in, LocalDate date) {
        return evaluateAll(List.of(q), in, date).get(0);
    }

    /** One state read and one overlap query serve every asked question of the date. */
    @Override
    public List<Finding> evaluateAll(List<Question> questions, DateCheckInput in, LocalDate date) {
        if (!gates.isOn(GATE)) return all(questions, "source_off");
        Optional<TransitSyncState> state = states.findById(TransitSyncState.IDFM_PRIM);
        if (state.isEmpty() || state.get().getSyncedAt() == null) return all(questions, "not_synced");
        Instant syncedAt = state.get().getSyncedAt();
        Instant fetchedAt = state.get().getFeedUpdatedAt() != null ? state.get().getFeedUpdatedAt() : syncedAt;
        // A feed frozen upstream still answers 200, so its own update time ages out like a failed poll.
        Instant oldest = clock.instant().minus(Duration.ofHours(props.getMaxAgeHours()));
        if (syncedAt.isBefore(oldest) || fetchedAt.isBefore(oldest)) return all(questions, "stale");

        Map<String, Window> windows = new HashMap<>();
        Instant from = null;
        Instant to = null;
        for (Question q : questions) {
            Window w = window(params.get(q.id()), in, date);
            windows.put(q.id(), w);
            from = from == null || w.start().isBefore(from) ? w.start() : from;
            to = to == null || w.end().isAfter(to) ? w.end() : to;
        }
        List<Row> rows = new ArrayList<>();
        for (TransitDisruption d : disruptions.findOverlapping(TransitSyncState.IDFM_PRIM, from, to)) {
            Row r = parse(d);
            if (r != null) rows.add(r);
        }
        Near near = near(in);
        List<Finding> out = new ArrayList<>();
        for (Question q : questions) {
            out.add(answer(q, params.get(q.id()), windows.get(q.id()), rows, near, in, date, fetchedAt));
        }
        return out;
    }

    /** One stops-state read and one box query per date, only when the check's event has a venue point. */
    private Near near(DateCheckInput in) {
        if (in.venueLat() == null || in.venueLng() == null) return Near.NONE;
        Instant synced = states.findById(TransitSyncState.IDFM_STOPS).map(TransitSyncState::getSyncedAt).orElse(null);
        if (synced == null) return Near.NONE;
        int radius = props.getStopRadiusM();
        return new Near(stops.nearby(in.venueLat(), in.venueLng(), radius), radius,
                synced.atZone(ZoneOffset.UTC).toLocalDate().toString());
    }

    private Finding answer(Question q, QParams p, Window w, List<Row> rows, Near near, DateCheckInput in,
                           LocalDate date, Instant fetchedAt) {
        boolean strike = q.id().equals(STRIKE);
        TreeSet<String> railLines = new TreeSet<>();
        TreeSet<String> nearLabels = new TreeSet<>();
        boolean nearRail = false;
        String worst = null;
        boolean blocking = false;
        for (Row r : rows) {
            if (!r.kind().equals(strike ? PrimClassifier.STRIKE : PrimClassifier.WORKS)) continue;
            if (!strike && !BLOQUANTE.equals(r.severity())) continue;
            if (r.periods().stream().noneMatch(x -> x.begin().isBefore(w.end()) && x.end().isAfter(w.start()))) continue;
            List<String> rail = r.lines().stream()
                    .filter(l -> "line".equals(l.level()) && PrimClassifier.RAIL_MODES.contains(l.mode()))
                    .map(Line::label).toList();
            // any mode counts at a stop near the venue; line-level buses never count
            List<Line> close = r.lines().stream()
                    .filter(l -> "stop".equals(l.level()) && l.stop() != null && near.refs().contains(l.stop())).toList();
            if (rail.isEmpty() && close.isEmpty()) continue;
            if (!rail.isEmpty()) {
                railLines.addAll(rail);
                blocking |= BLOQUANTE.equals(r.severity());
            }
            for (Line l : close) {
                nearLabels.add(l.label());
                nearRail |= PrimClassifier.RAIL_MODES.contains(l.mode());
            }
            if (worst == null || rank(r.severity()) > rank(worst)) worst = r.severity();
        }
        if (!railLines.isEmpty() || !nearLabels.isEmpty()) {
            int network = railLines.isEmpty() ? 0
                    : railLines.size() >= p.wideMinLines() || (strike && blocking) ? 2 : 1;
            int close = nearLabels.isEmpty() ? 0 : strike || nearRail ? 2 : 1;
            TreeSet<String> lines = new TreeSet<>(railLines);
            lines.addAll(nearLabels);
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("date", date.toString());
            facts.put("lines", listed(lines));
            facts.put("lineCount", lines.size());
            facts.put("severity", worst);
            if (nearLabels.isEmpty()) {
                facts.put("scope", "network");
            } else {
                facts.put("scope", "near_venue");
                facts.put("radiusM", near.radiusM());
                facts.put("stopsUrl", stopsUrl);
                facts.put("stopsLicence", stopsLicence);
                facts.put("stopsLicenceUrl", stopsLicenceUrl);
                facts.put("stopsUpdated", near.updated());
            }
            facts.put("licence", licence);
            facts.put("licenceUrl", licenceUrl);
            facts.put("credit", credit);
            return Finding.found(q, Kind.RISK, Math.max(network, close), facts, url, fetchedAt);
        }
        if (date.isAfter(in.today().plusDays(p.clearMaxAheadDays()))) return Finding.notChecked(q, "too_far_ahead");
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("scope", "network");
        facts.put("licence", licence);
        facts.put("licenceUrl", licenceUrl);
        facts.put("credit", credit);
        return Finding.clear(q, facts, url, fetchedAt);
    }

    /** Organizer hours, else the question's; the end is on the date when after the start hour, else the next day. */
    private static Window window(QParams p, DateCheckInput in, LocalDate date) {
        ZoneId zone = in.zone();
        int startHour = in.startHour() != null ? in.startHour() : p.nightStartHour();
        int endHour = in.endHour() != null ? in.endHour() : p.nightEndHour();
        LocalDate endDate = endHour > startHour ? date : date.plusDays(1);
        return new Window(date.atTime(startHour, 0).atZone(zone).toInstant(),
                endDate.atTime(endHour, 0).atZone(zone).toInstant());
    }

    private static List<Finding> all(List<Question> questions, String reason) {
        return questions.stream().map(q -> Finding.notChecked(q, reason)).toList();
    }

    private static int rank(String severity) {
        return severity == null ? 0 : SEVERITY_RANK.getOrDefault(severity, 0);
    }

    /** Sorted labels, at most five, then {@code +N} for the rest. */
    static String listed(TreeSet<String> lines) {
        List<String> shown = lines.stream().limit(MAX_LISTED_LINES).toList();
        String s = String.join(", ", shown);
        return lines.size() > MAX_LISTED_LINES ? s + " +" + (lines.size() - MAX_LISTED_LINES) : s;
    }

    /** A malformed stored row is logged and skipped, so one bad row never fails the check. */
    private static Row parse(TransitDisruption d) {
        try {
            List<Line> lines = new ArrayList<>();
            for (JsonNode n : array(d.getLinesJson())) {
                lines.add(new Line(n.get("label").asText(), n.get("mode").asText(), n.get("level").asText(),
                        n.path("stop").asText(null)));
            }
            List<Period> periods = new ArrayList<>();
            for (JsonNode n : array(d.getPeriodsJson())) {
                periods.add(new Period(Instant.parse(n.get("begin").asText()), Instant.parse(n.get("end").asText())));
            }
            return new Row(d.getKind(), d.getSeverity(), lines, periods);
        } catch (Exception e) {
            log.warn("TransitEvaluator: skipping transit_disruption {} with malformed JSON ({})", d.getDisruptionId(),
                    e.getClass().getSimpleName());
            return null;
        }
    }

    private static JsonNode array(String json) throws Exception {
        JsonNode n = JSON.readTree(json);
        if (n == null || !n.isArray()) throw new IllegalArgumentException("not a JSON array");
        return n;
    }
}
