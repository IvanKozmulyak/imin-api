package com.imin.iminapi.migration;

import com.imin.iminapi.referral.ReferralCode;
import com.imin.iminapi.support.SharedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V199 on a database of its own: every existing order gets a distinct, well-formed crew code, and an insert that
 * does not name the column (the previous build during a rolling deploy, raw SQL, the demo seeds) still succeeds.
 */
class ReferralCodeMigrationTest {

    private static final int EXISTING = 200;

    private DataSource ds;

    // The last migration before V199 in this tree; Flyway refuses a target version that has no file.
    @BeforeAll
    static void buildTemplates() {
        SharedPostgres.buildTemplates("192");
    }

    @AfterEach
    void dropDatabase() {
        if (ds != null) SharedPostgres.drop(ds);
    }

    @Test
    void existingOrdersGetDistinctCodes_andInsertsWithoutTheColumnStillWork() {
        ds = // The last migration before V199 in this tree; Flyway needs a target that exists.
        ds = SharedPostgres.migratedDatabase("v199", "192");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        UUID org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        jdbc.update("insert into organizations (id, name, slug, contact_email, country) values (?, 'Org', ?, 'o@example.com', 'FR')",
                org, "v199-" + org);
        jdbc.update("insert into users (id, org_id, email, email_lower, role) values (?, ?, 'u@example.com', 'u@example.com', 'OWNER')",
                user, org);
        jdbc.update("insert into events (id, org_id, slug, created_by) values (?, ?, ?, ?)", event, org, "v199-" + event, user);
        // One statement for every row, as the demo seeds insert theirs.
        jdbc.update("insert into orders (id, token, event_id, org_id, email, total_minor, currency, payment_method) "
                + "select gen_random_uuid(), 'tok-' || g, ?, ?, 'b' || g || '@example.com', 1500, 'EUR', 'card' "
                + "from generate_series(1, ?) g", event, org, EXISTING);

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("199").load().migrate();

        List<String> codes = jdbc.queryForList("select referral_code from orders", String.class);
        assertThat(codes).hasSize(EXISTING).doesNotContainNull().doesNotHaveDuplicates()
                .allMatch(ReferralCode::isWellFormed, "well-formed crew code");

        UUID late = UUID.randomUUID();
        jdbc.update("insert into orders (id, token, event_id, org_id, email, total_minor, currency, payment_method) "
                + "values (?, ?, ?, ?, 'late@example.com', 1500, 'EUR', 'card')", late, "tok-" + late, event, org);
        String lateCode = jdbc.queryForObject("select referral_code from orders where id = ?", String.class, late);
        assertThat(lateCode).matches(ReferralCode::isWellFormed, "well-formed crew code");
        assertThat(codes).doesNotContain(lateCode);
    }
}
