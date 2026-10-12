-- V189__moderation_holds.sql
-- A staff measure against an organizer: sales suspended and/or payouts held, for the whole org
-- (event_id null) or one event. One row per claim; lifting closes that row only. No FK, like
-- audit_logs: the record of who froze whose money outlives an org or event delete.
CREATE TABLE moderation_holds (
    id                  UUID PRIMARY KEY,
    org_id              UUID         NOT NULL,
    event_id            UUID,
    suspend_sales       BOOLEAN      NOT NULL,
    hold_payouts        BOOLEAN      NOT NULL,
    ground              VARCHAR(32)  NOT NULL
        CHECK (ground IN ('aup_breach', 'terms_breach', 'fraud', 'safety', 'authority_order', 'reported_event')),
    facts               TEXT         NOT NULL CHECK (char_length(facts) BETWEEN 1 AND 2000),
    reason_withheld     BOOLEAN      NOT NULL DEFAULT FALSE,
    placed_by           UUID         NOT NULL,
    placed_by_email     VARCHAR(320),
    placed_at           TIMESTAMPTZ  NOT NULL,
    notice_sent_at      TIMESTAMPTZ,
    lifted_at           TIMESTAMPTZ,
    lifted_by           UUID,
    lifted_by_email     VARCHAR(320),
    lift_note           VARCHAR(1000),
    lift_notice_sent_at TIMESTAMPTZ,
    CHECK (suspend_sales OR hold_payouts),
    CHECK ((lifted_at IS NULL) = (lifted_by IS NULL))
);

CREATE INDEX idx_moderation_holds_active_org ON moderation_holds (org_id) WHERE lifted_at IS NULL;
CREATE INDEX idx_moderation_holds_active_event ON moderation_holds (event_id)
    WHERE lifted_at IS NULL AND event_id IS NOT NULL;
