package com.imin.iminapi.predictor.sources;

import com.imin.iminapi.predictor.model.TransitSyncState;
import com.imin.iminapi.predictor.repository.OpenEventOccurrenceRepository;
import com.imin.iminapi.predictor.repository.ReferenceCalendarEntryRepository;
import com.imin.iminapi.predictor.repository.TransitSyncStateRepository;
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
    /** {@code syncSource} value dated from {@code transit_sync_state.synced_at}. */
    public static final String IDFM_PRIM = TransitSyncState.IDFM_PRIM;
    /** {@code syncSource} value dated from {@code transit_sync_state.synced_at} of the weekly stops sync. */
    public static final String IDFM_STOPS = TransitSyncState.IDFM_STOPS;
    /** Every valid {@code syncSource}: an {@code open_event_occurrence.source} id, {@link #WIKIMEDIA}, {@link #IDFM_PRIM} or {@link #IDFM_STOPS}. */
    public static final Set<String> SOURCES = sources();

    private final ReferenceCalendarEntryRepository repository;
    private final OpenEventOccurrenceRepository occurrences;
    private final WikimediaPageviewMonthRepository wikimedia;
    private final TransitSyncStateRepository transit;

    public SourceSyncDates(ReferenceCalendarEntryRepository repository, OpenEventOccurrenceRepository occurrences,
                           WikimediaPageviewMonthRepository wikimedia, TransitSyncStateRepository transit) {
        this.repository = repository;
        this.occurrences = occurrences;
        this.wikimedia = wikimedia;
        this.transit = transit;
    }

    private static Set<String> sources() {
        Set<String> s = new HashSet<>(OpenEventSource.SOURCE_IDS);
        s.add(WIKIMEDIA);
        s.add(IDFM_PRIM);
        s.add(IDFM_STOPS);
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
     * matches nothing deletes rows from the window start, so the date can fall back; Wikimedia only upserts. PRIM and
     * the IDFM stops are dated from their last ok sync, so a successful poll that returned no rows still dates PRIM.
     */
    @Override
    public Optional<LocalDate> lastUpdatedOfSource(String source) {
        if (IDFM_PRIM.equals(source) || IDFM_STOPS.equals(source)) {
            return utcDate(transit.findById(source).map(TransitSyncState::getSyncedAt).orElse(null));
        }
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
