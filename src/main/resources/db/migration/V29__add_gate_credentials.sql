-- Gate-scanner authentication: one shared org-level credential per organization,
-- and a separate session table so gate tokens are completely decoupled from
-- user `auth_sessions` (gate phones never carry a real user identity).
--
-- See the spec at docs/superpowers/specs/2026-05-25-gate-scanner-auth.md (if
-- present); FE consumer is the `imin-tickets-gate` Next.js project.

CREATE TABLE gate_credentials (
    org_id          UUID         PRIMARY KEY REFERENCES organizations (id) ON DELETE CASCADE,
    password_hash   VARCHAR(255) NOT NULL,
    last_rotated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE gate_sessions (
    id              UUID         PRIMARY KEY,
    org_id          UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    token_hash      CHAR(64)     NOT NULL,
    issued_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,
    revoked_at      TIMESTAMP,
    CONSTRAINT uq_gate_sessions_token UNIQUE (token_hash)
);
CREATE INDEX ix_gate_sessions_org ON gate_sessions (org_id);
