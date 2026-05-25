-- V28__refunds.sql
-- Refund domain: full or partial Stripe refunds with proportional application-fee reversal.
-- Pure webhook-driven inventory release. See spec 2026-05-25-stripe-refunds-design.md.

-- Snapshot the application fee Stripe deducted at checkout, so refund proration
-- can compute proportional fee refund without an extra Stripe round-trip.
ALTER TABLE orders
  ADD COLUMN application_fee_minor BIGINT NOT NULL DEFAULT 0;

-- Snapshot the price-at-purchase on each ticket so refund amount survives tier
-- price changes between purchase and refund.
ALTER TABLE tickets
  ADD COLUMN price_minor INTEGER NOT NULL DEFAULT 0;

-- Backfill existing tickets with the current tier price. Acceptable approximation
-- for pre-launch data (no real orders exist yet).
UPDATE tickets t
   SET price_minor = COALESCE((SELECT tt.price_minor FROM ticket_tiers tt WHERE tt.id = t.tier_id), 0);

CREATE TABLE refunds (
  id                            UUID PRIMARY KEY,
  order_id                      UUID NOT NULL REFERENCES orders(id),
  stripe_refund_id              VARCHAR(255),
  stripe_charge_id              VARCHAR(255),
  stripe_payment_intent_id      VARCHAR(255) NOT NULL,
  amount_minor                  BIGINT NOT NULL,
  currency                      VARCHAR(8) NOT NULL,
  application_fee_refund_minor  BIGINT NOT NULL DEFAULT 0,
  reason                        VARCHAR(32) NOT NULL,
  status                        VARCHAR(16) NOT NULL,
  failure_code                  VARCHAR(64),
  failure_message               VARCHAR(500),
  initiated_by_user_id          UUID NOT NULL REFERENCES users(id),
  idempotency_key               VARCHAR(128) NOT NULL,
  created_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at                    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- UNIQUE on a nullable column. Multiple NULLs are allowed under standard SQL
-- ("nulls are distinct"), which both PostgreSQL and H2 follow — so this only
-- enforces uniqueness once stripe_refund_id is set, which is exactly what we want.
CREATE UNIQUE INDEX refunds_stripe_refund_id_unique
  ON refunds (stripe_refund_id);

CREATE UNIQUE INDEX refunds_order_idem_unique
  ON refunds (order_id, idempotency_key);

CREATE INDEX refunds_stripe_charge_id_idx ON refunds (stripe_charge_id);
CREATE INDEX refunds_order_id_idx ON refunds (order_id);

CREATE TABLE refund_tickets (
  refund_id  UUID NOT NULL REFERENCES refunds(id),
  ticket_id  UUID NOT NULL REFERENCES tickets(id),
  PRIMARY KEY (refund_id, ticket_id)
);

-- A ticket can be in at most one refund row. Enforces "no double refund of same ticket"
-- at the storage layer even under concurrent POSTs from two organizers.
CREATE UNIQUE INDEX refund_tickets_ticket_id_unique
  ON refund_tickets (ticket_id);
