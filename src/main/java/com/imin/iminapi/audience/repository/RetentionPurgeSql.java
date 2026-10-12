package com.imin.iminapi.audience.repository;

/**
 * Memberships due for the 3-year retention purge: not erase_pending, and the newest contact (own purchase or
 * attendance, contact from the person, an order of the org for the address, a person-sourced email consent, an
 * imported last purchase date) is before the cutoff; with no contact known, the membership's own age decides.
 * The provenance date is compared as a date with {@code :cutoffDate}, which equals its start of day in the org
 * zone compared with {@code :cutoffAt}, so no zone name reaches Postgres.
 */
public final class RetentionPurgeSql {

    private RetentionPurgeSql() {}

    private static final String CONTACTS = "SELECT m.membership_id, m.created_at,"
            + " GREATEST(m.last_purchase, m.last_attended, f.last_contact_from_person_at,"
            + " (SELECT max(o.created_at) FROM orders o"
            + " WHERE o.org_id = m.org_id AND o.email_normalized = c.normalized_email),"
            + " (SELECT max(r.occurred_at) FROM consent_records r"
            + " WHERE r.membership_id = m.membership_id AND r.channel = 'email' AND r.status = 'subscribed'"
            + " AND r.source IN (:personSources))) AS last_contact_at,"
            + " (SELECT max(p.last_purchase_date) FROM import_row_provenance p"
            + " WHERE p.membership_id = m.membership_id) AS last_purchase_date"
            + " FROM memberships m"
            + " JOIN consumers c ON c.consumer_id = m.consumer_id"
            + " LEFT JOIN fan_features f ON f.membership_id = m.membership_id"
            + " WHERE m.org_id = :orgId AND m.status <> 'erase_pending'";

    private static final String DUE = " WHERE (x.last_contact_at IS NULL OR x.last_contact_at < :cutoffAt)"
            + " AND (x.last_purchase_date IS NULL OR x.last_purchase_date < :cutoffDate)"
            + " AND (x.last_contact_at IS NOT NULL OR x.last_purchase_date IS NOT NULL OR x.created_at < :cutoffAt)";

    /** Rows of {@code [membership_id, created_at]}, oldest first, after the keyset cursor. */
    public static final String CANDIDATES = "SELECT x.membership_id, x.created_at FROM (" + CONTACTS + ") x" + DUE
            + " AND (x.created_at, x.membership_id) > (:afterCreatedAt, :afterId)"
            + " ORDER BY x.created_at, x.membership_id LIMIT :limit";

    /** The same test for given ids, read again under the membership lock. */
    public static final String CANDIDATES_AMONG = "SELECT x.membership_id, x.created_at FROM (" + CONTACTS
            + " AND m.membership_id IN (:ids)) x" + DUE;

    public static final String COUNT = "SELECT COUNT(*) FROM (" + CONTACTS + ") x" + DUE;
}
