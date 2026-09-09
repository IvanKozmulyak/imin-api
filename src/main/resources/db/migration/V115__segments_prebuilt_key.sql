-- Segment resolution routed on the free-text display name: a segment an organizer (or the
-- AI namer) called "VIP" was resolved with the prebuilt VIP query instead of its own
-- rules_json, so the campaign built from it mailed a different list than the dashboard
-- showed. Give the seven system segments a stable key to route on, and backfill the rows
-- that already exist. A NULL key means "custom" — evaluate rules_json, whatever the name.
ALTER TABLE segments ADD COLUMN prebuilt_key VARCHAR(32);

UPDATE segments SET prebuilt_key = 'REPEAT'           WHERE prebuilt = true AND name = 'Repeat'           AND prebuilt_key IS NULL;
UPDATE segments SET prebuilt_key = 'VIP'              WHERE prebuilt = true AND name = 'VIP'              AND prebuilt_key IS NULL;
UPDATE segments SET prebuilt_key = 'LAPSED'           WHERE prebuilt = true AND name = 'Lapsed'           AND prebuilt_key IS NULL;
UPDATE segments SET prebuilt_key = 'FIRST_TIMERS'     WHERE prebuilt = true AND name = 'First-timers'     AND prebuilt_key IS NULL;
UPDATE segments SET prebuilt_key = 'PROMOTERS'        WHERE prebuilt = true AND name = 'Promoters'        AND prebuilt_key IS NULL;
UPDATE segments SET prebuilt_key = 'BOUGHT_NO_SHOWED' WHERE prebuilt = true AND name = 'Bought-no-showed' AND prebuilt_key IS NULL;
UPDATE segments SET prebuilt_key = 'NEWEST_30D'       WHERE prebuilt = true AND name = 'Newest-30d'       AND prebuilt_key IS NULL;

-- Momentum resolves its default target through this key.
CREATE INDEX ix_segments_org_prebuilt_key ON segments (org_id, prebuilt_key);
