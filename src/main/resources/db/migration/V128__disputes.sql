-- V128__disputes.sql
-- Chargeback/dispute registry. Unlike `settlements` (a read-model annotation on the
-- backing transfer row) this table is the AUTHORITATIVE dispute state: it gates payouts
-- while a dispute is open, carries the lost face value the organizer's net must absorb,
-- and records which order lost its tickets. H2/PG-compatible: no jsonb, no native enum,
-- no inline CHECK on `status` (an unnamed check gets a different auto-generated name in
-- PostgreSQL than in H2, so it could never be altered portably later).

CREATE TABLE disputes (
  id                        UUID PRIMARY KEY,
  -- The du_... Stripe id. UNIQUE so every charge.dispute.* delivery for the same
  -- dispute upserts one row rather than minting a new one per lifecycle event.
  stripe_dispute_id         VARCHAR(64) NOT NULL UNIQUE,
  org_id                    UUID NOT NULL REFERENCES organizations(id),
  -- Nullable: a dispute whose charge resolves to no imin order still has to be
  -- recorded (it blocks the org's payouts) but cannot be attributed to an event.
  event_id                  UUID REFERENCES events(id),
  order_id                  UUID REFERENCES orders(id),
  stripe_charge_id          VARCHAR(64),
  stripe_payment_intent_id  VARCHAR(255),
  amount_minor              BIGINT NOT NULL,
  currency                  VARCHAR(8) NOT NULL,
  -- 'open' | 'won' | 'lost' | 'withdrawn_reinstated'.
  status                    VARCHAR(32) NOT NULL,
  opened_at                 TIMESTAMP WITH TIME ZONE,
  closed_at                 TIMESTAMP WITH TIME ZONE,
  -- Stripe event.created of the delivery that last wrote this row; an older event
  -- arriving afterwards is dropped instead of rewriting settled state.
  last_event_at             TIMESTAMP WITH TIME ZONE,
  created_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Drives the org-level payout block (count of open disputes) and the per-event
-- net reduction (sum of open/lost face value).
CREATE INDEX disputes_org_status_idx ON disputes (org_id, status);
CREATE INDEX disputes_event_status_idx ON disputes (event_id, status);
