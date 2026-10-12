package com.imin.iminapi.cancellation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code event_cancellations} and {@code event_cancellation_orders}. Every write is one targeted, conditional
 * statement: there is no entity, so no full-row save can revert a state another writer just set.
 */
@Repository
public class EventCancellationRepository {

    /** States a pass still reads; the others are terminal. */
    static final String NON_TERMINAL = "('pending','in_progress','triggered','waiting_dispute','needs_attention')";
    private static final String ACTIVE_REFUND = "('REQUESTED','PENDING','SUCCEEDED')";
    private static final String WITHHOLDING_DISPUTE = "('open','lost')";

    private final JdbcTemplate jdbc;

    public EventCancellationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- cancellation and enrollment ----------

    public boolean exists(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM event_cancellations WHERE event_id = ?)", Boolean.class, eventId));
    }

    public void insertCancellation(UUID eventId, UUID orgId, UUID actorUserId, Instant now) {
        jdbc.update("""
                INSERT INTO event_cancellations (event_id, org_id, cancelled_by_user_id, cancelled_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING
                """, eventId, orgId, actorUserId, ts(now));
    }

    /** Enrolls every order of one cancelled event not enrolled yet. */
    public int enroll(UUID eventId, Instant now) {
        return jdbc.update("""
                INSERT INTO event_cancellation_orders (order_id, event_id, state, created_at, updated_at)
                SELECT o.id, ec.event_id, 'pending', ?, ?
                  FROM orders o JOIN event_cancellations ec ON ec.event_id = o.event_id
                 WHERE ec.event_id = ?
                ON CONFLICT (order_id) DO NOTHING
                """, ts(now), ts(now), eventId);
    }

    /** Orders paid after their event was cancelled: fulfilment does not check the event status. */
    public int enrollAll(Instant now) {
        // ponytail: re-reads every cancelled event's orders each tick; add a watermark if cancellations pile up.
        return jdbc.update("""
                INSERT INTO event_cancellation_orders (order_id, event_id, state, created_at, updated_at)
                SELECT o.id, ec.event_id, 'pending', ?, ?
                  FROM orders o JOIN event_cancellations ec ON ec.event_id = o.event_id
                 WHERE NOT EXISTS (SELECT 1 FROM event_cancellation_orders i WHERE i.order_id = o.id)
                ON CONFLICT (order_id) DO NOTHING
                """, ts(now), ts(now));
    }

    public long countOrders(UUID eventId, boolean testMode) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM orders WHERE event_id = ? AND test_mode = ?",
                Long.class, eventId, testMode);
        return n == null ? 0 : n;
    }

    // ---------- refund pass ----------

    /** Non-terminal items free to claim: never-read first, then the longest unread. */
    public List<UUID> findDue(Instant now, int limit) {
        return jdbc.queryForList("SELECT order_id FROM event_cancellation_orders WHERE state IN " + NON_TERMINAL
                + " AND (claimed_until IS NULL OR claimed_until < ?)"
                + " ORDER BY claimed_until NULLS FIRST, created_at, order_id LIMIT ?", UUID.class, ts(now), limit);
    }

    /** Leases a non-terminal item until {@code until}; false when another pass holds it or it is terminal. */
    public boolean claim(UUID orderId, Instant now, Instant until) {
        return jdbc.update("UPDATE event_cancellation_orders SET claimed_until = ?, updated_at = ?"
                + " WHERE order_id = ? AND state IN " + NON_TERMINAL
                + " AND (claimed_until IS NULL OR claimed_until < ?)", ts(until), ts(now), orderId, ts(now)) == 1;
    }

    /** Facts of one enrolled order. Dispute statuses are stored lowercase, refund statuses uppercase. */
    public Optional<ItemFacts> loadFacts(UUID orderId) {
        List<ItemFacts> rows = jdbc.query("""
                SELECT i.order_id, i.event_id, i.state, i.attempts, ec.cancelled_by_user_id,
                       i.notify_outcome IS NOT NULL AS notice_started,
                       o.total_minor, o.application_fee_minor, o.test_mode, o.stripe_payment_intent_id,
                       (SELECT count(*) FROM disputes d WHERE d.order_id = o.id AND d.status = 'open') AS open_disputes,
                       (SELECT coalesce(sum(d.amount_minor), 0) FROM disputes d
                         WHERE d.order_id = o.id AND d.status = 'lost') AS lost_minor,
                       (SELECT coalesce(sum(r.amount_minor), 0) FROM refunds r
                         WHERE r.order_id = o.id AND r.status IN """ + ACTIVE_REFUND + """
                       ) AS active_minor,
                       (SELECT coalesce(sum(r.application_fee_refund_minor), 0) FROM refunds r
                         WHERE r.order_id = o.id AND r.status IN """ + ACTIVE_REFUND + """
                       ) AS active_fee_minor,
                       EXISTS (SELECT 1 FROM refunds r WHERE r.order_id = o.id AND r.status = 'REQUESTED') AS has_requested,
                       EXISTS (SELECT 1 FROM refunds r WHERE r.order_id = o.id AND r.status = 'PENDING') AS has_pending
                  FROM event_cancellation_orders i
                  JOIN orders o ON o.id = i.order_id
                  JOIN event_cancellations ec ON ec.event_id = i.event_id
                 WHERE i.order_id = ?
                """, (rs, n) -> new ItemFacts(
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("event_id", UUID.class),
                        CancellationItemState.fromWire(rs.getString("state")),
                        rs.getInt("attempts"),
                        rs.getObject("cancelled_by_user_id", UUID.class),
                        rs.getLong("total_minor"),
                        rs.getLong("application_fee_minor"),
                        rs.getBoolean("test_mode"),
                        rs.getString("stripe_payment_intent_id"),
                        rs.getLong("open_disputes"),
                        rs.getLong("lost_minor"),
                        rs.getLong("active_minor"),
                        rs.getLong("active_fee_minor"),
                        rs.getBoolean("has_requested"),
                        rs.getBoolean("has_pending"),
                        rs.getBoolean("notice_started")), orderId);
        return rows.stream().findFirst();
    }

    /** Writes a derived state; a null refund id or error keeps the stored one. */
    public void writeState(UUID orderId, CancellationItemState state, UUID refundId, String error, Instant now) {
        jdbc.update("""
                UPDATE event_cancellation_orders
                   SET state = ?, last_refund_id = coalesce(?, last_refund_id),
                       last_error = coalesce(?, last_error), updated_at = ?
                 WHERE order_id = ?
                """, state.wire(), refundId, truncate(error), ts(now), orderId);
    }

    /** A refused attempt: counts it and backs off 15 min per attempt. Returns the new attempt count. */
    public int recordRefusal(UUID orderId, String error, Instant now) {
        Integer attempts = jdbc.queryForObject("""
                UPDATE event_cancellation_orders
                   SET attempts = attempts + 1, last_error = ?,
                       claimed_until = CAST(? AS timestamptz) + (attempts + 1) * INTERVAL '15 minutes',
                       updated_at = ?
                 WHERE order_id = ?
                RETURNING attempts
                """, Integer.class, truncate(error), ts(now), ts(now), orderId);
        return attempts == null ? 0 : attempts;
    }

    /** Frees the lease so the next pass picks the item up first. */
    public void releaseClaim(UUID orderId) {
        jdbc.update("UPDATE event_cancellation_orders SET claimed_until = NULL WHERE order_id = ?", orderId);
    }

    /** The newest refund on the order that moved or is moving money. */
    public Optional<UUID> latestActiveRefundId(UUID orderId) {
        return jdbc.queryForList("SELECT id FROM refunds WHERE order_id = ? AND status IN ('PENDING','SUCCEEDED')"
                + " ORDER BY created_at DESC, id LIMIT 1", UUID.class, orderId).stream().findFirst();
    }

    // ---------- buyer notice ----------

    /**
     * Claims up to {@code limit} un-notified items of running-mode orders (another mode's buyer is never mailed),
     * counting the attempt. A claim older than {@code staleBefore} is taken again.
     */
    public List<NotifyClaim> claimNotify(int limit, Instant now, Instant staleBefore, boolean testMode) {
        return jdbc.query("""
                UPDATE event_cancellation_orders i
                   SET notify_claimed_at = ?, notify_attempts = i.notify_attempts + 1, updated_at = ?
                 WHERE i.notify_outcome IS NULL AND (i.notify_claimed_at IS NULL OR i.notify_claimed_at < ?)
                   AND i.order_id IN (
                       SELECT c.order_id FROM event_cancellation_orders c JOIN orders o ON o.id = c.order_id
                        WHERE c.notify_outcome IS NULL
                          AND (c.notify_claimed_at IS NULL OR c.notify_claimed_at < ?)
                          AND o.test_mode = ?
                        ORDER BY c.created_at, c.order_id
                        LIMIT ?
                        FOR UPDATE OF c SKIP LOCKED)
                RETURNING i.order_id, i.notify_attempts
                """, (rs, n) -> new NotifyClaim(rs.getObject("order_id", UUID.class), rs.getInt("notify_attempts")),
                ts(now), ts(now), ts(staleBefore), ts(staleBefore), testMode, limit);
    }

    /** What the notice says. {@code earlierRefundedMinor} leaves out the cancellation's own refunds. */
    public Optional<NotifyFacts> notifyFacts(UUID orderId) {
        List<NotifyFacts> rows = jdbc.query("""
                SELECT o.email, o.buyer_locale, o.total_minor, o.currency, e.name AS event_name,
                       org.contact_email,
                       (SELECT coalesce(sum(r.amount_minor), 0) FROM refunds r
                         WHERE r.order_id = o.id AND r.status IN """ + ACTIVE_REFUND + """
                           AND r.reason <> 'EVENT_CANCELLED') AS earlier_minor,
                       (SELECT count(*) FROM disputes d
                         WHERE d.order_id = o.id AND d.status IN """ + WITHHOLDING_DISPUTE + """
                       ) AS disputes
                  FROM orders o
                  JOIN events e ON e.id = o.event_id
                  JOIN organizations org ON org.id = o.org_id
                 WHERE o.id = ?
                """, (rs, n) -> new NotifyFacts(orderId, rs.getString("email"), rs.getString("buyer_locale"),
                        rs.getLong("total_minor"), rs.getString("currency"), rs.getString("event_name"),
                        rs.getString("contact_email"), rs.getLong("earlier_minor"), rs.getLong("disputes")), orderId);
        return rows.stream().findFirst();
    }

    public void markNotified(UUID orderId, String outcome, Instant now) {
        jdbc.update("UPDATE event_cancellation_orders SET notify_outcome = ?, notified_at = ?, updated_at = ?"
                + " WHERE order_id = ? AND notify_outcome IS NULL", outcome, ts(now), ts(now), orderId);
    }

    public void releaseNotify(UUID orderId) {
        jdbc.update("UPDATE event_cancellation_orders SET notify_claimed_at = NULL WHERE order_id = ?"
                + " AND notify_outcome IS NULL", orderId);
    }

    // ---------- reads ----------

    /** One row per order of the event in the running mode, with its refund and dispute sums. */
    public List<PreviewRow> previewRows(UUID eventId, boolean testMode) {
        return jdbc.query("""
                SELECT o.currency, o.total_minor, o.application_fee_minor,
                       (SELECT coalesce(sum(r.amount_minor), 0) FROM refunds r
                         WHERE r.order_id = o.id AND r.status IN """ + ACTIVE_REFUND + """
                       ) AS active_minor,
                       (SELECT coalesce(sum(r.application_fee_refund_minor), 0) FROM refunds r
                         WHERE r.order_id = o.id AND r.status IN """ + ACTIVE_REFUND + """
                       ) AS active_fee_minor,
                       (SELECT count(*) FROM disputes d
                         WHERE d.order_id = o.id AND d.status IN """ + WITHHOLDING_DISPUTE + """
                       ) AS disputes
                  FROM orders o
                 WHERE o.event_id = ? AND o.test_mode = ?
                """, (rs, n) -> new PreviewRow(rs.getString("currency"), rs.getLong("total_minor"),
                        rs.getLong("application_fee_minor"), rs.getLong("active_minor"),
                        rs.getLong("active_fee_minor"), rs.getLong("disputes")), eventId, testMode);
    }

    /** Buckets of the event's running-mode orders; empty when the event has no cancellation. */
    public Optional<StatusRow> status(UUID eventId, Instant overdueBefore, boolean testMode) {
        List<StatusRow> rows = jdbc.query("""
                SELECT ec.cancelled_at,
                       count(o.id) AS total,
                       count(o.id) FILTER (WHERE i.state IN ('triggered','refunded')) AS refunded,
                       count(o.id) FILTER (WHERE i.state IN ('pending','in_progress')) AS processing,
                       count(o.id) FILTER (WHERE i.state = 'waiting_dispute') AS waiting,
                       count(o.id) FILTER (WHERE i.state = 'dispute_lost') AS settled,
                       count(o.id) FILTER (WHERE i.state = 'nothing_to_refund') AS free,
                       count(o.id) FILTER (WHERE i.state = 'needs_attention') AS attention,
                       count(o.id) FILTER (WHERE i.state IN ('pending','in_progress','needs_attention')
                                           AND ec.cancelled_at < ?) AS overdue,
                       count(o.id) FILTER (WHERE i.notify_outcome = 'sent') AS emailed
                  FROM event_cancellations ec
                  LEFT JOIN event_cancellation_orders i ON i.event_id = ec.event_id
                  LEFT JOIN orders o ON o.id = i.order_id AND o.test_mode = ?
                 WHERE ec.event_id = ?
                 GROUP BY ec.cancelled_at
                """, (rs, n) -> new StatusRow(instant(rs, "cancelled_at"), rs.getLong("total"),
                        rs.getLong("refunded"), rs.getLong("processing"), rs.getLong("waiting"),
                        rs.getLong("settled"), rs.getLong("free"), rs.getLong("attention"),
                        rs.getLong("overdue"), rs.getLong("emailed")),
                ts(overdueBefore), testMode, eventId);
        return rows.stream().findFirst();
    }

    /** Per event: running-mode items in {@code states} of a cancellation older than {@code cutoff}. */
    public List<EventCount> countByEventCancelledBefore(String states, Instant cutoff, boolean testMode) {
        return jdbc.query("""
                SELECT i.event_id, count(*) AS n
                  FROM event_cancellation_orders i
                  JOIN event_cancellations ec ON ec.event_id = i.event_id
                  JOIN orders o ON o.id = i.order_id
                 WHERE i.state IN """ + states + """
                   AND ec.cancelled_at < ? AND o.test_mode = ?
                 GROUP BY i.event_id
                """, (rs, n) -> new EventCount(rs.getObject("event_id", UUID.class), rs.getLong("n")),
                ts(cutoff), testMode);
    }

    // ---------- rows ----------

    public record ItemFacts(UUID orderId, UUID eventId, CancellationItemState state, int attempts,
                            UUID cancelledByUserId, long totalMinor, long feeMinor, boolean testMode,
                            String paymentIntentId, long openDisputes, long lostDisputedMinor,
                            long activeRefundedMinor, long activeFeeRefundedMinor,
                            boolean hasRequestedRefund, boolean hasPendingRefund, boolean noticeStarted) {}

    public record NotifyClaim(UUID orderId, int attempts) {}

    public record NotifyFacts(UUID orderId, String email, String locale, long totalMinor, String currency,
                              String eventName, String organizerContact, long earlierRefundedMinor,
                              long disputes) {}

    public record PreviewRow(String currency, long totalMinor, long feeMinor, long activeRefundedMinor,
                             long activeFeeRefundedMinor, long disputes) {}

    public record StatusRow(Instant cancelledAt, long total, long refunded, long processing, long waitingOnDispute,
                            long settledByBank, long free, long needsAttention, long overdue, long emailed) {}

    public record EventCount(UUID eventId, long count) {}

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }
}
