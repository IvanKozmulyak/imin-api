-- The Stripe description a payout was first requested with, kept so an idempotent replay
-- sends identical params even if the event is renamed meanwhile. NULL on rows planned earlier.
ALTER TABLE payout_runs ADD COLUMN stripe_description VARCHAR(255);
