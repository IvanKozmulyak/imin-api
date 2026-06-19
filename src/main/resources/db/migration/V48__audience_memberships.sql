-- Audience Tier C: per-org projection of a consumer's activity.
-- One row per (org, consumer). All behavioural and consent state is denormalized here
-- for efficient list/filter queries. consent_status + consent_basis are kept in sync
-- by ConsentService on every capture/unsubscribe (M3).
CREATE TABLE memberships (
    membership_id    UUID         PRIMARY KEY,
    org_id           UUID         NOT NULL,
    consumer_id      UUID         NOT NULL REFERENCES consumers(consumer_id),

    -- status: active | erase_pending
    status           VARCHAR(16)  NOT NULL DEFAULT 'active',

    display_name     VARCHAR(255),
    city             VARCHAR(128),
    genres           TEXT,         -- JSON array via StringListJsonConverter

    -- derived aggregates (recomputed from source rows on each ingestion event)
    events           INT          NOT NULL DEFAULT 0,
    attended         INT          NOT NULL DEFAULT 0,
    no_show          INT          NOT NULL DEFAULT 0,
    orders           INT          NOT NULL DEFAULT 0,
    spend_minor      BIGINT       NOT NULL DEFAULT 0,
    aov_minor        BIGINT       NOT NULL DEFAULT 0,

    first_seen       TIMESTAMP WITH TIME ZONE,
    last_purchase    TIMESTAMP WITH TIME ZONE,
    last_attended    TIMESTAMP WITH TIME ZONE,
    recency_days     INT,

    -- RFM bands 0-5
    rfm_r            SMALLINT     NOT NULL DEFAULT 0,
    rfm_f            SMALLINT     NOT NULL DEFAULT 0,
    rfm_m            SMALLINT     NOT NULL DEFAULT 0,

    -- lifecycle: prospect | firsttime | repeat | vip | lapsing | dormant | wonback
    lifecycle        VARCHAR(16)  NOT NULL DEFAULT 'prospect',

    first_touch_src  VARCHAR(128) NOT NULL DEFAULT 'organic',

    last_email_open  TIMESTAMP WITH TIME ZONE,
    last_email_click TIMESTAMP WITH TIME ZONE,

    -- denormalized consent state (M3) — updated by ConsentService
    -- consent_status: never | subscribed | unsubscribed
    consent_status   VARCHAR(16)  NOT NULL DEFAULT 'never',
    -- consent_basis: explicit | soft_opt_in | NULL (no lawful basis)
    consent_basis    VARCHAR(16),

    nps              SMALLINT,
    vibe             VARCHAR(64),
    quote            TEXT,
    tags             TEXT,         -- JSON array via StringListJsonConverter
    notes            TEXT,

    -- DSAR erasure scheduling (Art.17): set to now()+30d when erase requested
    erase_at         TIMESTAMP WITH TIME ZONE,

    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Tenant isolation: every query must join on org_id
CREATE UNIQUE INDEX ux_memberships_org_consumer  ON memberships (org_id, consumer_id);
-- Lifecycle filter (segment queries)
CREATE INDEX ix_memberships_org_lifecycle        ON memberships (org_id, lifecycle);
-- Keyset pagination sort: (created_at DESC, membership_id DESC) — both non-null (S2)
CREATE INDEX ix_memberships_org_created_id       ON memberships (org_id, created_at DESC, membership_id DESC);
