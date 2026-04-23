ALTER TABLE generated_event
    ADD COLUMN org_id          UUID,
    ADD COLUMN name            TEXT,
    ADD COLUMN description     TEXT,
    ADD COLUMN palette_hexes   TEXT,
    ADD COLUMN confidence_pct  INTEGER;

ALTER TABLE generated_event
    ADD CONSTRAINT fk_generated_event_org
    FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE CASCADE;

CREATE INDEX ix_generated_event_org ON generated_event (org_id);
