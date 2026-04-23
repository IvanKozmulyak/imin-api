CREATE TABLE organizations (
    id              UUID         PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    contact_email   VARCHAR(320) NOT NULL,
    country         VARCHAR(2)   NOT NULL,
    timezone        VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    plan            VARCHAR(32)  NOT NULL DEFAULT 'growth',
    plan_monthly_euros INTEGER   NOT NULL DEFAULT 89,
    currency        VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
    id              UUID         PRIMARY KEY,
    org_id          UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    email           VARCHAR(320) NOT NULL,
    email_lower     VARCHAR(320) NOT NULL,
    name            VARCHAR(255) NOT NULL DEFAULT '',
    password_hash   VARCHAR(255),
    role            VARCHAR(16)  NOT NULL,
    avatar_initials VARCHAR(2)   NOT NULL DEFAULT '',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at  TIMESTAMP,
    CONSTRAINT uq_users_email_lower UNIQUE (email_lower)
);
CREATE INDEX ix_users_org ON users (org_id);

CREATE TABLE auth_sessions (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash      CHAR(64)     NOT NULL,
    issued_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,
    last_used_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at      TIMESTAMP,
    user_agent      VARCHAR(512),
    CONSTRAINT uq_sessions_token UNIQUE (token_hash)
);
CREATE INDEX ix_sessions_user ON auth_sessions (user_id);

CREATE TABLE idempotency_keys (
    id              UUID         PRIMARY KEY,
    org_id          UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    route           VARCHAR(128) NOT NULL,
    "key"           VARCHAR(128) NOT NULL,
    response_status INTEGER      NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uq_idem_key UNIQUE (org_id, route, "key")
);
CREATE INDEX ix_idem_expires ON idempotency_keys (expires_at);
