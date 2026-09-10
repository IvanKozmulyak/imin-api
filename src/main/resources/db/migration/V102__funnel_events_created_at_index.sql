-- V102__funnel_events_created_at_index.sql
-- Supports the retention purge (FunnelRetentionJob, imin.analytics.funnel-retention-days).
--
-- V41's only index is (event_id, stage). The nightly purge asks the opposite
-- question — "everything older than this instant, across all events" — which
-- without this index is a full scan of an append-only table that has never had
-- a row removed since it was created.

CREATE INDEX idx_funnel_events_created_at ON event_funnel_events (created_at);
