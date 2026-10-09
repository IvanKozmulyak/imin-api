-- The dashboard pulse polls "newest 32 orders of an org / of an event" every 5 s; these let it stop after 32 rows.
-- Plain CREATE INDEX: orders is small in prod and Flyway runs each migration in a transaction, so CONCURRENTLY is unavailable.
CREATE INDEX IF NOT EXISTS idx_orders_org_created_at ON orders (org_id, created_at);
CREATE INDEX IF NOT EXISTS idx_orders_event_created_at ON orders (event_id, created_at);
