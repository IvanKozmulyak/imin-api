package com.imin.iminapi.predictor.sources.openevents;

import com.imin.iminapi.predictor.sources.openevents.OpenEventCities.City;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One open-data event listing (OpenAgenda, Que Faire à Paris; DATAtourisme plugs in here with
 * {@code gate() = "datatourisme"}, licence Licence Ouverte 2.0 and {@code credit} = {@code hasBeenCreatedBy}).
 * Clients filter every upstream classification field and return night dates, never descriptions.
 */
public interface OpenEventSource {

    /** {@code ck_open_event_occurrence_source} (V165). */
    Set<String> SOURCE_IDS = Set.of("openagenda", "quefaireaparis", "datatourisme");
    /** {@code ck_open_event_occurrence_licence} (V165). */
    Set<String> LICENCES = Set.of("Licence Ouverte 2.0", "ODbL 1.0");

    /** A run (exhibition, season, daily class): 4 or more nights inside any 7 consecutive days… */
    int RUN_SPAN_DAYS = 7;
    int RUN_MIN_NIGHTS = 4;
    /** …or more than 12 inside any 30 consecutive days (over 3 a week); a twice-weekly residency peaks at 10. */
    int DENSE_SPAN_DAYS = 30;
    int DENSE_MAX_NIGHTS = 12;

    /**
     * True when the listing is a run, not a night out. Judged on every night it has upstream, never on the fetch
     * window, so the answer does not change with where the window falls.
     */
    static boolean isRun(java.util.NavigableSet<LocalDate> all) {
        for (LocalDate first : all) {
            if (all.subSet(first, true, first.plusDays(RUN_SPAN_DAYS - 1L), true).size() >= RUN_MIN_NIGHTS) return true;
            if (all.subSet(first, true, first.plusDays(DENSE_SPAN_DAYS - 1L), true).size() > DENSE_MAX_NIGHTS) return true;
        }
        return false;
    }

    /** Stored in {@code open_event_occurrence.source}. */
    String id();

    /** SourceGates key. */
    String gate();

    /** Licence every row of this source carries; credited in {@code genre_week_count.sources_json}. */
    String licence();

    /** Upstream still serves past events, so a city's first run can fill 182 days of history. */
    boolean backfills();

    boolean covers(City city);

    /** Nights in [from, to]; throws {@link OpenEventsRateLimitedException} on 429, anything else on a failed call. */
    Fetch fetch(City city, LocalDate from, LocalDate to);

    /** One kept listing; {@code nights} are already night dates (Europe/Paris, before 06:00 = previous night). */
    record RawEvent(String sourceEventId, String title, String url, List<LocalDate> nights, List<String> keywords,
                    String licence, String credit) {
        public RawEvent {
            nights = List.copyOf(nights);
            keywords = List.copyOf(keywords);
        }
    }

    /** {@code partial} = a page or offset cap stopped the read, so the stored rows must be kept; drops by reason. */
    record Fetch(List<RawEvent> events, boolean partial, Map<String, Integer> dropped) {
        public Fetch {
            events = List.copyOf(events);
            dropped = Map.copyOf(dropped);
        }
    }
}
