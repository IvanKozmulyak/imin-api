-- Audience Tier C: segment definitions.
-- kind='dynamic'  rules evaluated live (re-resolves on each query)
-- kind='static'   snapshot_ids frozen at snapshot time
CREATE TABLE segments (
    id            UUID        PRIMARY KEY,
    org_id        UUID        NOT NULL,
    name          VARCHAR(128) NOT NULL,
    -- kind: dynamic | static
    kind          VARCHAR(8)  NOT NULL DEFAULT 'dynamic',
    prebuilt      BOOLEAN     NOT NULL DEFAULT false,
    -- JSON array of {field, operator, value} rule objects
    rules_json    TEXT,
    -- JSON array of membership_id UUIDs for static snapshots
    snapshot_ids  TEXT,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ix_segments_org ON segments (org_id, created_at DESC);
