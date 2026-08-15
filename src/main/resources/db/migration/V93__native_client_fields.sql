-- V93__native_client_fields.sql
-- Two one-way doors for the native app: idempotency on the native checkout, and
-- a client label on the funnel beacon. Both are cheap now and either impossible
-- or permanently half-broken once a binary is in a store, because a shipped app
-- cannot be force-updated.

-- A native client retries on a dropped connection; the web never could, because
-- its checkout was a browser navigation, not a retriable fetch. InventoryService
-- .reserve writes a new hold on EVERY call, before Stripe is touched, so without
-- this a retry mints a second 30-minute hold on real inventory AND a second
-- PaymentIntent. Added after v1.0.0 it would protect only newer binaries — the
-- launch cohort would keep the duplicate-hold behaviour forever.
--
-- The unique index is over a nullable column, exactly like
-- uq_ticket_reservations_session (V27:53): both PostgreSQL and H2 treat NULLs as
-- distinct in a unique index, so unkeyed callers (the whole web flow) are
-- unaffected and only keyed requests participate. No partial index — H2 backs
-- the test suite and does not support one.
ALTER TABLE ticket_reservations ADD COLUMN idempotency_key VARCHAR(128) NULL;
CREATE UNIQUE INDEX uq_ticket_reservations_idem ON ticket_reservations (idempotency_key);

-- Which client the funnel event came from. NULL = web, so every existing row
-- keeps its current meaning and no backfill is needed. Without it, app traffic
-- merges indistinguishably into web "direct" on the live, organizer-facing
-- /api/v1/analytics/attribution read-model and quietly makes already-shipped
-- conversion numbers wrong. Deliberately its own column and NOT utm_source: the
-- shipped auto-tag feature already writes that field, and colliding with it
-- would corrupt live campaign attribution.
ALTER TABLE event_funnel_events ADD COLUMN client VARCHAR(16) NULL;
