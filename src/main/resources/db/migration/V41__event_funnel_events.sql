-- V41__event_funnel_events.sql
-- Append-only instrumentation log for the top two stages of the per-event
-- conversion funnel (PAGE_VIEW, CHECKOUT_START). The PAYMENTS_COMPLETED stage
-- is NOT stored here — it is derived from the orders table at read time so
-- webhook replays cannot double-count it. Rows are written by the public
-- /track beacon endpoint; per-stage counts use COUNT(DISTINCT anon_id).

CREATE TABLE event_funnel_events (
    id          UUID PRIMARY KEY,
    event_id    UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    stage       VARCHAR(32) NOT NULL,   -- 'PAGE_VIEW' | 'CHECKOUT_START'
    anon_id     VARCHAR(64) NOT NULL,   -- per-session id from the buyer's sessionStorage
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_funnel_events_event_stage ON event_funnel_events(event_id, stage);
