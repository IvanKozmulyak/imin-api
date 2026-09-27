-- The audience plan's per-event send count (CandidateSql.SEND_COUNTS) looks campaigns up by
-- event_id at any age; V52 indexed only (org_id, channel, status) and (status, scheduled_at).
-- H2/PG-compatible.
CREATE INDEX idx_campaigns_event_id ON campaigns (event_id);
