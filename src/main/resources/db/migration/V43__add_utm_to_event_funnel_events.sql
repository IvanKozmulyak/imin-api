-- V43__add_utm_to_event_funnel_events.sql
-- UTM attribution: extend the append-only funnel beacon log with the campaign
-- tags the buyer's browser arrived with. All columns are NULLABLE so the
-- existing /track beacon callers that omit them keep working unchanged
-- (backward compatible — no default rewrite of historical rows).
--
-- referrer_host is the bare hostname of document.referrer (e.g. "instagram.com")
-- and powers the "untagged links" suggestion screen. utm_* mirror the standard
-- UTM query parameters captured on landing.

ALTER TABLE event_funnel_events ADD COLUMN utm_source    VARCHAR(128);
ALTER TABLE event_funnel_events ADD COLUMN utm_medium    VARCHAR(128);
ALTER TABLE event_funnel_events ADD COLUMN utm_campaign  VARCHAR(128);
ALTER TABLE event_funnel_events ADD COLUMN utm_content   VARCHAR(128);
ALTER TABLE event_funnel_events ADD COLUMN referrer_host VARCHAR(255);

-- Attribution reads group by source / referrer_host across an org's events.
CREATE INDEX idx_funnel_events_utm_source ON event_funnel_events(utm_source);
CREATE INDEX idx_funnel_events_referrer_host ON event_funnel_events(referrer_host);
