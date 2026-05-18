-- Two-counter inventory model on ticket_tiers.
--
-- A buyer's checkout session reserves seats up front (increments `reserved`);
-- fulfillment promotes the hold to `sold`; expiry/cancellation releases
-- it. The available count any new buyer sees is `quantity - reserved - sold`.
-- The cross-counter invariant `quantity >= reserved + sold` is enforced at the
-- service layer (InventoryService) — not at the DB — because it's racy under
-- concurrent admins lowering `quantity` mid-checkout and we'd rather log/repair
-- than reject the row.
ALTER TABLE ticket_tiers
    ADD COLUMN reserved INTEGER NOT NULL DEFAULT 0
        CONSTRAINT ck_ticket_tiers_reserved_nonneg CHECK (reserved >= 0);
