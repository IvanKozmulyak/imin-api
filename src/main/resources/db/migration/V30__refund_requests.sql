-- V30__refund_requests.sql
-- Buyer-initiated refund requests. Distinct from the Stripe-side `refunds`
-- table (V28) — a request is a CS/audit concept and may or may not become a
-- Stripe refund. See spec 2026-05-25-buyer-refund-requests-design.md.

CREATE TABLE refund_requests (
  id                   UUID PRIMARY KEY,
  order_id             UUID NOT NULL REFERENCES orders(id),
  org_id               UUID NOT NULL,
  event_id             UUID NOT NULL,
  buyer_email          VARCHAR(254) NOT NULL,
  buyer_phone          VARCHAR(32),
  reason               VARCHAR(32) NOT NULL,
  explanation          TEXT NOT NULL,
  status               VARCHAR(16) NOT NULL,
  decision_note        TEXT,
  decided_by_user_id   UUID REFERENCES users(id),
  decided_at           TIMESTAMP WITH TIME ZONE,
  refund_id            UUID REFERENCES refunds(id),
  -- pending_marker = order_id while status='PENDING', NULL otherwise.
  -- A plain UNIQUE on this column enforces "one open request per order"
  -- on both Postgres and H2. Partial-WHERE indexes (which would otherwise
  -- be the natural choice) are Postgres-only; V13 uses the same
  -- nullable-marker pattern for the same reason. The service is
  -- responsible for keeping pending_marker in sync with status.
  pending_marker       UUID,
  created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_refund_requests_org_status_created
  ON refund_requests (org_id, status, created_at DESC);
CREATE INDEX idx_refund_requests_event_created
  ON refund_requests (event_id, created_at DESC);
CREATE INDEX idx_refund_requests_order_status
  ON refund_requests (order_id, status);

-- NULLs are distinct in both Postgres and H2 (PG-compat), so this UNIQUE
-- only enforces uniqueness when pending_marker is set — i.e., on PENDING
-- rows. Two concurrent submits race-lose with a unique violation that the
-- service maps to REFUND_REQUEST_ALREADY_OPEN.
CREATE UNIQUE INDEX uq_refund_requests_one_open_per_order
  ON refund_requests (pending_marker);
