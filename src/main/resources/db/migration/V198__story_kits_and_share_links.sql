-- Short links an organizer hands out (one per event and purpose) and the Instagram kit made for an event.

CREATE TABLE share_links (
    code         VARCHAR(12) PRIMARY KEY,
    org_id       UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    event_id     UUID        NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    purpose      VARCHAR(32) NOT NULL,
    utm_source   VARCHAR(128) NOT NULL,
    utm_medium   VARCHAR(128) NOT NULL,
    utm_campaign VARCHAR(128) NOT NULL,
    created_by   UUID        REFERENCES users(id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_share_links_event_purpose UNIQUE (event_id, purpose)
);

CREATE INDEX ix_share_links_org ON share_links (org_id);
CREATE INDEX ix_share_links_created_by ON share_links (created_by);

-- assets / skipped are JSON text like the other marketing tables; revision serialises remake vs caption writes.
CREATE TABLE story_kits (
    id                   UUID PRIMARY KEY,
    org_id               UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    event_id             UUID        NOT NULL UNIQUE REFERENCES events(id) ON DELETE CASCADE,
    share_link_code      VARCHAR(12) NOT NULL REFERENCES share_links(code) ON DELETE CASCADE,
    locale               VARCHAR(2)  NOT NULL,
    assets               TEXT        NOT NULL,
    skipped              TEXT        NOT NULL,
    ai_derived           BOOLEAN     NOT NULL,
    source_poster_url    TEXT        NOT NULL,
    source_starts_at     TIMESTAMPTZ,
    source_timezone      VARCHAR(64) NOT NULL,
    source_venue_name    TEXT,
    source_venue_city    TEXT        NOT NULL DEFAULT '',
    caption              TEXT,
    caption_ai_generated BOOLEAN,
    caption_created_at   TIMESTAMPTZ,
    revision             INT         NOT NULL DEFAULT 0,
    created_by           UUID        REFERENCES users(id) ON DELETE SET NULL,
    created_at           TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_story_kits_org ON story_kits (org_id);
CREATE INDEX ix_story_kits_share_link ON story_kits (share_link_code);
CREATE INDEX ix_story_kits_created_by ON story_kits (created_by);
