-- V206: per-event switches for "last tickets" / "price goes up" alerts, and the alerts the evaluator prepares from
-- stored tier facts. One alert per (event, tier, kind) for ever; every status change is a conditional UPDATE.
CREATE TABLE stock_alert_settings (
    event_id               UUID         PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE,
    org_id                 UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    last_tickets_on        BOOLEAN      NOT NULL DEFAULT FALSE,
    -- NULL = auto: min(30, ceil(20% of the tier)), the dashboard's "almost gone" rule; a fixed number only lowers it.
    last_tickets_threshold INT          NULL CHECK (last_tickets_threshold IS NULL OR last_tickets_threshold BETWEEN 1 AND 30),
    price_up_on            BOOLEAN      NOT NULL DEFAULT FALSE,
    price_up_lead_hours    INT          NOT NULL DEFAULT 24 CHECK (price_up_lead_hours IN (24, 48, 72)),
    mode                   VARCHAR(8)   NOT NULL DEFAULT 'ask' CHECK (mode IN ('ask', 'auto')),
    auto_enabled_by        UUID         NULL REFERENCES users(id) ON DELETE SET NULL,
    auto_enabled_at        TIMESTAMP WITH TIME ZONE NULL,
    updated_by             UUID         NULL REFERENCES users(id) ON DELETE SET NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX ix_stock_alert_settings_on ON stock_alert_settings (event_id) WHERE last_tickets_on OR price_up_on;

CREATE TABLE stock_alerts (
    id                  UUID         PRIMARY KEY,
    org_id              UUID         NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    event_id            UUID         NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    -- No FK: a deleted tier keeps its alert, so what a sent email quoted stays readable.
    tier_id             UUID         NOT NULL,
    kind                VARCHAR(16)  NOT NULL CHECK (kind IN ('last_tickets', 'price_up')),
    status              VARCHAR(16)  NOT NULL CHECK (status IN ('ready', 'drafted', 'dismissed', 'expired', 'withdrawn')),
    facts_json          TEXT         NOT NULL,
    facts_at_send_json  TEXT         NULL,
    reach_at_prepare    INT          NOT NULL,
    locale              VARCHAR(2)   NOT NULL,
    campaign_id         UUID         NULL REFERENCES campaigns(id) ON DELETE SET NULL,
    withdrawn_reason    VARCHAR(24)  NULL,
    expired_reason      VARCHAR(24)  NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    acted_at            TIMESTAMP WITH TIME ZONE NULL,
    acted_by            UUID         NULL REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uq_stock_alerts_event_tier_kind UNIQUE (event_id, tier_id, kind)
);

CREATE INDEX ix_stock_alerts_campaign ON stock_alerts (campaign_id);
CREATE INDEX ix_stock_alerts_ready ON stock_alerts (created_at) WHERE status = 'ready';
