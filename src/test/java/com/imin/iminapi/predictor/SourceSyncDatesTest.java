package com.imin.iminapi.predictor;

import com.imin.iminapi.predictor.model.OpenEventOccurrence;
import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import com.imin.iminapi.predictor.model.WikimediaPageviewMonth;
import com.imin.iminapi.predictor.repository.OpenEventOccurrenceRepository;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import com.imin.iminapi.predictor.repository.WikimediaPageviewMonthRepository;
import com.imin.iminapi.predictor.sources.SourceSyncDates;
import com.imin.iminapi.predictor.sources.openevents.OpenEventsWriter;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source dates on the shared database, inside the test's rolled-back transaction. A source date is the newest
 * row of the whole source, so a test first hides the source's other rows in that transaction; prefixes and
 * cities carry random letters.
 */
@IminIntegrationTest
@Transactional
class SourceSyncDatesTest {

    private final String letters = DateCheckControllerTest.letters();
    private final String prefix = "https://holidays-" + letters + ".test/jours-feries/";
    private final String lille = "lille" + letters;

    @Autowired ReferenceCalendarEntryRepository repository;
    @Autowired SourceSyncDates dates;
    @Autowired OpenEventOccurrenceRepository occurrences;
    @Autowired WikimediaPageviewMonthRepository pageviews;
    @Autowired OpenEventsWriter writer;
    @Autowired JdbcTemplate jdbc;

    /** Rolled back with the test, so other tests' rows come back untouched. */
    private void hideOtherRowsOf(String source) {
        if (SourceSyncDates.WIKIMEDIA.equals(source)) jdbc.update("DELETE FROM wikimedia_pageviews_month");
        else jdbc.update("DELETE FROM open_event_occurrence WHERE source = ?", source);
    }

    private void night(String source, String cityKey, String eventId, String syncedAt) {
        night(source, cityKey, eventId, LocalDate.of(2026, 10, 10), syncedAt);
    }

    private void night(String source, String cityKey, String eventId, LocalDate nightDate, String syncedAt) {
        OpenEventOccurrence o = new OpenEventOccurrence();
        o.setSource(source);
        o.setSourceEventId(eventId + "-" + letters);
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

    /**
     * Rows: the newest row under the prefix; a prefix with no row under it; blank, spaces and null, which
     * must not match everything.
     */
    @ParameterizedTest
    @CsvSource(value = {"prefix, 2026-09-27", "empty-prefix, NULL", "blank, NULL", "spaces, NULL", "null, NULL"},
            nullValues = "NULL")
    void lastUpdatedIsTheNewestSyncUnderThePrefix(String query, LocalDate expected) {
        store(prefix + "metropole/2026.json", "a", "2026-09-20T02:30:00Z");
        store(prefix + "alsace-moselle/2026.json", "b", "2026-09-27T02:30:00Z");
        store("https://other-" + letters + ".test/jours-feries/2026.json", "c", "2026-09-29T02:30:00Z");
        String asked = switch (query) {
            case "prefix" -> prefix;
            case "empty-prefix" -> "https://holidays-" + letters + ".test/none/";
            case "blank" -> "";
            case "spaces" -> "  ";
            default -> null;
        };

        assertThat(dates.lastUpdated(asked)).isEqualTo(Optional.ofNullable(expected));
    }

    @Test
    void likeWildcardsInThePrefixMatchLiterally() {
        store("https://h" + letters + "Xtest/a", "a", "2026-09-20T02:30:00Z");

        assertThat(dates.lastUpdated("https://h" + letters + "Xtest/")).contains(LocalDate.of(2026, 9, 20));
        assertThat(dates.lastUpdated("https://h" + letters + "_test/")).isEmpty();
        assertThat(dates.lastUpdated("https://h" + letters + "%")).isEmpty();
    }

    /**
     * Rows: an open-event source's newest sync in UTC (23:30Z is already the next day in Paris) and the same
     * source with no rows; Wikimedia's newest sync and Wikimedia with no rows. Each also holds a newer row of
     * another source, which must not count.
     */
    @ParameterizedTest
    @CsvSource(value = {"openagenda, true, 2026-09-27", "openagenda, false, NULL",
            "wikimedia, true, 2026-09-28", "wikimedia, false, NULL"}, nullValues = "NULL")
    void sourceDateIsItsNewestStoredSync(String source, boolean stored, LocalDate expected) {
        hideOtherRowsOf(source);
        if (source.equals("openagenda")) {
            night("quefaireaparis", "paris" + letters, "c", "2026-09-29T03:45:00Z");
            if (stored) {
                night("openagenda", lille, "a", "2026-09-20T03:45:00Z");
                night("openagenda", lille, "b", "2026-09-27T23:30:00Z");
            }
        } else {
            night("openagenda", lille, "a", "2026-09-30T03:45:00Z");
            if (stored) {
                month("House_music", "2026-09-21T03:15:00Z");
                month("Techno", "2026-09-28T03:15:00Z");
            }
        }

        assertThat(dates.lastUpdatedOfSource(source)).isEqualTo(Optional.ofNullable(expected));
    }

    @Test
    void runMatchingNothingFallsBackToTheNewestRowStillHeld() {
        hideOtherRowsOf("openagenda");
        LocalDate windowStart = LocalDate.of(2026, 9, 21);
        night("openagenda", lille, "past", LocalDate.of(2026, 9, 10), "2026-09-20T03:45:00Z");
        night("openagenda", lille, "future", LocalDate.of(2026, 10, 10), "2026-09-27T03:45:00Z");
        assertThat(dates.lastUpdatedOfSource("openagenda")).contains(LocalDate.of(2026, 9, 27));

        writer.replaceFuture("openagenda", lille, windowStart, List.of(), Instant.parse("2026-10-01T03:45:00Z"));
        assertThat(dates.lastUpdatedOfSource("openagenda")).contains(LocalDate.of(2026, 9, 20));

        writer.replaceFuture("openagenda", lille, LocalDate.of(2026, 9, 1), List.of(), Instant.parse("2026-10-01T03:45:00Z"));
        assertThat(dates.lastUpdatedOfSource("openagenda")).isEmpty();
    }
}
