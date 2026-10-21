package com.imin.iminapi.migration;

import com.imin.iminapi.support.SharedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V194 on a database seeded at V192: every row that left the queue gets a send time, the rest stay null. */
class CampaignRecipientSentAtMigrationTest {

    private static final List<String> LEFT = List.of(
            "sent", "delivered", "opened", "clicked", "bounced", "complained", "unsubscribed");
    private static final List<String> NOT_LEFT = List.of("pending", "failed", "skipped");
    private static final Instant DELIVERED = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant LAST_EVENT = Instant.parse("2026-10-02T10:00:00Z");

    private DataSource ds;
    private JdbcTemplate jdbc;
    private UUID campaign;

    @BeforeEach
    void seedAtV192() {
        ds = SharedPostgres.migratedDatabase("recipient_sent_at", "192");
        jdbc = new JdbcTemplate(ds);
        campaign = UUID.randomUUID();
        jdbc.update("insert into campaigns (id, org_id, channel, name) values (?, ?, 'email', 'C')",
                campaign, UUID.randomUUID());
        for (String status : LEFT) {
            row(status + "-delivered", status, DELIVERED);
            row(status + "-only-event", status, null);
        }
        for (String status : NOT_LEFT) {
            row(status + "-delivered", status, DELIVERED);
            row(status + "-only-event", status, null);
        }
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    }

    @AfterEach
    void dropDatabase() {
        if (ds != null) SharedPostgres.drop(ds);
    }

    private void row(String email, String status, Instant deliveredAt) {
        jdbc.update("insert into campaign_recipients (id, campaign_id, email, status, delivered_at, last_event_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), campaign, email, status,
                deliveredAt == null ? null : Timestamp.from(deliveredAt), Timestamp.from(LAST_EVENT));
    }

    private Instant sentAt(String email) {
        Timestamp t = jdbc.queryForObject("select sent_at from campaign_recipients where email = ?", Timestamp.class, email);
        return t == null ? null : t.toInstant();
    }

    @Test
    void everyRowThatLeftTheQueueGetsADeliveryOrLastEventTime() {
        for (String status : LEFT) {
            assertThat(sentAt(status + "-delivered")).as(status + " with delivered_at").isEqualTo(DELIVERED);
            assertThat(sentAt(status + "-only-event")).as(status + " without delivered_at").isEqualTo(LAST_EVENT);
        }
    }

    @Test
    void rowsThatNeverLeftStayNull() {
        for (String status : NOT_LEFT) {
            assertThat(sentAt(status + "-delivered")).as(status).isNull();
            assertThat(sentAt(status + "-only-event")).as(status).isNull();
        }
    }
}
