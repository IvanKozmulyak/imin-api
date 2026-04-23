CREATE TABLE events (
    id                  UUID         PRIMARY KEY,
    org_id              UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name                VARCHAR(255) NOT NULL DEFAULT '',
    slug                VARCHAR(255) NOT NULL,
    visibility          VARCHAR(16)  NOT NULL DEFAULT 'public',
    status              VARCHAR(16)  NOT NULL DEFAULT 'draft',
    genre               VARCHAR(64)  NOT NULL DEFAULT '',
    type                VARCHAR(64)  NOT NULL DEFAULT '',
    starts_at           TIMESTAMP,
    ends_at             TIMESTAMP,
    timezone            VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    venue_name          VARCHAR(255),
    venue_street        VARCHAR(255) NOT NULL DEFAULT '',
    venue_city          VARCHAR(255) NOT NULL DEFAULT '',
    venue_postal_code   VARCHAR(32)  NOT NULL DEFAULT '',
    venue_country       VARCHAR(2),
    description         TEXT         NOT NULL DEFAULT '',
    poster_url          TEXT,
    video_url           TEXT,
    cover_url           TEXT,
    capacity            INTEGER      NOT NULL DEFAULT 0,
    sold                INTEGER      NOT NULL DEFAULT 0,
    revenue_minor       BIGINT       NOT NULL DEFAULT 0,
    currency            VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    squads_enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    min_squad_size      INTEGER      NOT NULL DEFAULT 3,
    squad_discount_pct  INTEGER      NOT NULL DEFAULT 0,
    on_sale_at          TIMESTAMP,
    sale_closes_at      TIMESTAMP,
    created_by          UUID         NOT NULL REFERENCES users (id),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at        TIMESTAMP,
    deleted_at          TIMESTAMP,
    CONSTRAINT uq_events_org_slug UNIQUE (org_id, slug)
);
CREATE INDEX ix_events_org_status ON events (org_id, status);
CREATE INDEX ix_events_org_starts_at ON events (org_id, starts_at);
CREATE INDEX ix_events_deleted ON events (deleted_at);

CREATE TABLE ticket_tiers (
    id                  UUID         PRIMARY KEY,
    event_id            UUID         NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name                VARCHAR(128) NOT NULL,
    kind                VARCHAR(32)  NOT NULL,
    price_minor         INTEGER      NOT NULL,
    quantity            INTEGER      NOT NULL,
    sold                INTEGER      NOT NULL DEFAULT 0,
    sale_closes_at      TIMESTAMP,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order          INTEGER      NOT NULL DEFAULT 0
);
CREATE INDEX ix_tiers_event ON ticket_tiers (event_id);

CREATE TABLE promo_codes (
    id                  UUID         PRIMARY KEY,
    event_id            UUID         NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    code                VARCHAR(64)  NOT NULL,
    discount_pct        INTEGER      NOT NULL,
    max_uses            INTEGER      NOT NULL,
    used_count          INTEGER      NOT NULL DEFAULT 0,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_promo_event_code UNIQUE (event_id, code)
);

CREATE TABLE predictions (
    event_id            UUID         PRIMARY KEY REFERENCES events (id) ON DELETE CASCADE,
    score               INTEGER      NOT NULL,
    range_low           INTEGER      NOT NULL,
    range_high          INTEGER      NOT NULL,
    confidence_pct      INTEGER      NOT NULL,
    insight             TEXT         NOT NULL,
    model_version       VARCHAR(64)  NOT NULL,
    computed_at         TIMESTAMP    NOT NULL
);
