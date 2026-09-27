-- V135__audience_plans.sql
-- Stored audience plans per event: counts and ranges only, never a membership id. The latest
-- row with superseded_by NULL is current. Locale is not part of a plan: summaries holds one
-- entry per locale. H2/PG-compatible: JSON in TEXT, no native enum, no unnamed CHECK.

CREATE TABLE audience_plans (
    id                      UUID              PRIMARY KEY,
    org_id                  UUID              NOT NULL,
    event_id                UUID              NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    -- cold | warm | hot
    mode                    VARCHAR(8)        NOT NULL,
    capacity                INT               NOT NULL,
    target_pct              INT               NOT NULL,
    target_tickets          INT               NOT NULL,
    tickets_per_order       DOUBLE PRECISION  NOT NULL,
    -- JSON array of class keys the organizer left out
    excluded_segments       TEXT              NOT NULL,
    mailable                INT               NOT NULL,
    -- null in cold mode (not computed)
    expected_low            INT,
    expected_mid            INT,
    expected_high           INT,
    coverage_low            DOUBLE PRECISION,
    coverage_mid            DOUBLE PRECISION,
    coverage_high           DOUBLE PRECISION,
    -- strong | medium | weak | cold
    verdict                 VARCHAR(8)        NOT NULL,
    gap_low                 INT               NOT NULL,
    gap_high                INT               NOT NULL,
    reach_needed            TEXT              NOT NULL,
    gap_exceeds_tribe       BOOLEAN,
    small_groups_not_shown  INT               NOT NULL,
    other_genre_invited     BOOLEAN           NOT NULL,
    other_genre_held_back   INT               NOT NULL,
    -- JSON object reason -> count
    exclusions              TEXT              NOT NULL,
    today_date              DATE              NOT NULL,
    event_date              DATE              NOT NULL,
    launch_date             DATE              NOT NULL,
    d3_date                 DATE,
    event_started           BOOLEAN           NOT NULL,
    actions                 TEXT              NOT NULL,
    logic_version           INT               NOT NULL,
    priors_version          INT               NOT NULL,
    calibration_version     INT               NOT NULL,
    inputs_hash             VARCHAR(64)       NOT NULL,
    portrait_id             UUID,
    -- JSON object locale -> summary; null until a summary exists
    summaries               TEXT,
    model_id                VARCHAR(128),
    tokens_in               INT,
    tokens_out              INT,
    cost_usd                NUMERIC(8, 4),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    superseded_by           UUID              REFERENCES audience_plans(id) ON DELETE SET NULL
);

CREATE INDEX ix_audience_plans_org_event_created ON audience_plans (org_id, event_id, created_at);

CREATE TABLE audience_plan_segments (
    id                  UUID              PRIMARY KEY,
    plan_id             UUID              NOT NULL REFERENCES audience_plans(id) ON DELETE CASCADE,
    position            INT               NOT NULL,
    class               VARCHAR(16)       NOT NULL,
    -- same | adjacent | other | unknown
    genre_fit           VARCHAR(8)        NOT NULL,
    mailable            INT               NOT NULL,
    rate_low            DOUBLE PRECISION  NOT NULL,
    rate_mid            DOUBLE PRECISION  NOT NULL,
    rate_high           DOUBLE PRECISION  NOT NULL,
    tickets_per_order   DOUBLE PRECISION  NOT NULL,
    expected_low        INT               NOT NULL,
    expected_mid        INT               NOT NULL,
    expected_high       INT               NOT NULL,
    -- own | imin | prior
    confidence          VARCHAR(8)        NOT NULL,
    reason              TEXT              NOT NULL
);

CREATE INDEX ix_audience_plan_segments_plan ON audience_plan_segments (plan_id, position);

