package com.imin.iminapi.audienceplan.repository;

/**
 * ConsentGate as one native query (H2 and Postgres): each org member with its first exclusion reason, null = mailable.
 * Empty list parameters are bound as a never-matching sentinel; only channel='email' consents count.
 */
public final class ConsentGateSql {

    private ConsentGateSql() {}

    // Inside the consent subquery: r = consent_records, m = memberships of the org.
    static final String PAID_ORDER_OF_ORG = """
            EXISTS (SELECT 1 FROM orders o
                     WHERE o.id = r.order_id AND o.org_id = m.org_id
                       AND o.payment_method = 'stripe' AND o.total_minor > 0 AND o.test_mode = FALSE
                       AND EXISTS (SELECT 1 FROM tickets t
                                    WHERE t.order_id = o.id AND t.state NOT IN ('refunded', 'revoked')))""";

    static final String ACCEPTED_PROVENANCE = """
            EXISTS (SELECT 1 FROM import_row_provenance p
                      JOIN audience_imports ai ON ai.id = p.import_id
                     WHERE p.membership_id = m.membership_id AND p.accepted = TRUE
                       AND p.marketing_status = 'opted_in' AND ai.org_id = m.org_id)""";

    // Organizer-typed captures never carry a text version, so every explicit proof also needs one.
    // A soft opt-in counts only when captured at this org's own paid checkout.
    static final String PROVEN = "((r.lawful_basis = 'explicit' AND r.text_version IS NOT NULL AND ("
            + " (r.source IN (:namedSources) AND r.text_version IN (:namedVersions))"
            + " OR (r.source IN (:provenanceSources) AND " + ACCEPTED_PROVENANCE + ")"
            + " OR r.source IN (:textVersionSources)))"
            + " OR (r.lawful_basis IN (:softOptInBases) AND r.source = 'checkout' AND r.order_id IS NOT NULL"
            + " AND " + PAID_ORDER_OF_ORG + "))";

    static final String CONTACT_WITHIN_RETENTION = "((f.last_contact_from_person_at IS NOT NULL"
            + " AND f.last_contact_from_person_at >= :cutoffAt)"
            + " OR (r.source IN (:personSources) AND r.occurred_at >= :cutoffAt)"
            + " OR EXISTS (SELECT 1 FROM import_row_provenance p2"
            + " WHERE p2.membership_id = m.membership_id AND p2.accepted = TRUE"
            + " AND p2.last_purchase_date IS NOT NULL AND p2.last_purchase_date >= :cutoffDate))";

    // A door/survey sign-up whose address is not yet confirmed grants nothing, so it never ranks.
    static final String CONFIRMED_R = "(r.confirmation_required = FALSE OR r.confirmed_at IS NOT NULL)";
    static final String CONFIRMED_LO = "(lo.confirmation_required = FALSE OR lo.confirmed_at IS NOT NULL)";

    // Latest subscribing email consent per member: on equal occurred_at a proven record wins, then the higher id.
    private static final String LATEST_CONSENT_HEAD = "SELECT x.id, x.membership_id, x.lawful_basis, x.source,"
            + " x.occurred_at, x.proven, ROW_NUMBER() OVER (PARTITION BY x.membership_id"
            + " ORDER BY x.occurred_at DESC, x.proven DESC, x.id DESC) AS rn"
            + " FROM (SELECT r.id, r.membership_id, r.lawful_basis, r.source, r.occurred_at,"
            + " CASE WHEN " + PROVEN + " THEN 1 ELSE 0 END AS proven"
            + " FROM consent_records r JOIN memberships m ON m.membership_id = r.membership_id"
            + " WHERE m.org_id = :orgId AND r.channel = 'email' AND r.status = 'subscribed'"
            + " AND " + CONFIRMED_R;

    private static final String LATEST_CONSENT_TAIL = ") x";

    private static final String VERDICT_HEAD = "SELECT m.membership_id AS membership_id, CASE"
            + " WHEN m.status = 'erase_pending' THEN 'erase_pending'"
            + " WHEN c.normalized_email IS NULL OR c.normalized_email = '' THEN 'no_email'"
            + " WHEN m.consent_status = 'unsubscribed' OR EXISTS (SELECT 1 FROM marketing_optouts mo"
            + " WHERE mo.email_normalized = c.normalized_email AND mo.org_id = m.org_id AND mo.channel = 'email')"
            + " THEN 'unsubscribed'"
            + " WHEN EXISTS (SELECT 1 FROM suppression_entries s WHERE s.scope = 'marketing'"
            + " AND s.org_id = m.org_id AND s.membership_id = m.membership_id)"
            + " OR EXISTS (SELECT 1 FROM suppression_entries sd WHERE sd.scope = 'deliverability'"
            + " AND sd.normalized_email = c.normalized_email) THEN 'suppressed'"
            + " WHEN m.objected_profiling = TRUE THEN 'objected'"
            + " WHEN r.id IS NULL OR r.lawful_basis IS NULL THEN 'no_basis'"
            + " WHEN r.proven = 0 THEN 'legacy_unproven'"
            + " WHEN NOT " + CONTACT_WITHIN_RETENTION + " THEN 'retention_3y'"
            + " ELSE NULL END AS reason, r.lawful_basis AS basis"
            + " FROM memberships m"
            + " LEFT JOIN consumers c ON c.consumer_id = m.consumer_id"
            + " LEFT JOIN (";

    private static final String VERDICT_TAIL = ") r ON r.membership_id = m.membership_id AND r.rn = 1"
            + " LEFT JOIN fan_features f ON f.membership_id = m.membership_id"
            + " WHERE m.org_id = :orgId";

    /** One row per org member: {@code membership_id}, {@code reason} (null = mailable), {@code basis} of the verdict's consent. */
    static final String VERDICTS = VERDICT_HEAD + LATEST_CONSENT_HEAD + LATEST_CONSENT_TAIL + VERDICT_TAIL;

    public static final String MAILABLE_IDS =
            "SELECT g.membership_id FROM (" + VERDICTS + ") g WHERE g.reason IS NULL";

    /** Rows of {@code [reason, basis, count]}; mailable rows are split by the basis the gate accepted. */
    public static final String REASON_BASIS_COUNTS =
            "SELECT g.reason, g.basis, COUNT(*) FROM (" + VERDICTS + ") g GROUP BY g.reason, g.basis";

    /** VERDICTS for given ids; the filter also sits inside the ranking so the rest of the org is never ranked. */
    public static final String REASONS_FOR_IDS = VERDICT_HEAD
            + LATEST_CONSENT_HEAD + " AND r.membership_id IN (:ids)" + LATEST_CONSENT_TAIL
            + VERDICT_TAIL + " AND m.membership_id IN (:ids)";

    /** One member's granting email consents that PROVEN rejects (what the gate calls legacy_unproven). */
    public static final String UNPROVEN_GRANT_IDS = "SELECT r.id FROM consent_records r"
            + " JOIN memberships m ON m.membership_id = r.membership_id"
            + " WHERE m.org_id = :orgId AND m.membership_id = :membershipId"
            + " AND r.channel = 'email' AND r.status = 'subscribed' AND r.lawful_basis IS NOT NULL"
            + " AND (CASE WHEN " + PROVEN + " THEN 1 ELSE 0 END) = 0";

    /** Bulk attestation source written before per-row provenance existed. */
    public static final String LEGACY_IMPORT_SOURCE = "organizer_import";

    // Only a pre-provenance bulk import grants the email basis: the contact's real age is unknown.
    static final String LEGACY_IMPORT_ONLY = "(EXISTS (SELECT 1 FROM consent_records li"
            + " WHERE li.membership_id = m.membership_id AND li.channel = 'email' AND li.status = 'subscribed'"
            + " AND li.source = '" + LEGACY_IMPORT_SOURCE + "')"
            + " AND NOT EXISTS (SELECT 1 FROM consent_records lo"
            + " WHERE lo.membership_id = m.membership_id AND lo.channel = 'email' AND lo.status = 'subscribed'"
            + " AND lo.lawful_basis IS NOT NULL AND lo.source <> '" + LEGACY_IMPORT_SOURCE + "'"
            + " AND " + CONFIRMED_LO + ")"
            + " AND NOT EXISTS (SELECT 1 FROM import_row_provenance lp WHERE lp.membership_id = m.membership_id))";

    // Retention job: email-subscribed live members past the window, whatever their basis; only with a fresh
    // fan_features row. CASE makes a missing consent row count as "no contact" rather than an unknown.
    // Rows are [membership_id, legacy_import]; legacy_import = 1 marks a member the job must skip.
    private static final String RETENTION_HEAD = "SELECT m.membership_id,"
            + " CASE WHEN " + LEGACY_IMPORT_ONLY + " THEN 1 ELSE 0 END AS legacy_import"
            + " FROM memberships m"
            + " JOIN fan_features f ON f.membership_id = m.membership_id"
            + " LEFT JOIN (";

    private static final String RETENTION_TAIL = ") r ON r.membership_id = m.membership_id AND r.rn = 1"
            + " WHERE m.org_id = :orgId AND m.status <> 'erase_pending' AND m.consent_status = 'subscribed'"
            + " AND f.updated_at >= :freshSince"
            + " AND (CASE WHEN " + CONTACT_WITHIN_RETENTION + " THEN 1 ELSE 0 END) = 0";

    public static final String RETENTION_EXPIRED = RETENTION_HEAD
            + LATEST_CONSENT_HEAD + LATEST_CONSENT_TAIL + RETENTION_TAIL;

    public static final String RETENTION_EXPIRED_FOR_IDS = RETENTION_HEAD
            + LATEST_CONSENT_HEAD + " AND r.membership_id IN (:ids)" + LATEST_CONSENT_TAIL
            + RETENTION_TAIL + " AND m.membership_id IN (:ids)";

    /** Count of the member's paid live orders (same org, by email) created at or after {@code cutoffAt}. */
    public static final String PAID_ORDERS_SINCE = "SELECT COUNT(*) FROM memberships m"
            + " JOIN consumers c ON c.consumer_id = m.consumer_id"
            + " JOIN orders o ON o.org_id = m.org_id AND o.email_normalized = c.normalized_email"
            + " WHERE m.org_id = :orgId AND m.membership_id = :membershipId"
            + " AND o.payment_method = 'stripe' AND o.total_minor > 0 AND o.test_mode = FALSE"
            + " AND o.created_at >= :cutoffAt"
            + " AND EXISTS (SELECT 1 FROM tickets t WHERE t.order_id = o.id AND t.state NOT IN ('refunded', 'revoked'))";
}
