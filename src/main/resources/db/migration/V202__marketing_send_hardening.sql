-- The Idempotency-Key of the batch a pending row is in, committed before the provider call so a replay after a
-- timeout or a restart carries the same key and the provider answers with the first result instead of mailing again.
ALTER TABLE campaign_recipients
    ADD COLUMN send_key VARCHAR(100),
    ADD COLUMN send_key_at TIMESTAMPTZ;

-- Plain CREATE INDEX: Flyway runs each migration in a transaction; the partial index covers only rows in flight.
CREATE INDEX IF NOT EXISTS ix_campaign_recipients_send_key
    ON campaign_recipients (campaign_id, send_key_at)
    WHERE status = 'pending' AND send_key IS NOT NULL;

-- Why the org's marketing sending is paused (complaint_rate | bounce_rate), and when IMIN staff last resumed it:
-- the breaker counts only events after that instant, so an old complaint cannot pause a resumed org again.
ALTER TABLE organizations
    ADD COLUMN marketing_paused_reason VARCHAR(32),
    ADD COLUMN marketing_resumed_at TIMESTAMPTZ;
