-- Ticket issuance — paid-flow idempotency, redemption state, recovery rate limit.
--
-- The paid flow now persists an Order + N Ticket rows from the
-- payment_intent.succeeded webhook (PaidCheckoutService). The PI id is the
-- canonical idempotency key: a UNIQUE constraint here is the second line of
-- defence behind the in-handler short-circuit, so duplicate webhook deliveries
-- never issue twice.
ALTER TABLE orders ADD COLUMN stripe_payment_intent_id VARCHAR(128);
ALTER TABLE orders ADD CONSTRAINT orders_stripe_payment_intent_id_unique UNIQUE (stripe_payment_intent_id);
-- NULLs allowed for the free path; PG UNIQUE treats multiple NULLs as distinct.

-- Ticket redemption state machine.
-- Default flips from 'pre' to 'issued' so new rows use the new vocabulary; existing
-- 'pre' rows are translated as 'issued' at the API boundary (TicketState.fromWire)
-- rather than via a destructive backfill.
ALTER TABLE tickets ADD COLUMN redeemed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE tickets ADD COLUMN redeemed_by_user_id UUID;
ALTER TABLE tickets ALTER COLUMN state SET DEFAULT 'issued';

-- Self-recovery rate-limit counter. Cheap counts-since-cutoff queries; we don't
-- need Redis for a flow that runs <100x/day.
CREATE TABLE order_recovery_attempts (
    id           UUID PRIMARY KEY,
    email        VARCHAR(254) NOT NULL,
    ip_hash      VARCHAR(64) NOT NULL,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_recovery_email_time ON order_recovery_attempts (email, attempted_at);
CREATE INDEX idx_recovery_ip_time    ON order_recovery_attempts (ip_hash, attempted_at);
