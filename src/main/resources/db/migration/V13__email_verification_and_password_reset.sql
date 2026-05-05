-- 1. Track email verification on users
ALTER TABLE users ADD COLUMN verified_at TIMESTAMP NULL;
-- Backfill so existing users don't get locked out on deploy
UPDATE users SET verified_at = CURRENT_TIMESTAMP WHERE verified_at IS NULL;

-- 2. Email verification codes (4-digit, 10-minute expiry, single-use, max 5 attempts)
CREATE TABLE email_verification_codes (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code            CHAR(4)      NOT NULL,
    expires_at      TIMESTAMP    NOT NULL,
    consumed_at     TIMESTAMP,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_evc_user_active
    ON email_verification_codes(user_id);

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
