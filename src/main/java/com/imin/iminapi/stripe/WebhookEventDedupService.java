package com.imin.iminapi.stripe;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records Stripe webhook event ids we've successfully accepted so a Stripe
 * retry (or dashboard replay) of the same event id doesn't re-fire the
 * downstream side-effects.
 *
 * <p>Implementation note: we INSERT and rely on the PRIMARY KEY violation,
 * NOT a SELECT-then-INSERT. The SELECT-then-INSERT pattern has a TOCTOU race:
 * two webhook deliveries arriving simultaneously can both observe "not yet
 * processed" before either inserts, and both proceed.
 *
 * <p>JDBC is used (rather than JPA) so a duplicate-key exception does not
 * mark the surrounding {@code @Transactional} as rollback-only — JPA leaves
 * the {@code EntityManager} in a broken state after a constraint violation,
 * while a JDBC {@code DuplicateKeyException} is recoverable. The caller can
 * then simply log + return on a duplicate; on the first successful insert,
 * the row commits with the rest of the handler's writes (or rolls back
 * together if the handler throws, so the Stripe retry re-processes from
 * scratch).
 */
@Component
public class WebhookEventDedupService {

    private static final String INSERT_SQL = """
            INSERT INTO processed_webhook_events (stripe_event_id, event_type)
            VALUES (:eventId, :eventType)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public WebhookEventDedupService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Attempts to claim {@code eventId} for processing.
     *
     * @return {@code true} when this caller successfully recorded the event
     *         (i.e. it has not been processed before in this DB), {@code false}
     *         when an earlier delivery already recorded it (duplicate primary
     *         key — caller should log + ack + return without doing work).
     */
    public boolean tryRecord(String eventId, String eventType) {
        if (eventId == null || eventId.isBlank()) {
            // Defensive: an event with no id can't be deduped — treat as fresh.
            return true;
        }
        try {
            jdbc.update(INSERT_SQL, new MapSqlParameterSource()
                    .addValue("eventId", eventId)
                    .addValue("eventType", eventType == null ? "unknown" : eventType));
            return true;
        } catch (DuplicateKeyException dup) {
            return false;
        }
    }
}
