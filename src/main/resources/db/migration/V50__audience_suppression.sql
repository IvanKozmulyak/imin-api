-- Audience Tier C: suppression entries for two scopes.
--
-- scope='marketing'      org-scoped, keyed by (org_id, membership_id)
-- scope='deliverability' platform-shared, keyed by normalized_email (hard bounces etc.)
--
-- Partial unique indexes are NOT used here because H2 doesn't support partial
-- indexes with WHERE clauses. Uniqueness is enforced in the application layer
-- (SuppressionService). Plain indexes are used for query performance.
-- The logical uniqueness semantics are:
--   marketing:       one row per (org_id, membership_id)
--   deliverability:  one row per normalized_email
CREATE TABLE suppression_entries (
    id               UUID         PRIMARY KEY,
    scope            VARCHAR(20)  NOT NULL,  -- 'marketing' | 'deliverability'
    org_id           UUID,
    membership_id    UUID         REFERENCES memberships(membership_id) ON DELETE CASCADE,
    normalized_email VARCHAR(254),
    -- reason: hard-bounce | unsubscribe | spam | manual
    reason           VARCHAR(24)  NOT NULL,
    since            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    system_owned     BOOLEAN      NOT NULL DEFAULT false
);

-- Marketing suppression: lookup by org + membership
CREATE INDEX ix_supp_marketing_org_mem ON suppression_entries (scope, org_id, membership_id);

-- Deliverability suppression: lookup by email
CREATE INDEX ix_supp_deliverability_email ON suppression_entries (scope, normalized_email);
