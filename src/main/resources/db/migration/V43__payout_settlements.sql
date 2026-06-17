-- V43__payout_settlements.sql
-- Settlements read-model for Track A payouts. This table MIRRORS Stripe
-- transfer/payout objects delivered via webhooks — it moves NO money and holds
-- no double-entry ledger. It exists so /payouts/summary + history can be served
-- from our own DB instead of a live Stripe list call. H2/PG-compatible
-- (no jsonb, no native enum); enums are stored as VARCHAR per V28__refunds.sql.

CREATE TABLE settlements (
  id                  UUID PRIMARY KEY,
  org_id              UUID NOT NULL REFERENCES organizations(id),
  -- The tr_... or po_... Stripe id this row mirrors. UNIQUE so a webhook replay
  -- (or two distinct event ids for the same object) upserts one row, not many.
  stripe_object_id    VARCHAR(64) NOT NULL UNIQUE,
  -- 'transfer' | 'payout' — which Stripe object family this row mirrors.
  object_type         VARCHAR(16) NOT NULL,
  amount_minor        BIGINT NOT NULL,
  currency            VARCHAR(8) NOT NULL,
  -- 'pending' | 'in_transit' | 'paid' | 'failed' | 'reversed'.
  status              VARCHAR(24) NOT NULL,
  -- CSV of event UUIDs this row covers. ponytail: CSV read-model field,
  -- normalize to a join table only if we ever query payouts by event.
  event_ids           TEXT,
  -- Stripe payout arrival_date when known (NULL for transfers / pending payouts).
  arrival_at          TIMESTAMP WITH TIME ZONE,
  -- When a PAYOUT row reached PAID (payout arrival_date if present, else ingest
  -- time). NULL for transfers and for payouts not yet paid. Drives the
  -- "this month" summary window so a month-boundary payout lands in the right
  -- calendar month rather than being bucketed by created_at.
  paid_at             TIMESTAMP WITH TIME ZONE,
  failure_reason      TEXT,
  created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Drives the org-scoped payout history list (newest first) and summary windows.
CREATE INDEX settlements_org_created_idx ON settlements (org_id, created_at);
