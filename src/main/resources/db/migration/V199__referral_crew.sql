-- V199__referral_crew.sql
-- Crew links: every order gets a share code (?ref=), a friend's order points at the order whose link it used,
-- and an event may offer a non-monetary perk that unlocks once enough tickets were bought with one link.

SET LOCAL lock_timeout = '10s';

-- (a) The share code. Order.onWrite sets 10 base62 chars; the volatile default gives every existing row, and any
-- insert that omits the column (the previous build during a rolling deploy, raw SQL, demo seeds), 12 hex chars.
ALTER TABLE orders ADD COLUMN referral_code VARCHAR(16) NOT NULL
    DEFAULT left(md5(gen_random_uuid()::text || clock_timestamp()::text), 12);
CREATE UNIQUE INDEX uq_orders_referral_code ON orders (referral_code);

-- (b) The order whose link this order was bought with. Insert-only; SET NULL keeps a friend's order when an event
-- delete cascades through both rows in any order.
ALTER TABLE orders ADD COLUMN referred_by_order_id UUID REFERENCES orders (id) ON DELETE SET NULL;
CREATE INDEX idx_orders_referred_by ON orders (referred_by_order_id) WHERE referred_by_order_id IS NOT NULL;

-- (c) One perk per event. enabled = false is "stop new unlocks": existing unlocks keep their snapshot.
CREATE TABLE event_referral_perks (
    event_id   UUID PRIMARY KEY REFERENCES events (id) ON DELETE CASCADE,
    label      VARCHAR(60)  NOT NULL CHECK (char_length(btrim(label)) BETWEEN 3 AND 60),
    threshold  SMALLINT     NOT NULL CHECK (threshold BETWEEN 1 AND 20),
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    updated_by UUID REFERENCES users (id) ON DELETE SET NULL
);

-- (d) One unlock per referrer order. label/threshold are the snapshot the buyer was promised; given_* is the door.
CREATE TABLE referral_perk_unlocks (
    order_id       UUID PRIMARY KEY REFERENCES orders (id) ON DELETE CASCADE,
    event_id       UUID         NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    label          VARCHAR(60)  NOT NULL,
    threshold      SMALLINT     NOT NULL,
    unlocked_at    TIMESTAMPTZ  NOT NULL,
    given_at       TIMESTAMPTZ,
    given_via      VARCHAR(16) CHECK (given_via IN ('gate', 'dashboard')),
    given_by_label VARCHAR(120),
    CHECK ((given_at IS NULL) = (given_via IS NULL))
);

CREATE INDEX idx_referral_perk_unlocks_event ON referral_perk_unlocks (event_id);
