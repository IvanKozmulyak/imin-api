package com.imin.iminapi.predictor.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Metropolitan school holidays (zones A/B/C) from data.education.gouv.fr {@code fr-en-calendrier-scolaire}
 * (Licence Ouverte 2.0). Pupils' dates only; one row per zone, deduplicated across académies.
 */
public class FrenchSchoolCalendarSync implements CalendarSource {

    private static final Logger log = LoggerFactory.getLogger(FrenchSchoolCalendarSync.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String EXPORT_URL = "https://data.education.gouv.fr/api/explore/v2.1/catalog/datasets/"
            + "fr-en-calendrier-scolaire/exports/json";
    // source_url is the writer's scope key: changing it orphans rows stored under the old URL until cleaned up
    static final String SOURCE_URL = "https://data.education.gouv.fr/explore/dataset/fr-en-calendrier-scolaire/";
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final Map<String, String> ZONES = Map.of("Zone A", "FR-ZA", "Zone B", "FR-ZB", "Zone C", "FR-ZC");

    private final RestClient http;
    private final CalendarSyncProperties props;

    public FrenchSchoolCalendarSync(RestClient http, CalendarSyncProperties props) {
        this.http = http;
        this.props = props;
    }

    @Override
    public String key() { return "fr-school"; }

    /** The school year running on {@code today} starts that year from August, else the year before. */
    static int firstSchoolYear(LocalDate today) {
        return today.getMonthValue() >= 8 ? today.getYear() : today.getYear() - 1;
    }

    static String url(int firstYear, int lastYear) {
        List<String> years = new ArrayList<>();
        for (int y = firstYear; y <= lastYear; y++) years.add("\"" + y + "-" + (y + 1) + "\"");
        String where = "annee_scolaire in (" + String.join(",", years) + ")";
        return EXPORT_URL + "?where=" + URLEncoder.encode(where, StandardCharsets.UTF_8);
    }

    @Override
    public List<Batch> fetch(LocalDate today) {
        int first = firstSchoolYear(today);
        int last = first + props.getYearsAhead();
        LocalDate from = LocalDate.of(first, 8, 1);
        LocalDate to = LocalDate.of(last + 1, 7, 31);
        String url = url(first, last);
        JsonNode rows;
        try {
            String body = http.get().uri(URI.create(url)).retrieve().body(String.class);
            rows = JSON.readTree(body == null ? "" : body);
            if (rows == null || !rows.isArray()) throw new IllegalStateException("not a JSON array");
        } catch (Exception e) {
            log.warn("FrenchSchoolCalendarSync: {} failed, keeping stored rows: {}", url, e.toString());
            return List.of();
        }
        Map<String, CalendarRow> byKey = new LinkedHashMap<>();
        for (JsonNode r : rows) {
            CalendarRow row = toRow(r);
            if (row == null || row.date().isBefore(from) || row.date().isAfter(to)) continue;
            byKey.putIfAbsent(row.key(), row);
        }
        return List.of(new Batch(SOURCE_URL, Set.of("school"), from, to, new ArrayList<>(byKey.values())));
    }

    /** Null for teachers' rows, zones outside metropolitan A/B/C, or unreadable dates. */
    static CalendarRow toRow(JsonNode r) {
        if (r.path("population").asText("").startsWith("Enseignants")) return null;
        String region = ZONES.get(r.path("zones").asText(""));
        String name = r.path("description").asText("").trim();
        if (region == null || name.isEmpty()) return null;
        try {
            LocalDate start = OffsetDateTime.parse(r.path("start_date").asText())
                    .atZoneSameInstant(PARIS).toLocalDate();
            ZonedDateTime end = OffsetDateTime.parse(r.path("end_date").asText()).atZoneSameInstant(PARIS);
            // end_date is local midnight of the day school starts again; store the last day off
            LocalDate last = end.toLocalTime().equals(LocalTime.MIDNIGHT)
                    ? end.toLocalDate().minusDays(1) : end.toLocalDate();
            return new CalendarRow("FR", region, start, last, "school", name, SOURCE_URL);
        } catch (Exception e) {
            return null;
        }
    }
}
