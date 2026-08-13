-- V87__buyer_account_area.sql
-- Event reminders (buyer-account-area spec §4.7).
--
-- Per ORDER, not per ticket. The epic's §6.2 says per-ticket; that is wrong —
-- a four-ticket order would send four emails. An order carries exactly one
-- event_id, so the reminder window is unambiguous at the order grain.
--
-- Two markers, not one: the T-24h and T-3h nudges are independent claims. An
-- order that missed its 24h window (bought at T-5h) must still get the 3h one.
ALTER TABLE orders ADD COLUMN reminder_24h_at TIMESTAMP WITH TIME ZONE NULL;
ALTER TABLE orders ADD COLUMN reminder_3h_at  TIMESTAMP WITH TIME ZONE NULL;

-- Indexes for the sweeper's pending scan ("which orders for this event are
-- still owed a nudge?"). The natural shape is a Postgres PARTIAL index
-- (... (event_id) WHERE reminder_24h_at IS NULL), but H2 — which backs the
-- test suite — has no partial indexes. V76 set the portable house fix for
-- exactly this scan and it applies unchanged here: a LEADING marker column
-- clusters the NULLs together, so the scan touches only pending rows and
-- reads event_id straight off the index. Leading with event_id instead would
-- leave the marker unusable as a prefix and defeat the whole point.
CREATE INDEX ix_orders_reminder_24h_pending ON orders (reminder_24h_at, event_id);
CREATE INDEX ix_orders_reminder_3h_pending  ON orders (reminder_3h_at, event_id);
