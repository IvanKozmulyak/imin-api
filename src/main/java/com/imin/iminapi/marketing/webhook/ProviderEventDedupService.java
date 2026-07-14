package com.imin.iminapi.marketing.webhook;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Idempotent claim on (provider, provider_event_id). Same contract as
 * {@link com.imin.iminapi.stripe.WebhookEventDedupService}: INSERT and rely
 * on the UNIQUE violation, NOT SELECT-then-INSERT (that has a TOCTOU race).
 * JDBC — not JPA — so a duplicate-key exception does not poison the
 * surrounding {@code @Transactional}; the caller logs + skips on false, and
 * on the first successful claim the row commits with the projector's writes
 * (or rolls back together if projection throws, so a Resend retry re-runs).
 */
@Component
public class ProviderEventDedupService {

    private static final String INSERT_SQL = """
            INSERT INTO provider_events
              (id, provider, provider_event_id, provider_message_id,
               campaign_id, recipient_id, type, payload, occurred_at)
            VALUES
              (:id, :provider, :eventId, :messageId,
               :campaignId, :recipientId, :type, :payload, now())
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ProviderEventDedupService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return {@code true} when this caller recorded the event for the first
     *         time (proceed with projection); {@code false} on replay (skip).
     */
    public boolean tryClaim(String provider, String eventId, String messageId,
                            UUID campaignId, UUID recipientId, String type, String payload) {
        if (eventId == null || eventId.isBlank()) {
            return true; // can't dedup a missing id — treat as fresh
        }
        try {
            jdbc.update(INSERT_SQL, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("provider", provider)
                    .addValue("eventId", eventId)
                    .addValue("messageId", messageId)
                    .addValue("campaignId", campaignId)
                    .addValue("recipientId", recipientId)
                    .addValue("type", type)
                    .addValue("payload", payload));
            return true;
        } catch (DuplicateKeyException dup) {
            return false;
        }
    }
}
