-- V45__payout_runs.sql
-- Track B (manual payouts) Phase 2: the TRIGGER LEDGER for imin-INITIATED bank
-- payouts. Distinct from the settlements read-model (V43) — settlements is a
-- POST-HOC mirror of Stripe payout/transfer state delivered by webhooks (a row
-- appears only AFTER Stripe emits payout.created), so it gives no pre-call guard
-- against double-paying, carries no idempotency key, and has no in-flight state.
--
-- A payout_runs row is written BEFORE the Stripe Payout.create call, keyed by a
-- DETERMINISTIC idempotency_key ("evt:<eventId>:attempt:<n>") that is reused on
-- the Stripe call so a crash/replica-overlap replays to the SAME po_ and never
-- double-pays. status transitions: planned -> submitted -> paid | failed.
--
-- H2/PG-compatible: UUID/BIGINT/VARCHAR/TIMESTAMP WITH TIME ZONE/TEXT only — no
-- jsonb, no native PG enum (status is VARCHAR per V28/V43 convention). now()
-- default mirrors V43__payout_settlements.sql.

CREATE TABLE payout_runs (
  id                  UUID PRIMARY KEY,
  org_id              UUID NOT NULL REFERENCES organizations(id),
  event_id            UUID NOT NULL REFERENCES events(id),
  -- The connected account (acct_...) the payout is created ON. Org-level
  -- double-pay guard keys off this: at most one in-flight run per account.
  stripe_account_id   VARCHAR(64) NOT NULL,
  amount_minor        BIGINT NOT NULL,
  currency            VARCHAR(8) NOT NULL,
  -- 'planned' | 'submitted' | 'paid' | 'failed' (VARCHAR per V28/V43 convention,
  -- no native enum). Stored lowercase via PayoutRunStatusConverter.
  status              VARCHAR(16) NOT NULL,
  -- Bumped only when a prior run for the event is FAILED; carried in the
  -- idempotency_key so a retry mints a fresh Stripe idempotency key.
  attempt             INT NOT NULL DEFAULT 1,
  -- Deterministic "evt:<eventId>:attempt:<attempt>"; reused on Payout.create via
  -- RequestOptions.setIdempotencyKey(...). UNIQUE is the real pre-call guard that
  -- makes concurrent replicas converge on one row.
  idempotency_key     VARCHAR(128) NOT NULL UNIQUE,
  -- po_..., set when the run reaches SUBMITTED. UNIQUE so a webhook can match
  -- back exactly one run.
  stripe_payout_id    VARCHAR(64) UNIQUE,
  failure_reason      TEXT,
  submitted_at        TIMESTAMP WITH TIME ZONE,
  paid_at             TIMESTAMP WITH TIME ZONE,
  created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  -- One in-flight/successful run per (event, attempt); a retry after FAILED uses
  -- a new attempt. The UNIQUE(idempotency_key) above is the primary guard; this
  -- is the event-scoped backstop.
  CONSTRAINT payout_runs_event_attempt_uniq UNIQUE (event_id, attempt)
);

-- Org-level in-flight double-pay guard (status IN planned/submitted for an acct).
CREATE INDEX payout_runs_org_status_idx ON payout_runs (org_id, status);
-- Account-level in-flight double-pay guard — the actual column the guard queries
-- (existsByStripeAccountIdAndStatusIn keys off stripe_account_id, not org_id).
CREATE INDEX payout_runs_acct_status_idx ON payout_runs (stripe_account_id, status);
-- Per-event existence guard (status IN planned/submitted/paid for an event).
CREATE INDEX payout_runs_event_status_idx ON payout_runs (event_id, status);
