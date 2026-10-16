package com.imin.iminapi.migration;

import com.imin.iminapi.support.SharedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V190 on a database of its own: every existing organization counts as set up from its creation, except one
 * with no Stripe account whose founding owner came through social sign-in, which is asked to finish setup once.
 */
class OrgSetupBackfillMigrationTest {

    private static final Instant CREATED = Instant.parse("2026-03-01T22:30:00Z");

    private DataSource ds;

    @BeforeAll
    static void buildTemplates() {
        SharedPostgres.buildTemplates("189");
    }

    @AfterEach
    void dropDatabase() {
        if (ds != null) SharedPostgres.drop(ds);
    }

    @Test
    void onlyASocialSignUpWithoutStripe_isAskedToFinishSetup_whateverItsCountry() {
        ds = SharedPostgres.migratedDatabase("v190", "189");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Map<String, UUID> orgs = new LinkedHashMap<>();
        Duration later = Duration.ofDays(3);

        orgs.put("email sign-up", org(jdbc, "NL", null));
        founder(jdbc, orgs.get("email sign-up"), "hash", null);

        orgs.put("google sign-up", org(jdbc, "NL", null));
        founder(jdbc, orgs.get("google sign-up"), null, Duration.ZERO);

        orgs.put("google sign-up with Stripe", org(jdbc, "NL", "acct_" + UUID.randomUUID()));
        founder(jdbc, orgs.get("google sign-up with Stripe"), null, Duration.ZERO);

        orgs.put("google sign-up, country changed", org(jdbc, "FR", null));
        founder(jdbc, orgs.get("google sign-up, country changed"), null, Duration.ZERO);

        orgs.put("google sign-up, empty Stripe id", org(jdbc, "NL", ""));
        founder(jdbc, orgs.get("google sign-up, empty Stripe id"), null, Duration.ZERO);

        orgs.put("google sign-up, password set later", org(jdbc, "NL", null));
        founder(jdbc, orgs.get("google sign-up, password set later"), "hash", Duration.ZERO);

        orgs.put("apple sign-up, identity revoked", org(jdbc, "NL", null));
        founder(jdbc, orgs.get("apple sign-up, identity revoked"), null, null);

        orgs.put("email sign-up, google linked later", org(jdbc, "NL", null));
        founder(jdbc, orgs.get("email sign-up, google linked later"), "hash", later);

        orgs.put("email sign-up, invited co-owner on google", org(jdbc, "NL", null));
        founder(jdbc, orgs.get("email sign-up, invited co-owner on google"), "hash", null);
        invitedOwnerOnGoogle(jdbc, orgs.get("email sign-up, invited co-owner on google"), later);

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("190").load().migrate();

        Map<String, Instant> expected = new LinkedHashMap<>();
        expected.put("email sign-up", CREATED);
        expected.put("google sign-up", null);
        expected.put("google sign-up with Stripe", CREATED);
        expected.put("google sign-up, country changed", null);
        expected.put("google sign-up, empty Stripe id", null);
        expected.put("google sign-up, password set later", null);
        expected.put("apple sign-up, identity revoked", null);
        expected.put("email sign-up, google linked later", CREATED);
        expected.put("email sign-up, invited co-owner on google", CREATED);
        Map<String, Instant> actual = new LinkedHashMap<>();
        orgs.forEach((label, id) -> actual.put(label, setupCompletedAt(jdbc, id)));
        assertThat(actual).as("null = asked to finish setup").containsExactlyEntriesOf(expected);
        assertThat(jdbc.queryForObject("select count(*) from organizations where country is null", Integer.class))
                .as("the backfill never clears a country").isZero();
    }

    private static Instant setupCompletedAt(JdbcTemplate jdbc, UUID org) {
        Timestamp at = jdbc.queryForObject("select setup_completed_at from organizations where id = ?",
                Timestamp.class, org);
        return at == null ? null : at.toInstant();
    }

    private static UUID org(JdbcTemplate jdbc, String country, String stripeAccountId) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organizations (id, name, slug, contact_email, country, stripe_account_id, created_at)"
                        + " values (?, 'Org', ?, 'o@example.com', ?, ?, ?)",
                id, "v190-" + id, country, stripeAccountId, Timestamp.from(CREATED));
        return id;
    }

    /** The owner created with the org; {@code identityAfter} null means no provider identity row. */
    private static void founder(JdbcTemplate jdbc, UUID org, String passwordHash, Duration identityAfter) {
        UUID user = user(jdbc, org, passwordHash, CREATED.plusMillis(5));
        if (identityAfter != null) identity(jdbc, user, CREATED.plusMillis(9).plus(identityAfter));
    }

    /** A second owner invited days later with no password, who then signed in with Google. */
    private static void invitedOwnerOnGoogle(JdbcTemplate jdbc, UUID org, Duration after) {
        UUID user = user(jdbc, org, null, CREATED.plus(after));
        identity(jdbc, user, CREATED.plus(after).plusSeconds(30));
    }

    private static UUID user(JdbcTemplate jdbc, UUID org, String passwordHash, Instant created) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, org_id, email, email_lower, role, password_hash, created_at)"
                        + " values (?, ?, ?, ?, 'OWNER', ?, ?)",
                id, org, id + "@example.com", id + "@example.com", passwordHash, Timestamp.from(created));
        return id;
    }

    private static void identity(JdbcTemplate jdbc, UUID user, Instant created) {
        jdbc.update("insert into user_identities (id, user_id, provider, provider_user_id, created_at)"
                + " values (?, ?, 'google', ?, ?)", UUID.randomUUID(), user, "sub-" + user, Timestamp.from(created));
    }
}
