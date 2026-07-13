-- Phase 2 §2.2: unified recipient snapshot for both channels.
-- membership_id is NULL … ON DELETE SET NULL so DSAR's hard delete of a membership
-- (DsarService.executeErase) never blocks on this FK; UNIQUE(campaign_id, membership_id)
-- tolerates multiple NULLs in Postgres, so anonymized audit rows remain.
CREATE TABLE campaign_recipients (
    id                  UUID         PRIMARY KEY,
    campaign_id         UUID         NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    membership_id       UUID         REFERENCES memberships(membership_id) ON DELETE SET NULL,
    email               VARCHAR(254),
    phone_e164          VARCHAR(20),
    -- status: pending | sent | delivered | bounced | failed | complained | unsubscribed | skipped
    status              VARCHAR(16)  NOT NULL DEFAULT 'pending',
    skip_reason         VARCHAR(32),
    rendered_body       TEXT,
    segment_count       SMALLINT,
    provider_message_id VARCHAR(64),
    opened_at           TIMESTAMP WITH TIME ZONE,
    clicked_at          TIMESTAMP WITH TIME ZONE,
    delivered_at        TIMESTAMP WITH TIME ZONE,
    last_event_at       TIMESTAMP WITH TIME ZONE,
    attempt_count       SMALLINT     NOT NULL DEFAULT 0,
    error_code          VARCHAR(32),
    CONSTRAINT uq_campaign_recipient UNIQUE (campaign_id, membership_id)
);

CREATE INDEX ix_campaign_recipients_status ON campaign_recipients (campaign_id, status);
CREATE INDEX ix_campaign_recipients_provider_msg ON campaign_recipients (provider_message_id);
