package com.imin.iminapi.predictor.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Public holidays of the neighbour and target countries from openholidaysapi.org (ODbL). One call
 * and one batch per country and year; subdivision rows are stored under their ISO 3166-2 code.
 * Only full-day {@code Public} holidays above commune level count as a day off.
 */
public class OpenHolidaysSync implements CalendarSource {

    private static final Logger log = LoggerFactory.getLogger(OpenHolidaysSync.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // source_url is the writer's scope key: changing it orphans rows stored under the old URL until cleaned up
    static final String BASE_URL = "https://openholidaysapi.org/PublicHolidays?";
    static final List<String> COUNTRIES = List.of("LU", "DE", "BE", "CH", "ES", "PT", "NL");
    static final Set<String> KINDS = Set.of("holiday");
    /** V162 {@code reference_calendar.region VARCHAR(16)}. */
    static final int MAX_REGION = 16;

    private final RestClient http;
    private final CalendarSyncProperties props;
    private final List<String> countries;

    public OpenHolidaysSync(RestClient http, CalendarSyncProperties props) {
        this(http, props, COUNTRIES);
    }

    OpenHolidaysSync(RestClient http, CalendarSyncProperties props, List<String> countries) {
        this.http = http;
        this.props = props;
        this.countries = List.copyOf(countries);
    }

    public static String url(String country, int year) {
        return BASE_URL + "countryIsoCode=" + country + "&languageIsoCode=EN&validFrom=" + year
                + "-01-01&validTo=" + year + "-12-31";
    }

    @Override
    public String key() { return "openholidays"; }

    @Override
    public String scopePrefix() { return BASE_URL; }

    @Override
    public List<Batch> fetch(LocalDate today) {
        List<Batch> out = new ArrayList<>();
        for (String country : countries) {
            for (int y = today.getYear(); y <= today.getYear() + props.getYearsAhead(); y++) {
                LocalDate from = LocalDate.of(y, 1, 1);
                LocalDate to = LocalDate.of(y, 12, 31);
                String url = url(country, y);
                JsonNode body = get(url);
                if (body == null) continue;
                List<CalendarRow> rows = rows(country, url, body, from, to);
                if (rows.isEmpty()) continue;
                out.add(new Batch(url, KINDS, from, to, rows));
            }
        }
        return out;
    }

    /** A full day off for the whole country or a region: not a bank, optional, half-day or commune-level holiday. */
    static boolean dayOff(JsonNode h) {
        return "Public".equals(h.path("type").asText())
                && "FullDay".equals(h.path("temporalScope").asText())
                && !"Local".equals(h.path("regionalScope").asText());
    }

    /** Rows of one answer; overlong codes and regional rows with no subdivision are skipped and counted. */
    static List<CalendarRow> rows(String country, String url, JsonNode body, LocalDate from, LocalDate to) {
        List<CalendarRow> out = new ArrayList<>();
        int overlong = 0;
        int noSubdivision = 0;
        for (JsonNode h : body) {
            if (!dayOff(h)) continue;
            LocalDate start;
            LocalDate end;
            try {
                start = LocalDate.parse(h.path("startDate").asText());
                end = LocalDate.parse(h.path("endDate").asText(h.path("startDate").asText()));
            } catch (Exception e) {
                continue;
            }
            if (start.isBefore(from) || start.isAfter(to)) continue;
            String name = name(h);
            if (name.isEmpty()) continue;
            if (h.path("nationwide").asBoolean(false)) {
                out.add(new CalendarRow(country, "", start, end, "holiday", name, url));
                continue;
            }
            JsonNode subs = h.path("subdivisions");
            if (!subs.isArray() || subs.isEmpty()) {
                noSubdivision++;
                continue;
            }
            for (JsonNode s : subs) {
                String code = s.path("code").asText("");
                if (code.isEmpty()) continue;
                if (code.length() > MAX_REGION) {
                    overlong++;
                    continue;
                }
                out.add(new CalendarRow(country, code, start, end, "holiday", name, url));
            }
        }
        if (overlong > 0 || noSubdivision > 0) {
            log.warn("OpenHolidaysSync: {} skipped {} subdivision codes over {} chars and {} regional rows without subdivisions",
                    url, overlong, MAX_REGION, noSubdivision);
        }
        return out;
    }

    /** The EN text, else the first one; "" when there is none. */
    private static String name(JsonNode h) {
        JsonNode names = h.path("name");
        if (!names.isArray() || names.isEmpty()) return "";
        for (JsonNode n : names) {
            if ("EN".equalsIgnoreCase(n.path("language").asText())) return n.path("text").asText("").trim();
        }
        return names.get(0).path("text").asText("").trim();
    }

    /** The JSON array, or null when the call or the body failed or was empty (logged, never thrown). */
    private JsonNode get(String url) {
        try {
            String body = http.get().uri(URI.create(url)).retrieve().body(String.class);
            JsonNode root = JSON.readTree(body == null ? "" : body);
            if (root == null || !root.isArray()) throw new IllegalStateException("not a JSON array");
            if (root.isEmpty()) throw new IllegalStateException("no holidays");
            return root;
        } catch (Exception e) {
            log.warn("OpenHolidaysSync: {} failed, keeping stored rows: {}", url, e.toString());
            return null;
        }
    }
}
