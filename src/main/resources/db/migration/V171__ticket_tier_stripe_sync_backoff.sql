-- Backoff for TierStripeSyncSweeper: claims taken on a tier still missing its Stripe ids, and when it is next due.
ALTER TABLE ticket_tiers ADD COLUMN stripe_sync_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ticket_tiers ADD COLUMN stripe_sync_next_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE ticket_tiers ADD CONSTRAINT ck_ticket_tiers_stripe_sync_attempts_nonneg CHECK (stripe_sync_attempts >= 0);
