-- Stripe Connect status mirror — fields previously fetched live on every /stripe/status call.
-- Source events: v2.core.account.requirements.updated, v2.core.account.recipient.capability_status_updated.
ALTER TABLE organizations ADD COLUMN stripe_connect_state VARCHAR(32);
ALTER TABLE organizations ADD COLUMN stripe_payouts_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organizations ADD COLUMN stripe_details_submitted BOOLEAN NOT NULL DEFAULT FALSE;
-- TEXT (not JSONB) for cross-engine compatibility: PG and H2 both accept TEXT,
-- but H2's PG-compat-mode JSONB columns re-encode the value on read and Jackson
-- then sees a JSON-encoded string instead of the underlying JSON array. The Java
-- converter (StringListJsonConverter) (de)serializes JSON manually, so we don't
-- need the DB to enforce JSON validity.
ALTER TABLE organizations ADD COLUMN stripe_requirements_currently_due TEXT NOT NULL DEFAULT '[]';
ALTER TABLE organizations ADD COLUMN stripe_requirements_past_due TEXT NOT NULL DEFAULT '[]';
ALTER TABLE organizations ADD COLUMN stripe_disabled_reason VARCHAR(128);
ALTER TABLE organizations ADD COLUMN stripe_connect_status_updated_at TIMESTAMP(6) WITHOUT TIME ZONE;

-- Existing rows: derive an initial state from what we already know. The mirror's lazy
-- first-read (StripeConnectService.getStatus) will refine these on the next call.
UPDATE organizations
SET stripe_connect_state = CASE
        WHEN stripe_account_id IS NULL OR stripe_account_id = '' THEN 'NOT_STARTED'
        ELSE 'ONBOARDING'
    END;

ALTER TABLE organizations ALTER COLUMN stripe_connect_state SET NOT NULL;
ALTER TABLE organizations ALTER COLUMN stripe_connect_state SET DEFAULT 'NOT_STARTED';
