package com.imin.iminapi.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V162 on a fresh database per test: the date-check tables, the ledger CHECKs and the events link; V165 open events; V167 radar runs. */
abstract class DateCheckMigrationScenarios {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 9, 30, 12, 0, 0, 0, ZoneOffset.UTC);

    /** A new, empty database per call. */
    abstract DataSource freshDatabase();

    private static void migrate(DataSource ds, String target) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load().migrate();
    }

    private static JdbcTemplate latest(DataSource ds) {
        migrate(ds, "latest");
        return new JdbcTemplate(ds);
    }

    // ---- prediction_ledger ---------------------------------------------------------

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
    void ledgerAcceptsNullEventIdOnlyForDateCheckSurface() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID org = org(jdbc);
        UUID dateCheck = dateCheck(jdbc, org, Map.of());
        UUID id = ledger(jdbc, null, org, "DATE_CHECK", dateCheck);
        assertThat(jdbc.queryForObject("select event_id from prediction_ledger where id = ?", UUID.class, id)).isNull();
    }

    @Test
    void ledgerRejectsNullEventIdForEventSurfaces() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID org = org(jdbc);
        for (String surface : List.of("PRE_PUBLISH", "REFORECAST", "ACTIONS")) {
            assertThatThrownBy(() -> ledger(jdbc, null, org, surface, null))
                    .as(surface)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_prediction_ledger_event_or_date_check");
        }
    }

    @Test
    void ledgerAcceptsDateCheckRowWithEvent() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID org = org(jdbc);
        UUID event = event(jdbc, org);
        UUID dateCheck = dateCheck(jdbc, org, Map.of());
        UUID id = ledger(jdbc, event, org, "DATE_CHECK", dateCheck);
        assertThat(jdbc.queryForObject("select event_id from prediction_ledger where id = ?", UUID.class, id)).isEqualTo(event);
    }

    @Test
    void ledgerRejectsDateCheckRowWithoutDateCheckId() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID org = org(jdbc);
        UUID event = event(jdbc, org);
        assertThatThrownBy(() -> ledger(jdbc, event, org, "DATE_CHECK", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_prediction_ledger_date_check_id");
        // An event surface needs no date check.
        assertThatCode(() -> ledger(jdbc, event, org, "PRE_PUBLISH", null)).doesNotThrowAnyException();
    }

    // ---- named CHECKs ----------------------------------------------------------------

    /** One CHECK, the table it guards, a valid row builder and the single override that breaks it. */
    record CheckCase(String constraint, BiFunction<JdbcTemplate, Map<String, Object>, UUID> insert,
                     Map<String, Object> bad) {
        @Override
        public String toString() { return constraint; }
    }

    static Stream<CheckCase> checks() {
        return Stream.of(
                new CheckCase("ck_date_check_status", DateCheckMigrationScenarios::dateCheckFresh, Map.of("status", "queued")),
                new CheckCase("ck_date_check_date_verdict", DateCheckMigrationScenarios::dateCheckDate, Map.of("verdict", "maybe")),
                new CheckCase("ck_date_check_date_risk", DateCheckMigrationScenarios::dateCheckDate, Map.of("risk_score", 11)),
                new CheckCase("ck_date_check_date_opp", DateCheckMigrationScenarios::dateCheckDate, Map.of("opp_score", -1)),
                new CheckCase("ck_date_check_date_coverage", DateCheckMigrationScenarios::dateCheckDate,
                        Map.of("coverage", new BigDecimal("1.001"))),
                new CheckCase("ck_date_check_finding_kind", DateCheckMigrationScenarios::finding, Map.of("kind", "neutral")),
                new CheckCase("ck_date_check_finding_status", DateCheckMigrationScenarios::finding, Map.of("status", "maybe")),
                new CheckCase("ck_date_check_finding_source_kind", DateCheckMigrationScenarios::finding,
                        Map.of("source_kind", "bogus")),
                new CheckCase("ck_date_check_finding_window", DateCheckMigrationScenarios::finding, Map.of("time_window", "year")),
                new CheckCase("ck_date_check_finding_strength", DateCheckMigrationScenarios::finding,
                        Map.of("source_kind", "structured", "strength", 4)),
                new CheckCase("ck_date_check_finding_weight", DateCheckMigrationScenarios::finding, Map.of("weight", 0)),
                new CheckCase("ck_date_check_finding_soft_strength", DateCheckMigrationScenarios::finding,
                        Map.of("source_kind", "web", "strength", 3)),
                new CheckCase("ck_date_check_finding_stop_source", DateCheckMigrationScenarios::finding,
                        Map.of("source_kind", "organizer", "stop_factor", true)),
                new CheckCase("ck_reference_calendar_kind", DateCheckMigrationScenarios::referenceCalendar, Map.of("kind", "party")),
                new CheckCase("ck_reference_calendar_range", DateCheckMigrationScenarios::referenceCalendar,
                        Map.of("end_date", LocalDate.of(2026, 12, 24))),
                new CheckCase("ck_predictor_job_status", DateCheckMigrationScenarios::predictorJob, Map.of("status", "paused")),
                new CheckCase("ck_genre_week_count_nonneg", DateCheckMigrationScenarios::genreWeekCount, Map.of("event_count", -1)),
                new CheckCase("ck_org_connector_kind", DateCheckMigrationScenarios::orgConnector, Map.of("kind", "facebook")),
                new CheckCase("ck_open_event_occurrence_source", DateCheckMigrationScenarios::openEventOccurrence,
                        Map.of("source", "other")),
                new CheckCase("ck_open_event_occurrence_licence", DateCheckMigrationScenarios::openEventOccurrence,
                        Map.of("licence", "CC-BY")),
                new CheckCase("ck_date_check_origin", DateCheckMigrationScenarios::dateCheckFresh, Map.of("origin", "bot")),
                new CheckCase("ck_date_check_radar_milestone", DateCheckMigrationScenarios::radarCheck,
                        Map.of("radar_milestone", 5)),
                new CheckCase("ck_date_check_radar_shape", DateCheckMigrationScenarios::radarCheck,
                        radarWithout("radar_night")),
                new CheckCase("ck_date_check_radar_shape", DateCheckMigrationScenarios::dateCheckFresh,
                        Map.of("radar_milestone", 14)),
                new CheckCase("ck_date_check_radar_shape", DateCheckMigrationScenarios::radarCheck,
                        radarWithout("radar_milestone")),
                new CheckCase("ck_date_check_radar_shape", DateCheckMigrationScenarios::dateCheckFresh,
                        Map.of("radar_night", LocalDate.of(2026, 10, 15))),
                new CheckCase("ck_date_check_radar_shape", DateCheckMigrationScenarios::organizerCheckWithPrev,
                        Map.of("radar_prev_id", EXISTING_CHECK)));
    }

    private static Map<String, Object> radarWithout(String column) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(column, null);
        return m;
    }

    @ParameterizedTest
    @MethodSource("checks")
    void namedChecksRejectOutOfDomainValues(CheckCase c) {
        JdbcTemplate jdbc = latest(freshDatabase());
        assertThatCode(() -> c.insert().apply(jdbc, Map.of())).as("valid sibling").doesNotThrowAnyException();
        assertThatThrownBy(() -> c.insert().apply(jdbc, c.bad()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsIgnoringCase(c.constraint()));
    }

    @Test
    void findingStrengthThreeAndStopFactorAllowedForStructuredSources() {
        JdbcTemplate jdbc = latest(freshDatabase());
        assertThatCode(() -> finding(jdbc, Map.of("source_kind", "structured", "strength", 3, "stop_factor", true)))
                .doesNotThrowAnyException();
        assertThatCode(() -> finding(jdbc, Map.of("source_kind", "internal", "strength", 3, "stop_factor", true)))
                .doesNotThrowAnyException();
    }

    @Test
    void findingAcceptsOrganizerInputAsASoftSource() {
        JdbcTemplate jdbc = latest(freshDatabase());
        assertThatCode(() -> finding(jdbc, Map.of("source_kind", "input", "strength", 2))).doesNotThrowAnyException();
        assertThatThrownBy(() -> finding(jdbc, Map.of("source_kind", "input", "strength", 3)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsIgnoringCase("ck_date_check_finding_soft_strength"));
        assertThatThrownBy(() -> finding(jdbc, Map.of("source_kind", "input", "stop_factor", true)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsIgnoringCase("ck_date_check_finding_stop_source"));
    }

    // ---- radar runs --------------------------------------------------------------------

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
    void radarRunUniquePerEventNightMilestone() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID org = org(jdbc);
        UUID event = event(jdbc, org);
        UUID prev = dateCheck(jdbc, org, Map.of());
        LocalDate night = LocalDate.of(2026, 10, 15);
        Map<String, Object> key = Map.of("org_id", org, "event_id", event, "radar_night", night, "radar_milestone", 14,
                "radar_prev_id", prev);
        radarCheck(jdbc, key);
        assertThatThrownBy(() -> radarCheck(jdbc, key))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsIgnoringCase("uq_date_check_radar_run"));
        assertThatCode(() -> radarCheck(jdbc, Map.of("org_id", org, "event_id", event, "radar_night", night,
                "radar_milestone", 7, "radar_prev_id", prev))).doesNotThrowAnyException();
    }

    @Test
    void radarRunKeyIncludesTheBaseline() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID org = org(jdbc);
        UUID event = event(jdbc, org);
        UUID prev = dateCheck(jdbc, org, Map.of());
        UUID otherPrev = dateCheck(jdbc, org, Map.of());
        LocalDate night = LocalDate.of(2026, 10, 15);
        radarCheck(jdbc, Map.of("org_id", org, "event_id", event, "radar_night", night, "radar_milestone", 14,
                "radar_prev_id", prev));
        // Two writers from the same baseline collide; a run from another baseline is a new key.
        assertThatThrownBy(() -> radarCheck(jdbc, Map.of("org_id", org, "event_id", event, "radar_night", night,
                "radar_milestone", 14, "radar_prev_id", prev)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsIgnoringCase("uq_date_check_radar_run"));
        assertThatCode(() -> radarCheck(jdbc, Map.of("org_id", org, "event_id", event, "radar_night", night,
                "radar_milestone", 14, "radar_prev_id", otherPrev))).doesNotThrowAnyException();
    }

    // ---- unique keys -----------------------------------------------------------------

    @Test
    void referenceCalendarUniqueTreatsOmittedRegionAsOneKey() {
        JdbcTemplate jdbc = latest(freshDatabase());
        String sql = "insert into reference_calendar (id, country, calendar_date, kind, name, source_url, synced_at)"
                + " values (?, 'FR', DATE '2026-12-25', 'holiday', 'Noel', 'https://example.org', ?)";
        jdbc.update(sql, UUID.randomUUID(), NOW);
        assertThat(jdbc.queryForObject("select region from reference_calendar", String.class)).isEmpty();
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), NOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsIgnoringCase("uq_reference_calendar_entry"));
        // Same day and name for one region is a different key.
        assertThatCode(() -> referenceCalendar(jdbc, Map.of("region", "A", "calendar_date", LocalDate.of(2026, 12, 25),
                "name", "Noel"))).doesNotThrowAnyException();
    }

    @Test
    void genreWeekCountUniqueTreatsOmittedSubGenreAsOneKey() {
        JdbcTemplate jdbc = latest(freshDatabase());
        String sql = "insert into genre_week_count (id, city_key, genre_family, week_start, event_count, updated_at)"
                + " values (?, 'paris', 'electronic', DATE '2026-10-05', 3, ?)";
        jdbc.update(sql, UUID.randomUUID(), NOW);
        assertThat(jdbc.queryForObject("select sub_genre from genre_week_count", String.class)).isEmpty();
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), NOW))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsIgnoringCase("uq_genre_week_count"));
        assertThatCode(() -> genreWeekCount(jdbc, Map.of("city_key", "paris", "genre_family", "electronic",
                "sub_genre", "techno", "week_start", LocalDate.of(2026, 10, 5)))).doesNotThrowAnyException();
    }

    @Test
    void openEventOccurrenceUniquePerSourceEventAndNight() {
        JdbcTemplate jdbc = latest(freshDatabase());
        Map<String, Object> key = Map.of("source", "openagenda", "source_event_id", "13287689",
                "night_date", LocalDate.of(2026, 10, 16));
        openEventOccurrence(jdbc, key);
        assertThatThrownBy(() -> openEventOccurrence(jdbc, key))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsIgnoringCase("uq_open_event_occurrence"));
        // Another night, or the same id from another source, is a different key; every allowed value inserts.
        assertThatCode(() -> openEventOccurrence(jdbc, Map.of("source", "openagenda", "source_event_id", "13287689",
                "night_date", LocalDate.of(2026, 10, 17)))).doesNotThrowAnyException();
        for (String source : List.of("quefaireaparis", "datatourisme")) {
            assertThatCode(() -> openEventOccurrence(jdbc, Map.of("source", source, "source_event_id", "13287689",
                    "night_date", LocalDate.of(2026, 10, 16), "licence", "ODbL 1.0"))).as(source).doesNotThrowAnyException();
        }
        Map<String, Object> defaults = jdbc.queryForMap(
                "select genre_keys, community, credit from open_event_occurrence where source = 'datatourisme'");
        assertThat(defaults.get("genre_keys")).isEqualTo("[]");
        assertThat(defaults.get("community")).isEqualTo(false);
        assertThat(defaults.get("credit")).isNull();
    }

    @Test
    void dateCheckDateUniquePerCandidateDate() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID dc = dateCheck(jdbc, org(jdbc), Map.of());
        LocalDate day = LocalDate.of(2026, 11, 14);
        dateCheckDate(jdbc, Map.of("date_check_id", dc, "candidate_date", day));
        assertThatThrownBy(() -> dateCheckDate(jdbc, Map.of("date_check_id", dc, "candidate_date", day)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsIgnoringCase("uq_date_check_date"));
        assertThatCode(() -> dateCheckDate(jdbc, Map.of("date_check_id", dc, "candidate_date", day.plusDays(1))))
                .doesNotThrowAnyException();
    }

    // ---- foreign keys ----------------------------------------------------------------

    @Test
    void orgDeleteCascadesDateCheckTreeAndConnectors() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID org = org(jdbc);
        UUID otherOrg = org(jdbc);
        UUID dc = dateCheck(jdbc, org, Map.of());
        UUID dcd = dateCheckDate(jdbc, Map.of("date_check_id", dc));
        finding(jdbc, Map.of("date_check_date_id", dcd));
        orgConnector(jdbc, Map.of("org_id", org));
        UUID keptDc = dateCheck(jdbc, otherOrg, Map.of());
        UUID keptConnector = orgConnector(jdbc, Map.of("org_id", otherOrg));

        jdbc.update("delete from organizations where id = ?", org);

        assertThat(jdbc.queryForList("select id from date_check", UUID.class)).containsExactly(keptDc);
        assertThat(jdbc.queryForObject("select count(*) from date_check_date", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from date_check_finding", Integer.class)).isZero();
        assertThat(jdbc.queryForList("select id from org_connector", UUID.class)).containsExactly(keptConnector);
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

    @Test
    void eventsDateCheckIdMustReferenceADateCheck() {
        JdbcTemplate jdbc = latest(freshDatabase());
        UUID event = event(jdbc, org(jdbc));
        assertThatThrownBy(() -> jdbc.update("update events set date_check_id = ? where id = ?", UUID.randomUUID(), event))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).containsIgnoringCase("fk_events_date_check"));
    }

    // ---- row builders ----------------------------------------------------------------

    private static UUID insert(JdbcTemplate jdbc, String table, Map<String, Object> defaults, Map<String, Object> overrides) {
        Map<String, Object> cols = new LinkedHashMap<>(defaults);
        cols.putAll(overrides);
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
        UUID user = UUID.randomUUID();
        jdbc.update("insert into users (id, org_id, email, email_lower, role) values (?, ?, ?, ?, 'OWNER')",
                user, org, user + "@example.com", user + "@example.com");
        UUID event = UUID.randomUUID();
        jdbc.update("insert into events (id, org_id, slug, created_by) values (?, ?, ?, ?)", event, org, "v162-" + event, user);
        return event;
    }

    private static UUID ledger(JdbcTemplate jdbc, UUID event, UUID org, String surface, UUID dateCheck) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into prediction_ledger (id, event_id, org_id, surface, stage, model_id, prompt_version,
                    input_snapshot_hash, date_check_id)
                values (?, ?, ?, ?, 0, 'm', '1.0.0', 'h', ?)""", id, event, org, surface, dateCheck);
        return id;
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

    private static UUID orgConnector(JdbcTemplate jdbc, Map<String, Object> overrides) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (!overrides.containsKey("org_id")) d.put("org_id", org(jdbc));
        d.put("kind", "shotgun");
        d.put("token_enc", "enc");
        d.put("connected_by", UUID.randomUUID());
        d.put("connected_at", NOW);
        return insert(jdbc, "org_connector", d, overrides);
    }
}
