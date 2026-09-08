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

    /**
     * {@code payload} is deliberately absent from the column list, so it stays
     * NULL. It used to carry the raw Resend and Bird bodies — recipient
     * addresses and originating phone numbers — and <b>nothing has ever read
     * it</b>: the complaint-rate breaker counts rows, it does not open them. The
     * 2026-09 legal audit called that indefinite retention of personal data with
     * no purpose and no purge. Not writing it is smaller and safer than a purge
     * job for the same reason: a body that was never stored cannot be missed by
     * an erasure request. The columns kept are exactly the ones something reads —
     * the ids, the type, and the timestamp.
     */
    private static final String INSERT_SQL = """
            INSERT INTO provider_events
              (id, provider, provider_event_id, provider_message_id,
               campaign_id, recipient_id, type, occurred_at)
            VALUES
              (:id, :provider, :eventId, :messageId,
               :campaignId, :recipientId, :type, now())
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
                            UUID campaignId, UUID recipientId, String type) {
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
                    .addValue("type", type));
            return true;
        } catch (DuplicateKeyException dup) {
            return false;
        }
    }
}
