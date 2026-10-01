package com.imin.iminapi.predictor.sources;

import com.imin.iminapi.predictor.dto.PublicDataSourcesResponse.PublicDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The credited outside datasets from {@code predictor/sources.yaml}, loaded once at startup.
 * Any invalid entry throws {@link IllegalStateException} naming it, so a bad file stops boot.
 */
@Component
public class DataSourceCatalog {

    public static final String LOCATION = "classpath:predictor/sources.yaml";
    static final Set<String> USED_FOR =
            Set.of("public_holidays", "school_holidays", "bridge_days", "dst", "hijri", "weather", "genre_interest",
                    "football_fixtures", "local_events");
    private static final String ACTIVE = "active";

    /** Where an entry's {@code lastUpdated} comes from. */
    public interface SyncDates {
        /** Latest sync date of the {@code reference_calendar} rows under a {@code syncPrefix}. */
        Optional<LocalDate> lastUpdated(String prefix);

        /** Latest sync date of the rows stored for a {@code syncSource}. */
        Optional<LocalDate> lastUpdatedOfSource(String source);
    }

    private record Entry(PublicDataSource source, String gate, String syncPrefix, String syncSource) {}

    private final String reviewedOn;
    private final List<Entry> entries;
    private final SourceGates gates;

    @Autowired
    public DataSourceCatalog(ResourceLoader resources, SourceGates gates) {
        DataSourceCatalog parsed = read(resources, gates);
        this.reviewedOn = parsed.reviewedOn;
        this.entries = parsed.entries;
        this.gates = gates;
    }

    private DataSourceCatalog(String reviewedOn, List<Entry> entries, SourceGates gates) {
        this.reviewedOn = reviewedOn;
        this.entries = entries;
        this.gates = gates;
    }

    public static DataSourceCatalog load(ResourceLoader resources, SourceGates gates) {
        return read(resources, gates);
    }

    private static DataSourceCatalog read(ResourceLoader resources, SourceGates gates) {
        Resource resource = resources.getResource(LOCATION);
        if (!resource.exists()) {
            throw new IllegalStateException("predictor sources: file not found: " + LOCATION);
        }
        try (InputStream in = resource.getInputStream()) {
            return parse(in, gates);
        } catch (IOException e) {
            throw new IllegalStateException("predictor sources: cannot read " + LOCATION, e);
        }
    }

    public static DataSourceCatalog parse(InputStream in, SourceGates gates) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object root;
        try {
            root = new Yaml(new SafeConstructor(options)).load(in);
        } catch (YAMLException e) {
            throw new IllegalStateException("predictor sources: invalid YAML: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalStateException("predictor sources: expected a mapping at the root");
        }
        String reviewedOn = reviewedOn(map.get("reviewedOn"));
        if (!(map.get("sources") instanceof List<?> raw)) {
            throw new IllegalStateException("predictor sources: sources must be a list");
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            if (!(raw.get(i) instanceof Map<?, ?> m)) {
                throw new IllegalStateException("predictor sources: sources[" + i + "] must be a mapping");
            }
            String id = text(m, "id", "sources[" + i + "]");
            if (!seen.add(id)) {
                throw new IllegalStateException("predictor sources " + id + ": duplicate id");
            }
            List<String> usedFor = usedFor(m, id);
            String licenceUrl = https(m, "licenceUrl", id);
            String url = https(m, "url", id);
            String gate = text(m, "gate", id);
            if (!gates.keys().contains(gate)) {
                throw new IllegalStateException("predictor sources " + id + ".gate: unknown gate '" + gate + "'");
            }
            String syncPrefix = m.get("syncPrefix") == null ? null : https(m, "syncPrefix", id);
            String syncSource = m.get("syncSource") == null ? null : text(m, "syncSource", id);
            if (syncSource != null && !SourceSyncDates.SOURCES.contains(syncSource)) {
                throw new IllegalStateException("predictor sources " + id + ".syncSource: unknown source '"
                        + syncSource + "'");
            }
            if (syncPrefix != null && syncSource != null) {
                throw new IllegalStateException("predictor sources " + id
                        + ": syncPrefix and syncSource are mutually exclusive");
            }
            entries.add(new Entry(new PublicDataSource(id, text(m, "name", id), usedFor, text(m, "licence", id),
                    licenceUrl, text(m, "creditLine", id), url, ACTIVE, null), gate, syncPrefix, syncSource));
        }
        return new DataSourceCatalog(reviewedOn, List.copyOf(entries), gates);
    }

    public String reviewedOn() {
        return reviewedOn;
    }

    /**
     * Entries whose gate is on right now, in file order, dated from their {@code syncPrefix} or {@code syncSource};
     * an entry with neither, or with no date, gets null.
     */
    public List<PublicDataSource> active(SyncDates dates) {
        return entries.stream().filter(e -> gates.isOn(e.gate())).map(e -> withDate(e, dates)).toList();
    }

    /** The entry with this id whatever its gate, without {@code lastUpdated}. */
    public Optional<PublicDataSource> byId(String id) {
        return entries.stream().filter(e -> e.source().id().equals(id)).map(Entry::source).findFirst();
    }

    /** The entry's {@code syncSource} whatever its gate. */
    public Optional<String> syncSource(String id) {
        return entries.stream().filter(e -> e.source().id().equals(id)).findFirst().map(Entry::syncSource);
    }

    private static PublicDataSource withDate(Entry e, SyncDates dates) {
        Optional<LocalDate> lastUpdated;
        if (e.syncPrefix() != null) {
            lastUpdated = dates.lastUpdated(e.syncPrefix());
        } else if (e.syncSource() != null) {
            lastUpdated = dates.lastUpdatedOfSource(e.syncSource());
        } else {
            return e.source();
        }
        String date = lastUpdated.map(LocalDate::toString).orElse(null);
        PublicDataSource s = e.source();
        return new PublicDataSource(s.id(), s.name(), s.usedFor(), s.licence(), s.licenceUrl(), s.creditLine(),
                s.url(), s.status(), date);
    }

    // SnakeYAML reads an unquoted 2026-09-30 as a timestamp; a quoted one stays text.
    private static String reviewedOn(Object raw) {
        if (raw instanceof Date d) {
            return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate().toString();
        }
        if (raw instanceof String s) {
            try {
                return LocalDate.parse(s.strip()).toString();
            } catch (DateTimeParseException e) {
                throw new IllegalStateException("predictor sources reviewedOn: '" + s + "' is not an ISO date");
            }
        }
        throw new IllegalStateException("predictor sources reviewedOn: is missing");
    }

    private static String text(Map<?, ?> m, String key, String where) {
        if (!(m.get(key) instanceof String s) || s.isBlank()) {
            throw new IllegalStateException("predictor sources " + where + "." + key + ": is missing or blank");
        }
        return s.strip();
    }

    private static String https(Map<?, ?> m, String key, String id) {
        String v = text(m, key, id);
        if (!v.startsWith("https://")) {
            throw new IllegalStateException("predictor sources " + id + "." + key + ": must be an https URL");
        }
        return v;
    }

    private static List<String> usedFor(Map<?, ?> m, String id) {
        if (!(m.get("usedFor") instanceof List<?> raw) || raw.isEmpty()) {
            throw new IllegalStateException("predictor sources " + id + ".usedFor: must be a non-empty list");
        }
        List<String> out = new ArrayList<>();
        for (Object v : raw) {
            if (!(v instanceof String s) || !USED_FOR.contains(s)) {
                throw new IllegalStateException("predictor sources " + id + ".usedFor: unknown value '" + v + "'");
            }
            out.add(s);
        }
        return List.copyOf(out);
    }
}
