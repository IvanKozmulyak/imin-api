# Retention counts (read-only SQL)

Counts to run **after the retention build deploys and before** flipping `IMIN_AUDIENCE_RETENTION_ENABLED` or
`IMIN_AUDIENCE_RETENTION_PURGE_ENABLED`. Every statement is a plain `SELECT`; nothing here writes.

How to run: open a read-only session (`railway connect` → `psql`, then `SET default_transaction_read_only = on;`)
and paste one block at a time. Each total must equal the matching dry-run log line of the night before:

| Block | Log line (Railway) | Field |
|---|---|---|
| R1 | `SurveyRetentionJob: dry run, …` (04:30 Paris) | survey answers past the retention window |
| R2 | `AudienceRetentionPurgeJob: dry run, …` (04:15 Paris) | `memberships expired=` (sum over orgs) |
| R3 | same line | `optOuts expired=` |
| R4 | same line | `recipientRows expired=` |

Stop and investigate on any mismatch. After the purge flag is on, re-run R2–R4 the next morning: each should be 0
for every org, unless the log line says `capped=true` (at most 5,000 memberships a night; the rest go the
following nights).

The 3-year cutoff is local midnight in the org's timezone of today − 1095 days (`RetentionCutoff`; blank = UTC).
An invalid zone name makes Postgres error, where the app falls back to UTC, so first run
`SELECT DISTINCT timezone FROM organizations;` and fix or exclude any name Postgres rejects.

`RetentionCountsSqlPostgresTest` runs every `sql` block of this file against a migrated Postgres and checks R2–R4
against the job's own dry-run counts on seeded rows, so a schema or rule change that breaks them fails the build.
The person-sourced consent list (`'checkout', 'door_qr', 'survey'`) is copied from
`src/main/resources/audienceplan/logic-v1.yaml` (`legal.explicit_sources` with `organizer_named_text_version` or
`text_version`); update both together.

## R1. Survey answers `SurveyRetentionJob` would delete

Mirrors `SurveyRetentionJob.run`: answers carry only their UTC day, so the cutoff is a UTC midnight on purpose.

```sql
SELECT count(*) AS survey_answers_past_window
  FROM survey_responses
 WHERE created_at < (date_trunc('day', now() AT TIME ZONE 'UTC') - interval '1095 days') AT TIME ZONE 'UTC';
```

## R2. Memberships the purge would delete, per org

Mirrors `RetentionPurgeSql`: not `erase_pending`, and the newest contact (own purchase or attendance, contact
from the person, any order of the org for the address, a person-sourced subscribing email consent, an imported
last purchase date at local midnight) is before the cutoff; with no contact known, `created_at` decides.

```sql
WITH org_cut AS (
  SELECT x.org_id, x.tz,
         ((now() AT TIME ZONE x.tz)::date - 1095) AS cutoff_date,
         (((now() AT TIME ZONE x.tz)::date - 1095)::timestamp AT TIME ZONE x.tz) AS cutoff_at
    FROM (SELECT DISTINCT m.org_id, COALESCE(NULLIF(TRIM(o.timezone), ''), 'UTC') AS tz
            FROM memberships m LEFT JOIN organizations o ON o.id = m.org_id) x
)
SELECT m.org_id, count(*) AS memberships_to_delete
  FROM memberships m
  JOIN org_cut oc ON oc.org_id = m.org_id
  JOIN consumers c ON c.consumer_id = m.consumer_id
  LEFT JOIN fan_features f ON f.membership_id = m.membership_id
 WHERE m.status <> 'erase_pending'
   AND COALESCE(GREATEST(
         m.last_purchase,
         m.last_attended,
         f.last_contact_from_person_at,
         (SELECT max(o.created_at) FROM orders o
           WHERE o.org_id = m.org_id AND o.email_normalized = c.normalized_email),
         (SELECT max(r.occurred_at) FROM consent_records r
           WHERE r.membership_id = m.membership_id AND r.channel = 'email' AND r.status = 'subscribed'
             AND r.source IN ('checkout', 'door_qr', 'survey')),
         (SELECT max(p.last_purchase_date)::timestamp AT TIME ZONE oc.tz FROM import_row_provenance p
           WHERE p.membership_id = m.membership_id)),
       m.created_at) < oc.cutoff_at
 GROUP BY m.org_id
 ORDER BY m.org_id;
```

## R3. Opposition-list rows the purge would delete, per org

Mirrors `MarketingOptOutRepository.deleteByOrgIdAndCreatedAtBefore`: 3 years from `created_at`.

```sql
WITH org_cut AS (
  SELECT x.org_id,
         (((now() AT TIME ZONE x.tz)::date - 1095)::timestamp AT TIME ZONE x.tz) AS cutoff_at
    FROM (SELECT DISTINCT mo.org_id, COALESCE(NULLIF(TRIM(o.timezone), ''), 'UTC') AS tz
            FROM marketing_optouts mo LEFT JOIN organizations o ON o.id = mo.org_id) x
)
SELECT mo.org_id, count(*) AS opt_outs_to_delete
  FROM marketing_optouts mo
  JOIN org_cut oc ON oc.org_id = mo.org_id
 WHERE mo.created_at < oc.cutoff_at
 GROUP BY mo.org_id
 ORDER BY mo.org_id;
```

## R4. Campaign recipient rows the purge would redact, per org

Mirrors `CampaignRecipientRepository.redactPiiForCampaignsEndedBefore`: finished rows of campaigns that ended
(`sent`, `canceled`, `failed`) before the cutoff still carrying an address; pending rows are kept.

```sql
WITH org_cut AS (
  SELECT x.org_id,
         (((now() AT TIME ZONE x.tz)::date - 1095)::timestamp AT TIME ZONE x.tz) AS cutoff_at
    FROM (SELECT DISTINCT c.org_id, COALESCE(NULLIF(TRIM(o.timezone), ''), 'UTC') AS tz
            FROM campaigns c LEFT JOIN organizations o ON o.id = c.org_id) x
)
SELECT c.org_id, count(*) AS recipient_rows_to_redact
  FROM campaign_recipients r
  JOIN campaigns c ON c.id = r.campaign_id
  JOIN org_cut oc ON oc.org_id = c.org_id
 WHERE c.status IN ('sent', 'canceled', 'failed')
   AND r.status <> 'pending'
   AND COALESCE(c.sent_at, c.created_at) < oc.cutoff_at
   AND (r.email IS NOT NULL OR r.phone_e164 IS NOT NULL)
 GROUP BY c.org_id
 ORDER BY c.org_id;
```
