package com.imin.iminapi.predictor.sources;

import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

/** The last day a synced source's rows were written to {@code reference_calendar}. */
@Component
public class SourceSyncDates {

    private final ReferenceCalendarEntryRepository repository;

    public SourceSyncDates(ReferenceCalendarEntryRepository repository) {
        this.repository = repository;
    }

    /** UTC date of the newest {@code synced_at} under the URL prefix; empty for a blank prefix or no rows. */
    public Optional<LocalDate> lastUpdated(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return Optional.empty();
        }
        Instant latest = repository.findLatestSyncedAtLike(escapeLike(prefix) + "%");
        return Optional.ofNullable(latest).map(i -> i.atZone(ZoneOffset.UTC).toLocalDate());
    }

    // '!' is the query's ESCAPE character, so '%' and '_' in a URL match literally.
    static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
