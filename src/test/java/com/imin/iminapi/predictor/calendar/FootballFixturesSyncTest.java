package com.imin.iminapi.predictor.calendar;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.imin.iminapi.predictor.calendar.FootballFixturesSync.FixtureName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FootballFixturesSyncTest {

    private static final String KEY = "test-key-7f3a";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FL1 = FootballFixturesSync.url("FL1");
    private static final String CL = FootballFixturesSync.url("CL");
    /** Recorded row ids (football-data match ids) in the trimmed answers. */
    private static final int LORIENT_PARIS_FC = 559668;
    private static final int TOULOUSE_PSG_TBC = 559609;
    private static final int MARSEILLE_PSG_FINISHED = 559675;
    private static final int PSG_BARCA = 575367;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private static FootballDataProperties props(boolean enabled, String key) {
        FootballDataProperties p = new FootballDataProperties();
        p.setEnabled(enabled);
        p.setApiKey(key);
        return p;
    }

    private FootballFixturesSync sync() {
        return new FootballFixturesSync(builder.build(), props(true, KEY), () -> true);
    }

    private static ObjectNode recorded(String code) {
        try {
            return (ObjectNode) JSON.readTree(CalendarFixtures.text("football-" + code + ".json"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The recorded answer with only the given match kept, edited in place. */
    private static String only(String code, int matchId, Consumer<ObjectNode> edit) {
        ObjectNode root = recorded(code);
        ArrayNode kept = JSON.createArrayNode();
        for (JsonNode m : root.path("matches")) {
            if (m.path("id").asInt() == matchId) {
                edit.accept((ObjectNode) m);
                kept.add(m);
            }
        }
        assertThat(kept).as("recorded match " + matchId).hasSize(1);
        root.set("matches", kept);
        return root.toString();
    }

    private static String only(String code, int matchId) {
        return only(code, matchId, m -> {});
    }

    private void respond(String url, String body) {
        server.expect(requestTo(url)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private List<CalendarSource.Batch> fetch(String fl1, String cl) {
        return fetch(fl1, cl, TODAY);
    }

    private List<CalendarSource.Batch> fetch(String fl1, String cl, LocalDate today) {
        respond(FL1, fl1);
        respond(CL, cl);
        List<CalendarSource.Batch> out = sync().fetch(today);
        server.verify();
        return out;
    }

    private static List<String> names(List<CalendarSource.Batch> batches, String url) {
        return batches.stream().filter(b -> b.sourceUrl().equals(url))
                .flatMap(b -> b.rows().stream()).map(CalendarRow::name).toList();
    }

    private static String emptyCl() {
        ObjectNode root = recorded("CL");
        root.set("matches", JSON.createArrayNode());
        return root.toString();
    }

    private static List<ILoggingEvent> capture(Runnable work) {
        Logger logger = (Logger) LoggerFactory.getLogger(FootballFixturesSync.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            work.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void gateOffMakesNoCallAndHasNoScopePrefix() {
        // flag and key set, but the football gate (date check, calendar sync) is off
        FootballFixturesSync off = new FootballFixturesSync(builder.build(), props(true, KEY), () -> false);

        assertThat(off.fetch(TODAY)).isEmpty();
        server.verify();
        assertThat(off.scopePrefix()).isNull();
        assertThat(sync().scopePrefix()).isEqualTo("https://api.football-data.org/v4/competitions/");
        assertThat(sync().key()).isEqualTo("football-data");
        assertThat(FL1).isEqualTo("https://api.football-data.org/v4/competitions/FL1/matches").startsWith(sync().scopePrefix());
    }

    @Test
    void keySentAsHeaderNeverInUrl() {
        server.expect(requestTo(FL1)).andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-Auth-Token", KEY))
                .andExpect(r -> assertThat(r.getURI().toString()).isEqualTo(FL1).doesNotContain(KEY))
                .andRespond(withSuccess(only("FL1", LORIENT_PARIS_FC), MediaType.APPLICATION_JSON));
        server.expect(requestTo(CL)).andExpect(header("X-Auth-Token", KEY))
                .andRespond(withSuccess(emptyCl(), MediaType.APPLICATION_JSON));

        sync().fetch(TODAY);

        server.verify();
    }

    @Test
    void timedMatchNameCarriesParisKickoff() {
        // recorded 2026-10-10T18:45:00Z, CEST
        List<CalendarSource.Batch> batches = fetch(only("FL1", LORIENT_PARIS_FC), emptyCl());

        assertThat(batches).extracting(CalendarSource.Batch::sourceUrl, CalendarSource.Batch::from, CalendarSource.Batch::to)
                .containsExactly(tuple(FL1, TODAY, TODAY.plusDays(400)), tuple(CL, TODAY, TODAY.plusDays(400)));
        assertThat(batches).allSatisfy(b -> assertThat(b.kinds()).containsExactly("fixture"));
        CalendarRow row = batches.get(0).rows().get(0);
        assertThat(row).isEqualTo(new CalendarRow("FR", "", LocalDate.of(2026, 10, 10), null, "fixture",
                "FL1|20:45|525|1045|Lorient – Paris FC", FL1));
    }

    @Test
    void scheduledMatchNameIsTbc() {
        // recorded SCHEDULED rows carry a 00:00Z placeholder on the matchday's Saturday
        List<CalendarSource.Batch> batches = fetch(only("FL1", TOULOUSE_PSG_TBC), emptyCl());

        CalendarRow row = batches.get(0).rows().get(0);
        assertThat(row.date()).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(row.endDate()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(row.name()).isEqualTo("FL1|TBC|511|524|Toulouse – PSG");
    }

    @ParameterizedTest
    @CsvSource({"2026-12-04T00:00:00Z,2026-12-04,2026-12-06", "2026-12-05T00:00:00Z,2026-12-04,2026-12-06",
            "2026-12-06T00:00:00Z,2026-12-04,2026-12-06", "2026-12-09T00:00:00Z,2026-12-08,2026-12-10"})
    void tbcMatchCoversItsMatchday(String placeholder, String from, String to) {
        String fl1 = only("FL1", TOULOUSE_PSG_TBC, m -> m.put("utcDate", placeholder));

        CalendarRow row = fetch(fl1, emptyCl()).get(0).rows().get(0);

        assertThat(row.date()).isEqualTo(LocalDate.parse(from));
        assertThat(row.endDate()).isEqualTo(LocalDate.parse(to));
    }

    @Test
    void runOnSaturdayKeepsTheRunningMatchdayRange() {
        // today is the Saturday placeholder: the Fri–Sun range is still running though its Friday is past
        LocalDate saturday = LocalDate.of(2026, 12, 5);

        List<CalendarSource.Batch> batches = fetch(only("FL1", TOULOUSE_PSG_TBC), emptyCl(), saturday);

        CalendarRow row = batches.get(0).rows().get(0);
        assertThat(row.date()).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(row.endDate()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(batches.get(0).from()).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(batches.get(1).from()).isEqualTo(saturday);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PLAY", "PAUSED", "EXTRA_TIME", "PENALTY_SHOOTOUT", "FINISHED"})
    void playedStatusKeptWithKickoff(String status) {
        String fl1 = only("FL1", LORIENT_PARIS_FC, m -> m.put("status", status));

        assertThat(names(fetch(fl1, emptyCl()), FL1)).containsExactly("FL1|20:45|525|1045|Lorient – Paris FC");
    }

    /** Edits to one recorded match that make the sync skip it. */
    static Stream<Arguments> skippedMatches() {
        List<Arguments> rows = new ArrayList<>();
        for (String status : List.of("POSTPONED", "SUSPENDED", "CANCELLED", "AWARDED", "FOO")) {
            rows.add(Arguments.of("FL1 status " + status, false,
                    (Consumer<ObjectNode>) m -> m.put("status", status)));
        }
        for (String stage : List.of("RELEGATION", "FOO")) {
            rows.add(Arguments.of("FL1 stage " + stage, false, (Consumer<ObjectNode>) m -> m.put("stage", stage)));
        }
        for (String stage : List.of("QUALIFICATION_ROUND_3", "PRELIMINARY_ROUND", "FOO")) {
            rows.add(Arguments.of("CL stage " + stage, true, (Consumer<ObjectNode>) m -> m.put("stage", stage)));
        }
        rows.add(Arguments.of("competition code mismatch", false,
                (Consumer<ObjectNode>) m -> ((ObjectNode) m.path("competition")).put("code", "FL2")));
        rows.add(Arguments.of("TBD team", true, (Consumer<ObjectNode>) m -> ((ObjectNode) m.path("awayTeam")).putNull("id")));
        rows.add(Arguments.of("unparseable utcDate", false, (Consumer<ObjectNode>) m -> m.put("utcDate", "soon")));
        return rows.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("skippedMatches")
    void matchSkipped(String name, boolean champions, Consumer<ObjectNode> edit) {
        List<CalendarSource.Batch> batches = champions
                ? fetch(recorded("FL1").toString(), only("CL", PSG_BARCA, edit))
                : fetch(only("FL1", LORIENT_PARIS_FC, edit), emptyCl());

        assertThat(names(batches, champions ? CL : FL1)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEAGUE_STAGE", "PLAYOFFS", "LAST_16", "QUARTER_FINALS", "SEMI_FINALS", "FINAL"})
    void clMainStageKept(String stage) {
        String cl = only("CL", PSG_BARCA, m -> m.put("stage", stage));

        assertThat(names(fetch(recorded("FL1").toString(), cl), CL)).containsExactly("CL|21:00|524|81|PSG – Barça");
    }

    @Test
    void clMatchWithoutFrenchClubSkipped() {
        // the full recorded CL answer: French clubs are those of the FL1 answer (PSG, Lille, Lens among them)
        List<CalendarSource.Batch> batches = fetch(recorded("FL1").toString(), recorded("CL").toString());

        assertThat(names(batches, CL)).containsExactly(
                "CL|18:45|546|498|RC Lens – Sporting CP",
                "CL|21:00|65|524|Man City – PSG",
                "CL|21:00|524|81|PSG – Barça");
    }

    @Test
    void fl1FailureSkipsClAndLogsError() {
        server.expect(ExpectedCount.once(), requestTo(FL1)).andRespond(withServerError());

        List<ILoggingEvent> logs = capture(() -> assertThat(sync().fetch(TODAY)).isEmpty());

        server.verify();
        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getThrowableProxy()).isNotNull();
        });
        assertThat(logs).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains(KEY));
    }

    @Test
    void fl1BodyWithoutMatchesIsAFailure() {
        server.expect(requestTo(FL1)).andRespond(withSuccess("{\"errorCode\":403}", MediaType.APPLICATION_JSON));

        List<ILoggingEvent> logs = capture(() -> assertThat(sync().fetch(TODAY)).isEmpty());

        server.verify();
        assertThat(logs).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    void clFailureKeepsFl1Batch() {
        respond(FL1, only("FL1", LORIENT_PARIS_FC));
        server.expect(requestTo(CL)).andRespond(withServerError());

        List<ILoggingEvent> logs = new ArrayList<>();
        List<CalendarSource.Batch> batches = new ArrayList<>();
        logs.addAll(capture(() -> batches.addAll(sync().fetch(TODAY))));

        server.verify();
        assertThat(batches).extracting(CalendarSource.Batch::sourceUrl).containsExactly(FL1);
        assertThat(batches.get(0).rows()).hasSize(1);
        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains(CL);
        });
        assertThat(logs).noneSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    void clFailureAfterEmptyFl1ReturnsOnlyTheEmptyFl1Batch() {
        // FL1 answered with nothing to keep and CL failed: no CL batch, the FL1 window still comes back empty
        respond(FL1, only("FL1", LORIENT_PARIS_FC, m -> m.put("status", "POSTPONED")));
        server.expect(requestTo(CL)).andRespond(withServerError());

        List<CalendarSource.Batch> batches = sync().fetch(TODAY);

        server.verify();
        assertThat(batches).extracting(CalendarSource.Batch::sourceUrl, CalendarSource.Batch::from, CalendarSource.Batch::to)
                .containsExactly(tuple(FL1, TODAY, TODAY.plusDays(400)));
        assertThat(batches.get(0).rows()).isEmpty();
    }

    @Test
    void lateKickoffBelongsToPreviousNight() {
        // 22:30Z on 10 Oct is 00:30 Paris on 11 Oct
        String fl1 = only("FL1", LORIENT_PARIS_FC, m -> m.put("utcDate", "2026-10-10T22:30:00Z"));

        CalendarRow row = fetch(fl1, emptyCl()).get(0).rows().get(0);

        assertThat(row.date()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(row.name()).startsWith("FL1|00:30|");
    }

    @Test
    void rowsOutsideWindowDropped() {
        // the recorded FINISHED match is before today; a copy moved past today+400 is after the window
        ObjectNode root = recorded("FL1");
        ArrayNode kept = JSON.createArrayNode();
        for (JsonNode m : root.path("matches")) {
            int id = m.path("id").asInt();
            if (id == MARSEILLE_PSG_FINISHED || id == LORIENT_PARIS_FC) kept.add(m);
            if (id == TOULOUSE_PSG_TBC) kept.add(((ObjectNode) m.deepCopy()).put("utcDate", "2027-11-13T00:00:00Z"));
        }
        root.set("matches", kept);

        List<CalendarSource.Batch> batches = fetch(root.toString(), emptyCl());

        assertThat(batches.get(0).from()).isEqualTo(TODAY);
        assertThat(batches.get(0).to()).isEqualTo(LocalDate.of(2027, 11, 5));
        assertThat(names(batches, FL1)).containsExactly("FL1|20:45|525|1045|Lorient – Paris FC");
    }

    @Test
    void successfulRunWithNoMatchInWindowWarnsNotErrors() {
        // off-season: both answers arrive, the only match is before today
        respond(FL1, only("FL1", MARSEILLE_PSG_FINISHED));
        respond(CL, emptyCl());
        List<CalendarSource.Batch> batches = new ArrayList<>();

        List<ILoggingEvent> logs = capture(() -> batches.addAll(sync().fetch(TODAY)));

        server.verify();
        assertThat(batches).extracting(CalendarSource.Batch::sourceUrl).containsExactly(FL1, CL);
        assertThat(batches).allSatisfy(b -> assertThat(b.rows()).isEmpty());
        assertThat(logs).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("no match in");
        });
        assertThat(logs).noneSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
    }

    @Test
    void unmappedFl1TeamLogsWarn() {
        String fl1 = only("FL1", LORIENT_PARIS_FC, m -> ((ObjectNode) m.path("awayTeam")).put("id", 99999)
                .put("shortName", "Promoted FC"));
        respond(FL1, fl1);
        respond(CL, emptyCl());

        List<ILoggingEvent> logs = capture(() -> sync().fetch(TODAY));

        assertThat(logs).filteredOn(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("unmapped"))
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("99999").contains("Promoted FC"));
    }

    @Test
    void longLabelCutTo255() {
        String fl1 = only("FL1", LORIENT_PARIS_FC, m -> ((ObjectNode) m.path("homeTeam")).put("shortName", "L".repeat(300)));

        String name = names(fetch(fl1, emptyCl()), FL1).get(0);

        assertThat(name).hasSize(255).startsWith("FL1|20:45|525|1045|LLL");
    }

    @Test
    void fixtureNameRoundTrips() {
        String timed = FixtureName.format("FL1", LocalTime.of(21, 0), 524, 516, "PSG – Marseille");
        String tbc = FixtureName.format("CL", null, 524, 81, "A|B – C");

        assertThat(timed).isEqualTo("FL1|21:00|524|516|PSG – Marseille");
        assertThat(FixtureName.parse(timed)).isEqualTo(new FixtureName("FL1", LocalTime.of(21, 0), 524, 516, "PSG – Marseille"));
        assertThat(tbc).isEqualTo("CL|TBC|524|81|A|B – C");
        assertThat(FixtureName.parse(tbc)).isEqualTo(new FixtureName("CL", null, 524, 81, "A|B – C"));
    }
}
