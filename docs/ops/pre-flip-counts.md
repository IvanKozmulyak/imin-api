# Pre-flip counts (read-only SQL)

Counts to run **before** flipping `IMIN_CONSENT_GATE_ALL_CAMPAIGNS`, `IMIN_LEGAL_IDENTITY_ALL_CAMPAIGNS`
or `IMIN_AUDIENCE_PLAN_SOFT_OPT_IN`. Every statement is a plain `SELECT`; nothing here writes.

How to run: open a read-only session (`railway connect` → `psql`, then `SET default_transaction_read_only = on;`)
and paste one block at a time. Share the output with Ivan/Bohdan before any flip.

`PreFlipCountsSqlPostgresTest` runs every `sql` block of this file against a migrated Postgres and checks
query 1 against `SendGateService` and `ConsentGate` on seeded members, so a schema or gate change that
breaks these queries fails the build. The gate parameters below are copied from
`src/main/resources/audienceplan/logic-v1.yaml` (`legal.*`); update both together.

## 1. Members SendGate would mail but ConsentGate blocks, per org and reason

What flipping `IMIN_CONSENT_GATE_ALL_CAMPAIGNS` removes from manual and Momentum sends. Mirrors
`SendGateService.evaluate` (the `sendgate_ok` column) and `ConsentGateSql.VERDICTS` (the `consent_reason`
column) with `soft-opt-in-enabled=false`; a door/survey sign-up awaiting address confirmation grants nothing
(`ConsentGateSql.CONFIRMED_R`), so it never becomes the latest consent. The 3-year cutoff is local midnight
in the org's timezone (blank = UTC; an invalid zone name makes Postgres error, where the app falls back to UTC).

```sql
WITH org_cut AS (
    SELECT o.id AS org_id,
           ((now() AT TIME ZONE o.tz)::date - 1095) AS cutoff_date,
           (((now() AT TIME ZONE o.tz)::date - 1095)::timestamp AT TIME ZONE o.tz) AS cutoff_at
      FROM (SELECT id, COALESCE(NULLIF(TRIM(timezone), ''), 'UTC') AS tz FROM organizations) o
),
latest AS (
    SELECT x.*, ROW_NUMBER() OVER (PARTITION BY x.membership_id
                                   ORDER BY x.occurred_at DESC, x.proven DESC, x.id DESC) AS rn
      FROM (SELECT r.id, r.membership_id, r.lawful_basis, r.source, r.occurred_at,
                   CASE WHEN (r.lawful_basis = 'explicit' AND r.text_version IS NOT NULL AND (
                                  (r.source IN ('checkout') AND r.text_version IN ('checkout-org-named-2026-09'))
                               OR (r.source IN ('organizer_import_row') AND EXISTS (
                                      SELECT 1 FROM import_row_provenance p
                                        JOIN audience_imports ai ON ai.id = p.import_id
                                       WHERE p.membership_id = m.membership_id AND p.accepted = TRUE
                                         AND p.marketing_status = 'opted_in' AND ai.org_id = m.org_id))
                               OR r.source IN ('door_qr', 'survey')))
                        THEN 1 ELSE 0 END AS proven
              FROM consent_records r
              JOIN memberships m ON m.membership_id = r.membership_id
             WHERE r.channel = 'email' AND r.status = 'subscribed'
               AND (r.confirmation_required = FALSE OR r.confirmed_at IS NOT NULL)) x
),
verdicts AS (
    SELECT m.org_id, m.membership_id,
           CASE
             WHEN m.status = 'erase_pending' THEN 'erase_pending'
             WHEN c.normalized_email IS NULL OR c.normalized_email = '' THEN 'no_email'
             WHEN m.consent_status = 'unsubscribed' OR EXISTS (
                    SELECT 1 FROM marketing_optouts mo
                     WHERE mo.email_normalized = c.normalized_email AND mo.org_id = m.org_id
                       AND mo.channel = 'email') THEN 'unsubscribed'
             WHEN EXISTS (SELECT 1 FROM suppression_entries s WHERE s.scope = 'marketing'
                            AND s.org_id = m.org_id AND s.membership_id = m.membership_id)
               OR EXISTS (SELECT 1 FROM suppression_entries sd WHERE sd.scope = 'deliverability'
                            AND sd.normalized_email = c.normalized_email) THEN 'suppressed'
             WHEN m.objected_profiling = TRUE THEN 'objected'
             WHEN r.id IS NULL OR r.lawful_basis IS NULL THEN 'no_basis'
             WHEN r.proven = 0 THEN 'legacy_unproven'
             WHEN NOT ((f.last_contact_from_person_at IS NOT NULL AND f.last_contact_from_person_at >= oc.cutoff_at)
                       OR (r.source IN ('checkout', 'door_qr', 'survey') AND r.occurred_at >= oc.cutoff_at)
                       OR EXISTS (SELECT 1 FROM import_row_provenance p2
                                   WHERE p2.membership_id = m.membership_id AND p2.accepted = TRUE
                                     AND p2.last_purchase_date IS NOT NULL
                                     AND p2.last_purchase_date >= oc.cutoff_date)) THEN 'retention_3y'
             ELSE NULL
           END AS consent_reason,
           (m.status IS DISTINCT FROM 'erase_pending'
            AND m.consent_status IS DISTINCT FROM 'unsubscribed'
            AND NOT EXISTS (SELECT 1 FROM suppression_entries s2 WHERE s2.scope = 'marketing'
                              AND s2.org_id = m.org_id AND s2.membership_id = m.membership_id)
            AND c.normalized_email IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM suppression_entries sd2 WHERE sd2.scope = 'deliverability'
                              AND sd2.normalized_email = c.normalized_email)
            AND m.consent_basis IS NOT NULL) AS sendgate_ok
      FROM memberships m
      JOIN org_cut oc ON oc.org_id = m.org_id
      LEFT JOIN consumers c ON c.consumer_id = m.consumer_id
      LEFT JOIN latest r ON r.membership_id = m.membership_id AND r.rn = 1
      LEFT JOIN fan_features f ON f.membership_id = m.membership_id
)
SELECT o.name AS org_name, v.org_id,
       COUNT(*) FILTER (WHERE v.sendgate_ok) AS sendgate_mailable,
       COUNT(*) FILTER (WHERE v.sendgate_ok AND v.consent_reason IS NULL) AS mailable_after_flip,
       COUNT(*) FILTER (WHERE v.sendgate_ok AND v.consent_reason = 'legacy_unproven') AS blocked_legacy_unproven,
       COUNT(*) FILTER (WHERE v.sendgate_ok AND v.consent_reason = 'no_basis') AS blocked_no_basis,
       COUNT(*) FILTER (WHERE v.sendgate_ok AND v.consent_reason = 'retention_3y') AS blocked_retention_3y,
       COUNT(*) FILTER (WHERE v.sendgate_ok AND v.consent_reason = 'objected') AS blocked_objected,
       COUNT(*) FILTER (WHERE v.sendgate_ok AND v.consent_reason = 'unsubscribed') AS blocked_unsubscribed,
       COUNT(*) FILTER (WHERE v.sendgate_ok AND v.consent_reason = 'suppressed') AS blocked_suppressed,
       COUNT(*) FILTER (WHERE v.sendgate_ok AND v.consent_reason = 'no_email') AS blocked_no_email
  FROM verdicts v
  JOIN organizations o ON o.id = v.org_id
 GROUP BY o.name, v.org_id
HAVING COUNT(*) FILTER (WHERE v.sendgate_ok) > 0
 ORDER BY sendgate_mailable DESC, o.name;
```

`unsubscribed`/`suppressed`/`no_email` rows in the blocked columns come from the checks ConsentGate adds on top
of SendGate (per-org `marketing_optouts`, blank email). `erase_pending` never appears: SendGate drops those too.

## 2. Orgs without a legal identity that have campaigns in flight

What flipping `IMIN_LEGAL_IDENTITY_ALL_CAMPAIGNS` blocks: every campaign of these orgs becomes unschedulable
(409 `ORG_LEGAL_IDENTITY_MISSING`), and scheduled or retryable ones stop being claimed. Blank means empty
after `TRIM`, as in `CampaignRepository.claimDue` (the app's `isBlank` also treats tabs/newlines as blank).

```sql
SELECT o.name AS org_name, o.id AS org_id,
       (TRIM(COALESCE(o.legal_name, '')) = '') AS missing_legal_name,
       (TRIM(COALESCE(o.legal_contact, '')) = '') AS missing_legal_contact,
       c.origin, c.status, COUNT(*) AS campaigns,
       MAX(c.scheduled_at) AS latest_scheduled_at
  FROM organizations o
  JOIN campaigns c ON c.org_id = o.id
 WHERE (TRIM(COALESCE(o.legal_name, '')) = '' OR TRIM(COALESCE(o.legal_contact, '')) = '')
   AND (c.status IN ('draft', 'scheduled', 'sending') OR (c.status = 'failed' AND c.attempts < 3))
 GROUP BY o.name, o.id, o.legal_name, o.legal_contact, c.origin, c.status
 ORDER BY o.name, c.origin, c.status;
```

Orgs that sent any campaign in the last 90 days but have no legal identity (who to tell first):

```sql
SELECT o.name AS org_name, o.id AS org_id, COUNT(*) AS campaigns_sent_90d, MAX(c.sent_at) AS last_sent_at
  FROM organizations o
  JOIN campaigns c ON c.org_id = o.id
 WHERE (TRIM(COALESCE(o.legal_name, '')) = '' OR TRIM(COALESCE(o.legal_contact, '')) = '')
   AND c.sent_at >= now() - interval '90 days'
 GROUP BY o.name, o.id
 ORDER BY last_sent_at DESC;
```

## 3. Historical `soft_opt_in` consent rows by type

Input to the `IMIN_AUDIENCE_PLAN_SOFT_OPT_IN` decision (nothing writes `soft_opt_in` any more). "Paid order of
the org" is `ConsentGateSql.PAID_ORDER_OF_ORG`: the record's `order_id` is a live Stripe order of the member's
org with a positive total and at least one ticket not refunded or revoked; that is the only shape the flag
would count, and only when it is the member's latest subscribing email consent (unconfirmed door/survey
sign-ups excluded, as in ConsentGate).

```sql
WITH latest AS (
    SELECT r.id, ROW_NUMBER() OVER (PARTITION BY r.membership_id
                                    ORDER BY r.occurred_at DESC, r.id DESC) AS rn
      FROM consent_records r
     WHERE r.channel = 'email' AND r.status = 'subscribed'
       AND (r.confirmation_required = FALSE OR r.confirmed_at IS NOT NULL)
)
SELECT r.channel, r.status, r.source,
       CASE
         WHEN r.order_id IS NULL THEN 'no_order'
         WHEN o.id IS NULL THEN 'order_missing'
         WHEN o.org_id <> m.org_id THEN 'order_of_other_org'
         WHEN o.test_mode THEN 'test_order'
         WHEN o.payment_method = 'stripe' AND o.total_minor > 0
              AND EXISTS (SELECT 1 FROM tickets t WHERE t.order_id = o.id
                            AND t.state NOT IN ('refunded', 'revoked')) THEN 'paid_order_of_org'
         WHEN o.total_minor = 0 OR o.payment_method <> 'stripe' THEN 'free_order'
         ELSE 'paid_order_all_tickets_refunded_or_revoked'
       END AS order_type,
       (NULLIF(TRIM(COALESCE(r.proof_text, '')), '') IS NOT NULL) AS has_proof_text,
       (l.rn = 1) AS is_latest_email_grant,
       COUNT(*) AS records,
       COUNT(DISTINCT r.membership_id) AS members,
       MIN(r.occurred_at) AS first_at, MAX(r.occurred_at) AS last_at
  FROM consent_records r
  JOIN memberships m ON m.membership_id = r.membership_id
  LEFT JOIN orders o ON o.id = r.order_id
  LEFT JOIN latest l ON l.id = r.id
 WHERE r.lawful_basis = 'soft_opt_in'
 GROUP BY 1, 2, 3, 4, 5, 6
 ORDER BY records DESC;
```
