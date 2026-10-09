-- V179__refund_attempts.sql
-- Refund attempts are committed before Stripe is called; these columns let the reconciler find
-- an attempt whose outcome is unknown. NULL stripe_attempt_at = a row written before this deploy.
ALTER TABLE refunds ADD COLUMN stripe_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE refunds ADD COLUMN stripe_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE refunds ADD CONSTRAINT ck_refunds_stripe_attempts CHECK (stripe_attempts >= 0);

CREATE INDEX refunds_unresolved_attempt_idx ON refunds (stripe_attempt_at)
  WHERE status = 'REQUESTED' AND stripe_refund_id IS NULL AND stripe_attempt_at IS NOT NULL;

-- Repair: a refund Stripe answered failed/canceled synchronously kept its ticket claims, which
-- made those tickets unrefundable forever. No money moved for them, so release the claims.
DELETE FROM refund_tickets rt
 USING refunds r, tickets t
 WHERE rt.refund_id = r.id
   AND t.id = rt.ticket_id
   AND r.status IN ('FAILED', 'CANCELED')
   AND t.state <> 'refunded';
