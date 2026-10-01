package com.imin.iminapi.predictor.sources.openevents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.predictor.rules.NightDates;
import com.imin.iminapi.predictor.sources.openevents.OpenEventCities.City;
import com.imin.iminapi.util.EventNormalization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Que Faire à Paris? (Ville de Paris, opendata.paris.fr Explore v2.1, ODbL 1.0, no key). Upstream drops events
 * once they end, so it never backfills. Its time offsets are placeholders (every occurrence says +02:00, winter
 * too), so the wall-clock time is read as Paris local time.
 */
public class QueFaireAParisClient implements OpenEventSource {

    private static final Logger log = LoggerFactory.getLogger(QueFaireAParisClient.class);
    static final String BASE_URL = "https://opendata.paris.fr/api/explore/v2.1/catalog/datasets/que-faire-a-paris-/records";
    static final String LICENCE = "ODbL 1.0";
    /** Explore v2.1 caps: limit ≤ 100 and offset + limit ≤ 10000 (both answered 400 when exceeded, 2026-10-01). */
    static final int LIMIT = 100;
    static final int MAX_OFFSET_PLUS_LIMIT = 10000;
    private static final String SELECT = "id,url,title,date_start,date_end,occurrences,address_zipcode,address_city,"
            + "qfap_tags,locations";
    private static final Pattern PARIS_ZIP = Pattern.compile("^75\\d{3}$");
    // locations[].location_type seen 2026-10-01: address, lieu, service, text (a place) and url (online only)
    private static final Set<String> PHYSICAL = Set.of("address", "lieu", "service", "text");
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Duration MAX_TIMING = Duration.ofHours(24);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;

    public QueFaireAParisClient(RestClient.Builder builder) {
        this.http = builder.build();
    }

    @Override public String id() { return "quefaireaparis"; }
    @Override public String gate() { return "quefaireaparis"; }
    @Override public String licence() { return LICENCE; }
    @Override public boolean backfills() { return false; }
    @Override public boolean covers(City city) { return city.quefaireaparis(); }

    @Override
    public Fetch fetch(City city, LocalDate from, LocalDate to) {
        List<RawEvent> events = new ArrayList<>();
        Map<String, Integer> dropped = new TreeMap<>();
        int offset = 0;
        long total;
        while (true) {
            JsonNode root = page(from, to, offset);
            total = root.path("total_count").asLong();
            JsonNode results = root.path("results");
            for (JsonNode r : results) {
                String reason = keep(r, from, to, events);
                if (reason != null) dropped.merge(reason, 1, Integer::sum);
            }
            offset += LIMIT;
            if (results.isEmpty() || offset >= total || offset + LIMIT > MAX_OFFSET_PLUS_LIMIT) break;
        }
        boolean partial = offset < total;
        if (partial) {
            log.warn("QueFaireAParisClient: {} records match but the offset cap stops at {}; {} is partial",
                    total, offset, city.key());
        }
        return new Fetch(events, partial, dropped);
    }

    static URI uri(LocalDate from, LocalDate to, int offset) {
        // date_start is a timestamp: "<= date'to+1'" keeps everything that starts on the last day
        String where = "date_end >= date'" + from + "' AND date_start <= date'" + to.plusDays(1) + "'";
        return URI.create(BASE_URL + "?select=" + enc(SELECT) + "&where=" + enc(where) + "&order_by=id&limit=" + LIMIT
                + "&offset=" + offset);
    }

    private JsonNode page(LocalDate from, LocalDate to, int offset) {
        return http.get().uri(uri(from, to, offset)).exchange((req, res) -> {
            int status = res.getStatusCode().value();
            if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new OpenEventsRateLimitedException("Que Faire à Paris 429 at offset " + offset);
            }
            if (!res.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Que Faire à Paris " + status + " at offset " + offset);
            }
            JsonNode root;
            try {
                root = JSON.readTree(res.getBody());
            } catch (IOException e) {
                throw new IllegalStateException("Que Faire à Paris: body is not JSON at offset " + offset, e);
            }
            if (root == null || !root.path("results").isArray() || !root.path("total_count").isIntegralNumber()) {
                throw new IllegalStateException("Que Faire à Paris: no results array at offset " + offset);
            }
            return root;
        }, true);
    }

    /** Adds the record and returns null, or returns the drop reason. */
    private static String keep(JsonNode r, LocalDate from, LocalDate to, List<RawEvent> out) {
        String id = text(r.path("id"));
        if (id == null) return "no_id";
        String url = text(r.path("url"));
        if (url == null) return "no_url";
        String title = text(r.path("title"));
        if (title == null) return "title";
        String zip = text(r.path("address_zipcode"));
        String cityName = text(r.path("address_city"));
        boolean paris = (zip != null && PARIS_ZIP.matcher(zip).matches())
                || (cityName != null && "paris".equals(EventNormalization.cityKey(cityName)));
        if (!paris) return "city";
        if (!physical(r.path("locations"))) return "online";
        TreeSet<LocalDate> all = new TreeSet<>();
        String occurrences = text(r.path("occurrences"));
        try {
            if (occurrences != null) {
                for (String occ : occurrences.split(";")) {
                    String[] ends = occ.strip().split("_");
                    if (ends.length != 2) return "timings";
                    LocalDateTime begin = wallClock(ends[0]);
                    String reason = length(begin, wallClock(ends[1]));
                    if (reason != null) return reason;
                    all.add(night(begin));
                }
            } else {
                String start = text(r.path("date_start"));
                String end = text(r.path("date_end"));
                if (start == null || end == null) return "timings";
                LocalDateTime begin = wallClock(start);
                String reason = length(begin, wallClock(end));
                if (reason != null) return reason;
                all.add(night(begin));
            }
        } catch (DateTimeParseException e) {
            return "timings";
        }
        TreeSet<LocalDate> nights = new TreeSet<>(all.subSet(from, true, to, true));
        if (OpenEventSource.isRun(all)) return "run";
        if (nights.isEmpty()) return "no_night";
        List<String> tags = new ArrayList<>();
        String rawTags = text(r.path("qfap_tags"));
        if (rawTags != null) for (String t : rawTags.split(";")) if (!t.isBlank()) tags.add(t.strip());
        out.add(new RawEvent(id, title, url, List.copyOf(nights), tags, LICENCE, null));
        return null;
    }

    private static boolean physical(JsonNode locations) {
        if (!locations.isArray()) return false;
        for (JsonNode l : locations) if (PHYSICAL.contains(l.path("location_type").asText(""))) return true;
        return false;
    }

    private static LocalDateTime wallClock(String raw) {
        return OffsetDateTime.parse(raw.strip()).toLocalDateTime();
    }

    private static String length(LocalDateTime begin, LocalDateTime end) {
        // upstream writes an overnight end (23:00 -> 01:30) on the begin date: it is the next morning
        if (end.isBefore(begin) && end.toLocalDate().equals(begin.toLocalDate())) end = end.plusDays(1);
        Duration d = Duration.between(begin.atZone(PARIS), end.atZone(PARIS));
        if (d.isNegative()) return "timings";
        return d.compareTo(MAX_TIMING) > 0 ? "long_run" : null;
    }

    /** A 00:00 start is a date with no time (75 such occurrences on 2026-10-01), not the small hours of the night before. */
    private static LocalDate night(LocalDateTime begin) {
        return begin.toLocalTime().equals(LocalTime.MIDNIGHT) ? begin.toLocalDate()
                : NightDates.nightOf(begin.atZone(PARIS).toInstant(), PARIS);
    }

    private static String text(JsonNode n) {
        return n.isTextual() && !n.asText().isBlank() ? n.asText().strip() : null;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
