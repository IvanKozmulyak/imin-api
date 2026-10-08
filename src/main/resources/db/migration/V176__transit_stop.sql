-- V176: IDFM stop positions (dataset "arrets", Licence Ouverte 2.0) for the near-venue rule of questions 6.1/6.2.
-- Internal derived store only: replaced whole by the weekly sync, never served raw.
ALTER TABLE transit_sync_state DROP CONSTRAINT ck_transit_sync_state_source;
ALTER TABLE transit_sync_state
    ADD CONSTRAINT ck_transit_sync_state_source CHECK (source IN ('idfm-prim', 'idfm-stops'));

CREATE TABLE transit_stop (
    source VARCHAR(16) NOT NULL,
    stop_ref VARCHAR(80) NOT NULL,            -- IDFM:<arrid> or IDFM:monomodalStopPlace:<zdaid>
    lat DOUBLE PRECISION NOT NULL,
    lng DOUBLE PRECISION NOT NULL,
    synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_transit_stop PRIMARY KEY (source, stop_ref),
    CONSTRAINT ck_transit_stop_source CHECK (source IN ('idfm-stops'))
);
CREATE INDEX ix_transit_stop_source_lat_lng ON transit_stop (source, lat, lng);
