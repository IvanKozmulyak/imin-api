-- Dead since their writers were removed; empty in prod on 2026-10-12.
SET LOCAL lock_timeout = '10s';
DROP TABLE predictions;
DROP TABLE social_copy;
ALTER TABLE generated_event DROP COLUMN accent_colors, DROP COLUMN poster_urls, DROP COLUMN pricing_notes;
ALTER TABLE concept DROP COLUMN tagline;
ALTER TABLE campaigns DROP COLUMN html_rendered, DROP COLUMN text_rendered;
ALTER TABLE campaign_recipients DROP COLUMN rendered_body;
ALTER TABLE audience_plans DROP COLUMN portrait_id;
ALTER TABLE audience_portraits DROP COLUMN genre_profile_id, DROP COLUMN reviewed_by, DROP COLUMN reviewed_at;
ALTER TABLE auth_sessions DROP COLUMN user_agent;
ALTER TABLE gate_sessions DROP COLUMN revoked_at;
ALTER TABLE event_outcomes DROP COLUMN venue_type, DROP COLUMN indoor_open_air;
ALTER TABLE open_event_occurrence DROP COLUMN credit;
ALTER TABLE provider_events DROP COLUMN payload;
ALTER TABLE events DROP COLUMN sold;
ALTER TABLE memberships DROP COLUMN vibe, DROP COLUMN quote;
