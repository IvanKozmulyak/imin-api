-- V175: IDFM PRIM traffic messages (Licence Mobilités) for date-check questions 6.1/6.2. Internal derived store only:
-- never exported raw. Replaced whole on each good poll; transit_sync_state records the last poll per source.
CREATE TABLE transit_disruption (
    id UUID PRIMARY KEY,
    source VARCHAR(16) NOT NULL,
    disruption_id VARCHAR(64) NOT NULL,
    cause VARCHAR(32),
    severity VARCHAR(32),
    kind VARCHAR(8) NOT NULL,
    title VARCHAR(500),                       -- classification input only, never rendered
    lines_json TEXT NOT NULL,                 -- [{ref,label,mode,level:'line'|'stop'}]
    periods_json TEXT NOT NULL,               -- [{begin,end}] as UTC ISO instants
    first_begin TIMESTAMP WITH TIME ZONE NOT NULL,
    last_end TIMESTAMP WITH TIME ZONE NOT NULL,
    last_update TIMESTAMP WITH TIME ZONE,
    synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_transit_disruption UNIQUE (source, disruption_id),
    CONSTRAINT ck_transit_disruption_source CHECK (source IN ('idfm-prim')),
    CONSTRAINT ck_transit_disruption_kind CHECK (kind IN ('strike', 'works', 'other'))
);
CREATE INDEX ix_transit_disruption_source_end ON transit_disruption (source, last_end);

CREATE TABLE transit_sync_state (
    source VARCHAR(16) PRIMARY KEY,
    synced_at TIMESTAMP WITH TIME ZONE,       -- last ok fetch
    feed_updated_at TIMESTAMP WITH TIME ZONE, -- the feed's own lastUpdatedDate of that fetch
    disruption_count INT,
    last_status VARCHAR(16) NOT NULL,
    last_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_transit_sync_state_source CHECK (source IN ('idfm-prim')),
    CONSTRAINT ck_transit_sync_state_status
        CHECK (last_status IN ('ok', 'failed', 'unusable', 'rejected_key', 'rate_limited'))
);
