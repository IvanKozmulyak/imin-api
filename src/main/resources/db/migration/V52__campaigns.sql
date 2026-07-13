-- V52: Unified marketing campaigns table (channel discriminator: email|sms).
-- Phase 1 uses the email + shared columns; sms-only and send-audit columns are
-- present-but-nullable so later phases (2/3/4) extend behaviour without a schema fork.
-- Timezone is NOT stored per campaign: quiet hours / scheduled_at read organizations.timezone (V5).

CREATE TABLE campaigns (
    id                      UUID PRIMARY KEY,
    org_id                  UUID NOT NULL,
    channel                 VARCHAR(8)   NOT NULL,                       -- email|sms
    name                    VARCHAR(120) NOT NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'draft',       -- draft|scheduled|sending|sent|failed|canceled
    segment_id              UUID,
    event_id                UUID,
    scheduled_at            TIMESTAMP WITH TIME ZONE,
    sent_at                 TIMESTAMP WITH TIME ZONE,
    recipient_count         INT,
    excluded_count          INT,
    exclusion_summary       TEXT,                                        -- JSON blob (SendGate audit snapshot); TEXT for H2/PG parity
    attempts                SMALLINT     NOT NULL DEFAULT 0,
    last_error              TEXT,
    origin                  VARCHAR(16)  NOT NULL DEFAULT 'manual',      -- manual|momentum
    momentum_suggestion_id  UUID,
    -- email-only:
    subject                 VARCHAR(200),
    preheader               VARCHAR(200),
    body_md                 TEXT,
    html_rendered           TEXT,
    text_rendered           TEXT,
    -- sms-only (unused in Phase 1, present for forward compatibility):
    body_template           TEXT,
    sender_id               VARCHAR(11),
    created_by              UUID,
    created_at              TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMP WITH TIME ZONE  NOT NULL DEFAULT now()
);

CREATE INDEX idx_campaigns_org_channel_status ON campaigns (org_id, channel, status);
CREATE INDEX idx_campaigns_status_scheduled  ON campaigns (status, scheduled_at);
