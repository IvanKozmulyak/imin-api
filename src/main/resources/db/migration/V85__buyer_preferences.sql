-- V85__buyer_preferences.sql
-- Buyer-accounts epic §4.3, verbatim. DDL only: no JPA entities, no services
-- and no endpoints ship with it in R1.1.
--
-- Why it lands here rather than in its own slice. The phasing puts V85 in R1.6,
-- but R1.4 (saved-events merge) reads buyer_saved_events and R1.5 (erasure)
-- deletes from all three tables — both earlier. Shipping the DDL now removes
-- an ordering hazard and costs nothing: an empty table changes no behaviour.
-- Later slices must build ON this file, not add their own V85.

CREATE TABLE buyer_saved_events (
    buyer_account_id  UUID        NOT NULL REFERENCES buyer_accounts (id) ON DELETE CASCADE,
    event_id          UUID        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- The composite PK gives idempotent PUT and the buyer_account_id-prefix
    -- index for free.
    PRIMARY KEY (buyer_account_id, event_id)
);

-- Two columns, both describing senders that do not exist yet (§6).
-- `ticket_delivery` is deliberately absent: delivering a ticket someone paid
-- for is performance of a contract, not marketing, and carries no opt-out.
-- `organizer_updates` is deliberately absent too: it is DERIVED (§6.3), and
-- storing it would create a second source of truth for a legal record.
-- `product_news` defaults FALSE — every other consent default in this schema
-- that defaults true is either transactional or a documented soft opt-in with
-- proof text, and a platform marketing list is neither.
CREATE TABLE buyer_notification_preferences (
    buyer_account_id  UUID     PRIMARY KEY REFERENCES buyer_accounts (id) ON DELETE CASCADE,
    event_reminders   BOOLEAN  NOT NULL DEFAULT TRUE,
    product_news      BOOLEAN  NOT NULL DEFAULT FALSE
);

-- Sticky per-(address, organizer) marketing opt-out. Keyed by address, not by
-- account, so an unsubscribe from someone with no imin account is still
-- recorded and still binds if they open one later. Nothing but account erasure
-- ever deletes a row.
--
-- It cannot be derived from consent_records: ConsentService.unsubscribe writes
-- status = 'unsubscribed' identically regardless of surface, so the only trace
-- of WHERE an unsubscribe came from is consent_records.source on an append-only
-- table. Without this table, the account-level "organizer updates" toggle would
-- be a consent-laundering device — flipping it ON would resurrect organizers
-- the buyer had explicitly unsubscribed from (§6.3).
--
-- The write path lands in R1.6, deliberately BEFORE the Release-2 toggle that
-- reads it: if the table only starts recording when the toggle appears, the
-- first buyer to flip it ON re-subscribes to everyone they left earlier.
CREATE TABLE marketing_optouts (
    email_normalized  VARCHAR(254) NOT NULL,
    org_id            UUID         NOT NULL,
    channel           VARCHAR(16)  NOT NULL,   -- email | sms
    source            VARCHAR(32)  NOT NULL,   -- footer_link | one_click | dsar_object | preference_centre_row
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (email_normalized, org_id, channel)
);
