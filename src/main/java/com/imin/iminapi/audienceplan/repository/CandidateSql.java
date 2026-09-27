package com.imin.iminapi.audienceplan.repository;

/** Candidate builder inputs for one org and one event, as native queries (H2 and Postgres); no nullable parameter. */
public final class CandidateSql {

    private CandidateSql() {}

    /** Rows of {@code [membership_id, class, taste, no_show_n]}; the org comes from the membership, not the feature row. */
    public static final String FEATURES = "SELECT f.membership_id, f.class, f.taste, f.no_show_n"
            + " FROM fan_features f JOIN memberships m ON m.membership_id = f.membership_id"
            + " WHERE m.org_id = :orgId";

    /** Members holding a live ticket of a real (non-test) order for the event. */
    public static final String BOUGHT_EVENT = "SELECT DISTINCT m.membership_id FROM memberships m"
            + " JOIN consumers c ON c.consumer_id = m.consumer_id"
            + " JOIN orders o ON o.org_id = m.org_id AND o.email_normalized = c.normalized_email"
            + " WHERE m.org_id = :orgId AND o.event_id = :eventId AND o.test_mode = FALSE"
            + " AND EXISTS (SELECT 1 FROM tickets t WHERE t.order_id = o.id"
            + " AND t.state NOT IN ('refunded', 'revoked'))";

    // Same "sent" predicate as CampaignVolumeGuard's frequency floor; unsubscribed/complained rows do not count.
    static final String SENT = "r.status IN ('sent', 'delivered', 'opened', 'clicked')";

    /**
     * Rows of {@code [membership_id, sends since floor, sends for the event (all time), sends since month]}.
     * Two index-friendly branches instead of one OR: the recent window and this event's campaigns.
     */
    public static final String SEND_COUNTS = "SELECT s.membership_id,"
            + " SUM(s.floor_n), SUM(s.event_n), SUM(s.month_n) FROM ("
            + "SELECT r.membership_id,"
            + " CASE WHEN r.last_event_at >= :floorSince THEN 1 ELSE 0 END AS floor_n,"
            + " 0 AS event_n,"
            + " CASE WHEN r.last_event_at >= :monthSince THEN 1 ELSE 0 END AS month_n"
            + " FROM campaign_recipients r"
            + " JOIN memberships m ON m.membership_id = r.membership_id"
            + " WHERE m.org_id = :orgId AND " + SENT
            + " AND r.last_event_at >= :scanSince"
            + " UNION ALL "
            + "SELECT r.membership_id, 0, 1, 0"
            + " FROM campaigns cp"
            + " JOIN campaign_recipients r ON r.campaign_id = cp.id"
            + " JOIN memberships m ON m.membership_id = r.membership_id"
            + " WHERE cp.event_id = :eventId AND m.org_id = :orgId AND " + SENT
            + ") s GROUP BY s.membership_id";
}
