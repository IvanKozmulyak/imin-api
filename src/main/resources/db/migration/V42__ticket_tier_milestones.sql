-- Per-tier sales-milestone ledger. One row per (tier, threshold) that has fired,
-- so the 50/80/100% organizer notifications send exactly once even across
-- concurrent confirmSold() calls, webhook retries, and app restarts.
-- The UNIQUE (tier_id, threshold) constraint is the dedup key; the write happens
-- inside InventoryService.confirmSold() under the tier's pessimistic row lock.
CREATE TABLE ticket_tier_milestones (
    id          UUID        PRIMARY KEY,
    tier_id     UUID        NOT NULL REFERENCES ticket_tiers (id) ON DELETE CASCADE,
    threshold   INT         NOT NULL,
    reached_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_tier_milestone UNIQUE (tier_id, threshold)
);
