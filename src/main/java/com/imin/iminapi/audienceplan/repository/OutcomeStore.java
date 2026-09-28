package com.imin.iminapi.audienceplan.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Native SQL for after-event outcomes and calibration (H2 and Postgres; no nullable parameter). Reads people only to
 * count them: every row it returns or writes is an aggregate.
 */
@Repository
public class OutcomeStore {

    /** Scope of a calibration row. */
    public static final String SCOPE_ORG = "org";
    public static final String SCOPE_IMIN = "imin";
    /** {@code org_id} of the IMIN-wide calibration rows. */
    public static final UUID IMIN_ORG = new UUID(0L, 0L);

    /** The email left the provider for this person; bounced, failed, skipped and pending did not. */
    static final String SENT_STATUSES = "('sent', 'delivered', 'opened', 'clicked', 'complained', 'unsubscribed')";

    /** A real order of this member for the experiment's event, placed after assignment and before door close. */
    private static final String ORDER_MATCH = " o.org_id = e.org_id AND o.event_id = e.event_id"
            + " AND o.email_normalized = c.normalized_email AND o.test_mode = FALSE"
            + " AND o.created_at >= a.assigned_at AND o.created_at <= :doorClose";

    private static final String LIVE_TICKET = " t.state NOT IN ('refunded', 'revoked')";

    /** Cancelled or soft-deleted events are neither collected nor calibrated. */
    private static final String COUNTED_EVENT = " ev.deleted_at IS NULL AND ev.status <> 'CANCELLED'";

    static final String TALLIES = "SELECT x.experiment_id, COUNT(*) AS members, SUM(x.sent) AS sent,"
            + " SUM(x.bought) AS bought, SUM(x.sent * x.bought) AS sent_bought, SUM(x.tickets) AS tickets,"
            + " SUM(x.attended) AS attended, SUM(x.unsubscribed) AS unsubscribed, SUM(x.complained) AS complained"
            + " FROM (SELECT a.experiment_id,"
            + " CASE WHEN EXISTS (SELECT 1 FROM campaign_recipients r WHERE r.campaign_id = e.campaign_id"
            + "   AND r.membership_id = a.membership_id AND r.status IN " + SENT_STATUSES + ") THEN 1 ELSE 0 END AS sent,"
            + " CASE WHEN EXISTS (SELECT 1 FROM orders o JOIN tickets t ON t.order_id = o.id WHERE" + ORDER_MATCH
            + "   AND" + LIVE_TICKET + ") THEN 1 ELSE 0 END AS bought,"
            + " (SELECT COUNT(*) FROM orders o JOIN tickets t ON t.order_id = o.id WHERE" + ORDER_MATCH
            + "   AND" + LIVE_TICKET + ") AS tickets,"
            + " CASE WHEN EXISTS (SELECT 1 FROM orders o JOIN tickets t ON t.order_id = o.id WHERE" + ORDER_MATCH
            + "   AND t.state = 'redeemed') THEN 1 ELSE 0 END AS attended,"
            + " CASE WHEN EXISTS (SELECT 1 FROM consent_records cr WHERE cr.membership_id = a.membership_id"
            + "   AND cr.channel = 'email' AND cr.status = 'unsubscribed'"
            + "   AND cr.occurred_at > a.assigned_at AND cr.occurred_at <= :until) THEN 1 ELSE 0 END AS unsubscribed,"
            + " CASE WHEN EXISTS (SELECT 1 FROM campaign_recipients r JOIN campaigns cp ON cp.id = r.campaign_id"
            + "   WHERE cp.event_id = e.event_id AND r.membership_id = a.membership_id AND r.status = 'complained'"
            + "   AND r.last_event_at <= :until) THEN 1 ELSE 0 END AS complained"
            + " FROM audience_assignments a"
            + " JOIN audience_experiments e ON e.id = a.experiment_id"
            + " JOIN memberships m ON m.membership_id = a.membership_id"
            + " JOIN consumers c ON c.consumer_id = m.consumer_id"
            + " WHERE e.org_id = :orgId AND e.event_id = :eventId) x"
            + " GROUP BY x.experiment_id";

    static final String NEW_GUESTS = "SELECT COUNT(DISTINCT o.email_normalized) FROM orders o"
            + " WHERE o.org_id = :orgId AND o.event_id = :eventId AND o.test_mode = FALSE"
            + " AND o.email_normalized IS NOT NULL AND o.created_at <= :doorClose"
            + " AND EXISTS (SELECT 1 FROM tickets t WHERE t.order_id = o.id AND" + LIVE_TICKET + ")"
            + " AND NOT EXISTS (SELECT 1 FROM memberships m JOIN consumers c ON c.consumer_id = m.consumer_id"
            + "   WHERE m.org_id = o.org_id AND c.normalized_email = o.email_normalized"
            + "   AND m.created_at < :firstAssignedAt)";

    private final NamedParameterJdbcTemplate jdbc;

    public OutcomeStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One arm of an event's experiments with its plan segment (all segment fields null when it has none). */
    public record Experiment(UUID id, String arm, int members, UUID campaignId, UUID planSegmentId, String classKey,
                             String genreFit, Double rateLow, Double rateMid, Double rateHigh, String confidence,
                             Instant createdAt) {}

    /** Counts of one arm; {@code sentBought} = bought among the members who were sent the email. */
    public record Tally(int members, int sent, int bought, int sentBought, int tickets, int attended,
                        int unsubscribed, int complained) {
        public static final Tally ZERO = new Tally(0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** A stored arm outcome. */
    public record StoredArm(UUID experimentId, UUID planSegmentId, String classKey, String genreFit, String arm,
                            String phase, Tally tally, Instant computedAt) {}

    /** {@code newGuests} is null when no assignment was left to date the first invitation. */
    public record StoredEvent(String phase, Integer newGuests, Instant doorClosedAt, Instant computedAt) {}

    /** An event with experiments, for the collector's scan. */
    public record EventRef(UUID eventId, UUID orgId, Instant startsAt, Instant endsAt) {}

    /** A calibration aggregate; {@code orgId} is {@link #IMIN_ORG} for scope imin. */
    public record Calibration(String scope, UUID orgId, String classKey, String genreFit, String arm, int n,
                              int bought, int events) {}

    // ── reads ──────────────────────────────────────────────────────────────

    public List<Experiment> experiments(UUID orgId, UUID eventId) {
        return jdbc.query("SELECT e.id, e.arm, e.members, e.campaign_id, e.plan_segment_id, e.created_at,"
                        + " s.class, s.genre_fit, s.rate_low, s.rate_mid, s.rate_high, s.confidence"
                        + " FROM audience_experiments e LEFT JOIN audience_plan_segments s ON s.id = e.plan_segment_id"
                        + " WHERE e.org_id = :orgId AND e.event_id = :eventId ORDER BY e.created_at, e.id",
                new MapSqlParameterSource().addValue("orgId", orgId).addValue("eventId", eventId),
                (rs, i) -> new Experiment(uuid(rs, "id"), rs.getString("arm"), rs.getInt("members"),
                        uuid(rs, "campaign_id"), uuid(rs, "plan_segment_id"), rs.getString("class"),
                        rs.getString("genre_fit"), dbl(rs, "rate_low"), dbl(rs, "rate_mid"), dbl(rs, "rate_high"),
                        rs.getString("confidence"), instant(rs, "created_at")));
    }

    /**
     * Per experiment of the event: orders count until {@code doorClose}, unsubscribes and complaints until
     * {@code until}. Experiments whose members were all erased are absent.
     */
    public Map<UUID, Tally> tallies(UUID orgId, UUID eventId, Instant doorClose, Instant until) {
        Map<UUID, Tally> out = new HashMap<>();
        jdbc.query(TALLIES, new MapSqlParameterSource()
                .addValue("orgId", orgId).addValue("eventId", eventId)
                .addValue("doorClose", ts(doorClose)).addValue("until", ts(until)), rs -> {
                    out.put(uuid(rs, "experiment_id"), new Tally(rs.getInt("members"), rs.getInt("sent"),
                            rs.getInt("bought"), rs.getInt("sent_bought"), rs.getInt("tickets"),
                            rs.getInt("attended"), rs.getInt("unsubscribed"), rs.getInt("complained")));
                });
        return out;
    }

    /** The first time anyone was assigned for this event; empty when no assignment is left. */
    public Optional<Instant> firstAssignedAt(UUID orgId, UUID eventId) {
        List<Instant> at = jdbc.query("SELECT MIN(a.assigned_at) AS first_at FROM audience_assignments a"
                        + " JOIN audience_experiments e ON e.id = a.experiment_id"
                        + " WHERE e.org_id = :orgId AND e.event_id = :eventId",
                new MapSqlParameterSource().addValue("orgId", orgId).addValue("eventId", eventId),
                (rs, i) -> instant(rs, "first_at"));
        return at.isEmpty() ? Optional.empty() : Optional.ofNullable(at.get(0));
    }

    public int newGuests(UUID orgId, UUID eventId, Instant firstAssignedAt, Instant doorClose) {
        Integer n = jdbc.queryForObject(NEW_GUESTS, new MapSqlParameterSource()
                .addValue("orgId", orgId).addValue("eventId", eventId)
                .addValue("firstAssignedAt", ts(firstAssignedAt)).addValue("doorClose", ts(doorClose)), Integer.class);
        return n == null ? 0 : n;
    }

    /** The earliest future send time of a scheduled campaign among {@code campaignIds}. */
    public Optional<Instant> nextScheduled(UUID orgId, List<UUID> campaignIds, Instant after) {
        if (campaignIds.isEmpty()) return Optional.empty();
        List<Instant> at = jdbc.query("SELECT MIN(scheduled_at) AS next_at FROM campaigns"
                        + " WHERE org_id = :orgId AND id IN (:ids) AND status = 'scheduled' AND scheduled_at > :after",
                new MapSqlParameterSource().addValue("orgId", orgId).addValue("ids", campaignIds)
                        .addValue("after", ts(after)),
                (rs, i) -> instant(rs, "next_at"));
        return at.isEmpty() ? Optional.empty() : Optional.ofNullable(at.get(0));
    }

    public List<StoredArm> storedArms(UUID orgId, UUID eventId) {
        return jdbc.query("SELECT * FROM audience_outcomes WHERE org_id = :orgId AND event_id = :eventId",
                new MapSqlParameterSource().addValue("orgId", orgId).addValue("eventId", eventId),
                (rs, i) -> new StoredArm(uuid(rs, "experiment_id"), uuid(rs, "plan_segment_id"),
                        rs.getString("class"), rs.getString("genre_fit"), rs.getString("arm"), rs.getString("phase"),
                        new Tally(rs.getInt("members"), rs.getInt("sent"), rs.getInt("bought"),
                                rs.getInt("sent_bought"), rs.getInt("tickets"), rs.getInt("attended"),
                                rs.getInt("unsubscribed"), rs.getInt("complained")),
                        instant(rs, "computed_at")));
    }

    public Optional<StoredEvent> storedEvent(UUID orgId, UUID eventId) {
        List<StoredEvent> rows = jdbc.query("SELECT phase, new_guests, door_closed_at, computed_at"
                        + " FROM audience_event_outcomes WHERE org_id = :orgId AND event_id = :eventId",
                new MapSqlParameterSource().addValue("orgId", orgId).addValue("eventId", eventId),
                (rs, i) -> new StoredEvent(rs.getString("phase"), integer(rs, "new_guests"),
                        instant(rs, "door_closed_at"), instant(rs, "computed_at")));
        return rows.stream().findFirst();
    }

    /** Events with experiments that started in {@code [from, to]}, neither cancelled nor deleted. */
    public List<EventRef> eventsWithExperiments(Instant from, Instant to) {
        return jdbc.query("SELECT ev.id, ev.org_id, ev.starts_at, ev.ends_at FROM events ev"
                        + " WHERE ev.starts_at >= :from AND ev.starts_at <= :to AND" + COUNTED_EVENT
                        + " AND EXISTS (SELECT 1 FROM audience_experiments e WHERE e.event_id = ev.id"
                        + "   AND e.org_id = ev.org_id)"
                        + " ORDER BY ev.starts_at, ev.id",
                new MapSqlParameterSource().addValue("from", ts(from)).addValue("to", ts(to)),
                (rs, i) -> new EventRef(uuid(rs, "id"), uuid(rs, "org_id"), instant(rs, "starts_at"),
                        instant(rs, "ends_at")));
    }

    public List<Calibration> calibration() {
        return jdbc.query("SELECT scope, org_id, class, genre_fit, arm, n, bought, events FROM response_calibration"
                        + " ORDER BY scope, org_id, class, genre_fit, arm",
                (rs, i) -> new Calibration(rs.getString("scope"), uuid(rs, "org_id"), rs.getString("class"),
                        rs.getString("genre_fit"), rs.getString("arm"), rs.getInt("n"), rs.getInt("bought"),
                        rs.getInt("events")));
    }

    /**
     * Org-scope sums per class × fit × arm over stored outcomes with a plan segment: invitation arms count the members
     * who were sent the email and those of them who bought; the holdout counts its members. Outcomes of events
     * cancelled or deleted after collection are left out.
     */
    public List<Calibration> orgCalibrationFromOutcomes() {
        return jdbc.query("SELECT o.org_id, o.class, o.genre_fit, o.arm,"
                        + " SUM(CASE WHEN o.arm = 'holdout' THEN o.members ELSE o.sent END) AS n,"
                        + " SUM(CASE WHEN o.arm = 'holdout' THEN o.bought ELSE o.sent_bought END) AS b,"
                        + " COUNT(DISTINCT o.event_id) AS events"
                        + " FROM audience_outcomes o JOIN events ev ON ev.id = o.event_id AND ev.org_id = o.org_id"
                        + " WHERE o.class IS NOT NULL AND o.genre_fit IS NOT NULL AND" + COUNTED_EVENT
                        + " GROUP BY o.org_id, o.class, o.genre_fit, o.arm",
                (rs, i) -> new Calibration(SCOPE_ORG, uuid(rs, "org_id"), rs.getString("class"),
                        rs.getString("genre_fit"), rs.getString("arm"), rs.getInt("n"), rs.getInt("b"),
                        rs.getInt("events")));
    }

    // ── writes (caller's transaction) ──────────────────────────────────────

    /** Replaces the event's stored outcome: every arm row and the event row. */
    public void replaceOutcome(UUID orgId, UUID eventId, String phase, List<Experiment> arms, Map<UUID, Tally> tallies,
                               Integer newGuests, Instant doorClose, Instant computedAt) {
        MapSqlParameterSource key = new MapSqlParameterSource().addValue("eventId", eventId);
        jdbc.update("DELETE FROM audience_outcomes WHERE event_id = :eventId", key);
        jdbc.update("DELETE FROM audience_event_outcomes WHERE event_id = :eventId", key);
        for (Experiment x : arms) {
            Tally t = tallies.getOrDefault(x.id(), Tally.ZERO);
            jdbc.update("INSERT INTO audience_outcomes (experiment_id, org_id, event_id, plan_segment_id, class,"
                            + " genre_fit, arm, phase, members, sent, bought, sent_bought, tickets, attended,"
                            + " unsubscribed, complained, computed_at) VALUES (:experimentId, :orgId, :eventId,"
                            + " :planSegmentId, :classKey, :genreFit, :arm, :phase, :members, :sent, :bought,"
                            + " :sentBought, :tickets, :attended, :unsubscribed, :complained, :computedAt)",
                    new MapSqlParameterSource()
                            .addValue("experimentId", x.id()).addValue("orgId", orgId).addValue("eventId", eventId)
                            .addValue("planSegmentId", x.planSegmentId(), java.sql.Types.OTHER)
                            .addValue("classKey", x.classKey(), java.sql.Types.VARCHAR)
                            .addValue("genreFit", x.genreFit(), java.sql.Types.VARCHAR)
                            .addValue("arm", x.arm()).addValue("phase", phase)
                            .addValue("members", t.members()).addValue("sent", t.sent())
                            .addValue("bought", t.bought()).addValue("sentBought", t.sentBought())
                            .addValue("tickets", t.tickets()).addValue("attended", t.attended())
                            .addValue("unsubscribed", t.unsubscribed()).addValue("complained", t.complained())
                            .addValue("computedAt", ts(computedAt)));
        }
        jdbc.update("INSERT INTO audience_event_outcomes (event_id, org_id, phase, new_guests, door_closed_at,"
                        + " computed_at) VALUES (:eventId, :orgId, :phase, :newGuests, :doorClose, :computedAt)",
                new MapSqlParameterSource().addValue("eventId", eventId).addValue("orgId", orgId)
                        .addValue("phase", phase).addValue("newGuests", newGuests, java.sql.Types.INTEGER)
                        .addValue("doorClose", ts(doorClose)).addValue("computedAt", ts(computedAt)));
    }

    /** Replaces every calibration row. */
    public void replaceCalibration(List<Calibration> rows, Instant updatedAt) {
        jdbc.update("DELETE FROM response_calibration", new MapSqlParameterSource());
        for (Calibration c : rows) {
            jdbc.update("INSERT INTO response_calibration (id, scope, org_id, class, genre_fit, arm, n, bought,"
                            + " events, updated_at) VALUES (:id, :scope, :orgId, :classKey, :genreFit, :arm, :n,"
                            + " :bought, :events, :updatedAt)",
                    new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("scope", c.scope())
                            .addValue("orgId", c.orgId()).addValue("classKey", c.classKey())
                            .addValue("genreFit", c.genreFit()).addValue("arm", c.arm()).addValue("n", c.n())
                            .addValue("bought", c.bought()).addValue("events", c.events())
                            .addValue("updatedAt", ts(updatedAt)));
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static OffsetDateTime ts(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Double dbl(ResultSet rs, String column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
