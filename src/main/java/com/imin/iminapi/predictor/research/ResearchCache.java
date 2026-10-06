package com.imin.iminapi.predictor.research;

import com.imin.iminapi.predictor.research.FindingValidator.Checked;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Validated research items per org, so a repeat check skips the paid call. Keyed by org, city, country, genre,
 * sub-genre, date span and UTC day; 24 h at most. Stores no page excerpts. ponytail: in-memory per instance, so a
 * deploy or a second replica pays one more call; 500 entries, least recently used evicted.
 */
@Component
public class ResearchCache {

    static final Duration TTL = Duration.ofHours(24);
    static final int MAX_ENTRIES = 500;

    public record Key(UUID orgId, String city, String country, String genre, String subGenre, LocalDate from,
                      LocalDate to, LocalDate utcDay) {
        public Key {
            city = norm(city);
            country = norm(country);
            genre = norm(genre);
            subGenre = norm(subGenre);
        }
    }

    /** The items kept from one call and when that call was made. */
    public record Entry(List<Checked> items, Instant fetchedAt) {
        public Entry {
            items = List.copyOf(items);
        }
    }

    private final Clock clock;
    private final Map<Key, Entry> entries = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, ResearchCache.Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public ResearchCache(Clock clock) {
        this.clock = clock;
    }

    public synchronized Optional<Entry> get(Key key) {
        Entry e = entries.get(key);
        if (e == null) return Optional.empty();
        if (e.fetchedAt().plus(TTL).isBefore(clock.instant())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(e);
    }

    public synchronized void put(Key key, Entry entry) {
        entries.put(key, entry);
    }

    /** Drops every entry; the next check for each key pays one call. */
    public synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }

    private static String norm(String s) {
        return s == null ? "" : QuoteDates.fold(s.trim()).toLowerCase(Locale.ROOT);
    }
}
