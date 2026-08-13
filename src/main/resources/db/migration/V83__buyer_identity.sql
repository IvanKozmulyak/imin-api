-- V83__buyer_identity.sql
-- Buyer accounts (R1.1 of the buyer-accounts epic,
-- imin-public/docs/superpowers/plans/2026-08-11-buyer-accounts-epic.md §4.1).
--
-- These tables are deliberately SEPARATE from `users` / `auth_sessions` /
-- `consumers` (§2.1):
--   * users.email_lower is globally UNIQUE and users.org_id is NOT NULL, so an
--     organizer who buys a ticket with their work address would collide with
--     their own buyer account;
--   * auth_sessions.user_id FKs to users, so sharing the session table means
--     sharing the user table;
--   * consumers is a projection that AudienceBackfillJob rebuilds and
--     DsarService.executeErase deletes — credentials must not live on a row
--     another job may drop.
--
-- Consumer stays the platform identity ANCHOR but there is no FK to it: one
-- account owns N verified addresses and consumers.normalized_email is unique
-- (V47:10), so a singular consumer_id would be wrong. Resolution happens by
-- normalized email at the point of need.

CREATE TABLE buyer_accounts (
    id             UUID          PRIMARY KEY,
    password_hash  VARCHAR(255)  NULL,          -- NULL for Google-only accounts (mirrors users.password_hash)
    display_name   VARCHAR(255)  NULL,
    city           VARCHAR(128)  NULL,          -- matches memberships.city (V48:14)
    locale         VARCHAR(5)    NULL,          -- matches orders.buyer_locale (V78:15)
    status         VARCHAR(16)   NOT NULL DEFAULT 'active',   -- active | delete_pending
    -- activated_at is an EVENT timestamp (when the account first had any
    -- verified address). It is never read as a permission — "this account may
    -- sign in" is derived from the primary buyer_account_emails row having
    -- verified_at IS NOT NULL (§2.3). Two sources of truth for a security
    -- boundary is exactly what §2.1 exists to avoid.
    activated_at   TIMESTAMP WITH TIME ZONE NULL,
    delete_at      TIMESTAMP WITH TIME ZONE NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMP WITH TIME ZONE NULL
);

-- Drives the erasure job (R1.5) and the sweeper's orphan-account pass.
CREATE INDEX ix_buyer_accounts_delete_due ON buyer_accounts (status, delete_at);
-- The sweeper deletes squatted accounts: zero email rows, never activated.
CREATE INDEX ix_buyer_accounts_activated ON buyer_accounts (activated_at, created_at);

CREATE TABLE buyer_account_emails (
    id                UUID          PRIMARY KEY,
    buyer_account_id  UUID          NOT NULL REFERENCES buyer_accounts (id) ON DELETE CASCADE,
    email             VARCHAR(254)  NOT NULL,   -- as typed, for display
    email_normalized  VARCHAR(254)  NOT NULL,   -- lower(trim(email)) via EmailNormalizer
    verified_at       TIMESTAMP WITH TIME ZONE NULL,
    added_via         VARCHAR(16)   NOT NULL,   -- signup | google | manual
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    -- Marker columns. H2 in PG-compat mode (the test suite runs Flyway against
    -- it) has no partial indexes, and a table-level UNIQUE takes no WHERE
    -- clause on Postgres either. Same pattern as refund_requests.pending_marker
    -- (V30:20-26, V30:38-43) and email_verification_codes (V13:19-23): NULLs
    -- are distinct in both engines, so a plain UNIQUE on a column that is only
    -- populated in the case we care about enforces a conditional uniqueness.
    -- The service layer is responsible for keeping both markers in sync:
    --   verified_key IS NOT NULL  <=>  verified_at IS NOT NULL
    --   primary_marker IS NOT NULL =>  verified_at IS NOT NULL
    verified_key      VARCHAR(254)  NULL,       -- = email_normalized while verified, else NULL
    primary_marker    UUID          NULL        -- = buyer_account_id while primary, else NULL
);

-- One VERIFIED address per platform (§2.3 rule 1). Unverified duplicates stay
-- legal on purpose: a global UNIQUE spanning unverified rows would let an
-- attacker squat an address they cannot verify and lock its owner out forever.
CREATE UNIQUE INDEX uq_bae_verified_email ON buyer_account_emails (verified_key);
-- Exactly one primary per account, expressed the only way both engines accept.
CREATE UNIQUE INDEX uq_bae_one_primary    ON buyer_account_emails (primary_marker);
-- An account cannot list the same address twice.
CREATE UNIQUE INDEX uq_bae_account_email  ON buyer_account_emails (buyer_account_id, email_normalized);
-- The orders join (§2.3) and the Consumer-survival guard (§7.2) look up by address.
CREATE INDEX ix_bae_email ON buyer_account_emails (email_normalized);
-- The 72-hour unverified sweep (§4.5).
CREATE INDEX ix_bae_unverified_gc ON buyer_account_emails (verified_at, created_at);

-- Google (and later Apple) identities are matched by SUBJECT, not by email:
-- matching on email breaks the moment a buyer changes the address on their
-- Google account, and forecloses Apple entirely (Hide My Email relay addresses
-- match no past order). Same shape as user_identities (V63:8-18), narrowed to
-- 254 for the email because buyer addresses join orders.email VARCHAR(254).
--
-- No JPA entity yet — R1.2 (credentials / BuyerOAuthService) adds it. The table
-- ships here so the identity migration is one file.
CREATE TABLE buyer_identities (
    id                UUID          PRIMARY KEY,
    buyer_account_id  UUID          NOT NULL REFERENCES buyer_accounts (id) ON DELETE CASCADE,
    provider          VARCHAR(16)   NOT NULL,   -- google (apple later)
    provider_user_id  VARCHAR(255)  NOT NULL,
    email_at_link     VARCHAR(254)  NULL,
    display_name      VARCHAR(255)  NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_buyer_identities_provider_sub UNIQUE (provider, provider_user_id)
);
CREATE INDEX ix_buyer_identities_account ON buyer_identities (buyer_account_id);
