-- V143: post-event survey. Per-event switch and URL token (minted on first enable, never rotated),
-- and one row per answer. Answers are never linked to a person: no membership or consent column, by design.
-- Additive only. H2/PG-compatible: JSON in TEXT, no enum, no CHECK.

ALTER TABLE events ADD COLUMN survey_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE events ADD COLUMN survey_token VARCHAR(32) NULL;
CREATE UNIQUE INDEX uq_events_survey_token ON events (survey_token);

CREATE TABLE survey_responses (
    id                UUID         PRIMARY KEY,
    org_id            UUID         NOT NULL,
    event_id          UUID         NOT NULL,
    home_commune      VARCHAR(80),
    -- JSON array of genre bucket keys (genres-v1.yaml whitelist)
    other_genres      TEXT,
    -- friend | instagram | tiktok | facebook | poster | imin | other
    heard_from        VARCHAR(16),
    -- 18_24 | 25_34 | 35_44 | 45_plus
    age_band          VARCHAR(8),
    first_time        BOOLEAN,
    notice_version    VARCHAR(32)  NOT NULL,
    locale            VARCHAR(8)   NOT NULL,
    -- Midnight UTC of the answer day, set by the app: no time of day, so no join to the consent record.
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_survey_responses_event ON survey_responses (event_id);
-- Retention sweep deletes answers older than legal.retention_days.
CREATE INDEX ix_survey_responses_created ON survey_responses (created_at);
