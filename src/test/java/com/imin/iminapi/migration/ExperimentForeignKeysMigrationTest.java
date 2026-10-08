package com.imin.iminapi.migration;

import com.imin.iminapi.support.SharedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V154 on a database of its own: stops at V153, seeds rows the new foreign keys would reject next to rows they accept,
 * then applies V154. It must drop exactly the orphans and leave the keys enforced.
 */
class ExperimentForeignKeysMigrationTest {

    private DataSource ds;

    @BeforeAll
    static void buildTemplates() {
        SharedPostgres.buildTemplates("153", "latest");
    }

    /** A new database of this test's own, migrated to {@code target}. */
    private DataSource databaseAt(String target) {
        ds = SharedPostgres.migratedDatabase("v154", target);
        return ds;
    }

    @AfterEach
    void dropDatabase() {
        if (ds != null) SharedPostgres.drop(ds);
    }

    private static void migrate(DataSource ds, String target) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target(target).load().migrate();
    }

    @Test
    void anEmptyDatabase_migratesAndEnforcesTheKeys() {
        JdbcTemplate jdbc = new JdbcTemplate(databaseAt("latest"));

        assertThatThrownBy(() -> jdbc.update("insert into audience_experiments (id, org_id, event_id, arm, members, seed)"
                + " values (?, ?, ?, 'launch', 0, 1)", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void orphanRows_areDropped_andEveryValidRowSurvives() {
        DataSource ds = databaseAt("153");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        jdbc.update("insert into organizations (id, name, slug, contact_email, country) values (?, 'Org', ?, 'o@example.com', 'FR')",
                org, "v154-" + org);
        jdbc.update("insert into users (id, org_id, email, email_lower, role) values (?, ?, 'u@example.com', 'u@example.com', 'OWNER')",
                user, org);
        jdbc.update("insert into events (id, org_id, slug, created_by) values (?, ?, ?, ?)", event, org, "v154-" + event, user);

        UUID plan = plan(jdbc, org, event);
        UUID orphanPlan = plan(jdbc, UUID.randomUUID(), event);
        UUID segment = planSegment(jdbc, plan);
        UUID orphanPlanSegment = planSegment(jdbc, orphanPlan);

        UUID kept = experiment(jdbc, org, event, plan, segment, "launch");
        UUID keptWithoutPlan = experiment(jdbc, org, event, null, null, "holdout");
        UUID noEvent = experiment(jdbc, org, UUID.randomUUID(), plan, segment, "d3");
        UUID noPlan = experiment(jdbc, org, event, UUID.randomUUID(), null, "launch");
        UUID noPlanSegment = experiment(jdbc, org, event, plan, UUID.randomUUID(), "d3");
        UUID onOrphanPlan = experiment(jdbc, org, event, orphanPlan, orphanPlanSegment, "launch");

        UUID keptMember = membership(jdbc, org);
        UUID droppedMember = membership(jdbc, org);
        assign(jdbc, kept, keptMember);
        assign(jdbc, noEvent, droppedMember);

        migrate(ds, "latest");

        assertThat(jdbc.queryForList("select id from audience_plans", UUID.class)).containsExactly(plan);
        assertThat(jdbc.queryForList("select id from audience_plan_segments", UUID.class)).containsExactly(segment);
        assertThat(jdbc.queryForList("select id from audience_experiments", UUID.class))
                .containsExactlyInAnyOrder(kept, keptWithoutPlan)
                .doesNotContain(noEvent, noPlan, noPlanSegment, onOrphanPlan);
        assertThat(jdbc.queryForList("select membership_id from audience_assignments", UUID.class))
                .containsExactly(keptMember);
        assertThat(jdbc.queryForList("select holdout_pct from audience_experiments", Integer.class))
                .containsOnlyNulls();

        // The keys hold from now on.
        assertThatThrownBy(() -> jdbc.update("update audience_plans set org_id = ? where id = ?", UUID.randomUUID(), plan))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> experiment(jdbc, org, event, UUID.randomUUID(), null, "launch"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> experiment(jdbc, org, event, plan, segment, "launch"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static UUID plan(JdbcTemplate jdbc, UUID org, UUID event) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into audience_plans (id, org_id, event_id, mode, capacity, target_pct, target_tickets,
                    tickets_per_order, excluded_segments, mailable, verdict, gap_low, gap_high, reach_needed,
                    small_groups_not_shown, other_genre_invited, other_genre_held_back, exclusions, today_date,
                    event_date, launch_date, event_started, actions, logic_version, priors_version,
                    calibration_version, inputs_hash)
                values (?, ?, ?, 'warm', 100, 85, 85, 1.6, '[]', 10, 'weak', 0, 0, '{}', 0, false, 0, '{}',
                    DATE '2026-09-01', DATE '2026-10-01', DATE '2026-09-01', false, '[]', 1, 1, 1, 'hash')""",
                id, org, event);
        return id;
    }

    private static UUID planSegment(JdbcTemplate jdbc, UUID plan) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into audience_plan_segments (id, plan_id, position, class, genre_fit, mailable, rate_low,
                    rate_mid, rate_high, tickets_per_order, expected_low, expected_mid, expected_high, confidence, reason)
                values (?, ?, 0, 'loyal', 'same', 10, 0.1, 0.2, 0.3, 1.6, 1, 2, 3, 'prior', '{}')""", id, plan);
        return id;
    }

    private static UUID experiment(JdbcTemplate jdbc, UUID org, UUID event, UUID plan, UUID segment, String arm) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into audience_experiments (id, org_id, event_id, plan_id, plan_segment_id, arm, members, seed)"
                + " values (?, ?, ?, ?, ?, ?, 1, 1)", id, org, event, plan, segment, arm);
        return id;
    }

    private static UUID membership(JdbcTemplate jdbc, UUID org) {
        UUID consumer = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        jdbc.update("insert into consumers (consumer_id, normalized_email) values (?, ?)", consumer,
                "v154-" + consumer + "@example.com");
        jdbc.update("insert into memberships (membership_id, org_id, consumer_id) values (?, ?, ?)", membership, org, consumer);
        return membership;
    }

    private static void assign(JdbcTemplate jdbc, UUID experiment, UUID membership) {
        jdbc.update("insert into audience_assignments (experiment_id, membership_id, arm) values (?, ?, 'launch')",
                experiment, membership);
    }
}
