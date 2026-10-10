-- V182__refund_stripe_checked_at.sql
-- Last time the reconciler read a PENDING refund's state from Stripe because its webhook never came.
-- Rotates rows that stay pending so a capped pass reaches the others.
ALTER TABLE refunds ADD COLUMN stripe_checked_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX refunds_pending_recheck_idx ON refunds (created_at)
  WHERE status = 'PENDING' AND stripe_refund_id IS NOT NULL;

-- Backs the reconciler's per-tick count of REQUESTED rows that never recorded an attempt.
CREATE INDEX refunds_unattempted_requested_idx ON refunds (created_at)
  WHERE status = 'REQUESTED' AND stripe_refund_id IS NULL AND stripe_attempt_at IS NULL;
