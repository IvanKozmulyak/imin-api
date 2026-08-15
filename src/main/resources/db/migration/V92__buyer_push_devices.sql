-- V92__buyer_push_devices.sql
-- Push notification devices for the native fan app (mobile Phase 0).
--
-- One row per installed app that has been granted OS notification permission
-- AND has a signed-in buyer. Guests never register: notify_subscriptions is
-- email-keyed and stays the guest path, which keeps this table free of an
-- unauthenticated write endpoint.
--
-- The token is a delivery ADDRESS, not a credential, so it is stored raw --
-- unlike buyer_sessions.token_hash, nothing here authenticates anybody, and a
-- hash would make sending impossible.
CREATE TABLE buyer_push_devices (
    id                UUID PRIMARY KEY,
    buyer_account_id  UUID NOT NULL REFERENCES buyer_accounts(id) ON DELETE CASCADE,
    expo_token        VARCHAR(255) NOT NULL,
    platform          VARCHAR(16)  NOT NULL,
    locale            VARCHAR(8),
    app_version       VARCHAR(32),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at        TIMESTAMP WITH TIME ZONE NULL,

    -- Globally unique, NOT unique per account. One physical device is one
    -- delivery address: when a second buyer signs in on it, the row must be
    -- RE-POINTED, not duplicated, or the first buyer keeps receiving alerts on
    -- a phone that is no longer theirs. This constraint is the backstop that
    -- turns a re-point bug into a failed write rather than a silent leak.
    CONSTRAINT uq_buyer_push_devices_token UNIQUE (expo_token)
);

-- The fan-out scan is "live devices for these accounts". Leading with the
-- revoked marker clusters the NULLs so the scan touches only live rows and
-- reads buyer_account_id straight off the index -- the portable substitute for
-- a Postgres partial index (... (buyer_account_id) WHERE revoked_at IS NULL),
-- which H2 does not support and H2 backs the test suite. This is the same
-- leading-marker shape V76 and V87 established for exactly this scan; leading
-- with buyer_account_id instead would leave the marker unusable as a prefix.
CREATE INDEX ix_buyer_push_devices_live ON buyer_push_devices (revoked_at, buyer_account_id);

-- The in-app switch for drop-alert pushes. The OS permission is the primary
-- gate; this is what the Notifications screen renders, and what lets someone
-- keep the permission while silencing imin. Default true: registering a device
-- at all is an affirmative act that already required an OS prompt.
ALTER TABLE buyer_notification_preferences
    ADD COLUMN push_drop_alerts BOOLEAN NOT NULL DEFAULT TRUE;
