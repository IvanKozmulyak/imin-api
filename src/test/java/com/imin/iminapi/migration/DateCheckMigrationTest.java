package com.imin.iminapi.migration;

import com.imin.iminapi.predictor.service.PredictorAlertStore;
import com.imin.iminapi.support.SharedPostgres;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V162 on a fresh database per test: the date-check tables, the ledger CHECKs and the events link; V165 open events;
 * V167 radar runs; V168 predictor alerts; V169 Radar mute and run-time snapshots; V173 web research state.
 */
class DateCheckMigrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneOffset.UTC);

    /** A new, empty database per call. */
    private static DataSource freshDatabase() {
        return SharedPostgres.freshDatabase("v162");
    }

    private static void migrate(DataSource ds, String target) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load().migrate();
    }

    private static JdbcTemplate latest(DataSource ds) {
        migrate(ds, "latest");
        return new JdbcTemplate(ds);
    }

    // ---- upgrades over existing rows -----------------------------------------------------

    @Test
    void existingLedgerRowsUnaffected() {
        DataSource ds = freshDatabase();
        migrate(ds, "161");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        UUID org = org(jdbc);
        UUID event = event(jdbc, org);
        UUID row = UUID.randomUUID();
        jdbc.update("""
                insert into prediction_ledger (id, event_id, org_id, surface, stage, model_id, prompt_version,
                    input_snapshot_hash, comparables_json, output_json, actual_sold, ape)
                values (?, ?, ?, 'PRE_PUBLISH', 0, 'm', '1.0.0', 'h', '{"ids":[]}', '{"x":1}', 12, 0.25)""",
                row, event, org);

        migrate(ds, "latest");

        Map<String, Object> r = jdbc.queryForMap("select * from prediction_ledger where id = ?", row);
        assertThat(r.get("event_id")).isEqualTo(event);
        assertThat(r.get("org_id")).isEqualTo(org);
        assertThat(r.get("surface")).isEqualTo("PRE_PUBLISH");
        assertThat(((Number) r.get("stage")).intValue()).isZero();
        assertThat(r.get("model_id")).isEqualTo("m");
        assertThat(r.get("prompt_version")).isEqualTo("1.0.0");
        assertThat(r.get("input_snapshot_hash")).isEqualTo("h");
        assertThat(r.get("comparables_json")).isEqualTo("{\"ids\":[]}");
        assertThat(r.get("output_json")).isEqualTo("{\"x\":1}");
        assertThat(((Number) r.get("actual_sold")).intValue()).isEqualTo(12);
        assertThat((BigDecimal) r.get("ape")).isEqualByComparingTo("0.25");
        for (String col : List.of("date_check_id", "question_bank_version", "tokens_in", "tokens_out", "cost_usd", "searches")) {
            assertThat(r).containsKey(col);
            assertThat(r.get(col)).as(col).isNull();
        }
        Map<String, Object> e = jdbc.queryForMap("select sub_genre, date_check_id from events where id = ?", event);
        assertThat(e.get("sub_genre")).isNull();
        assertThat(e.get("date_check_id")).isNull();
    }

    @Test
    void existingDateChecksKeepResearchOff() {
        DataSource ds = freshDatabase();
        migrate(ds, "171");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        UUID dc = dateCheck(jdbc, org(jdbc), Map.of());

        migrate(ds, "latest");

        Map<String, Object> r = jdbc.queryForMap(
                "select research, research_status, research_queued_at from date_check where id = ?", dc);
        assertThat(r.get("research")).isEqualTo(false);
        assertThat(r.get("research_status")).isEqualTo("off");
        assertThat(r.get("research_queued_at")).isNull();
    }

    @Test
    void existingDateChecksBecomeOrganizerRuns() {
        DataSource ds = freshDatabase();
        migrate(ds, "166");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        UUID dc = dateCheck(jdbc, org(jdbc), Map.of());

        migrate(ds, "latest");

        Map<String, Object> r = jdbc.queryForMap(
                "select origin, radar_milestone, radar_night, radar_prev_id from date_check where id = ?", dc);
        assertThat(r.get("origin")).isEqualTo("organizer");
        assertThat(r.get("radar_milestone")).isNull();
        assertThat(r.get("radar_night")).isNull();
        assertThat(r.get("radar_prev_id")).isNull();
    }

    @Test
    void radarRowsBackfillTheirVerdicts() {
        DataSource ds = freshDatabase();
        migrate(ds, "168");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        UUID org = org(jdbc);
        UUID event = event(jdbc, org);
        LocalDate night = LocalDate.of(2026, 10, 15);
        UUID prev = dateCheck(jdbc, org, Map.of("event_id", event));
        dateCheckDate(jdbc, Map.of("date_check_id", prev, "candidate_date", night, "verdict", "adjust", "risk_score", 4));
        dateCheckDate(jdbc, Map.of("date_check_id", prev, "candidate_date", night.plusDays(1), "verdict", "good",
                "risk_score", 1));
        UUID radar = radarCheck(jdbc, Map.of("org_id", org, "event_id", event, "radar_prev_id", prev));
        dateCheckDate(jdbc, Map.of("date_check_id", radar, "candidate_date", night, "verdict", "move", "risk_score", 7));

        migrate(ds, "latest");

        Map<String, Object> r = snapshot(jdbc, radar);
        assertThat(r.get("radar_prev_verdict")).isEqualTo("adjust");
        assertThat(((Number) r.get("radar_prev_risk")).intValue()).isEqualTo(4);
        assertThat(r.get("radar_verdict")).isEqualTo("move");
        assertThat(((Number) r.get("radar_risk")).intValue()).isEqualTo(7);
        assertThat(snapshot(jdbc, prev).values()).containsOnlyNulls();
    }

    @Test
    void radarRowWithStaleBaselineBackfillsNoBefore() {
        DataSource ds = freshDatabase();
        migrate(ds, "168");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        UUID org = org(jdbc);
        UUID event = event(jdbc, org);
        LocalDate night = LocalDate.of(2026, 10, 15);
        UUID prev = dateCheck(jdbc, org, Map.of("event_id", event));
        dateCheckDate(jdbc, Map.of("date_check_id", prev, "candidate_date", night.plusDays(9)));
        UUID radar = radarCheck(jdbc, Map.of("org_id", org, "event_id", event, "radar_prev_id", prev));
        dateCheckDate(jdbc, Map.of("date_check_id", radar, "candidate_date", night, "verdict", "move", "risk_score", 7));

        migrate(ds, "latest");

        Map<String, Object> r = snapshot(jdbc, radar);
        assertThat(r.get("radar_prev_verdict")).isNull();
        assertThat(r.get("radar_prev_risk")).isNull();
        assertThat(r.get("radar_verdict")).isEqualTo("move");
        assertThat(((Number) r.get("radar_risk")).intValue()).isEqualTo(7);
    }

    @Test
    void eventsStartUnmuted() {
        DataSource ds = freshDatabase();
        migrate(ds, "168");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        UUID event = event(jdbc, org(jdbc));

        migrate(ds, "latest");

        assertThat(jdbc.queryForObject("select radar_muted from events where id = ?", Boolean.class, event)).isFalse();
        UUID later = event(jdbc, org(jdbc));
        assertThat(jdbc.queryForObject("select radar_muted from events where id = ?", Boolean.class, later)).isFalse();
    }

    private static Map<String, Object> snapshot(JdbcTemplate jdbc, UUID checkId) {
        return jdbc.queryForMap("select radar_prev_verdict, radar_prev_risk, radar_verdict, radar_risk from date_check"
                + " where id = ?", checkId);
    }

    // ---- named constraints: the valid row inserts, the one override is rejected -----------

    /** One constraint, the table it guards, a valid row builder and the single override that breaks it. */
    record CheckCase(String constraint, BiFunction<JdbcTemplate, Map<String, Object>, UUID> insert,
                     Map<String, Object> bad) {
        @Override
        public String toString() { return constraint + " " + bad; }
    }

    static Stream<CheckCase> checks() {
        return Stream.of(
                // prediction_ledger
                new CheckCase("ck_prediction_ledger_event_or_date_check", DateCheckMigrationTest::ledgerRow,
                        cols("surface", "PRE_PUBLISH", "event_id", null)),
                new CheckCase("ck_prediction_ledger_event_or_date_check", DateCheckMigrationTest::ledgerRow,
                        cols("surface", "REFORECAST", "event_id", null)),
                new CheckCase("ck_prediction_ledger_event_or_date_check", DateCheckMigrationTest::ledgerRow,
                        cols("surface", "ACTIONS", "event_id", null)),
                new CheckCase("ck_prediction_ledger_date_check_id", DateCheckMigrationTest::ledgerRow,
                        Map.of("surface", "DATE_CHECK")),
                // events
                new CheckCase("fk_events_date_check", DateCheckMigrationTest::eventRow,
                        Map.of("date_check_id", UUID.randomUUID())),
                // date_check
                new CheckCase("ck_date_check_status", DateCheckMigrationTest::dateCheckFresh, Map.of("status", "queued")),
                new CheckCase("ck_date_check_origin", DateCheckMigrationTest::dateCheckFresh, Map.of("origin", "bot")),
                new CheckCase("ck_date_check_radar_milestone", DateCheckMigrationTest::radarCheck,
                        Map.of("radar_milestone", 5)),
                new CheckCase("ck_date_check_radar_shape", DateCheckMigrationTest::radarCheck,
                        radarWithout("radar_night")),
                new CheckCase("ck_date_check_radar_shape", DateCheckMigrationTest::dateCheckFresh,
                        Map.of("radar_milestone", 14)),
                new CheckCase("ck_date_check_radar_shape", DateCheckMigrationTest::radarCheck,
                        radarWithout("radar_milestone")),
                new CheckCase("ck_date_check_radar_shape", DateCheckMigrationTest::dateCheckFresh,
                        Map.of("radar_night", LocalDate.of(2026, 10, 15))),
                new CheckCase("ck_date_check_radar_shape", DateCheckMigrationTest::organizerCheckWithPrev,
                        Map.of("radar_prev_id", EXISTING_CHECK)),
                // Each bad row breaks one CHECK only: the pair stays complete so the shape CHECK cannot fire first.
                new CheckCase("ck_date_check_radar_verdicts", DateCheckMigrationTest::radarCheck,
                        Map.of("radar_verdict", "maybe", "radar_risk", 1)),
                new CheckCase("ck_date_check_radar_verdicts", DateCheckMigrationTest::radarCheck,
                        Map.of("radar_prev_verdict", "maybe", "radar_prev_risk", 1)),
                new CheckCase("ck_date_check_radar_risks", DateCheckMigrationTest::radarCheck,
                        Map.of("radar_verdict", "good", "radar_risk", 11)),
                new CheckCase("ck_date_check_radar_risks", DateCheckMigrationTest::radarCheck,
                        Map.of("radar_prev_verdict", "good", "radar_prev_risk", -1)),
                new CheckCase("ck_date_check_radar_snapshot_shape", DateCheckMigrationTest::radarCheck,
                        Map.of("radar_verdict", "good")),
                new CheckCase("ck_date_check_radar_snapshot_shape", DateCheckMigrationTest::radarCheck,
                        Map.of("radar_prev_risk", 3)),
                new CheckCase("ck_date_check_radar_snapshot_shape", DateCheckMigrationTest::dateCheckFresh,
                        Map.of("radar_verdict", "good", "radar_risk", 1)),
                new CheckCase("ck_date_check_radar_snapshot_shape", DateCheckMigrationTest::dateCheckFresh,
                        Map.of("radar_prev_verdict", "good", "radar_prev_risk", 1)),
                new CheckCase("ck_date_check_research_status", DateCheckMigrationTest::dateCheckFresh,
                        Map.of("research", true, "research_status", "queued")),
                new CheckCase("ck_date_check_research_off", DateCheckMigrationTest::dateCheckFresh,
                        Map.of("research", false, "research_status", "running")),
                // date_check_date
                new CheckCase("ck_date_check_date_verdict", DateCheckMigrationTest::dateCheckDate, Map.of("verdict", "maybe")),
                new CheckCase("ck_date_check_date_risk", DateCheckMigrationTest::dateCheckDate, Map.of("risk_score", 11)),
                new CheckCase("ck_date_check_date_opp", DateCheckMigrationTest::dateCheckDate, Map.of("opp_score", -1)),
                new CheckCase("ck_date_check_date_coverage", DateCheckMigrationTest::dateCheckDate,
                        Map.of("coverage", new BigDecimal("1.001"))),
                // date_check_finding
                new CheckCase("ck_date_check_finding_kind", DateCheckMigrationTest::finding, Map.of("kind", "neutral")),
                new CheckCase("ck_date_check_finding_status", DateCheckMigrationTest::finding, Map.of("status", "maybe")),
                new CheckCase("ck_date_check_finding_source_kind", DateCheckMigrationTest::finding,
                        Map.of("source_kind", "bogus")),
                new CheckCase("ck_date_check_finding_window", DateCheckMigrationTest::finding, Map.of("time_window", "year")),
                new CheckCase("ck_date_check_finding_strength", DateCheckMigrationTest::finding,
                        Map.of("source_kind", "structured", "strength", 4)),
                new CheckCase("ck_date_check_finding_weight", DateCheckMigrationTest::finding, Map.of("weight", 0)),
                new CheckCase("ck_date_check_finding_soft_strength", DateCheckMigrationTest::finding,
                        Map.of("source_kind", "web", "strength", 3)),
                new CheckCase("ck_date_check_finding_soft_strength", DateCheckMigrationTest::finding,
                        Map.of("source_kind", "input", "strength", 3)),
                new CheckCase("ck_date_check_finding_stop_source", DateCheckMigrationTest::finding,
                        Map.of("source_kind", "organizer", "stop_factor", true)),
                new CheckCase("ck_date_check_finding_stop_source", DateCheckMigrationTest::finding,
                        Map.of("source_kind", "input", "stop_factor", true)),
                // reference_calendar, predictor_job, genre_week_count, open_event_occurrence, predictor_alert
                new CheckCase("ck_reference_calendar_kind", DateCheckMigrationTest::referenceCalendar, Map.of("kind", "party")),
                new CheckCase("ck_reference_calendar_range", DateCheckMigrationTest::referenceCalendar,
                        Map.of("end_date", LocalDate.of(2026, 12, 24))),
                new CheckCase("ck_predictor_job_status", DateCheckMigrationTest::predictorJob, Map.of("status", "paused")),
                new CheckCase("ck_genre_week_count_nonneg", DateCheckMigrationTest::genreWeekCount, Map.of("event_count", -1)),
                new CheckCase("ck_open_event_occurrence_source", DateCheckMigrationTest::openEventOccurrence,
                        Map.of("source", "other")),
                new CheckCase("ck_open_event_occurrence_licence", DateCheckMigrationTest::openEventOccurrence,
                        Map.of("licence", "CC-BY")),
                new CheckCase("ck_predictor_alert_kind", DateCheckMigrationTest::predictorAlert,
                        Map.of("kind", "push")),
                new CheckCase("ck_predictor_alert_band_shape", DateCheckMigrationTest::predictorAlert,
                        Map.of("kind", "band", "date_check_id", EXISTING_CHECK)));
    }

    private static Map<String, Object> radarWithout(String column) {
        return cols(column, null);
    }

    @ParameterizedTest
    @MethodSource("checks")
    void namedConstraintsRejectOutOfDomainValues(CheckCase c) {
        JdbcTemplate jdbc = latest(freshDatabase());
        assertThatCode(() -> c.insert().apply(jdbc, Map.of())).as("valid sibling").doesNotThrowAnyException();
        assertThatThrownBy(() -> c.insert().apply(jdbc, c.bad()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsIgnoringCase(c.constraint()));
    }

    // ---- values the constraints deliberately allow -----------------------------------------

    /** A row the constraints must accept; {@code values} may insert the rows it points at, and every value is read back. */
    record AcceptCase(String label, String table, BiFunction<JdbcTemplate, Map<String, Object>, UUID> insert,
                      Function<JdbcTemplate, Map<String, Object>> values) {
        @Override
        public String toString() { return label; }
    }

    static Stream<AcceptCase> accepted() {
        return Stream.of(
                new AcceptCase("ledger DATE_CHECK without an event", "prediction_ledger", DateCheckMigrationTest::ledgerRow,
                        jdbc -> {
                            UUID org = org(jdbc);
                            return cols("org_id", org, "surface", "DATE_CHECK", "event_id", null,
                                    "date_check_id", dateCheck(jdbc, org, Map.of()));
                        }),
                new AcceptCase("ledger DATE_CHECK with an event", "prediction_ledger", DateCheckMigrationTest::ledgerRow,
                        jdbc -> {
                            UUID org = org(jdbc);
                            return cols("org_id", org, "surface", "DATE_CHECK", "event_id", event(jdbc, org),
                                    "date_check_id", dateCheck(jdbc, org, Map.of()));
                        }),
                new AcceptCase("ledger event surface needs no date check", "prediction_ledger",
                        DateCheckMigrationTest::ledgerRow, jdbc -> {
                            UUID org = org(jdbc);
                            return cols("org_id", org, "surface", "PRE_PUBLISH", "event_id", event(jdbc, org),
                                    "date_check_id", null);
                        }),
                new AcceptCase("finding structured strength 3 stop", "date_check_finding", DateCheckMigrationTest::finding,
                        jdbc -> Map.of("source_kind", "structured", "strength", 3, "stop_factor", true)),
                new AcceptCase("finding internal strength 3 stop", "date_check_finding", DateCheckMigrationTest::finding,
                        jdbc -> Map.of("source_kind", "internal", "strength", 3, "stop_factor", true)),
                new AcceptCase("finding organizer input is a soft source", "date_check_finding",
                        DateCheckMigrationTest::finding, jdbc -> Map.of("source_kind", "input", "strength", 2)),
                new AcceptCase("research running", "date_check", DateCheckMigrationTest::dateCheckFresh,
                        jdbc -> Map.of("research", true, "research_status", "running", "research_queued_at", NOW)),
                new AcceptCase("research done", "date_check", DateCheckMigrationTest::dateCheckFresh,
                        jdbc -> Map.of("research", true, "research_status", "done", "research_queued_at", NOW)),
                new AcceptCase("research failed", "date_check", DateCheckMigrationTest::dateCheckFresh,
                        jdbc -> Map.of("research", true, "research_status", "failed", "research_queued_at", NOW)),
                new AcceptCase("research flag alone", "date_check", DateCheckMigrationTest::dateCheckFresh,
                        jdbc -> Map.of("research", true)));
    }

    @ParameterizedTest
    @MethodSource("accepted")
    void valuesInsideTheDomainAreAccepted(AcceptCase c) {
        JdbcTemplate jdbc = latest(freshDatabase());
        Map<String, Object> values = c.values().apply(jdbc);
        UUID id = c.insert().apply(jdbc, values);
        Map<String, Object> row = jdbc.queryForMap("select * from " + c.table() + " where id = ?", id);
        values.forEach((col, expected) -> assertStored(col, row.get(col), expected));
    }

    private static void assertStored(String col, Object actual, Object expected) {
        if (expected == null) {
            assertThat(actual).as(col).isNull();
        } else if (expected instanceof Number n) {
            assertThat(new BigDecimal(actual.toString())).as(col).isEqualByComparingTo(new BigDecimal(n.toString()));
        } else if (expected instanceof OffsetDateTime t) {
            assertThat(((Timestamp) actual).toInstant()).as(col).isEqualTo(t.toInstant());
        } else {
            assertThat(actual).as(col).isEqualTo(expected);
        }
    }

    // ---- unique keys: the duplicate is rejected, the neighbouring keys are not --------------

    /** The first row is already in; {@code after} checks what the siblings left behind. */
    record UniqueProbe(ThrowingCallable duplicate, List<ThrowingCallable> siblings, Runnable after) {}

    record UniqueCase(String label, String constraint, Function<JdbcTemplate, UniqueProbe> setup) {
        @Override
        public String toString() { return label; }
    }

    static Stream<UniqueCase> uniqueKeys() {
        return Stream.of(
                new UniqueCase("radar run per event, night and milestone", "uq_date_check_radar_run", jdbc -> {
                    UUID org = org(jdbc);
                    UUID event = event(jdbc, org);
                    UUID prev = dateCheck(jdbc, org, Map.of());
                    LocalDate night = LocalDate.of(2026, 10, 15);
                    Map<String, Object> key = Map.of("org_id", org, "event_id", event, "radar_night", night,
                            "radar_milestone", 14, "radar_prev_id", prev);
                    radarCheck(jdbc, key);
                    return new UniqueProbe(() -> radarCheck(jdbc, key),
                            List.of(() -> radarCheck(jdbc, Map.of("org_id", org, "event_id", event, "radar_night", night,
                                    "radar_milestone", 7, "radar_prev_id", prev))), () -> { });
                }),
                // Two writers from the same baseline collide; a run from another baseline is a new key.
                new UniqueCase("radar run key includes the baseline", "uq_date_check_radar_run", jdbc -> {
                    UUID org = org(jdbc);
                    UUID event = event(jdbc, org);
                    UUID prev = dateCheck(jdbc, org, Map.of());
                    UUID otherPrev = dateCheck(jdbc, org, Map.of());
                    LocalDate night = LocalDate.of(2026, 10, 15);
                    radarCheck(jdbc, Map.of("org_id", org, "event_id", event, "radar_night", night, "radar_milestone", 14,
                            "radar_prev_id", prev));
                    return new UniqueProbe(
                            () -> radarCheck(jdbc, Map.of("org_id", org, "event_id", event, "radar_night", night,
                                    "radar_milestone", 14, "radar_prev_id", prev)),
                            List.of(() -> radarCheck(jdbc, Map.of("org_id", org, "event_id", event, "radar_night", night,
                                    "radar_milestone", 14, "radar_prev_id", otherPrev))), () -> { });
                }),
                new UniqueCase("predictor alert per event and day", "uq_predictor_alert_event_day", jdbc -> {
                    UUID event = event(jdbc, org(jdbc));
                    LocalDate day = LocalDate.of(2026, 10, 1);
                    predictorAlert(jdbc, Map.of("event_id", event, "alert_day", day));
                    return new UniqueProbe(
                            () -> predictorAlert(jdbc, Map.of("event_id", event, "alert_day", day, "kind", "band")),
                            List.of(() -> predictorAlert(jdbc, Map.of("event_id", event, "alert_day", day.plusDays(1)))),
                            () -> { });
                }),
                new UniqueCase("reference calendar treats an omitted region as one key", "uq_reference_calendar_entry",
                        jdbc -> {
                            String sql = "insert into reference_calendar (id, country, calendar_date, kind, name,"
                                    + " source_url, synced_at)"
                                    + " values (?, 'FR', DATE '2026-12-25', 'holiday', 'Noel', 'https://example.org', ?)";
                            jdbc.update(sql, UUID.randomUUID(), NOW);
                            assertThat(jdbc.queryForObject("select region from reference_calendar", String.class)).isEmpty();
                            // Same day and name for one region is a different key.
                            return new UniqueProbe(() -> jdbc.update(sql, UUID.randomUUID(), NOW),
                                    List.of(() -> referenceCalendar(jdbc, Map.of("region", "A",
                                            "calendar_date", LocalDate.of(2026, 12, 25), "name", "Noel"))), () -> { });
                        }),
                new UniqueCase("genre week count treats an omitted sub-genre as one key", "uq_genre_week_count", jdbc -> {
                    String sql = "insert into genre_week_count (id, city_key, genre_family, week_start, event_count,"
                            + " updated_at) values (?, 'paris', 'electronic', DATE '2026-10-05', 3, ?)";
                    jdbc.update(sql, UUID.randomUUID(), NOW);
                    assertThat(jdbc.queryForObject("select sub_genre from genre_week_count", String.class)).isEmpty();
                    return new UniqueProbe(() -> jdbc.update(sql, UUID.randomUUID(), NOW),
                            List.of(() -> genreWeekCount(jdbc, Map.of("city_key", "paris", "genre_family", "electronic",
                                    "sub_genre", "techno", "week_start", LocalDate.of(2026, 10, 5)))), () -> { });
                }),
                // Another night, or the same id from another source, is a different key; every allowed value inserts.
                new UniqueCase("open event occurrence per source event and night", "uq_open_event_occurrence", jdbc -> {
                    Map<String, Object> key = Map.of("source", "openagenda", "source_event_id", "13287689",
                            "night_date", LocalDate.of(2026, 10, 16));
                    openEventOccurrence(jdbc, key);
                    return new UniqueProbe(() -> openEventOccurrence(jdbc, key), List.of(
                            () -> openEventOccurrence(jdbc, Map.of("source", "openagenda", "source_event_id", "13287689",
                                    "night_date", LocalDate.of(2026, 10, 17))),
                            () -> openEventOccurrence(jdbc, Map.of("source", "quefaireaparis", "source_event_id", "13287689",
                                    "night_date", LocalDate.of(2026, 10, 16), "licence", "ODbL 1.0")),
                            () -> openEventOccurrence(jdbc, Map.of("source", "datatourisme", "source_event_id", "13287689",
                                    "night_date", LocalDate.of(2026, 10, 16), "licence", "ODbL 1.0"))), () -> {
                        Map<String, Object> defaults = jdbc.queryForMap(
                                "select genre_keys, community, credit from open_event_occurrence where source = 'datatourisme'");
                        assertThat(defaults.get("genre_keys")).isEqualTo("[]");
                        assertThat(defaults.get("community")).isEqualTo(false);
                        assertThat(defaults.get("credit")).isNull();
                    });
                }),
                new UniqueCase("date check date per candidate date", "uq_date_check_date", jdbc -> {
                    UUID dc = dateCheck(jdbc, org(jdbc), Map.of());
                    LocalDate day = LocalDate.of(2026, 11, 14);
                    dateCheckDate(jdbc, Map.of("date_check_id", dc, "candidate_date", day));
                    return new UniqueProbe(() -> dateCheckDate(jdbc, Map.of("date_check_id", dc, "candidate_date", day)),
                            List.of(() -> dateCheckDate(jdbc, Map.of("date_check_id", dc, "candidate_date", day.plusDays(1)))),
                            () -> { });
                }));
    }

    @ParameterizedTest
    @MethodSource("uniqueKeys")
    void uniqueKeysRejectOnlyTheDuplicate(UniqueCase c) {
        JdbcTemplate jdbc = latest(freshDatabase());
        UniqueProbe probe = c.setup().apply(jdbc);
        assertThatThrownBy(probe.duplicate())
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsIgnoringCase(c.constraint()));
        for (int i = 0; i < probe.siblings().size(); i++) {
            assertThatCode(probe.siblings().get(i)).as("sibling " + i).doesNotThrowAnyException();
        }
        probe.after().run();
    }

    // ---- predictor alerts --------------------------------------------------------------

    @Test
    void predictorAlertClaimSqlKeepsTheFirstRow() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID event = event(jdbc, org(jdbc));
        LocalDate day = LocalDate.of(2026, 10, 1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        jdbc.update(PredictorAlertStore.CLAIM_SQL, first, event, day, "band", null, NOW);
        assertThatCode(() -> jdbc.update(PredictorAlertStore.CLAIM_SQL, second, event, day, "radar", null, NOW))
                .doesNotThrowAnyException();
        assertThat(jdbc.queryForObject(PredictorAlertStore.WINNER_SQL, UUID.class, event, day)).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from predictor_alert", Integer.class)).isOne();
    }

    // ---- foreign keys ----------------------------------------------------------------

    @Test
    void predictorAlertFollowsItsEventAndCheck() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID org = org(jdbc);
        UUID gone = event(jdbc, org);
        UUID kept = event(jdbc, org);
        UUID check = dateCheck(jdbc, org, Map.of());
        predictorAlert(jdbc, Map.of("event_id", gone));
        UUID radar = predictorAlert(jdbc, Map.of("event_id", kept, "date_check_id", check));

        jdbc.update("delete from events where id = ?", gone);
        jdbc.update("delete from date_check where id = ?", check);

        assertThat(jdbc.queryForList("select id from predictor_alert", UUID.class)).containsExactly(radar);
        assertThat(jdbc.queryForObject("select date_check_id from predictor_alert where id = ?", UUID.class, radar))
                .isNull();
    }

    @Test
    void orgDeleteCascadesDateCheckTree() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID org = org(jdbc);
        UUID otherOrg = org(jdbc);
        UUID dc = dateCheck(jdbc, org, Map.of());
        UUID dcd = dateCheckDate(jdbc, Map.of("date_check_id", dc));
        finding(jdbc, Map.of("date_check_date_id", dcd));
        UUID keptDc = dateCheck(jdbc, otherOrg, Map.of());

        jdbc.update("delete from organizations where id = ?", org);

        assertThat(jdbc.queryForList("select id from date_check", UUID.class)).containsExactly(keptDc);
        assertThat(jdbc.queryForObject("select count(*) from date_check_date", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from date_check_finding", Integer.class)).isZero();
    }

    @Test
    void deletingDateCheckNullsEventLink() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID org = org(jdbc);
        UUID event = event(jdbc, org);
        UUID dc = dateCheck(jdbc, org, Map.of());
        jdbc.update("update events set date_check_id = ? where id = ?", dc, event);

        jdbc.update("delete from date_check where id = ?", dc);

        assertThat(jdbc.queryForObject("select date_check_id from events where id = ?", UUID.class, event)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from events where id = ?", Integer.class, event)).isOne();
    }

    @Test
    void deletingEventNullsDateCheckLink() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID org = org(jdbc);
        UUID event = event(jdbc, org);
        UUID dc = dateCheck(jdbc, org, Map.of("event_id", event));

        jdbc.update("delete from events where id = ?", event);

        assertThat(jdbc.queryForObject("select event_id from date_check where id = ?", UUID.class, dc)).isNull();
    }

    // ---- row builders ----------------------------------------------------------------

    /** Key/value pairs that may hold nulls; a null value leaves the column out of the insert. */
    private static Map<String, Object> cols(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static UUID insert(JdbcTemplate jdbc, String table, Map<String, Object> defaults, Map<String, Object> overrides) {
        Map<String, Object> cols = new LinkedHashMap<>(defaults);
        cols.putAll(overrides);
        cols.values().removeIf(java.util.Objects::isNull);
        UUID id = (UUID) cols.computeIfAbsent("id", k -> UUID.randomUUID());
        String names = String.join(", ", cols.keySet());
        String marks = cols.keySet().stream().map(k -> "?").collect(Collectors.joining(", "));
        jdbc.update("insert into " + table + " (" + names + ") values (" + marks + ")", cols.values().toArray());
        return id;
    }

    private static UUID org(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organizations (id, name, slug, contact_email, country) values (?, 'Org', ?, 'o@example.com', 'FR')",
                id, "v162-" + id);
        return id;
    }

    private static UUID event(JdbcTemplate jdbc, UUID org) {
        return eventRow(jdbc, Map.of("org_id", org));
    }

    private static UUID eventRow(JdbcTemplate jdbc, Map<String, Object> overrides) {
        UUID org = overrides.containsKey("org_id") ? (UUID) overrides.get("org_id") : org(jdbc);
        UUID user = UUID.randomUUID();
        jdbc.update("insert into users (id, org_id, email, email_lower, role) values (?, ?, ?, ?, 'OWNER')",
                user, org, user + "@example.com", user + "@example.com");
        UUID id = UUID.randomUUID();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", id);
        d.put("org_id", org);
        d.put("slug", "v162-" + id);
        d.put("created_by", user);
        return insert(jdbc, "events", d, overrides);
    }

    /** A valid event-surface ledger row; a key present with a null value leaves that column NULL. */
    private static UUID ledgerRow(JdbcTemplate jdbc, Map<String, Object> overrides) {
        UUID org = overrides.containsKey("org_id") ? (UUID) overrides.get("org_id") : org(jdbc);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("org_id", org);
        if (!overrides.containsKey("event_id")) d.put("event_id", event(jdbc, org));
        d.put("surface", "PRE_PUBLISH");
        d.put("stage", 0);
        d.put("model_id", "m");
        d.put("prompt_version", "1.0.0");
        d.put("input_snapshot_hash", "h");
        return insert(jdbc, "prediction_ledger", d, overrides);
    }

    private static UUID dateCheck(JdbcTemplate jdbc, UUID org, Map<String, Object> overrides) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("org_id", org);
        d.put("created_by", UUID.randomUUID());
        d.put("city", "Paris");
        d.put("country", "FR");
        d.put("genre_family", "electronic");
        d.put("status", "pending");
        d.put("question_bank_version", "qb-1");
        d.put("created_at", NOW);
        d.put("updated_at", NOW);
        return insert(jdbc, "date_check", d, overrides);
    }

    private static UUID dateCheckFresh(JdbcTemplate jdbc, Map<String, Object> overrides) {
        return dateCheck(jdbc, org(jdbc), overrides);
    }

    /** Stands for the id of a check inserted just before the row, so a foreign key cannot fire first. */
    private static final String EXISTING_CHECK = "existing-check";

    /** A valid organizer row; an {@link #EXISTING_CHECK} value is replaced by a real check id. */
    private static UUID organizerCheckWithPrev(JdbcTemplate jdbc, Map<String, Object> overrides) {
        UUID org = org(jdbc);
        Map<String, Object> o = new LinkedHashMap<>(overrides);
        o.replaceAll((k, v) -> EXISTING_CHECK.equals(v) ? dateCheck(jdbc, org, Map.of()) : v);
        return dateCheck(jdbc, org, o);
    }

    /** A valid radar row; nulls in the overrides remove a column. */
    private static UUID radarCheck(JdbcTemplate jdbc, Map<String, Object> overrides) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (!overrides.containsKey("org_id")) d.put("org_id", org(jdbc));
        d.put("origin", "radar");
        d.put("radar_milestone", 14);
        d.put("radar_night", LocalDate.of(2026, 10, 15));
        Map<String, Object> merged = new LinkedHashMap<>(d);
        merged.putAll(overrides);
        merged.values().removeIf(java.util.Objects::isNull);
        UUID org = (UUID) merged.remove("org_id");
        return dateCheck(jdbc, org, merged);
    }

    /** A valid radar alert; an {@link #EXISTING_CHECK} value is replaced by a real check id of the same org. */
    private static UUID predictorAlert(JdbcTemplate jdbc, Map<String, Object> overrides) {
        Map<String, Object> o = new LinkedHashMap<>(overrides);
        UUID event = (UUID) o.computeIfAbsent("event_id", k -> event(jdbc, org(jdbc)));
        UUID org = jdbc.queryForObject("select org_id from events where id = ?", UUID.class, event);
        o.replaceAll((k, v) -> EXISTING_CHECK.equals(v) ? dateCheck(jdbc, org, Map.of()) : v);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("alert_day", LocalDate.of(2026, 10, 1));
        d.put("kind", "radar");
        d.put("created_at", NOW);
        return insert(jdbc, "predictor_alert", d, o);
    }

    private static UUID dateCheckDate(JdbcTemplate jdbc, Map<String, Object> overrides) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (!overrides.containsKey("date_check_id")) d.put("date_check_id", dateCheckFresh(jdbc, Map.of()));
        d.put("candidate_date", LocalDate.of(2026, 11, 14));
        d.put("verdict", "good");
        d.put("risk_score", 2);
        d.put("opp_score", 7);
        d.put("coverage", new BigDecimal("0.750"));
        return insert(jdbc, "date_check_date", d, overrides);
    }

    private static UUID finding(JdbcTemplate jdbc, Map<String, Object> overrides) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (!overrides.containsKey("date_check_date_id")) d.put("date_check_date_id", dateCheckDate(jdbc, Map.of()));
        d.put("question_id", "Q1");
        d.put("kind", "risk");
        d.put("status", "found");
        d.put("strength", 2);
        d.put("weight", 1);
        d.put("source_kind", "web");
        d.put("time_window", "night");
        return insert(jdbc, "date_check_finding", d, overrides);
    }

    private static UUID referenceCalendar(JdbcTemplate jdbc, Map<String, Object> overrides) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("country", "FR");
        d.put("calendar_date", LocalDate.of(2026, 12, 25));
        d.put("kind", "holiday");
        d.put("name", "Noel " + UUID.randomUUID());
        d.put("source_url", "https://example.org");
        d.put("synced_at", NOW);
        return insert(jdbc, "reference_calendar", d, overrides);
    }

    private static UUID predictorJob(JdbcTemplate jdbc, Map<String, Object> overrides) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("kind", "calendar_sync");
        d.put("status", "queued");
        d.put("run_after", NOW);
        d.put("created_at", NOW);
        d.put("updated_at", NOW);
        return insert(jdbc, "predictor_job", d, overrides);
    }

    private static UUID genreWeekCount(JdbcTemplate jdbc, Map<String, Object> overrides) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("city_key", "paris-" + UUID.randomUUID());
        d.put("genre_family", "electronic");
        d.put("week_start", LocalDate.of(2026, 10, 5));
        d.put("event_count", 0);
        d.put("updated_at", NOW);
        return insert(jdbc, "genre_week_count", d, overrides);
    }

    private static UUID openEventOccurrence(JdbcTemplate jdbc, Map<String, Object> overrides) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("source", "openagenda");
        d.put("source_event_id", "e-" + UUID.randomUUID().toString().substring(0, 8));
        d.put("city_key", "lille");
        d.put("night_date", LocalDate.of(2026, 10, 16));
        d.put("title", "Nono La Grinta");
        d.put("title_key", "nono la grinta");
        d.put("url", "https://openagenda.com/fr/ville-de-lille/events/nono-la-grinta");
        d.put("licence", "Licence Ouverte 2.0");
        d.put("synced_at", NOW);
        return insert(jdbc, "open_event_occurrence", d, overrides);
    }
}
