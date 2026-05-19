-- Stripe webhook idempotency table.
--
-- Stripe delivers webhooks at-least-once: the same event id can arrive multiple
-- times (retries, dashboard replays, dropped 2xx ACKs). Without dedup, every
-- side-effecting handler (confirm sold, increment promo usedCount, release
-- reservation) can fire twice and over-count.
--
-- Contract: the handler INSERTs (event_id, type) as the FIRST step inside a
-- REQUIRED transaction. A duplicate-key violation means "already processed,
-- ack and bail." A real failure later in the handler rolls back the INSERT,
-- so the next Stripe retry will re-process from scratch. This is the same
-- pattern Stripe's own webhook docs recommend ("Make your event processing
-- idempotent: store processed event ids").
CREATE TABLE processed_webhook_events (
    stripe_event_id TEXT PRIMARY KEY,
    event_type      TEXT NOT NULL,
    processed_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
