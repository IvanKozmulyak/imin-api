package com.imin.iminapi.predictor.sources.openevents;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.imin.iminapi.predictor.repository.OpenEventOccurrenceRepository;
import com.imin.iminapi.predictor.sources.SourceGates;
import com.imin.iminapi.predictor.sources.openevents.OpenEventCities.City;
import com.imin.iminapi.predictor.sources.openevents.OpenEventSource.Fetch;
import com.imin.iminapi.predictor.sources.openevents.OpenEventSource.RawEvent;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsWriter.Row;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsWriter.Written;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenEventsJobTest {

    // 2026-10-01 is a Thursday: the previous ISO week starts on Monday 2026-09-21; +120 days is 2027-01-29.
    private static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDate FROM = LocalDate.of(2026, 9, 21);
    private static final LocalDate TO = LocalDate.of(2027, 1, 29);
    // today - 182 = 2026-04-02 (also a Thursday); the backfill window ends the day before FROM
    private static final LocalDate BACK_FROM = LocalDate.of(2026, 4, 2);
    private static final LocalDate BACK_TO = LocalDate.of(2026, 9, 20);
    private static final String LO = "Licence Ouverte 2.0";
    private static final String ODBL = "ODbL 1.0";

    private static final OpenEventCities CITIES = OpenEventCities.parse(new ByteArrayInputStream("""
            version: 1
            verified_on: 2026-10-01
            cities:
              lille:
                country: FR
                aliases: [lille]
                openagenda:
                  - { uid: 1, slug: ville-de-lille, name: "Ville de Lille", licence: "Licence Ouverte 2.0" }
              paris:
                country: FR
                aliases: [paris]
                quefaireaparis: true
                openagenda:
                  - { uid: 2, slug: paris-agenda, name: "Paris", licence: "Licence Ouverte 2.0" }
            """.getBytes(StandardCharsets.UTF_8)));
    private static final City LILLE = CITIES.city("lille").orElseThrow();
    private static final City PARIS = CITIES.city("paris").orElseThrow();
    private static final GenreMatcher MATCHER = GenreMatcher.load(new DefaultResourceLoader());

    private final OpenEventSource oa = source("openagenda", LO, true, c -> !c.openagenda().isEmpty());
    private final OpenEventSource qfap = source("quefaireaparis", ODBL, false, City::quefaireaparis);
    private final OpenEventsWriter writer = mock(OpenEventsWriter.class);
    private final OpenEventOccurrenceRepository repository = mock(OpenEventOccurrenceRepository.class);
    private final SourceGates gates = mock(SourceGates.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<OpenEventsJob> self = mock(ObjectProvider.class);
    private final Logger jobLog = (Logger) LoggerFactory.getLogger(OpenEventsJob.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    {
        logs.start();
        jobLog.addAppender(logs);
        when(gates.isOn("openagenda")).thenReturn(true);
        when(gates.isOn("quefaireaparis")).thenReturn(true);
        when(repository.existsBySourceAndCityKeyAndNightDateBefore(anyString(), anyString(), any())).thenReturn(true);
        // Que Faire à Paris has synced Paris since before the window, so it covers every week counted
        when(repository.findFirstBySourceAndCityKeyOrderBySyncedAtAsc("quefaireaparis", "paris"))
                .thenReturn(java.util.Optional.of(syncedAt(Instant.parse("2026-09-01T03:45:00Z"))));
        when(writer.replaceFuture(anyString(), anyString(), any(), anyList(), any())).thenReturn(new Written(1, 0, 0));
        when(writer.insertBackfill(anyString(), anyString(), any(), anyList(), any())).thenReturn(new Written(1, 0, 0));
        when(oa.fetch(any(), any(), any())).thenReturn(fetch(raw("1", "Soirée techno", FROM.plusDays(11))));
        when(qfap.fetch(any(), any(), any())).thenReturn(fetch(raw("q1", "Jazz au parc", FROM.plusDays(12))));
    }

    @AfterEach
    void detach() {
        jobLog.detachAppender(logs);
    }

    private static OpenEventSource source(String id, String licence, boolean backfills,
                                          java.util.function.Predicate<City> covers) {
        OpenEventSource s = mock(OpenEventSource.class);
        when(s.id()).thenReturn(id);
        when(s.gate()).thenReturn(id);
        when(s.licence()).thenReturn(licence);
        when(s.backfills()).thenReturn(backfills);
        when(s.covers(any())).thenAnswer(inv -> covers.test(inv.getArgument(0)));
        return s;
    }

    private static com.imin.iminapi.predictor.model.OpenEventOccurrence syncedAt(Instant at) {
        com.imin.iminapi.predictor.model.OpenEventOccurrence o = new com.imin.iminapi.predictor.model.OpenEventOccurrence();
        o.setSyncedAt(at);
        return o;
    }

    private static RawEvent raw(String id, String title, LocalDate... nights) {
        return new RawEvent(id, title, "https://example.org/" + id, List.of(nights), List.of(), LO, null);
    }

    private static Fetch fetch(RawEvent... events) {
        return new Fetch(List.of(events), false, Map.of());
    }

    private OpenEventsJob job(Executor executor) {
        OpenEventsJob job = new OpenEventsJob(CITIES, MATCHER, List.of(oa, qfap), writer, repository, gates,
                Clock.fixed(NOW, ZoneOffset.UTC), self, executor);
        when(self.getObject()).thenReturn(job);
        return job;
    }

    private OpenEventsJob job() {
        return job(Runnable::run);
    }

    private static List<LocalDate> mondays(LocalDate first, LocalDate last) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusWeeks(1)) out.add(d);
        return out;
    }

    private static final List<LocalDate> FUTURE_WEEKS = mondays(FROM, LocalDate.of(2027, 1, 25));
    private static final List<LocalDate> BACK_WEEKS = mondays(LocalDate.of(2026, 4, 6), LocalDate.of(2026, 9, 14));
    // a first Que Faire à Paris sync on Thursday 1 Oct fully covers weeks from Monday 5 Oct
    private static final List<LocalDate> QFAP_FIRST_WEEKS = mondays(LocalDate.of(2026, 10, 5), LocalDate.of(2027, 1, 25));

    private List<ILoggingEvent> at(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    @Test
    void gatesOffMakesNoCall() {
        when(gates.isOn("openagenda")).thenReturn(false);
        when(gates.isOn("quefaireaparis")).thenReturn(false);

        job().run();
        job().onStartup();

        verify(gates, atLeastOnce()).isOn("openagenda");
        verify(gates, atLeastOnce()).isOn("quefaireaparis");
        verify(oa, never()).fetch(any(), any(), any());
        verify(qfap, never()).fetch(any(), any(), any());
        verify(writer, never()).prune(any());
        verify(repository, never()).count();
    }

    @Test
    void windowIsLastMondayToPlus120() {
        job().run();

        verify(oa).fetch(LILLE, FROM, TO);
        verify(oa).fetch(PARIS, FROM, TO);
        verify(qfap).fetch(PARIS, FROM, TO);
        verify(qfap, never()).fetch(eq(LILLE), any(), any());
        assertThat(FUTURE_WEEKS).hasSize(19).startsWith(FROM).endsWith(LocalDate.of(2027, 1, 25));
        verify(writer).recount("lille", FUTURE_WEEKS, Map.of("openagenda", LO), NOW);
        verify(writer).recount("paris", FUTURE_WEEKS, Map.of("openagenda", LO, "quefaireaparis", ODBL), NOW);
        verify(writer).prune(TODAY);
        verify(writer, never()).insertBackfill(anyString(), anyString(), any(), anyList(), any());
    }

    @Test
    void nonBackfillingSourceReplacesFromTonightOnly() {
        // upstream has dropped last week's ended events: the stored ones must stay
        when(qfap.fetch(PARIS, FROM, TO)).thenReturn(fetch(raw("q1", "Jazz au parc", TODAY.plusDays(2))));

        job().run();

        verify(writer).replaceFuture(eq("quefaireaparis"), eq("paris"), eq(TODAY), anyList(), eq(NOW));
        verify(writer).replaceFuture(eq("openagenda"), eq("paris"), eq(FROM), anyList(), eq(NOW));
        verify(writer).replaceFuture(eq("openagenda"), eq("lille"), eq(FROM), anyList(), eq(NOW));
        verify(writer).recount("paris", FUTURE_WEEKS, Map.of("openagenda", LO, "quefaireaparis", ODBL), NOW);
    }

    @Test
    void firstSyncOfNonBackfillingSourceCountsFromNextMonday() {
        when(repository.existsBySourceAndCityKeyAndNightDateBefore("quefaireaparis", "paris", FROM)).thenReturn(false);
        when(repository.findFirstBySourceAndCityKeyOrderBySyncedAtAsc("quefaireaparis", "paris"))
                .thenReturn(java.util.Optional.empty());

        job().run();

        verify(repository).findFirstBySourceAndCityKeyOrderBySyncedAtAsc("quefaireaparis", "paris");
        assertThat(QFAP_FIRST_WEEKS).startsWith(LocalDate.of(2026, 10, 5)).hasSize(17);
        verify(writer).recount("paris", QFAP_FIRST_WEEKS, Map.of("openagenda", LO, "quefaireaparis", ODBL), NOW);
        verify(writer).recount("lille", FUTURE_WEEKS, Map.of("openagenda", LO), NOW);
    }

    @Test
    void secondSyncCountsFromItsFirstMonday() {
        // first synced Thursday 24 Sep: that week is short, so counting starts Monday 28 Sep, not the window's 21 Sep
        when(repository.findFirstBySourceAndCityKeyOrderBySyncedAtAsc("quefaireaparis", "paris"))
                .thenReturn(java.util.Optional.of(syncedAt(Instant.parse("2026-09-24T10:00:00Z"))));

        job().run();

        verify(writer).recount("paris", mondays(LocalDate.of(2026, 9, 28), LocalDate.of(2027, 1, 25)),
                Map.of("openagenda", LO, "quefaireaparis", ODBL), NOW);
    }

    @Test
    void sourceAddedLaterBackfillsExistingCity() {
        // Lille already has rows, but none from this source
        when(repository.existsBySourceAndCityKeyAndNightDateBefore("openagenda", "lille", FROM)).thenReturn(false);

        job().run();

        verify(oa).fetch(LILLE, BACK_FROM, BACK_TO);
        verify(writer).insertBackfill(eq("openagenda"), eq("lille"), eq(FROM), anyList(), eq(NOW));
        verify(oa, never()).fetch(PARIS, BACK_FROM, BACK_TO);
        verify(writer).recount("lille", BACK_WEEKS, Map.of("openagenda", LO), NOW);
    }

    @Test
    void onlyMatchedEventsBecomeRowsOnePerNight() {
        when(oa.fetch(LILLE, FROM, TO)).thenReturn(new Fetch(List.of(
                raw("1", "Soirée techno", FROM.plusDays(11), FROM.plusDays(12)),
                raw("2", "Conférence sur la lumière", FROM.plusDays(11)),
                raw("3", "Braderie de Lille", FROM.plusDays(13))), false, Map.of("status", 2)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Row>> rows = ArgumentCaptor.forClass(List.class);

        job().run();

        verify(writer).replaceFuture(eq("openagenda"), eq("lille"), eq(FROM), rows.capture(), eq(NOW));
        assertThat(rows.getValue()).extracting(Row::sourceEventId, Row::night, Row::genres, Row::community)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("1", FROM.plusDays(11), Set.of("house & techno"), false),
                        org.assertj.core.groups.Tuple.tuple("1", FROM.plusDays(12), Set.of("house & techno"), false),
                        org.assertj.core.groups.Tuple.tuple("3", FROM.plusDays(13), Set.of(), true));
        assertThat(rows.getValue().get(0).url()).isEqualTo("https://example.org/1");
        assertThat(rows.getValue().get(0).licence()).isEqualTo(LO);
        assertThat(at(Level.INFO)).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("unmatched=1").contains("status=2"));
    }

    @Test
    void firstRunBackfills182DaysForBackfillingSourceOnly() {
        when(repository.existsBySourceAndCityKeyAndNightDateBefore(anyString(), anyString(), any())).thenReturn(false);
        when(repository.findFirstBySourceAndCityKeyOrderBySyncedAtAsc("quefaireaparis", "paris"))
                .thenReturn(java.util.Optional.empty());
        when(oa.fetch(LILLE, BACK_FROM, BACK_TO)).thenReturn(fetch(raw("p1", "Rave", LocalDate.of(2026, 6, 5))));

        job().run();

        verify(oa).fetch(LILLE, BACK_FROM, BACK_TO);
        verify(oa).fetch(PARIS, BACK_FROM, BACK_TO);
        verify(qfap, never()).fetch(PARIS, BACK_FROM, BACK_TO);
        verify(writer).insertBackfill(eq("openagenda"), eq("lille"), eq(FROM), anyList(), eq(NOW));
        assertThat(BACK_WEEKS).hasSize(24);
        verify(writer).recount("lille", BACK_WEEKS, Map.of("openagenda", LO), NOW);
        verify(writer).recount("paris", BACK_WEEKS, Map.of("openagenda", LO), NOW);
        verify(writer).recount("paris", QFAP_FIRST_WEEKS, Map.of("openagenda", LO, "quefaireaparis", ODBL), NOW);
    }

    @Test
    void backfillRetriedAfterFailedInsert() {
        when(repository.existsBySourceAndCityKeyAndNightDateBefore("openagenda", "lille", FROM)).thenReturn(false);
        when(writer.insertBackfill(eq("openagenda"), eq("lille"), any(), anyList(), any()))
                .thenThrow(new IllegalStateException("db hiccup"))
                .thenReturn(new Written(3, 0, 0));

        job().run();
        verify(writer, never()).recount(eq("lille"), anyList(), anyMap(), any());
        job().run();

        verify(oa, times(2)).fetch(LILLE, BACK_FROM, BACK_TO);
        verify(writer, times(2)).insertBackfill(eq("openagenda"), eq("lille"), eq(FROM), anyList(), eq(NOW));
        verify(writer).recount("lille", BACK_WEEKS, Map.of("openagenda", LO), NOW);
    }

    @Test
    void storedBackfillWeeksWithoutCountsAreRecounted() {
        // a run backfilled Lille but its recount never happened: the past rows exist, two weeks have no count
        List<LocalDate> uncounted = List.of(LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 13));
        when(writer.uncountedWeeks("lille", BACK_WEEKS)).thenReturn(uncounted);

        job().run();

        verify(oa, never()).fetch(LILLE, BACK_FROM, BACK_TO);
        verify(writer).uncountedWeeks("lille", BACK_WEEKS);
        verify(writer).recount("lille", uncounted, Map.of("openagenda", LO), NOW);
        // Paris's Que Faire à Paris keeps no history, so only OpenAgenda's past rows are counted there
        verify(writer).uncountedWeeks("paris", BACK_WEEKS);
    }

    @Test
    void oneCityRecountFailureStillRecountsOthersPrunesAndLogs() {
        when(writer.recount(eq("lille"), anyList(), anyMap(), any())).thenThrow(new IllegalStateException("lock timeout"));

        job().run();

        verify(writer).recount("paris", FUTURE_WEEKS, Map.of("openagenda", LO, "quefaireaparis", ODBL), NOW);
        verify(writer).prune(TODAY);
        assertThat(at(Level.ERROR)).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).contains("lille");
            assertThat(e.getThrowableProxy().getMessage()).isEqualTo("lock timeout");
        });
        assertThat(at(Level.WARN)).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("1 recounts failed"));
    }

    @Test
    void firstSyncMondayUsesParisDateInSummer() {
        // Sunday 22:30Z on 27 Sep is Monday 00:30 in Paris: that Monday is covered
        when(repository.findFirstBySourceAndCityKeyOrderBySyncedAtAsc("quefaireaparis", "paris"))
                .thenReturn(java.util.Optional.of(syncedAt(Instant.parse("2026-09-27T22:30:00Z"))));
        job().run();
        verify(writer).recount("paris", mondays(LocalDate.of(2026, 9, 28), LocalDate.of(2027, 1, 25)),
                Map.of("openagenda", LO, "quefaireaparis", ODBL), NOW);

        // Monday 22:30Z on 21 Sep is Tuesday 00:30 in Paris (the UTC date would wrongly cover Monday 21 Sep)
        when(repository.findFirstBySourceAndCityKeyOrderBySyncedAtAsc("quefaireaparis", "paris"))
                .thenReturn(java.util.Optional.of(syncedAt(Instant.parse("2026-09-21T22:30:00Z"))));
        job().run();
        verify(writer, times(2)).recount("paris", mondays(LocalDate.of(2026, 9, 28), LocalDate.of(2027, 1, 25)),
                Map.of("openagenda", LO, "quefaireaparis", ODBL), NOW);
    }

    @Test
    void firstSyncMondayAcrossTheOctoberClockChange() {
        // Monday 26 Oct 22:30Z is 23:30 CET (clocks went back on the 25th): still Monday, so it is covered;
        // a fixed summer offset would read Tuesday 00:30 and start a week late
        when(repository.findFirstBySourceAndCityKeyOrderBySyncedAtAsc("quefaireaparis", "paris"))
                .thenReturn(java.util.Optional.of(syncedAt(Instant.parse("2026-10-26T22:30:00Z"))));

        job().run();

        verify(writer).recount("paris", mondays(LocalDate.of(2026, 10, 26), LocalDate.of(2027, 1, 25)),
                Map.of("openagenda", LO, "quefaireaparis", ODBL), NOW);
    }

    @Test
    void sourceFailureKeepsCountsAndWarns() {
        when(qfap.fetch(PARIS, FROM, TO)).thenThrow(new IllegalStateException("upstream 502"));

        job().run();

        verify(writer).replaceFuture(eq("openagenda"), eq("paris"), eq(FROM), anyList(), eq(NOW));
        verify(writer, never()).replaceFuture(eq("quefaireaparis"), anyString(), any(), anyList(), any());
        verify(writer, never()).recount(eq("paris"), anyList(), anyMap(), any());
        verify(writer).recount("lille", FUTURE_WEEKS, Map.of("openagenda", LO), NOW);
        assertThat(at(Level.WARN)).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("paris").contains("counts kept from last run"));
        assertThat(at(Level.WARN)).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("1 of 3"));
        assertThat(at(Level.ERROR)).isEmpty();
    }

    @Test
    void pageCapLeavesRowsAndCounts() {
        when(qfap.fetch(PARIS, FROM, TO)).thenReturn(new Fetch(List.of(raw("q1", "Jazz", FROM.plusDays(3))), true, Map.of()));

        job().run();

        verify(qfap).fetch(PARIS, FROM, TO);
        verify(writer, never()).replaceFuture(eq("quefaireaparis"), anyString(), any(), anyList(), any());
        verify(writer, never()).recount(eq("paris"), anyList(), anyMap(), any());
        assertThat(at(Level.WARN)).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("partial"));
    }

    @Test
    void rateLimitSkipsSourceRestOfRun() {
        when(oa.fetch(LILLE, FROM, TO)).thenThrow(new OpenEventsRateLimitedException("429"));

        job().run();

        verify(oa).fetch(LILLE, FROM, TO);
        verify(oa, never()).fetch(eq(PARIS), any(), any());
        verify(qfap).fetch(PARIS, FROM, TO);
        verify(writer).replaceFuture(eq("quefaireaparis"), eq("paris"), eq(TODAY), anyList(), eq(NOW));
        verify(writer, never()).recount(anyString(), anyList(), anyMap(), any());
        assertThat(at(Level.WARN)).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("2 of 3"));
        assertThat(at(Level.ERROR)).isEmpty();
    }

    @Test
    void earlyStopCountsRemainingAsFailed() {
        when(gates.isOn("quefaireaparis")).thenReturn(false);
        when(oa.fetch(LILLE, FROM, TO)).thenThrow(new OpenEventsRateLimitedException("429"));

        job().run();

        verify(oa).fetch(LILLE, FROM, TO);
        verify(oa, never()).fetch(eq(PARIS), any(), any());
        verify(writer).prune(TODAY);
        assertThat(at(Level.ERROR)).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).contains("2 of 2");
            assertThat(e.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void allPairsFailedLogsErrorWithThrowable() {
        when(oa.fetch(any(), any(), any())).thenThrow(new IllegalStateException("down"));
        when(qfap.fetch(any(), any(), any())).thenThrow(new IllegalStateException("down too"));

        job().run();

        verify(oa).fetch(LILLE, FROM, TO);
        verify(qfap).fetch(PARIS, FROM, TO);
        assertThat(at(Level.ERROR)).singleElement().satisfies(e -> {
            assertThat(e.getFormattedMessage()).contains("3 of 3");
            assertThat(e.getThrowableProxy()).isNotNull();
            assertThat(e.getThrowableProxy().getMessage()).startsWith("down");
        });
    }

    @Test
    void allSucceededNothingMatchedWarns() {
        when(oa.fetch(any(), any(), any())).thenReturn(fetch(raw("1", "Conférence", FROM.plusDays(2))));
        when(qfap.fetch(any(), any(), any())).thenReturn(fetch());

        job().run();

        verify(writer).replaceFuture(eq("openagenda"), eq("lille"), eq(FROM), eq(List.of()), eq(NOW));
        assertThat(at(Level.WARN)).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("no matched event"));
        assertThat(at(Level.ERROR)).isEmpty();
    }

    @Test
    void successLogsInfo() {
        job().run();

        verify(writer).replaceFuture(eq("quefaireaparis"), eq("paris"), eq(TODAY), anyList(), eq(NOW));
        assertThat(at(Level.INFO)).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("3 of 3"));
        assertThat(at(Level.WARN)).isEmpty();
        assertThat(at(Level.ERROR)).isEmpty();
    }

    @Test
    void sourceOutsideTableCheckFailsBoot() {
        OpenEventSource rogue = source("eventbrite", LO, false, c -> true);
        OpenEventSource badLicence = source("openagenda", "CC-BY", false, c -> true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenEventsConfig.checked(rogue))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ck_open_event_occurrence_source");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenEventsConfig.checked(badLicence))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ck_open_event_occurrence_licence");
        assertThat(OpenEventsConfig.checked(oa)).isSameAs(oa);
    }

    @Test
    void startupSeedsOnlyWhenEmpty() {
        when(repository.count()).thenReturn(0L);

        job().onStartup();

        verify(oa).fetch(LILLE, FROM, TO);
        verify(writer).prune(TODAY);
    }

    @Test
    void startupSeedsWithOneGateOn() {
        when(gates.isOn("openagenda")).thenReturn(false);
        when(repository.count()).thenReturn(0L);

        job().onStartup();

        verify(qfap).fetch(PARIS, FROM, TO);
        verify(oa, never()).fetch(any(), any(), any());
    }

    @Test
    void startupSkipsWhenRowsExist() {
        when(repository.count()).thenReturn(5L);

        job().onStartup();

        verify(repository).count();
        verify(oa, never()).fetch(any(), any(), any());
    }

    @Test
    void startupExecutorRejectionIsSwallowed() {
        when(repository.count()).thenReturn(0L);
        OpenEventsJob job = job(r -> {
            throw new RejectedExecutionException("full");
        });

        assertThatCode(job::onStartup).doesNotThrowAnyException();

        verify(repository).count();
        assertThat(at(Level.WARN)).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("full"));
    }

    @Test
    void startupRunFailureIsSwallowed() {
        when(repository.count()).thenReturn(0L);
        when(repository.findFirstBySourceAndCityKeyOrderBySyncedAtAsc("quefaireaparis", "paris"))
                .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> job().onStartup()).doesNotThrowAnyException();

        verify(repository).findFirstBySourceAndCityKeyOrderBySyncedAtAsc("quefaireaparis", "paris");
        assertThat(at(Level.WARN)).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("db down"));
    }
}
