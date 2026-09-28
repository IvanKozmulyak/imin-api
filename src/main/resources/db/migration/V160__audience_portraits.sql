-- V160__audience_portraits.sql
-- LLM research for a (genre bucket, city) portrait, shared by every org: text and cited URLs only, no sizes (those are
-- computed from open data on read) and no personal data. H2/PG-compatible: JSON in TEXT, no enum, named constraints.

CREATE TABLE audience_portraits (
    id               UUID         PRIMARY KEY,
    -- one of the 8 genre bucket keys (events.genre_key)
    genre_key        VARCHAR(64)  NOT NULL,
    city_key         VARCHAR(100) NOT NULL,
    -- pending (requested, no answer yet) | ready (groups stored) | empty (no usable answer)
    status           VARCHAR(16)  NOT NULL,
    -- JSON list of {label, description, basis, towns[], sources[{url, title}], confidence}
    research_groups  TEXT,
    -- bumped on every generation
    version          INT          NOT NULL DEFAULT 0,
    -- for the later merge with the Prediction Tool's genre profile; no FK until that table exists
    genre_profile_id UUID,
    model_id         VARCHAR(200),
    -- spend of the stored generation, plus any later refresh attempt that failed and kept that content
    tokens_in        INT,
    tokens_out       INT,
    cost_usd         NUMERIC(12, 4),
    generated_at     TIMESTAMP WITH TIME ZONE,
    -- ready: generated_at + 90 days; empty: a short retry window
    expires_at       TIMESTAMP WITH TIME ZONE,
    -- last GET of the pair; the refresh job only renews pairs requested in the last 90 days
    requested_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    -- last refresh-job attempt, whatever its outcome; a recent one keeps the row out of the next batches
    refresh_attempted_at TIMESTAMP WITH TIME ZONE,
    reviewed_by      VARCHAR(200),
    reviewed_at      TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_audience_portraits_pair UNIQUE (genre_key, city_key)
);

CREATE INDEX ix_audience_portraits_refresh ON audience_portraits (generated_at, requested_at);
