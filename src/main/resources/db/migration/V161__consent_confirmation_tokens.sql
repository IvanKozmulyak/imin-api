-- V161: one row per confirmation email sent for a door QR / survey sign-up. The emailed token is signed and
-- names this row; used_at makes it single-use, expires_at bounds it, sent_at drives the 24 h resend window.
-- Rows go with the membership or the consent record (erasure cascades). H2/PG-compatible.

CREATE TABLE consent_confirmation_tokens (
    id                UUID                     PRIMARY KEY,
    org_id            UUID                     NOT NULL,
    membership_id     UUID                     NOT NULL REFERENCES memberships(membership_id) ON DELETE CASCADE,
    consent_record_id UUID                     NOT NULL REFERENCES consent_records(id) ON DELETE CASCADE,
    locale            VARCHAR(8)               NOT NULL,
    sent_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at           TIMESTAMP WITH TIME ZONE NULL
);

CREATE INDEX ix_consent_confirmation_tokens_membership ON consent_confirmation_tokens (membership_id, sent_at);
CREATE INDEX ix_consent_confirmation_tokens_record ON consent_confirmation_tokens (consent_record_id);
