package com.imin.iminapi.marketing;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.webhook.ProviderEventDedupService;
import com.imin.iminapi.service.retention.PersonalDataRetentionSweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code provider_events.payload} held the raw Resend and Bird webhook bodies —
 * recipient addresses and originating phone numbers — and nothing ever read it:
 * the complaint-rate breaker counts rows, it does not open them. It was never
 * purged and no erasure request reached it.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class ProviderEventPayloadRetentionTest {

    @Autowired ProviderEventDedupService dedup;
    @Autowired JdbcTemplate jdbc;
    @Autowired PersonalDataRetentionSweeper sweeper;

    @Test
    void claiming_an_event_stores_the_ids_but_not_the_body() {
        String eventId = "svix_" + UUID.randomUUID();

        assertThat(dedup.tryClaim("resend", eventId, "msg_abc", null, null, "email.delivered")).isTrue();

        var row = jdbc.queryForMap(
                "SELECT type, provider_message_id, payload FROM provider_events "
                + "WHERE provider_event_id = ?", eventId);
        assertThat(row.get("payload")).isNull();
        // The fields that ARE read still land.
        assertThat(row.get("type")).isEqualTo("email.delivered");
        assertThat(row.get("provider_message_id")).isEqualTo("msg_abc");
    }

    @Test
    void the_dedup_contract_is_unchanged() {
        String eventId = "svix_" + UUID.randomUUID();
        assertThat(dedup.tryClaim("resend", eventId, "m", null, null, "email.opened")).isTrue();
        assertThat(dedup.tryClaim("resend", eventId, "m", null, null, "email.opened")).isFalse();
    }

    /** Rows written before this change still hold the bodies; the sweep clears them. */
    @Test
    void the_sweeper_clears_bodies_already_on_disk() {
        String eventId = "legacy_" + UUID.randomUUID();
        jdbc.update("INSERT INTO provider_events (id, provider, provider_event_id, type, payload) "
                    + "VALUES (random_uuid(), 'resend', ?, 'email.bounced', ?)",
                eventId, "{\"to\":\"ada@example.com\"}");

        sweeper.clearRetainedWebhookBodies();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM provider_events WHERE provider_event_id = ? AND payload IS NOT NULL",
                Integer.class, eventId)).isZero();
        // The row itself survives — it is the idempotency claim.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM provider_events WHERE provider_event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
    }
}
