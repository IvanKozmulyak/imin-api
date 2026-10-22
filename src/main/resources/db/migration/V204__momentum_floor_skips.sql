-- V204: the Momentum moment the hourly pass could not draft because too few people can get the email.
-- One row per event, counts only (no member ids); the evaluator rewrites or deletes it on every pass.
CREATE TABLE momentum_floor_skips (
    event_id      UUID         PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE,
    org_id        UUID         NOT NULL,
    trigger_type  VARCHAR(24)  NOT NULL,
    sendable      INT          NOT NULL,
    floor         INT          NOT NULL,
    checked_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_momentum_floor_skips_org_checked ON momentum_floor_skips (org_id, checked_at);
