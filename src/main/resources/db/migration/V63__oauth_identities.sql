-- External OAuth / OIDC identities (Google, Sign in with Apple).
--
-- Each row links one imin user to one provider identity. A user may link
-- several providers (one row each); (provider, provider_user_id) is globally
-- unique so the same Google/Apple account can never map to two imin users.
-- The row is created the first time a provider account authenticates and is
-- deleted when Apple sends a consent-revoked / account-delete server notice.
CREATE TABLE user_identities (
    id                UUID         PRIMARY KEY,
    user_id           UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider          VARCHAR(16)  NOT NULL,
    provider_user_id  VARCHAR(255) NOT NULL,
    email             VARCHAR(320),
    display_name      VARCHAR(255),
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_identities_provider_sub UNIQUE (provider, provider_user_id)
);
CREATE INDEX ix_user_identities_user ON user_identities (user_id);
