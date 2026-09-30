package com.imin.iminapi.predictor;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.predictor.model.ReferenceCalendarEntry;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import com.imin.iminapi.predictor.sources.SourceSyncDates;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
@Transactional
class SourceSyncDatesTest {

    private static final String PREFIX = "https://holidays.test/jours-feries/";

    @Autowired ReferenceCalendarEntryRepository repository;
    @Autowired SourceSyncDates dates;

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
}
