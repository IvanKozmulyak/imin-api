package com.imin.iminapi.predictor.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.predictor.rules.NightDates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Ligue 1 ({@code FL1}) and Champions League ({@code CL}) matches from football-data.org v4, stored as
 * kind {@code fixture} for FR (region ''). One GET and one batch per competition; only CL matches with a
 * club of the FL1 answer are kept. The API key goes in the {@code X-Auth-Token} header, never in a URL or log.
 * Calls only while the {@code football} source gate is on. A match without a confirmed kickoff is stored as a
 * range over its matchday (Friday–Sunday around football-data's placeholder day).
 */
public class FootballFixturesSync implements CalendarSource {

    private static final Logger log = LoggerFactory.getLogger(FootballFixturesSync.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    // source_url is the writer's scope key: changing it orphans rows stored under the old URL until cleaned up
    static final String BASE = "https://api.football-data.org/v4/competitions/";
    /** Linked from findings: the API URL itself needs a token. */
    public static final String PUBLIC_URL = "https://www.football-data.org/";
    static final String FL1 = "FL1";
    static final String CL = "CL";
    static final Set<String> KINDS = Set.of("fixture");
    static final int WINDOW_DAYS = 400;
    /** V162 {@code reference_calendar.name VARCHAR(255)}. */
    static final int MAX_NAME = 255;
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final String SCHEDULED = "SCHEDULED";
    /** Matches that take place; SCHEDULED has no confirmed kickoff yet. Lookup table read 2026-10-01. */
    static final Set<String> KEPT_STATUS = Set.of("TIMED", SCHEDULED, "IN_PLAY", "PAUSED", "EXTRA_TIME",
            "PENALTY_SHOOTOUT", "FINISHED");
    static final Map<String, Set<String>> KEPT_STAGES = Map.of(
            FL1, Set.of("REGULAR_SEASON"),
            CL, Set.of("LEAGUE_STAGE", "PLAYOFFS", "LAST_16", "QUARTER_FINALS", "SEMI_FINALS", "FINAL"));

    private final RestClient http;
    private final FootballDataProperties props;
    private final BooleanSupplier gate;

    /** {@code gate} is the {@code football} source gate, read on every call. */
    public FootballFixturesSync(RestClient http, FootballDataProperties props, BooleanSupplier gate) {
        this.http = http;
        this.props = props;
        this.gate = gate;
    }

    public static String url(String competition) {
        return BASE + competition + "/matches";
    }

    @Override
    public String key() { return "football-data"; }

    @Override
    public String scopePrefix() { return gate.getAsBoolean() ? BASE : null; }

    @Override
    public List<Batch> fetch(LocalDate today) {
        if (!gate.getAsBoolean()) return List.of();
        LocalDate to = today.plusDays(WINDOW_DAYS);
        JsonNode fl1;
        try {
            fl1 = get(FL1);
        } catch (Exception e) {
            // CL needs FL1's clubs, so the run stores nothing from this source
            log.error("FootballFixturesSync: {} failed, nothing stored from football-data this run, keeping stored rows",
                    url(FL1), e);
            return List.of();
        }
        Set<Integer> frenchClubs = teamIds(fl1);
        warnUnmapped(fl1);
        List<Batch> out = new ArrayList<>();
        out.add(batch(FL1, rows(FL1, fl1, null, today, to), today, to));
        Exception clFailure = null;
        try {
            out.add(batch(CL, rows(CL, get(CL), frenchClubs, today, to), today, to));
        } catch (Exception e) {
            clFailure = e;
            log.warn("FootballFixturesSync: {} failed, keeping stored rows: {}", url(CL), e.toString());
        }
        if (out.stream().allMatch(b -> b.rows().isEmpty())) {
            if (clFailure != null) {
                log.error("FootballFixturesSync: no fixture to store from {} or {}, keeping stored rows",
                        url(FL1), url(CL), clFailure);
            } else {
                // both answers arrived; an empty window (summer break) is not a failure
                log.warn("FootballFixturesSync: {} and {} have no match in [{}, {}], keeping stored rows",
                        url(FL1), url(CL), today, to);
            }
        }
        return out;
    }

    /** The batch window opens at today, or earlier for a kept range that started before today. */
    private static Batch batch(String code, List<CalendarRow> rows, LocalDate today, LocalDate to) {
        LocalDate from = rows.stream().map(CalendarRow::date).filter(d -> d.isBefore(today)).min(LocalDate::compareTo)
                .orElse(today);
        return new Batch(url(code), KINDS, from, to, rows);
    }

    /** Rows of one answer; every classification field is checked and each skip counted. */
    private static List<CalendarRow> rows(String code, JsonNode body, Set<Integer> frenchClubs, LocalDate from, LocalDate to) {
        String url = url(code);
        Map<String, Integer> skipped = new LinkedHashMap<>();
        int notFrench = 0;
        List<CalendarRow> out = new ArrayList<>();
        for (JsonNode m : body.path("matches")) {
            String status = m.path("status").asText("");
            JsonNode home = m.path("homeTeam");
            JsonNode away = m.path("awayTeam");
            if (!code.equals(m.path("competition").path("code").asText(""))) {
                skipped.merge("competition", 1, Integer::sum);
                continue;
            }
            if (!KEPT_STATUS.contains(status)) {
                skipped.merge("status " + status, 1, Integer::sum);
                continue;
            }
            if (!KEPT_STAGES.get(code).contains(m.path("stage").asText(""))) {
                skipped.merge("stage " + m.path("stage").asText(""), 1, Integer::sum);
                continue;
            }
            if (!home.path("id").isInt() || !away.path("id").isInt()) {
                skipped.merge("team tbd", 1, Integer::sum);
                continue;
            }
            int homeId = home.path("id").asInt();
            int awayId = away.path("id").asInt();
            if (frenchClubs != null && !frenchClubs.contains(homeId) && !frenchClubs.contains(awayId)) {
                notFrench++;
                continue;
            }
            Instant at;
            try {
                at = Instant.parse(m.path("utcDate").asText(""));
            } catch (Exception e) {
                skipped.merge("utcDate", 1, Integer::sum);
                continue;
            }
            boolean tbc = SCHEDULED.equals(status);
            ZonedDateTime local = at.atZone(PARIS);
            LocalDate date = tbc ? matchdayStart(local.toLocalDate()) : NightDates.nightOf(at, PARIS);
            LocalDate end = tbc ? matchdayEnd(local.toLocalDate()) : null;
            // a matchday range still running today is kept, though its Friday is past
            if ((end != null ? end : date).isBefore(from) || date.isAfter(to)) continue;
            LocalTime kickoff = tbc ? null : local.toLocalTime().withSecond(0).withNano(0);
            String label = home.path("shortName").asText("?") + " – " + away.path("shortName").asText("?");
            out.add(new CalendarRow("FR", "", date, end, "fixture",
                    FixtureName.format(code, kickoff, homeId, awayId, label), url));
        }
        if (!skipped.isEmpty()) {
            log.warn("FootballFixturesSync: {} skipped {} (kept {}, CL without a French club {})", url, skipped, out.size(), notFrench);
        }
        return out;
    }

    /**
     * A TBC match's placeholder (00:00Z, mostly the matchday's Saturday) is not its day: a weekend placeholder
     * covers Friday–Sunday; ponytail: a midweek one covers the day either side, as football-data names no day.
     */
    static LocalDate matchdayStart(LocalDate placeholder) {
        return weekend(placeholder) ? placeholder.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
                : placeholder.minusDays(1);
    }

    static LocalDate matchdayEnd(LocalDate placeholder) {
        return weekend(placeholder) ? placeholder.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                : placeholder.plusDays(1);
    }

    private static boolean weekend(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.FRIDAY || w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
    }

    private static Set<Integer> teamIds(JsonNode body) {
        Set<Integer> ids = new HashSet<>();
        for (JsonNode m : body.path("matches")) {
            for (String side : List.of("homeTeam", "awayTeam")) {
                JsonNode id = m.path(side).path("id");
                if (id.isInt()) ids.add(id.asInt());
            }
        }
        return Set.copyOf(ids);
    }

    /** One WARN per FL1 team with no city in {@link FootballClubs}, so a promoted club is visible. */
    private static void warnUnmapped(JsonNode fl1) {
        Map<Integer, String> unmapped = new LinkedHashMap<>();
        for (JsonNode m : fl1.path("matches")) {
            for (String side : List.of("homeTeam", "awayTeam")) {
                JsonNode t = m.path(side);
                if (t.path("id").isInt() && !FootballClubs.mapped(t.path("id").asInt())) {
                    unmapped.putIfAbsent(t.path("id").asInt(), t.path("shortName").asText(""));
                }
            }
        }
        unmapped.forEach((id, name) -> log.warn("FootballFixturesSync: unmapped FL1 team {} {}", id, name));
    }

    /** The answer object; throws on a failed call or a body without a {@code matches} array. */
    private JsonNode get(String code) throws Exception {
        String body = http.get().uri(URI.create(url(code)))
                .header("X-Auth-Token", props.getApiKey())
                .retrieve().body(String.class);
        JsonNode root = JSON.readTree(body == null ? "" : body);
        if (root == null || !root.isObject() || !root.path("matches").isArray()) {
            throw new IllegalStateException("not a matches answer");
        }
        return root;
    }

    /**
     * The stored {@code name}: {@code competition|HH:mm or TBC|homeId|awayId|label}, kickoff in Europe/Paris.
     * Ids make it unique per match; the label is cut so the whole name fits 255 chars.
     */
    public record FixtureName(String competition, LocalTime kickoff, int homeId, int awayId, String label) {

        private static final String TBC = "TBC";
        private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

        public static String format(String competition, LocalTime kickoff, int homeId, int awayId, String label) {
            String head = competition + "|" + (kickoff == null ? TBC : kickoff.format(HH_MM)) + "|" + homeId + "|" + awayId + "|";
            String l = label.length() > MAX_NAME - head.length() ? label.substring(0, MAX_NAME - head.length()) : label;
            return head + l;
        }

        /** Throws {@link IllegalArgumentException} on a name this class did not write. */
        public static FixtureName parse(String name) {
            String[] p = name == null ? new String[0] : name.split("\\|", 5);
            if (p.length != 5) throw new IllegalArgumentException("not a fixture name: " + name);
            try {
                LocalTime kickoff = TBC.equals(p[1]) ? null : LocalTime.parse(p[1], HH_MM);
                return new FixtureName(p[0], kickoff, Integer.parseInt(p[2]), Integer.parseInt(p[3]), p[4]);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("not a fixture name: " + name, e);
            }
        }
    }
}
