-- V84__buyer_sessions_and_credentials.sql
-- Buyer session + credential tables (buyer-accounts epic §4.2).
--
-- buyer_sessions is auth_sessions (V5:29-39) with the FK repointed at
-- buyer_accounts and one index the original lacks: ix_buyer_sessions_expiry,
-- without which the hourly sweeper table-scans every night forever.
--
-- The credential tables (codes / reset tokens / attempt counter) ship here so
-- the schema arrives in one migration. Only the sweeper touches them in R1.1;
-- R1.2 adds the signup / verify / login / reset services on top.

CREATE TABLE buyer_sessions (
    id                UUID        PRIMARY KEY,
    buyer_account_id  UUID        NOT NULL REFERENCES buyer_accounts (id) ON DELETE CASCADE,
    token_hash        CHAR(64)    NOT NULL,   -- sha256Hex(raw token); the raw value is never persisted
    issued_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    revoked_at        TIMESTAMP WITH TIME ZONE NULL,
    user_agent        VARCHAR(512) NULL,
    CONSTRAINT uq_buyer_sessions_token UNIQUE (token_hash)
);
CREATE INDEX ix_buyer_sessions_account ON buyer_sessions (buyer_account_id);
CREATE INDEX ix_buyer_sessions_expiry  ON buyer_sessions (expires_at);
-- The sweeper also drops sessions revoked more than 30 days ago (§4.5).
CREATE INDEX ix_buyer_sessions_revoked ON buyer_sessions (revoked_at);

-- NOT a copy of email_verification_codes (V13:8-18): six digits instead of
-- four, HMAC-peppered at rest instead of plaintext, own FK. On the organizer
-- side the code confirms an address on an account that owns nothing; on the
-- buyer side the same code is the gate on joining someone else's purchase
-- history, and GET /buyer/orders hands back an orderToken per order (§2.2).
CREATE TABLE buyer_email_verification_codes (
    id                UUID          PRIMARY KEY,
    buyer_account_id  UUID          NOT NULL REFERENCES buyer_accounts (id) ON DELETE CASCADE,
    email_normalized  VARCHAR(254)  NOT NULL,
    code_hash         CHAR(64)      NOT NULL,   -- HMAC-SHA256(IMIN_BUYER_CODE_SECRET, code)
    expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at       TIMESTAMP WITH TIME ZONE NULL,
    attempts          INTEGER       NOT NULL DEFAULT 0,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT chk_bevc_attempts_range CHECK (attempts >= 0 AND attempts <= 5)
);
-- Composite, not partial: H2 has no WHERE-clause indexes (V13:19-23 does the same).
CREATE INDEX ix_bevc_email_consumed ON buyer_email_verification_codes (email_normalized, consumed_at);
CREATE INDEX ix_bevc_expiry ON buyer_email_verification_codes (expires_at);

-- password_reset_tokens (V13:26-33) with the FK repointed. 30-minute TTL in
-- the service, matching PasswordResetService:24.
CREATE TABLE buyer_password_reset_tokens (
    id                UUID       PRIMARY KEY,
    buyer_account_id  UUID       NOT NULL REFERENCES buyer_accounts (id) ON DELETE CASCADE,
    token_hash        CHAR(64)   NOT NULL,
    expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at       TIMESTAMP WITH TIME ZONE NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_bprt_token UNIQUE (token_hash)
);
CREATE INDEX ix_bprt_expiry ON buyer_password_reset_tokens (expires_at);

-- The test-visible lockout counter (§2.2), modelled on order_recovery_attempts.
-- It exists because RateLimitConfig is @Profile("!test") — a security control
-- the test suite cannot assert on is a security control that will regress.
CREATE TABLE buyer_verification_attempts (
    id                UUID          PRIMARY KEY,
    email_normalized  VARCHAR(254)  NOT NULL,
    attempted_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    succeeded         BOOLEAN       NOT NULL DEFAULT false
);
CREATE INDEX ix_bva_email_time ON buyer_verification_attempts (email_normalized, attempted_at);
