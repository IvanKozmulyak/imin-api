package com.imin.iminapi.predictor.sources.openevents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.predictor.model.GenreWeekCount;
import com.imin.iminapi.predictor.model.OpenEventOccurrence;
import com.imin.iminapi.predictor.repository.GenreWeekCountRepository;
import com.imin.iminapi.predictor.repository.OpenEventOccurrenceRepository;
import com.imin.iminapi.predictor.rules.QuestionBank;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes open_event_occurrence and re-derives genre_week_count from it. No ON CONFLICT, so H2 runs it too;
 * counts are always recomputed from the stored rows, never applied as a delta.
 */
@Component
public class OpenEventsWriter {

    /** V165 column limits. */
    static final int MAX_TITLE = 255;
    static final int MAX_URL = 512;
    static final int MAX_SOURCE_EVENT_ID = 64;
    /** Rows older than this many days are pruned; covers the 182-day backfill. */
    public static final int RETENTION_DAYS = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One matched night of one listing; {@code genres} are bucket names. */
    public record Row(String sourceEventId, LocalDate night, String title, String url, Set<String> genres,
                      boolean community, String licence, String credit) {
        public Row {
            genres = Set.copyOf(genres);
        }
    }

    /** {@code droppedUrl}/{@code droppedId}: rows refused for an over-long url or source id. */
    public record Written(int inserted, int droppedUrl, int droppedId) {}

    private final OpenEventOccurrenceRepository occurrences;
    private final GenreWeekCountRepository counts;

    public OpenEventsWriter(OpenEventOccurrenceRepository occurrences, GenreWeekCountRepository counts) {
        this.occurrences = occurrences;
        this.counts = counts;
    }

    /** Deletes the source's city rows from {@code from} on, then inserts {@code rows} (one per event and night). */
    @Transactional
    public Written replaceFuture(String source, String cityKey, LocalDate from, List<Row> rows, Instant syncedAt) {
        occurrences.deleteBySourceAndCityKeyAndNightDateGreaterThanEqual(source, cityKey, from);
        return insert(source, cityKey, rows.stream().filter(r -> !r.night().isBefore(from)).toList(), syncedAt);
    }

    /** Past nights only, and only while this source has nothing stored before {@code before} for the city. */
    @Transactional
    public Written insertBackfill(String source, String cityKey, LocalDate before, List<Row> rows, Instant syncedAt) {
        if (occurrences.existsBySourceAndCityKeyAndNightDateBefore(source, cityKey, before)) return new Written(0, 0, 0);
        return insert(source, cityKey, rows.stream().filter(r -> r.night().isBefore(before)).toList(), syncedAt);
    }

    /**
     * Upserts genre_week_count(city, bucket, '', week) for every week and bucket: distinct (title_key, night) pairs
     * of the week across the sources in {@code sourceSet} (id → licence), zero counts included.
     */
    @Transactional
    public int recount(String cityKey, List<LocalDate> weeks, Map<String, String> sourceSet, Instant syncedAt) {
        if (weeks.isEmpty()) return 0;
        LocalDate first = weeks.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate last = weeks.stream().max(LocalDate::compareTo).orElseThrow().plusDays(6);
        List<OpenEventOccurrence> rows = occurrences.findByCityKeyAndNightDateBetween(cityKey, first, last).stream()
                .filter(o -> sourceSet.containsKey(o.getSource())).toList();
        Map<OpenEventOccurrence, Set<String>> genres = new HashMap<>();
        rows.forEach(o -> genres.put(o, genreKeys(o.getGenreKeys())));
        Map<String, GenreWeekCount> stored = new HashMap<>();
        for (GenreWeekCount c : counts.findByCityKeyAndWeekStartIn(cityKey, weeks)) {
            if (c.getSubGenre().isEmpty()) stored.put(c.getGenreFamily() + "|" + c.getWeekStart(), c);
        }
        List<GenreWeekCount> toSave = new ArrayList<>();
        for (LocalDate week : weeks) {
            LocalDate end = week.plusDays(6);
            for (String bucket : QuestionBank.GENRE_BUCKETS) {
                Set<String> all = new HashSet<>();
                Map<String, Set<String>> perSource = new LinkedHashMap<>();
                sourceSet.keySet().stream().sorted().forEach(s -> perSource.put(s, new HashSet<>()));
                for (OpenEventOccurrence o : rows) {
                    if (o.getNightDate().isBefore(week) || o.getNightDate().isAfter(end)) continue;
                    if (!genres.get(o).contains(bucket)) continue;
                    String pair = o.getTitleKey() + "|" + o.getNightDate();
                    all.add(pair);
                    perSource.get(o.getSource()).add(pair);
                }
                List<Map<String, Object>> credit = new ArrayList<>();
                perSource.forEach((s, pairs) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", s);
                    m.put("licence", sourceSet.get(s));
                    m.put("events", pairs.size());
                    credit.add(m);
                });
                GenreWeekCount c = stored.get(bucket + "|" + week);
                if (c == null) {
                    c = new GenreWeekCount();
                    c.setCityKey(cityKey);
                    c.setGenreFamily(bucket);
                    c.setSubGenre("");
                    c.setWeekStart(week);
                }
                c.setEventCount(all.size());
                c.setSourcesJson(json(credit));
                c.setUpdatedAt(syncedAt);
                toSave.add(c);
            }
        }
        counts.saveAll(toSave);
        return toSave.size();
    }

    /** Weeks among {@code weeks} with no genre_week_count row for the city yet. */
    @Transactional(readOnly = true)
    public List<LocalDate> uncountedWeeks(String cityKey, List<LocalDate> weeks) {
        if (weeks.isEmpty()) return List.of();
        Set<LocalDate> counted = new HashSet<>();
        counts.findByCityKeyAndWeekStartIn(cityKey, weeks).forEach(c -> counted.add(c.getWeekStart()));
        return weeks.stream().filter(w -> !counted.contains(w)).toList();
    }

    /** Deletes nights older than {@link #RETENTION_DAYS} days before {@code today}. */
    @Transactional
    public int prune(LocalDate today) {
        return occurrences.deleteByNightDateBefore(today.minusDays(RETENTION_DAYS));
    }

    private Written insert(String source, String cityKey, List<Row> rows, Instant syncedAt) {
        Set<String> seen = new HashSet<>();
        List<OpenEventOccurrence> toSave = new ArrayList<>();
        int droppedUrl = 0;
        int droppedId = 0;
        for (Row r : rows) {
            if (r.sourceEventId().length() > MAX_SOURCE_EVENT_ID) {
                droppedId++;
                continue;
            }
            if (r.url().length() > MAX_URL) {
                droppedUrl++;
                continue;
            }
            if (!seen.add(r.sourceEventId() + "|" + r.night())) continue;
            OpenEventOccurrence o = new OpenEventOccurrence();
            o.setSource(source);
            o.setSourceEventId(r.sourceEventId());
            o.setCityKey(cityKey);
            o.setNightDate(r.night());
            o.setTitle(cut(r.title()));
            o.setTitleKey(cut(GenreMatcher.normalise(r.title())));
            o.setUrl(r.url());
            o.setGenreKeys(json(QuestionBank.GENRE_BUCKETS.stream().filter(r.genres()::contains).toList()));
            o.setCommunity(r.community());
            o.setLicence(r.licence());
            o.setCredit(r.credit());
            o.setSyncedAt(syncedAt);
            toSave.add(o);
        }
        occurrences.saveAll(toSave);
        return new Written(toSave.size(), droppedUrl, droppedId);
    }

    private static String cut(String s) {
        return s.length() <= MAX_TITLE ? s : s.substring(0, MAX_TITLE);
    }

    /** The bucket names of an {@code open_event_occurrence.genre_keys} JSON array. */
    public static Set<String> genreKeys(String json) {
        try {
            return new HashSet<>(List.of(JSON.readValue(json, String[].class)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("open_event_occurrence.genre_keys is not a JSON array: " + json, e);
        }
    }

    private static String json(Collection<?> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
