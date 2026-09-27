-- V139__city_open_data.sql
-- Shared cache of public, aggregate open data per city (no personal data). One row per
-- (city_key, dataset); refreshed in place when expires_at passes. H2/PG-compatible:
-- JSON lives in TEXT, no native enum, named constraints only.

CREATE TABLE city_open_data (
    id          UUID                     PRIMARY KEY,
    -- EventNormalization.cityKey of the city name, e.g. 'metz'
    city_key    VARCHAR(120)             NOT NULL,
    -- insee_age | students | frontaliers | osm_venues
    dataset     VARCHAR(32)              NOT NULL,
    -- the source's own period: census year, academic year or reference date
    ref_period  VARCHAR(32)              NOT NULL,
    -- headline figure; NULL when the dataset has none (never 0 for unknown)
    headline    BIGINT,
    -- JSON object with the extracted figures
    payload     TEXT                     NOT NULL,
    source_url  VARCHAR(1000)            NOT NULL,
    licence     VARCHAR(64)              NOT NULL,
    attribution VARCHAR(255)             NOT NULL,
    fetched_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_city_open_data_city_dataset UNIQUE (city_key, dataset)
);
