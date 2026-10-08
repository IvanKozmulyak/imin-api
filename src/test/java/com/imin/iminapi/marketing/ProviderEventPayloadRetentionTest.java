package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.webhook.ProviderEventDedupService;
import com.imin.iminapi.service.retention.PersonalDataRetentionSweeper;
import com.imin.iminapi.support.IminIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code provider_events.payload} held the raw Resend and Bird webhook bodies —
 * recipient addresses and originating phone numbers — and nothing ever read it:
 * the complaint-rate breaker counts rows, it does not open them. It was never
 * purged and no erasure request reached it.
 */
@IminIntegrationTest
class ProviderEventPayloadRetentionTest {

    @Autowired ProviderEventDedupService dedup;
    @Autowired JdbcTemplate jdbc;
    @Autowired PersonalDataRetentionSweeper sweeper;

    private final List<String> eventIds = new ArrayList<>();

    @AfterEach
    void deleteOwnClaims() {
        for (String id : eventIds) jdbc.update("delete from provider_events where provider_event_id = ?", id);
    }

    private String own(String prefix) {
        String id = prefix + UUID.randomUUID();
        eventIds.add(id);
        return id;
    }

    @Test
    void claiming_an_event_stores_the_ids_but_not_the_body() {
        String eventId = own("svix_");

        assertThat(dedup.tryClaim("resend", eventId, "msg_abc", null, null, "email.delivered")).isTrue();

        var row = jdbc.queryForMap(
                "SELECT type, provider_message_id, payload FROM provider_events "
                + "WHERE provider_event_id = ?", eventId);
        assertThat(row.get("payload")).isNull();
        // The fields that ARE read still land.
        assertThat(row.get("type")).isEqualTo("email.delivered");
        assertThat(row.get("provider_message_id")).isEqualTo("msg_abc");
    }

    /** Rows written before this change still hold the bodies; the sweep clears them. */
    @Test
    void the_sweeper_clears_bodies_already_on_disk() {
        String eventId = own("legacy_");
        jdbc.update("INSERT INTO provider_events (id, provider, provider_event_id, type, payload) "
                    + "VALUES (?, 'resend', ?, 'email.bounced', ?)",
                UUID.randomUUID(), eventId, "{\"to\":\"ada@example.com\"}");
        // One context serves the run: release the lock an earlier sweep left, or this call is a no-op.
        jdbc.update("update shedlock set lock_until = locked_at where name = ?",
                "PersonalDataRetentionSweeper.webhookBodies");

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
