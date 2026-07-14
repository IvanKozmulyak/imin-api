-- Unified provider webhook event log (spec §2.2 V54; numbered V56 to stay
-- forward of the V55 head so Flyway never applies out-of-order).
--
-- ONE log for every outbound-channel provider (resend | bird | meta), not
-- one table per provider. The UNIQUE(provider, provider_event_id) is the
-- idempotent-replay guard: the receiver INSERTs (svix message id for Resend)
-- and treats a duplicate-key violation as "already ingested, ack + skip" —
-- the exact contract processed_webhook_events uses for Stripe (V25).
CREATE TABLE provider_events (
    id                  UUID PRIMARY KEY,
    provider            VARCHAR(16)  NOT NULL,          -- resend | bird | meta
    provider_event_id   VARCHAR(128) NOT NULL,          -- svix-id for Resend
    provider_message_id VARCHAR(64),                    -- Resend email id → recipient row
    campaign_id         UUID,
    recipient_id        UUID,
    type                VARCHAR(64),                    -- email.delivered | email.bounced | ...
    payload             TEXT,                           -- raw JSON (jsonb in PG; TEXT is portable to H2)
    occurred_at         TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_event UNIQUE (provider, provider_event_id)
);

-- Resolve inbound webhooks to a recipient row, and drive the complaint-rate
-- circuit breaker (count complaints per campaign) — spec §7.
CREATE INDEX idx_provider_events_message ON provider_events (provider_message_id);
CREATE INDEX idx_provider_events_campaign_type ON provider_events (campaign_id, type);

-- Attribution tile (spec §3): the funnel query groups by utm_campaign; only
-- utm_source/referrer_host were indexed (V43). Add the missing one.
CREATE INDEX idx_funnel_utm_campaign ON event_funnel_events (utm_campaign);
