package com.imin.iminapi.predictor.sources.openevents;

import com.imin.iminapi.predictor.repository.OpenEventOccurrenceRepository;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.openevents.GenreMatcher.Match;
import com.imin.iminapi.predictor.sources.openevents.OpenEventCities.City;
import com.imin.iminapi.predictor.sources.openevents.OpenEventSource.Fetch;
import com.imin.iminapi.predictor.sources.openevents.OpenEventSource.RawEvent;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsWriter.Row;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsWriter.Written;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;

/**
 * Weekly sync of open event listings into open_event_occurrence, then genre_week_count; also once at boot while
 * the table is empty. A (city, source) pair runs only while that source's gate is on. Counts of a city are
 * re-derived only when every planned source of the city succeeded, and only for weeks every source fully covers,
 * so neither a failure nor a source that keeps no history makes a week look quiet.
 */
@Component
public class OpenEventsJob {

    private static final Logger log = LoggerFactory.getLogger(OpenEventsJob.class);
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final int LOOKAHEAD_DAYS = 120;
    static final int BACKFILL_DAYS = 182;

    private final OpenEventCities cities;
    private final GenreMatcher matcher;
    private final List<OpenEventSource> sources;
    private final OpenEventsWriter writer;
    private final OpenEventOccurrenceRepository repository;
    private final SourceGates gates;
    private final Clock clock;
    /** This bean through its proxy, so the startup run takes the ShedLock too. */
    private final ObjectProvider<OpenEventsJob> self;
    private final Executor executor;

    public OpenEventsJob(OpenEventCities cities, GenreMatcher matcher, List<OpenEventSource> sources,
                         OpenEventsWriter writer, OpenEventOccurrenceRepository repository, SourceGates gates,
                         Clock clock, ObjectProvider<OpenEventsJob> self,
                         @Qualifier("openEventsSyncExecutor") Executor executor) {
        this.cities = cities;
        this.matcher = matcher;
        this.sources = List.copyOf(sources);
        this.writer = writer;
        this.repository = repository;
        this.gates = gates;
        this.clock = clock;
        this.self = self;
        this.executor = executor;
    }

    /** Off the boot thread: a slow or failing upstream never holds up startup. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            if (!anyGateOn() || repository.count() != 0) return;
            executor.execute(() -> {
                try {
                    self.getObject().run();
                } catch (Exception e) {
                    log.warn("OpenEventsJob startup run failed (weekly cron will retry): {}", e.toString());
                }
            });
        } catch (Exception e) {
            log.warn("OpenEventsJob startup check failed (weekly cron will retry): {}", e.toString());
        }
    }

    @Scheduled(cron = "0 45 5 * * MON", zone = "Europe/Paris")
    @SchedulerLock(name = "open_events_sync", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        if (!anyGateOn()) return;
        LocalDate today = LocalDate.now(clock.withZone(PARIS));
        LocalDate from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        LocalDate to = today.plusDays(LOOKAHEAD_DAYS);
        LocalDate backFrom = today.minusDays(BACKFILL_DAYS);
        Instant now = clock.instant();
        Set<String> rateLimited = new HashSet<>();
        Map<String, Integer> drops = new TreeMap<>();
        int planned = 0;
        int stored = 0;
        int failed = 0;
        int matchedRows = 0;
        int recountFailed = 0;
        Exception last = null;
        for (City city : cities.cities()) {
            List<OpenEventSource> plannedSources = sources.stream()
                    .filter(s -> gates.isOn(s.gate()) && s.covers(city)).toList();
            if (plannedSources.isEmpty()) continue;
            planned += plannedSources.size();
            // weeks before a non-backfilling source's first sync are never recounted: it cannot fill them
            LocalDate countFrom = from;
            for (OpenEventSource s : plannedSources) {
                if (s.backfills()) continue;
                LocalDate firstSync = repository.findFirstBySourceAndCityKeyOrderBySyncedAtAsc(s.id(), city.key())
                        .map(o -> o.getSyncedAt().atZone(PARIS).toLocalDate()).orElse(today);
                LocalDate covered = firstSync.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
                if (covered.isAfter(countFrom)) countFrom = covered;
            }
            boolean allOk = true;
            Map<String, String> backfilled = new LinkedHashMap<>();
            for (OpenEventSource s : plannedSources) {
                if (rateLimited.contains(s.id())) {
                    failed++;
                    allOk = false;
                    continue;
                }
                try {
                    // no past rows yet: a backfill that failed or never ran is retried on every run until it lands
                    boolean firstRun = !repository.existsBySourceAndCityKeyAndNightDateBefore(s.id(), city.key(), from);
                    Fetch future = s.fetch(city, from, to);
                    Fetch past = firstRun && s.backfills() ? s.fetch(city, backFrom, from.minusDays(1)) : null;
                    if (future.partial() || (past != null && past.partial())) {
                        log.warn("OpenEventsJob: {} {} partial (page cap), its stored rows are kept", s.id(), city.key());
                        failed++;
                        allOk = false;
                        continue;
                    }
                    List<Row> rows = rows(future, drops);
                    // a source that drops ended events would wipe last week's stored nights: replace from tonight only
                    Written w = writer.replaceFuture(s.id(), city.key(), s.backfills() ? from : today, rows, now);
                    count(w, drops);
                    matchedRows += rows.size();
                    if (past != null) {
                        List<Row> pastRows = rows(past, drops);
                        count(writer.insertBackfill(s.id(), city.key(), from, pastRows, now), drops);
                        matchedRows += pastRows.size();
                        backfilled.put(s.id(), s.licence());
                    }
                    stored++;
                } catch (OpenEventsRateLimitedException e) {
                    log.warn("OpenEventsJob: {} rate limited at {}, skipping it for the rest of this run", s.id(), city.key());
                    rateLimited.add(s.id());
                    failed++;
                    allOk = false;
                    last = e;
                } catch (Exception e) {
                    log.warn("OpenEventsJob: {} {} failed: {}", s.id(), city.key(), e.toString());
                    failed++;
                    allOk = false;
                    last = e;
                }
            }
            if (!allOk) {
                log.warn("OpenEventsJob: {} had a failed source, counts kept from last run", city.key());
                continue;
            }
            try {
                recount(city, plannedSources, countFrom, to, backFrom, from, backfilled, now);
            } catch (Exception e) {
                log.error("OpenEventsJob: recount of {} failed, its counts are kept from the last run", city.key(), e);
                recountFailed++;
            }
        }
        try {
            writer.prune(today);
        } catch (Exception e) {
            log.warn("OpenEventsJob: prune failed (next run retries): {}", e.toString());
        }
        if (stored == 0 && failed > 0) {
            log.error("OpenEventsJob: nothing stored, {} of {} source/city pairs failed", failed, planned, last);
        } else if (failed > 0 || recountFailed > 0) {
            log.warn("OpenEventsJob: {} of {} source/city pairs failed, {} recounts failed, {} matched rows, drops {}",
                    failed, planned, recountFailed, matchedRows, drops);
        } else if (matchedRows == 0) {
            log.warn("OpenEventsJob: {} of {} source/city pairs stored but no matched event; drops {}", stored, planned,
                    drops);
        } else {
            log.info("OpenEventsJob: done {}..{}, {} of {} source/city pairs stored, {} matched rows, drops {}", from, to,
                    stored, planned, matchedRows, drops);
        }
    }

    /**
     * Future weeks with every planned source; whole backfill weeks with the backfilling sources, both when this run
     * backfilled and when stored past rows have weeks never counted (a run whose recount did not happen).
     */
    private void recount(City city, List<OpenEventSource> plannedSources, LocalDate countFrom, LocalDate to,
                         LocalDate backFrom, LocalDate from, Map<String, String> backfilled, Instant now) {
        Map<String, String> sourceSet = new LinkedHashMap<>();
        plannedSources.forEach(s -> sourceSet.put(s.id(), s.licence()));
        List<LocalDate> weeks = mondays(countFrom, to);
        if (!weeks.isEmpty()) writer.recount(city.key(), weeks, sourceSet, now);
        // only whole weeks; the backfill's first, partial week keeps its rows but gets no count
        List<LocalDate> backWeeks = mondays(backFrom.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)),
                from.minusWeeks(1));
        if (!backfilled.isEmpty()) {
            writer.recount(city.key(), backWeeks, backfilled, now);
            return;
        }
        Map<String, String> withHistory = new LinkedHashMap<>();
        for (OpenEventSource s : plannedSources) {
            if (s.backfills() && repository.existsBySourceAndCityKeyAndNightDateBefore(s.id(), city.key(), from)) {
                withHistory.put(s.id(), s.licence());
            }
        }
        if (withHistory.isEmpty()) return;
        List<LocalDate> uncounted = writer.uncountedWeeks(city.key(), backWeeks);
        if (!uncounted.isEmpty()) writer.recount(city.key(), uncounted, withHistory, now);
    }

    private boolean anyGateOn() {
        boolean openagenda = gates.isOn("openagenda");
        boolean quefaireaparis = gates.isOn("quefaireaparis");
        return openagenda || quefaireaparis;
    }

    /** One row per matched event and night; events matching no genre and no local event are counted, not kept. */
    private List<Row> rows(Fetch fetch, Map<String, Integer> drops) {
        fetch.dropped().forEach((k, v) -> drops.merge(k, v, Integer::sum));
        List<Row> out = new ArrayList<>();
        for (RawEvent e : fetch.events()) {
            Match m = matcher.match(e.title(), e.keywords());
            if (!m.matched()) {
                drops.merge("unmatched", 1, Integer::sum);
                continue;
            }
            for (LocalDate night : e.nights()) {
                out.add(new Row(e.sourceEventId(), night, e.title(), e.url(), m.genres(), m.community(), e.licence(),
                        e.credit()));
            }
        }
        return out;
    }

    private static void count(Written w, Map<String, Integer> drops) {
        if (w.droppedUrl() > 0) drops.merge("url_too_long", w.droppedUrl(), Integer::sum);
        if (w.droppedId() > 0) drops.merge("id_too_long", w.droppedId(), Integer::sum);
    }

    static List<LocalDate> mondays(LocalDate first, LocalDate last) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); !d.isAfter(last); d = d.plusWeeks(1)) {
            out.add(d);
        }
        return out;
    }
}
