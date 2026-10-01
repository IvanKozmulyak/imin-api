package com.imin.iminapi.predictor.sources.openevents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.predictor.rules.NightDates;
import com.imin.iminapi.predictor.sources.openevents.OpenEventCities.Agenda;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Published events of a city's configured OpenAgenda agendas (API v2, public {@code oa_pk_} key in the {@code key}
 * header, never the query). Keeps an event only when every classification field says it physically happens in
 * the city as scheduled; anything missing or unknown is dropped, never defaulted.
 */
public class OpenAgendaClient implements OpenEventSource {

    private static final Logger log = LoggerFactory.getLogger(OpenAgendaClient.class);
    static final String BASE_URL = "https://api.openagenda.com/v2/agendas/";
    /** Public event page, checked live 2026-10-01: openagenda.com/fr/{agendaSlug}/events/{eventSlug}. */
    static final String EVENT_PAGE = "https://openagenda.com/fr/";
    static final int PAGE_SIZE = 300;
    static final int MAX_PAGES = 20;
    static final String LICENCE = "Licence Ouverte 2.0";
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final Duration MAX_TIMING = Duration.ofHours(24);
    // status: 1 scheduled, 2 rescheduled, 5 full (kept); 3 moved online, 4 postponed, 6 cancelled (dropped)
    private static final Set<Integer> KEPT_STATUS = Set.of(1, 2, 5);
    // attendanceMode: 1 offline, 3 mixed (kept); 2 online (dropped)
    private static final Set<Integer> KEPT_MODE = Set.of(1, 3);
    private static final int PUBLISHED = 2;
    private static final List<String> FIELDS = List.of("uid", "slug", "title", "keywords", "timings", "location.city",
            "location.countryCode", "status", "attendanceMode", "state");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final OpenEventsProperties props;

    public OpenAgendaClient(RestClient.Builder builder, OpenEventsProperties props) {
        this.http = builder.build();
        this.props = props;
    }

    @Override public String id() { return "openagenda"; }
    @Override public String gate() { return "openagenda"; }
    @Override public String licence() { return LICENCE; }
    /** Past events stay readable through timings filters (checked 2026-10-01). */
    @Override public boolean backfills() { return true; }
    @Override public boolean covers(City city) { return !city.openagenda().isEmpty(); }

    /** Agendas one after another; a page cap on any of them marks the whole fetch partial. */
    @Override
    public Fetch fetch(City city, LocalDate from, LocalDate to) {
        List<RawEvent> events = new ArrayList<>();
        Map<String, Integer> dropped = new TreeMap<>();
        boolean partial = false;
        for (Agenda agenda : city.openagenda()) {
            List<String> after = null;
            int pages = 0;
            do {
                JsonNode root = page(agenda, from, to, after);
                for (JsonNode e : root.path("events")) {
                    String reason = keep(e, city, agenda, from, to, events);
                    if (reason != null) dropped.merge(reason, 1, Integer::sum);
                }
                after = cursor(root.path("after"));
                pages++;
                if (root.path("events").isEmpty()) after = null;
            } while (after != null && pages < MAX_PAGES);
            if (after != null) {
                partial = true;
                log.warn("OpenAgendaClient: agenda {} ({}) still had pages after {}; {} is partial",
                        agenda.uid(), agenda.slug(), MAX_PAGES, city.key());
            }
        }
        return new Fetch(events, partial, dropped);
    }

    static URI uri(Agenda agenda, LocalDate from, LocalDate to, List<String> after) {
        StringBuilder q = new StringBuilder(BASE_URL).append(agenda.uid()).append("/events?size=").append(PAGE_SIZE)
                .append("&monolingual=fr")
                .append("&timings%5Bgte%5D=").append(enc(NightDates.nightStart(from, PARIS).toString()))
                .append("&timings%5Blte%5D=").append(enc(NightDates.nightStart(to.plusDays(1), PARIS).toString()));
        for (String f : FIELDS) q.append("&includeFields%5B%5D=").append(enc(f));
        if (after != null) for (String a : after) q.append("&after%5B%5D=").append(enc(a));
        return URI.create(q.toString());
    }

    private JsonNode page(Agenda agenda, LocalDate from, LocalDate to, List<String> after) {
        URI uri = uri(agenda, from, to, after);
        return http.get().uri(uri).header("key", props.getOpenagendaApiKey()).exchange((req, res) -> {
            int status = res.getStatusCode().value();
            if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new OpenEventsRateLimitedException("OpenAgenda 429 for agenda " + agenda.uid());
            }
            if (!res.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("OpenAgenda " + status + " for agenda " + agenda.uid());
            }
            JsonNode root;
            try {
                root = JSON.readTree(res.getBody());
            } catch (IOException e) {
                throw new IllegalStateException("OpenAgenda: body is not JSON for agenda " + agenda.uid(), e);
            }
            if (root == null || !root.path("events").isArray()) {
                throw new IllegalStateException("OpenAgenda: no events array for agenda " + agenda.uid());
            }
            return root;
        }, true);
    }

    /** Adds the event and returns null, or returns the drop reason. */
    private static String keep(JsonNode e, City city, Agenda agenda, LocalDate from, LocalDate to, List<RawEvent> out) {
        JsonNode uid = e.path("uid");
        if (!uid.isIntegralNumber()) return "no_id";
        if (!e.path("status").isInt() || !KEPT_STATUS.contains(e.path("status").asInt())) return "status";
        if (!e.path("attendanceMode").isInt() || !KEPT_MODE.contains(e.path("attendanceMode").asInt())) return "attendance";
        if (e.has("state") && (!e.path("state").isInt() || e.path("state").asInt() != PUBLISHED)) return "state";
        JsonNode location = e.path("location");
        if (!"FR".equalsIgnoreCase(location.path("countryCode").asText(""))) return "country";
        String cityName = location.path("city").asText("");
        if (cityName.isBlank() || !city.aliases().contains(EventNormalization.cityKey(cityName))) return "city";
        String title = title(e.path("title"));
        if (title == null) return "title";
        String slug = e.path("slug").asText("").strip();
        if (slug.isEmpty()) return "no_url";
        JsonNode timings = e.path("timings");
        if (!timings.isArray() || timings.isEmpty()) return "timings";
        TreeSet<LocalDate> all = new TreeSet<>();
        for (JsonNode t : timings) {
            OffsetDateTime begin;
            OffsetDateTime end;
            try {
                begin = OffsetDateTime.parse(t.path("begin").asText(""));
                end = OffsetDateTime.parse(t.path("end").asText(""));
            } catch (DateTimeParseException ex) {
                return "timings";
            }
            Duration length = Duration.between(begin, end);
            if (length.isNegative()) return "timings";
            if (length.compareTo(MAX_TIMING) > 0) return "long_run";
            all.add(NightDates.nightOf(begin.toInstant(), PARIS));
        }
        // timings[gte/lte] selects events but the answer carries every timing (checked live 2026-10-01)
        TreeSet<LocalDate> nights = new TreeSet<>(all.subSet(from, true, to, true));
        if (OpenEventSource.isRun(all)) return "run";
        if (nights.isEmpty()) return "no_night";
        out.add(new RawEvent(String.valueOf(uid.asLong()), title,
                EVENT_PAGE + agenda.slug() + "/events/" + slug, List.copyOf(nights), keywords(e.path("keywords")),
                LICENCE, null));
        return null;
    }

    /** Monolingual answers give a string; otherwise fr, else the first language present. */
    private static String title(JsonNode node) {
        if (node.isTextual()) return node.asText().isBlank() ? null : node.asText().strip();
        if (!node.isObject()) return null;
        if (node.path("fr").isTextual() && !node.path("fr").asText().isBlank()) return node.path("fr").asText().strip();
        for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
            JsonNode v = it.next();
            if (v.isTextual() && !v.asText().isBlank()) return v.asText().strip();
        }
        return null;
    }

    private static List<String> keywords(JsonNode node) {
        JsonNode list = node.isObject() ? node.path("fr") : node;
        List<String> out = new ArrayList<>();
        if (list.isArray()) for (JsonNode k : list) if (k.isTextual() && !k.asText().isBlank()) out.add(k.asText().strip());
        return out;
    }

    private static List<String> cursor(JsonNode after) {
        if (!after.isArray() || after.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (JsonNode a : after) out.add(a.asText());
        return out;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
