-- First-class reservation tracking + self-healing sweeper infrastructure.
--
-- Before this migration, ticket_tiers.reserved was a sum-only counter with no
-- per-hold metadata, so nothing local knew when an individual hold should
-- expire. Stripe webhooks were the sole release mechanism; any dropped event
-- left a phantom hold forever. This migration introduces:
--
--   1. ticket_reservations — one row per checkout-time hold, with the
--      Stripe session id (nullable for free checkouts) and an expires_at the
--      sweeper can scan. Status transitions: HELD → CONFIRMED (paid) or
--      HELD → RELEASED (webhook expired/failed, sweeper timeout, or Stripe
--      session-create failed in the API call).
--   2. shedlock — ShedLock's coordination table so the @Scheduled sweeper
--      runs at most once per tick across multiple API replicas.
--   3. Reset of ticket_tiers.reserved → 0. The previous counter values are
--      by-definition stale (the bug being fixed is that they never drained),
--      so we wipe and let the sweeper / new flow rebuild them from real holds.
--      In-flight Stripe sessions at deploy time will hit "Not enough tickets"
--      at fulfilment in the rare case where reserved had drifted; that's an
--      acceptable blast radius for a deploy window.

CREATE TABLE ticket_reservations (
    id                   UUID PRIMARY KEY,
    tier_id              UUID NOT NULL REFERENCES ticket_tiers(id) ON DELETE CASCADE,
    qty                  INTEGER NOT NULL CHECK (qty > 0),
    -- NULL for free checkouts (no Stripe session). When set, uniquely identifies
    -- the hold via the Stripe session id stamped in the metadata.
    stripe_session_id    VARCHAR(255),
    status               VARCHAR(16) NOT NULL CHECK (status IN ('HELD','CONFIRMED','RELEASED')),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- The wall-clock instant past which the sweeper releases a still-HELD row.
    -- Matches the Stripe session expires_at for paid flows.
    expires_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    released_at          TIMESTAMP WITH TIME ZONE,
    confirmed_at         TIMESTAMP WITH TIME ZONE,
    -- Why a HELD row left HELD: WEBHOOK_EXPIRED | WEBHOOK_FAILED | SWEEPER
    -- | STRIPE_CREATE_FAILED | CONFIRMED. Useful for forensics + dashboards.
    release_reason       VARCHAR(32)
);

-- Sweeper scan path: find HELD rows whose expires_at is in the past. Composite
-- index lets the planner range-scan by expires_at after filtering on status.
-- (Postgres-only partial indexes would be tighter; using a plain composite
-- index keeps the migration H2-compatible for tests.)
CREATE INDEX ix_ticket_reservations_status_expires
    ON ticket_reservations (status, expires_at);

-- Used by webhook handlers to resolve a Stripe session_id (from metadata or
-- event payload) back to the originating reservation row. Unique so a session
-- can't be associated with two reservations. Multiple NULL values (free
-- checkouts that have no session) are allowed — standard SQL UNIQUE semantics
-- treat NULL as distinct from NULL.
CREATE UNIQUE INDEX uq_ticket_reservations_session
    ON ticket_reservations (stripe_session_id);

-- ShedLock backing table. Schema is fixed by the library (see
-- https://github.com/lukas-krecan/ShedLock#configure-lockprovider). We use
-- TIMESTAMP WITH TIME ZONE instead of the docs' TIMESTAMP because the rest of the schema
-- already commits to TZ-aware timestamps.
CREATE TABLE shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP WITH TIME ZONE  NOT NULL,
    locked_at   TIMESTAMP WITH TIME ZONE  NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- Wipe stale reserved counters. The new flow rebuilds them on every reserve()
-- call from the ticket_reservations row, and the sweeper drains any pre-deploy
-- holds that were "active" but never released. Any genuinely in-flight Stripe
-- session at deploy time (rare, 30-min window) will fail with 409 at fulfilment
-- inside InventoryService.confirmSold; the buyer can retry. Acceptable.
UPDATE ticket_tiers SET reserved = 0 WHERE reserved <> 0;
