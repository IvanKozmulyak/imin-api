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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Questions 6.1 (strike) and 6.2 (works cutting service) on the night, from stored IDFM line-level rail messages.
 * A source that is off, never loaded or out of date answers not checked, never clear.
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

    private record Line(String label, String mode, String level) {}

    private record Period(Instant begin, Instant end) {}

    private record Row(String kind, String severity, List<Line> lines, List<Period> periods) {}

    private record Window(Instant start, Instant end) {}

    private final TransitDisruptionRepository disruptions;
    private final TransitSyncStateRepository states;
    private final SourceGates gates;
    private final PrimProperties props;
    private final Clock clock;
    private final Map<String, QParams> params = new HashMap<>();
    private final String url;
    private final String licence;
    private final String licenceUrl;
    private final String credit;

    public TransitEvaluator(QuestionBank bank, TransitDisruptionRepository disruptions,
                            TransitSyncStateRepository states, SourceGates gates, DataSourceCatalog catalog,
                            PrimProperties props, Clock clock) {
        this.disruptions = disruptions;
        this.states = states;
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
        List<Finding> out = new ArrayList<>();
        for (Question q : questions) out.add(answer(q, params.get(q.id()), windows.get(q.id()), rows, in, date, fetchedAt));
        return out;
    }

    private Finding answer(Question q, QParams p, Window w, List<Row> rows, DateCheckInput in, LocalDate date,
                           Instant fetchedAt) {
        boolean strike = q.id().equals(STRIKE);
        TreeSet<String> lines = new TreeSet<>();
        String worst = null;
        boolean blocking = false;
        for (Row r : rows) {
            if (!r.kind().equals(strike ? PrimClassifier.STRIKE : PrimClassifier.WORKS)) continue;
            if (!strike && !BLOQUANTE.equals(r.severity())) continue;
            if (r.periods().stream().noneMatch(x -> x.begin().isBefore(w.end()) && x.end().isAfter(w.start()))) continue;
            List<String> rail = r.lines().stream()
                    .filter(l -> "line".equals(l.level()) && PrimClassifier.RAIL_MODES.contains(l.mode()))
                    .map(Line::label).toList();
            if (rail.isEmpty()) continue;
            lines.addAll(rail);
            blocking |= BLOQUANTE.equals(r.severity());
            if (worst == null || rank(r.severity()) > rank(worst)) worst = r.severity();
        }
        if (!lines.isEmpty()) {
            int strength = lines.size() >= p.wideMinLines() || (strike && blocking) ? 2 : 1;
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("date", date.toString());
            facts.put("lines", listed(lines));
            facts.put("lineCount", lines.size());
            facts.put("severity", worst);
            facts.put("scope", "network");
            facts.put("licence", licence);
            facts.put("licenceUrl", licenceUrl);
            facts.put("credit", credit);
            return Finding.found(q, Kind.RISK, strength, facts, url, fetchedAt);
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
                lines.add(new Line(n.get("label").asText(), n.get("mode").asText(), n.get("level").asText()));
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
