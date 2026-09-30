package com.imin.iminapi.predictor.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * French public holidays from calendrier.api.gouv.fr (Licence Ouverte 2.0), zones metropole and
 * alsace-moselle, plus their ponts. One batch per zone and year; a failed year keeps its old rows.
 */
public class FrenchHolidaySync implements CalendarSource {

    private static final Logger log = LoggerFactory.getLogger(FrenchHolidaySync.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // source_url is the writer's scope key: changing it orphans rows stored under the old URL until cleaned up
    static final String BASE_URL = "https://calendrier.api.gouv.fr/jours-feries/";
    static final List<String> ALSACE_MOSELLE_REGIONS = List.of("FR-57", "FR-67", "FR-68");
    static final Set<String> KINDS = Set.of("holiday", "pont");

    private final RestClient http;
    private final CalendarSyncProperties props;

    public FrenchHolidaySync(RestClient http, CalendarSyncProperties props) {
        this.http = http;
        this.props = props;
    }

    static String url(String zone, int year) {
        return BASE_URL + zone + "/" + year + ".json";
    }

    @Override
    public String key() { return "fr-holidays"; }

    @Override
    public List<Batch> fetch(LocalDate today) {
        List<Batch> out = new ArrayList<>();
        for (int y = today.getYear(); y <= today.getYear() + props.getYearsAhead(); y++) {
            LocalDate from = LocalDate.of(y, 1, 1);
            LocalDate to = LocalDate.of(y, 12, 31);
            String metroUrl = url("metropole", y);
            Map<LocalDate, String> metro = get(metroUrl);
            if (metro == null) continue;   // alsace-only rows need the metropole list to tell them apart
            List<CalendarRow> national = new ArrayList<>();
            metro.forEach((d, n) -> national.add(new CalendarRow("FR", "", d, null, "holiday", n, metroUrl)));
            // next 1 Jan (a fixed date) only feeds the ponts, so a Tuesday New Year gives this year's 31 Dec
            List<CalendarRow> withNewYear = new ArrayList<>(national);
            String newYear = metro.get(LocalDate.of(y, 1, 1));
            if (newYear != null) {
                withNewYear.add(new CalendarRow("FR", "", LocalDate.of(y + 1, 1, 1), null, "holiday", newYear, metroUrl));
            }
            out.add(batch(metroUrl, from, to, national, withNewYear, withNewYear));

            String amUrl = url("alsace-moselle", y);
            Map<LocalDate, String> am = get(amUrl);
            if (am == null) continue;
            for (String region : ALSACE_MOSELLE_REGIONS) {
                List<CalendarRow> all = new ArrayList<>();
                List<CalendarRow> localOnly = new ArrayList<>();
                am.forEach((d, n) -> {
                    CalendarRow row = new CalendarRow("FR", region, d, null, "holiday", n, amUrl);
                    all.add(row);
                    if (!metro.containsKey(d)) localOnly.add(row);
                });
                out.add(batch(amUrl, from, to, localOnly, localOnly, all));
            }
        }
        return mergeByScope(out);
    }

    /**
     * Stored rows plus the ponts of the {@code owned} holidays that fall in [from, to];
     * {@code all} decides whether a pont day is itself a holiday.
     */
    private static Batch batch(String url, LocalDate from, LocalDate to, List<CalendarRow> stored,
                               List<CalendarRow> owned, List<CalendarRow> all) {
        List<CalendarRow> rows = new ArrayList<>(stored);
        Set<LocalDate> ownedDays = new HashSet<>();
        owned.forEach(r -> ownedDays.add(r.date()));
        for (CalendarRow p : ComputedCalendar.ponts(all)) {
            LocalDate holiday = p.date().getDayOfWeek() == DayOfWeek.MONDAY
                    ? p.date().plusDays(1) : p.date().minusDays(1);
            if (!ownedDays.contains(holiday) || p.date().isBefore(from) || p.date().isAfter(to)) continue;
            rows.add(p);
        }
        return new Batch(url, KINDS, from, to, rows);
    }

    /** The three Alsace-Moselle regions share one URL, so they must be one batch (one scope). */
    private static List<Batch> mergeByScope(List<Batch> batches) {
        Map<String, Batch> merged = new LinkedHashMap<>();
        for (Batch b : batches) {
            merged.merge(b.sourceUrl(), b, (a, c) -> {
                List<CalendarRow> rows = new ArrayList<>(a.rows());
                rows.addAll(c.rows());
                return new Batch(a.sourceUrl(), a.kinds(), a.from(), a.to(), rows);
            });
        }
        return new ArrayList<>(merged.values());
    }

    /** Date → name, or null when the call or the body failed (logged, never thrown). */
    private Map<LocalDate, String> get(String url) {
        try {
            String body = http.get().uri(URI.create(url)).retrieve().body(String.class);
            JsonNode root = JSON.readTree(body == null ? "" : body);
            if (root == null || !root.isObject()) throw new IllegalStateException("not a JSON object");
            Map<LocalDate, String> out = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                out.put(LocalDate.parse(e.getKey()), e.getValue().asText());
            }
            if (out.isEmpty()) throw new IllegalStateException("no holidays");
            return out;
        } catch (Exception e) {
            log.warn("FrenchHolidaySync: {} failed, keeping stored rows: {}", url, e.toString());
            return null;
        }
    }
}
