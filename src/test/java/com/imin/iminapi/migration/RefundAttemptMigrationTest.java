package com.imin.iminapi.migration;

import com.imin.iminapi.support.SharedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V179 on a database seeded at V178: only claims of refunds that moved no money are released. */
class RefundAttemptMigrationTest {

    private DataSource ds;
    private JdbcTemplate jdbc;
    private UUID org;
    private UUID event;
    private UUID order;
    private UUID failedIssued;
    private UUID canceledIssued;
    private UUID failedRefunded;
    private UUID pending;
    private UUID succeeded;

    @BeforeEach
    void seedAtV178ThenMigrate() {
        ds = SharedPostgres.migratedDatabase("refund_attempts", "178");
        jdbc = new JdbcTemplate(ds);

        org = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        event = UUID.randomUUID();
        order = UUID.randomUUID();
        jdbc.update("insert into organizations (id, name, slug, contact_email, country) values (?, 'Org', ?, 'o@example.com', 'FR')",
                org, "refund-" + org);
        jdbc.update("insert into users (id, org_id, email, email_lower, role) values (?, ?, 'u@example.com', 'u@example.com', 'OWNER')",
                user, org);
        jdbc.update("insert into events (id, org_id, slug, created_by) values (?, ?, ?, ?)", event, org, "refund-" + event, user);
        jdbc.update("insert into orders (id, token, event_id, org_id, email, total_minor, currency, payment_method) "
                + "values (?, ?, ?, ?, 'b@example.com', 7500, 'eur', 'card')", order, "tok-" + order, event, org);
        failedIssued = claim("FAILED", "issued");
        canceledIssued = claim("CANCELED", "issued");
        failedRefunded = claim("FAILED", "refunded");
        pending = claim("PENDING", "issued");
        succeeded = claim("SUCCEEDED", "refunded");

        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    @AfterEach
    void dropDatabase() {
        if (ds != null) SharedPostgres.drop(ds);
    }

    /** A refund row in {@code status} claiming one ticket in {@code ticketState}; returns the refund id. */
    private UUID claim(String status, String ticketState) {
        UUID ticket = UUID.randomUUID();
        UUID refund = UUID.randomUUID();
        jdbc.update("insert into tickets (id, token, order_id, event_id, tier_id, tier_name, state) "
                + "values (?, ?, ?, ?, ?, 'GA', ?)", ticket, "tkt-" + ticket, order, event, UUID.randomUUID(), ticketState);
        jdbc.update("insert into refunds (id, order_id, stripe_refund_id, stripe_payment_intent_id, amount_minor, currency, "
                + "reason, status, idempotency_key) values (?, ?, ?, 'pi_1', 1500, 'eur', 'OTHER', ?, ?)",
                refund, order, "re_" + refund, status, "k-" + refund);
        jdbc.update("insert into refund_tickets (refund_id, ticket_id) values (?, ?)", refund, ticket);
        return refund;
    }

    private List<UUID> claimed() {
        return jdbc.queryForList("select refund_id from refund_tickets", UUID.class);
    }

    @Test
    void v179ReleasesOnlyDeadClaims() {
        assertThat(claimed()).containsExactlyInAnyOrder(failedRefunded, pending, succeeded)
                .doesNotContain(failedIssued, canceledIssued);
        assertThat(jdbc.queryForList("select stripe_attempts from refunds", Integer.class)).containsOnly(0);
        assertThat(jdbc.queryForList("select stripe_attempt_at from refunds", Object.class)).containsOnlyNulls();
    }
}
