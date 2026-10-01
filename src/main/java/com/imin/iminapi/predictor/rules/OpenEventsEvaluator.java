package com.imin.iminapi.predictor.rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.predictor.dto.PublicDataSourcesResponse.PublicDataSource;
import com.imin.iminapi.predictor.model.GenreWeekCount;
import com.imin.iminapi.predictor.model.OpenEventOccurrence;
import com.imin.iminapi.predictor.repository.GenreWeekCountRepository;
import com.imin.iminapi.predictor.repository.OpenEventOccurrenceRepository;
import com.imin.iminapi.predictor.rules.QuestionBank.Kind;
import com.imin.iminapi.predictor.rules.QuestionBank.Question;
import com.imin.iminapi.predictor.rules.QuestionBank.SourceKind;
import com.imin.iminapi.predictor.sources.DataSourceCatalog;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.openevents.OpenEventCities;
import com.imin.iminapi.predictor.sources.openevents.OpenEventCities.City;
import com.imin.iminapi.predictor.sources.openevents.OpenEventSource;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsJob;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Questions 2.6 (genre events in the week vs the city norm), 5.3 (big community events on the night or either side)
 * and 2.3 (recurring same-genre parties landing on the date), from the open-data tables OpenEventsJob fills.
 * Reads only stored rows, so a check never waits on an upstream; each query runs at most once per date.
 */
@Component
public class OpenEventsEvaluator implements QuestionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(OpenEventsEvaluator.class);
    static final String WEEK_VS_NORM = "2.6";
    static final String COMMUNITY = "5.3";
    static final String RECURRING = "2.3";
    /** Past ISO weeks the city norm is the median of. */
    static final int NORM_WEEKS = 12;
    /** Counts last written longer ago than this are stale. */
    static final int STALE_DAYS = 14;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Set<String>> PARAM_KEYS = Map.of(
            WEEK_VS_NORM, Set.of("busy_ratio", "strong_ratio", "min_excess", "max_ahead_days"),
            COMMUNITY, Set.of("max_ahead_days"),
            RECURRING, Set.of("min_occurrences", "weekly_max_gap_days", "monthly_max_gap_days", "max_ahead_days"));

    private final OpenEventCities cities;
    private final List<OpenEventSource> sources;
    private final GenreWeekCountRepository counts;
    private final OpenEventOccurrenceRepository occurrences;
    private final SourceGates gates;
    private final DataSourceCatalog catalog;
    private final Map<String, Integer> maxAheadDays = new HashMap<>();
    private final double busyRatio;
    private final double strongRatio;
    private final int minExcess;
    private final int minOccurrences;
    private final int weeklyMaxGapDays;
    private final int monthlyMaxGapDays;

    public OpenEventsEvaluator(QuestionBank bank, OpenEventCities cities, List<OpenEventSource> sources,
                               GenreWeekCountRepository counts, OpenEventOccurrenceRepository occurrences,
                               SourceGates gates, DataSourceCatalog catalog) {
        this.cities = cities;
        this.sources = List.copyOf(sources);
        this.counts = counts;
        this.occurrences = occurrences;
        this.gates = gates;
        this.catalog = catalog;
        Map<String, Question> qs = new HashMap<>();
        for (String id : PARAM_KEYS.keySet()) {
            Question q = bank.questions().stream()
                    .filter(x -> x.id().equals(id) && x.source() == SourceKind.STRUCTURED).findFirst()
                    .orElseThrow(() -> new IllegalStateException("predictor question " + id + ": not in the bank"));
            for (String key : q.params().keySet()) {
                if (!PARAM_KEYS.get(id).contains(key)) {
                    throw new IllegalStateException("predictor question " + id + ": unknown params." + key);
                }
            }
            maxAheadDays.put(id, whole(q, "max_ahead_days", 1, OpenEventsJob.LOOKAHEAD_DAYS));
            qs.put(id, q);
        }
        Question week = qs.get(WEEK_VS_NORM);
        this.busyRatio = Params.of(week, "busy_ratio").doubleValue();
        if (busyRatio <= 1 || busyRatio > 5) throw outOfBounds(week, "busy_ratio", busyRatio);
        this.strongRatio = Params.of(week, "strong_ratio").doubleValue();
        if (strongRatio < busyRatio || strongRatio > 10) throw outOfBounds(week, "strong_ratio", strongRatio);
        this.minExcess = whole(week, "min_excess", 1, 20);
        Question rec = qs.get(RECURRING);
        this.minOccurrences = whole(rec, "min_occurrences", 2, 10);
        this.weeklyMaxGapDays = whole(rec, "weekly_max_gap_days", 7, 120);
        this.monthlyMaxGapDays = whole(rec, "monthly_max_gap_days", 28, 120);
        for (OpenEventSource s : this.sources) {
            PublicDataSource entry = catalog.byId(s.id()).orElseThrow(() ->
                    new IllegalStateException("open event source " + s.id() + " has no sources.yaml entry"));
            if (!entry.licence().equals(s.licence())) {
                throw new IllegalStateException("open event source " + s.id() + ": licence mismatch, sources.yaml says '"
                        + entry.licence() + "', the source says '" + s.licence() + "'");
            }
        }
    }

    private static int whole(Question q, String key, int min, int max) {
        double v = Params.of(q, key).doubleValue();
        if (v != Math.rint(v)) {
            throw new IllegalStateException("predictor question " + q.id() + ": params." + key + " must be whole");
        }
        if (v < min || v > max) throw outOfBounds(q, key, v);
        return (int) v;
    }

    private static IllegalStateException outOfBounds(Question q, String key, double v) {
        return new IllegalStateException("predictor question " + q.id() + ": params." + key + " out of bounds: " + v);
    }

    @Override
    public SourceKind source() { return SourceKind.STRUCTURED; }

    @Override
    public Set<String> questionIds() { return PARAM_KEYS.keySet(); }

    @Override
    public Finding evaluate(Question q, DateCheckInput in, LocalDate date) {
        return evaluateAll(List.of(q), in, date).get(0);
    }

    @Override
    public List<Finding> evaluateAll(List<Question> questions, DateCheckInput in, LocalDate date) {
        Reads reads = new Reads(in, date);
        return questions.stream().map(q -> answer(q, in, date, reads)).toList();
    }

    private Finding answer(Question q, DateCheckInput in, LocalDate d, Reads reads) {
        String blocked = reads.cityReason();
        if (blocked != null) return Finding.notChecked(q, blocked);
        if (ChronoUnit.DAYS.between(in.today(), d) > maxAheadDays.get(q.id())) return Finding.notChecked(q, "too_far_ahead");
        String sync = reads.syncReason();
        if (sync != null) return Finding.notChecked(q, sync);
        return switch (q.id()) {
            case WEEK_VS_NORM -> weekVsNorm(q, d, reads);
            case COMMUNITY -> community(q, d, reads);
            case RECURRING -> recurring(q, in, d, reads);
            default -> throw new IllegalArgumentException("not an open-events question: " + q.id());
        };
    }

    /** Each query runs at most once per date, and only once a question needs it. */
    private final class Reads {
        private final DateCheckInput in;
        private final LocalDate date;
        private final LocalDate thisMonday;
        private City city;
        private String cityReason;
        private boolean cityDone;
        private String syncReason;
        private boolean syncDone;
        private Map<LocalDate, GenreWeekCount> weeks;
        private List<OpenEventOccurrence> rows;

        Reads(DateCheckInput in, LocalDate date) {
            this.in = in;
            this.date = date;
            this.thisMonday = in.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        }

        String cityReason() {
            if (cityDone) return cityReason;
            cityDone = true;
            String key = in.cityKey();
            if (key.isBlank()) return cityReason = "not_provided";
            Optional<City> found = cities.resolve(key, in.country());
            if (found.isEmpty()) return cityReason = "no_source";
            city = found.get();
            boolean anyOff = sources.stream().anyMatch(s -> s.covers(city) && !gates.isOn(s.gate()));
            if (anyOff) return cityReason = "source_off";
            return null;
        }

        String syncReason() {
            if (syncDone) return syncReason;
            syncDone = true;
            Optional<GenreWeekCount> latest = counts.findTopByCityKeyOrderByUpdatedAtDesc(city.key());
            if (latest.isEmpty()) return syncReason = "not_synced";
            LocalDate updated = latest.get().getUpdatedAt().atZone(in.zone()).toLocalDate();
            if (updated.isBefore(in.today().minusDays(STALE_DAYS))) return syncReason = "stale";
            return null;
        }

        Map<LocalDate, GenreWeekCount> weeks() {
            if (weeks == null) {
                weeks = new HashMap<>();
                LocalDate to = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                counts.findByCityKeyAndGenreFamilyAndSubGenreAndWeekStartBetween(city.key(), in.genreFamily(), "",
                        thisMonday.minusWeeks(NORM_WEEKS), to).forEach(c -> weeks.put(c.getWeekStart(), c));
            }
            return weeks;
        }

        /** The 12 past weeks' counts, oldest first, or null when any is missing. */
        int[] history() {
            int[] out = new int[NORM_WEEKS];
            for (int i = 0; i < NORM_WEEKS; i++) {
                GenreWeekCount c = weeks().get(thisMonday.minusWeeks(NORM_WEEKS - i));
                if (c == null) return null;
                out[i] = c.getEventCount();
            }
            return out;
        }

        List<OpenEventOccurrence> rows() {
            if (rows == null) {
                rows = occurrences.findByCityKeyAndNightDateBetween(city.key(),
                        in.today().minusDays(OpenEventsWriter.RETENTION_DAYS),
                        in.today().plusDays(OpenEventsJob.LOOKAHEAD_DAYS));
            }
            return rows;
        }
    }

    private Finding weekVsNorm(Question q, LocalDate d, Reads reads) {
        int[] history = reads.history();
        if (history == null) return Finding.notChecked(q, "no_data");
        LocalDate weekStart = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        GenreWeekCount candidate = reads.weeks().get(weekStart);
        if (candidate == null) return Finding.notChecked(q, "no_data");
        int[] sorted = history.clone();
        Arrays.sort(sorted);
        double norm = (sorted[NORM_WEEKS / 2 - 1] + sorted[NORM_WEEKS / 2]) / 2.0;
        int count = candidate.getEventCount();
        double excess = count - norm;
        int strength;
        if (count >= strongRatio * norm && excess >= 2.0 * minExcess) strength = 2;
        else if (count >= busyRatio * norm && excess >= minExcess) strength = 1;
        else return Finding.clear(q);

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("weekStart", weekStart.toString());
        facts.put("count", count);
        facts.put("norm", norm == Math.rint(norm) ? (Object) (long) norm : (Object) norm);
        facts.put("normWeeks", NORM_WEEKS);
        List<Map<String, Object>> sources;
        try {
            sources = credits(candidate.getSourcesJson());
        } catch (JsonProcessingException e) {
            // A bad stored row must not fail the whole check; only the parse failure is caught.
            log.error("OpenEventsEvaluator: genre_week_count.sources_json of {} {} is not a JSON array",
                    candidate.getCityKey(), weekStart, e);
            return Finding.notChecked(q, "no_data");
        }
        facts.put("sources", sources);
        return Finding.found(q, Kind.RISK, strength, facts, null);
    }

    /** The week's sources_json entries, each with the catalog's url and credit line. */
    private List<Map<String, Object>> credits(String sourcesJson) throws JsonProcessingException {
        List<Map<String, Object>> raw =
                JSON.readValue(sourcesJson, JSON.getTypeFactory().constructCollectionType(List.class, Map.class));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("source", m.get("source"));
            s.put("licence", m.get("licence"));
            s.put("events", m.get("events"));
            catalog.byId(String.valueOf(m.get("source"))).ifPresent(c -> {
                s.put("url", c.url());
                s.put("credit", c.creditLine());
            });
            out.add(s);
        }
        return out;
    }

    private Finding community(Question q, LocalDate d, Reads reads) {
        List<OpenEventOccurrence> near = reads.rows().stream()
                .filter(OpenEventOccurrence::isCommunity)
                .filter(o -> !o.getNightDate().isBefore(d.minusDays(1)) && !o.getNightDate().isAfter(d.plusDays(1)))
                .sorted(Comparator.<OpenEventOccurrence>comparingLong(o -> Math.abs(ChronoUnit.DAYS.between(d, o.getNightDate())))
                        .thenComparing(OpenEventOccurrence::getTitle)
                        .thenComparing(OpenEventOccurrence::getSource))
                .toList();
        if (near.isEmpty()) return Finding.clear(q);
        Set<String> distinct = new HashSet<>();
        near.forEach(o -> distinct.add(o.getTitleKey() + "|" + o.getNightDate()));
        OpenEventOccurrence chosen = near.get(0);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("date", chosen.getNightDate().toString());
        facts.put("name", chosen.getTitle());
        facts.put("count", distinct.size());
        putSource(facts, chosen);
        return Finding.found(q, Kind.RISK, chosen.getNightDate().equals(d) ? 2 : 1, facts, chosen.getUrl());
    }

    private void putSource(Map<String, Object> facts, OpenEventOccurrence row) {
        facts.put("source", row.getSource());
        facts.put("licence", row.getLicence());
        facts.put("credit", catalog.byId(row.getSource()).map(PublicDataSource::creditLine).orElse(null));
    }

    private record Series(List<OpenEventOccurrence> rows, String pattern, Integer ordinal, int count, LocalDate lastDate,
                          boolean announced, OpenEventOccurrence latest) {}

    private record Pattern(String name, Integer ordinal, int count, LocalDate lastDate) {}

    private Finding recurring(Question q, DateCheckInput in, LocalDate d, Reads reads) {
        if (reads.history() == null) return Finding.notChecked(q, "no_data");
        Map<String, List<OpenEventOccurrence>> byTitle = new TreeMap<>();
        for (OpenEventOccurrence o : reads.rows()) {
            if (!OpenEventsWriter.genreKeys(o.getGenreKeys()).contains(in.genreFamily())) continue;
            byTitle.computeIfAbsent(o.getTitleKey(), k -> new ArrayList<>()).add(o);
        }
        List<Series> found = new ArrayList<>();
        for (List<OpenEventOccurrence> rows : byTitle.values()) {
            TreeSet<LocalDate> nights = new TreeSet<>();
            rows.forEach(o -> nights.add(o.getNightDate()));
            boolean announced = nights.remove(d);
            Pattern p = pattern(nights, d, announced);
            if (p == null) continue;
            OpenEventOccurrence latest = rows.stream()
                    .max(Comparator.comparing(OpenEventOccurrence::getNightDate)
                            .thenComparing(OpenEventOccurrence::getSource, Comparator.reverseOrder()))
                    .orElseThrow();
            found.add(new Series(rows, p.name(), p.ordinal(), p.count(), p.lastDate(), announced, latest));
        }
        if (found.isEmpty()) return Finding.clear(q);
        Series chosen = found.stream()
                .sorted(Comparator.comparing((Series s) -> !s.announced())
                        .thenComparing(Series::count, Comparator.reverseOrder())
                        .thenComparing(s -> s.latest().getTitle()))
                .findFirst().orElseThrow();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("name", chosen.latest().getTitle());
        facts.put("pattern", chosen.pattern());
        facts.put("weekday", d.getDayOfWeek().name().toLowerCase(Locale.ROOT));
        facts.put("ordinal", chosen.ordinal());
        facts.put("count", chosen.count());
        facts.put("lastDate", chosen.lastDate().toString());
        facts.put("announced", chosen.announced());
        facts.put("seriesCount", found.size());
        putSource(facts, chosen.latest());
        return Finding.found(q, Kind.RISK, chosen.announced() ? 2 : 1, facts, chosen.latest().getUrl());
    }

    /**
     * A weekly-shaped series (two same-weekday nights 7 days apart, or two in one month) is judged as weekly only;
     * otherwise last weekday of the month, then nth weekday. Null when nothing fits {@code d}.
     */
    private Pattern pattern(TreeSet<LocalDate> support, LocalDate d, boolean announced) {
        DayOfWeek weekday = d.getDayOfWeek();
        List<LocalDate> sameDay = support.stream().filter(n -> n.getDayOfWeek() == weekday).toList();
        if (weeklyShaped(sameDay)) return weekly(sameDay, d, announced);
        if (isLastOfMonth(d)) {
            Pattern p = monthly("monthly_last", null, sameDay.stream().filter(OpenEventsEvaluator::isLastOfMonth).toList(),
                    d, announced);
            if (p != null) return p;
        }
        int ordinal = ordinal(d);
        return monthly("monthly_nth", ordinal, sameDay.stream().filter(n -> ordinal(n) == ordinal).toList(), d, announced);
    }

    private static boolean weeklyShaped(List<LocalDate> sameDay) {
        Set<YearMonth> months = new HashSet<>();
        for (int i = 0; i < sameDay.size(); i++) {
            if (i > 0 && sameDay.get(i).equals(sameDay.get(i - 1).plusDays(7))) return true;
            if (!months.add(YearMonth.from(sameDay.get(i)))) return true;
        }
        return false;
    }

    /** A pattern night listed after {@code d} with none on it means the series skips {@code d}. */
    private static boolean skipsDate(List<LocalDate> nights, LocalDate d, boolean announced) {
        return !announced && nights.stream().anyMatch(n -> n.isAfter(d));
    }

    /** The latest chain of nights before {@code d}, each 7 days apart, long enough and within the weekly gap. */
    private Pattern weekly(List<LocalDate> sameDay, LocalDate d, boolean announced) {
        if (skipsDate(sameDay, d, announced)) return null;
        int run = 0;
        LocalDate prev = null;
        LocalDate chainEnd = null;
        int chainLength = 0;
        for (LocalDate n : sameDay) {
            if (!n.isBefore(d)) break;
            run = prev != null && n.equals(prev.plusDays(7)) ? run + 1 : 1;
            prev = n;
            if (run >= minOccurrences && within(n, d, weeklyMaxGapDays)) {
                chainEnd = n;
                chainLength = run;
            }
        }
        return chainEnd == null ? null : new Pattern("weekly", null, chainLength, chainEnd);
    }

    /** Enough nights before {@code d} in distinct months, the latest within the monthly gap. */
    private Pattern monthly(String name, Integer ordinal, List<LocalDate> nights, LocalDate d, boolean announced) {
        if (skipsDate(nights, d, announced)) return null;
        List<LocalDate> before = nights.stream().filter(n -> n.isBefore(d)).toList();
        Set<YearMonth> months = new HashSet<>();
        before.forEach(n -> months.add(YearMonth.from(n)));
        if (months.size() < minOccurrences) return null;
        LocalDate last = before.get(before.size() - 1);
        if (!within(last, d, monthlyMaxGapDays)) return null;
        return new Pattern(name, ordinal, months.size(), last);
    }

    private static boolean within(LocalDate night, LocalDate d, int maxGapDays) {
        return ChronoUnit.DAYS.between(night, d) <= maxGapDays;
    }

    private static boolean isLastOfMonth(LocalDate n) {
        return n.plusDays(7).getMonth() != n.getMonth();
    }

    private static int ordinal(LocalDate n) {
        return (n.getDayOfMonth() - 1) / 7 + 1;
    }
}
