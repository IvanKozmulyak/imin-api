package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.model.OpenEventOccurrence;
import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import com.imin.iminapi.predictor.model.WikimediaPageviewMonth;
import com.imin.iminapi.predictor.repository.OpenEventOccurrenceRepository;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import com.imin.iminapi.predictor.repository.WikimediaPageviewMonthRepository;
import com.imin.iminapi.predictor.sources.SourceSyncDates;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
@Transactional
class SourceSyncDatesTest {

    private static final String PREFIX = "https://holidays.test/jours-feries/";

    @Autowired ReferenceCalendarEntryRepository repository;
    @Autowired SourceSyncDates dates;
    @Autowired OpenEventOccurrenceRepository occurrences;
    @Autowired WikimediaPageviewMonthRepository pageviews;
    @Autowired OpenEventsWriter writer;

    private void night(String source, String cityKey, String eventId, String syncedAt) {
        night(source, cityKey, eventId, LocalDate.of(2026, 10, 10), syncedAt);
    }

    private void night(String source, String cityKey, String eventId, LocalDate nightDate, String syncedAt) {
        OpenEventOccurrence o = new OpenEventOccurrence();
        o.setSource(source);
        o.setSourceEventId(eventId);
        o.setCityKey(cityKey);
        o.setNightDate(nightDate);
        o.setTitle("Night " + eventId);
        o.setTitleKey("night " + eventId);
        o.setUrl("https://events.test/" + eventId);
        o.setLicence(source.equals("quefaireaparis") ? "ODbL 1.0" : "Licence Ouverte 2.0");
        o.setSyncedAt(Instant.parse(syncedAt));
        occurrences.saveAndFlush(o);
    }

    private void month(String article, String syncedAt) {
        WikimediaPageviewMonth m = new WikimediaPageviewMonth();
        m.setProject("fr.wikipedia");
        m.setArticle(article);
        m.setViewMonth(LocalDate.of(2026, 8, 1));
        m.setViews(100);
        m.setSyncedAt(Instant.parse(syncedAt));
        pageviews.saveAndFlush(m);
    }

    private void store(String sourceUrl, String name, String syncedAt) {
        ReferenceCalendarEntry e = new ReferenceCalendarEntry();
        e.setCountry("FR");
        e.setRegion("");
        e.setCalendarDate(LocalDate.of(2026, 1, 1));
        e.setKind("holiday");
        e.setName(name);
        e.setSourceUrl(sourceUrl);
        e.setSyncedAt(Instant.parse(syncedAt));
        repository.saveAndFlush(e);
    }

    @Test
    void latestSyncedAtUnderThePrefixIsTheDate() {
        store(PREFIX + "metropole/2026.json", "a", "2026-09-20T02:30:00Z");
        store(PREFIX + "alsace-moselle/2026.json", "b", "2026-09-27T02:30:00Z");
        store("https://other.test/jours-feries/2026.json", "c", "2026-09-29T02:30:00Z");

        assertThat(dates.lastUpdated(PREFIX)).contains(LocalDate.of(2026, 9, 27));
    }

    @Test
    void noRowUnderThePrefixIsEmpty() {
        store("https://other.test/jours-feries/2026.json", "c", "2026-09-29T02:30:00Z");

        assertThat(dates.lastUpdated(PREFIX)).isEqualTo(Optional.empty());
    }

    @Test
    void blankOrNullPrefixIsEmptyWithoutMatchingEverything() {
        store(PREFIX + "metropole/2026.json", "a", "2026-09-20T02:30:00Z");

        assertThat(dates.lastUpdated("")).isEmpty();
        assertThat(dates.lastUpdated("  ")).isEmpty();
        assertThat(dates.lastUpdated(null)).isEmpty();
    }

    @Test
    void likeWildcardsInThePrefixMatchLiterally() {
        store("https://holidaysXtest/a", "a", "2026-09-20T02:30:00Z");

        assertThat(dates.lastUpdated("https://holidays_test/")).isEmpty();
        assertThat(dates.lastUpdated("https://holidays%")).isEmpty();
    }

    @Test
    void openEventSourceDateIsItsNewestSyncInUtc() {
        night("openagenda", "lille", "a", "2026-09-20T03:45:00Z");
        // 2026-09-28 in Paris, 2026-09-27 in UTC
        night("openagenda", "lille", "b", "2026-09-27T23:30:00Z");
        night("quefaireaparis", "paris", "c", "2026-09-29T03:45:00Z");

        assertThat(dates.lastUpdatedOfSource("openagenda")).contains(LocalDate.of(2026, 9, 27));
    }

    @Test
    void openEventSourceWithNoRowsIsEmpty() {
        night("quefaireaparis", "paris", "c", "2026-09-29T03:45:00Z");

        assertThat(dates.lastUpdatedOfSource("openagenda")).isEqualTo(Optional.empty());
    }

    @Test
    void wikimediaDateIsItsNewestSync() {
        month("House_music", "2026-09-21T03:15:00Z");
        month("Techno", "2026-09-28T03:15:00Z");
        night("openagenda", "lille", "a", "2026-09-30T03:45:00Z");

        assertThat(dates.lastUpdatedOfSource(SourceSyncDates.WIKIMEDIA)).contains(LocalDate.of(2026, 9, 28));
    }

    @Test
    void wikimediaWithNoRowsIsEmpty() {
        night("openagenda", "lille", "a", "2026-09-30T03:45:00Z");

        assertThat(dates.lastUpdatedOfSource("wikimedia")).isEqualTo(Optional.empty());
    }

    @Test
    void runMatchingNothingFallsBackToTheNewestRowStillHeld() {
        LocalDate windowStart = LocalDate.of(2026, 9, 21);
        night("openagenda", "lille", "past", LocalDate.of(2026, 9, 10), "2026-09-20T03:45:00Z");
        night("openagenda", "lille", "future", LocalDate.of(2026, 10, 10), "2026-09-27T03:45:00Z");
        assertThat(dates.lastUpdatedOfSource("openagenda")).contains(LocalDate.of(2026, 9, 27));

        writer.replaceFuture("openagenda", "lille", windowStart, List.of(), Instant.parse("2026-10-01T03:45:00Z"));
        assertThat(dates.lastUpdatedOfSource("openagenda")).contains(LocalDate.of(2026, 9, 20));

        writer.replaceFuture("openagenda", "lille", LocalDate.of(2026, 9, 1), List.of(), Instant.parse("2026-10-01T03:45:00Z"));
        assertThat(dates.lastUpdatedOfSource("openagenda")).isEmpty();
    }
}
