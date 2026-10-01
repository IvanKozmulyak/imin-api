package com.imin.iminapi.predictor.sources;

import com.imin.iminapi.predictor.repository.OpenEventOccurrenceRepository;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import com.imin.iminapi.predictor.repository.WikimediaPageviewMonthRepository;
import com.imin.iminapi.predictor.sources.openevents.OpenEventSource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** The last day a synced source's rows were written to the table that stores them. */
@Component
public class SourceSyncDates implements DataSourceCatalog.SyncDates {

    /** {@code syncSource} value dated from {@code wikimedia_pageviews_month}. */
    public static final String WIKIMEDIA = "wikimedia";
    /** Every valid {@code syncSource}: an {@code open_event_occurrence.source} id or {@link #WIKIMEDIA}. */
    public static final Set<String> SOURCES = sources();

    private final ReferenceCalendarEntryRepository repository;
    private final OpenEventOccurrenceRepository occurrences;
    private final WikimediaPageviewMonthRepository wikimedia;

    public SourceSyncDates(ReferenceCalendarEntryRepository repository, OpenEventOccurrenceRepository occurrences,
                           WikimediaPageviewMonthRepository wikimedia) {
        this.repository = repository;
        this.occurrences = occurrences;
        this.wikimedia = wikimedia;
    }

    private static Set<String> sources() {
        Set<String> s = new HashSet<>(OpenEventSource.SOURCE_IDS);
        s.add(WIKIMEDIA);
        return Set.copyOf(s);
    }

    /** UTC date of the newest {@code synced_at} under the URL prefix; empty for a blank prefix or no rows. */
    @Override
    public Optional<LocalDate> lastUpdated(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return Optional.empty();
        }
        return utcDate(repository.findLatestSyncedAtLike(escapeLike(prefix) + "%"));
    }

    /**
     * UTC date of the newest stored row's sync for the source; empty when it has none. An open-event run that
     * matches nothing deletes rows from the window start, so the date can fall back; Wikimedia only upserts.
     */
    @Override
    public Optional<LocalDate> lastUpdatedOfSource(String source) {
        Instant latest = WIKIMEDIA.equals(source) ? wikimedia.findLatestSyncedAt() : occurrences.findLatestSyncedAt(source);
        return utcDate(latest);
    }

    private static Optional<LocalDate> utcDate(Instant latest) {
        return Optional.ofNullable(latest).map(i -> i.atZone(ZoneOffset.UTC).toLocalDate());
    }

    // '!' is the query's ESCAPE character, so '%' and '_' in a URL match literally.
    static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
