-- Stripe Connect integration.
--
-- Per-org connected accounts (one Stripe v2 account per organization) and
-- platform-level Stripe Products / Prices for each ticket tier. Products and
-- prices live on the *platform* account (not on the connected account) because
-- destination charges with `transfer_data.destination` accept platform-level
-- price ids.
ALTER TABLE organizations ADD COLUMN stripe_account_id VARCHAR(64);
ALTER TABLE ticket_tiers ADD COLUMN stripe_product_id VARCHAR(64);
ALTER TABLE ticket_tiers ADD COLUMN stripe_price_id VARCHAR(64);
CREATE INDEX ix_org_stripe ON organizations (stripe_account_id);
