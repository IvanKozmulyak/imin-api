-- Notify-me release sender (W0.5): make the one-shot "tickets are available" email
-- durable and non-repeating.
--
-- V23 created notify_subscriptions with the note that no sending pipeline existed yet.
-- NotifyReleaseSender is that pipeline. notified_at is the at-least-once delivery marker:
-- NULL means "still owed an email", non-NULL means "already told them, don't tell again".
-- Re-subscribing after a notification resets it to NULL (NotifySubscriptionService), so a
-- buyer who wants to hear about the next restock is not silently ignored.
ALTER TABLE notify_subscriptions ADD COLUMN notified_at TIMESTAMP WITH TIME ZONE NULL;

-- Index for the sweeper's pending scan ("which events still have un-notified
-- subscribers?"). The design called for a Postgres PARTIAL index
-- (... (event_id) WHERE notified_at IS NULL), but H2 — which backs the test suite —
-- has no partial indexes (same constraint noted in V50/V58). A leading notified_at
-- column gets the same effect portably: NULLs cluster together, so the scan touches
-- only pending rows and can read event_id straight off the index.
CREATE INDEX idx_notify_subscriptions_pending
    ON notify_subscriptions(notified_at, event_id);
