-- 1. Track email verification on users
ALTER TABLE users ADD COLUMN verified_at TIMESTAMP NULL;
-- Backfill so existing users don't get locked out on deploy.
-- NOTE: For very large `users` tables (>100K rows), consider an out-of-migration
-- batched backfill before deploying this script. At our current scale this is fine.
UPDATE users SET verified_at = CURRENT_TIMESTAMP WHERE verified_at IS NULL;

-- 2. Email verification codes (4-digit, 10-minute expiry, single-use, max 5 attempts)
CREATE TABLE email_verification_codes (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code            VARCHAR(4)   NOT NULL,
    expires_at      TIMESTAMP    NOT NULL,
    consumed_at     TIMESTAMP,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_evc_attempts_range CHECK (attempts >= 0 AND attempts <= 5)
);
-- Composite index supports the "find active codes for this user" lookup.
-- Postgres can use it with the consumed_at IS NULL predicate efficiently;
-- H2 also accepts this form (unlike a partial WHERE-clause index).
CREATE INDEX idx_evc_user_consumed
    ON email_verification_codes(user_id, consumed_at);

-- 3. Password reset tokens (32-byte random, sha256 stored, 30-minute expiry, single-use)
CREATE TABLE password_reset_tokens (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      CHAR(64)     NOT NULL UNIQUE,
    expires_at      TIMESTAMP    NOT NULL,
    consumed_at     TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_prt_token_hash ON password_reset_tokens(token_hash);
