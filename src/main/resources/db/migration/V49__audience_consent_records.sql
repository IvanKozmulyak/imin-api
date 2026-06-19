-- Audience Tier C: append-only consent audit trail.
-- No UPDATE or DELETE in application code; this table is immutable evidence.
-- Current state is denormalized onto memberships.consent_status/consent_basis.
CREATE TABLE consent_records (
    id             UUID        PRIMARY KEY,
    membership_id  UUID        NOT NULL REFERENCES memberships(membership_id) ON DELETE CASCADE,
    channel        VARCHAR(16) NOT NULL DEFAULT 'email',
    -- status: subscribed | unsubscribed
    status         VARCHAR(16) NOT NULL,
    -- lawful_basis: explicit | soft_opt_in | NULL
    lawful_basis   VARCHAR(16),
    source         VARCHAR(64) NOT NULL,
    proof_text     TEXT,
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ix_consent_membership ON consent_records (membership_id, occurred_at DESC);
