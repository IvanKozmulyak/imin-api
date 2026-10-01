-- V167: an event's radar re-run of its date check is a date_check row of origin 'radar'.
ALTER TABLE date_check ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'organizer';
ALTER TABLE date_check ADD COLUMN radar_milestone SMALLINT;
ALTER TABLE date_check ADD COLUMN radar_night DATE;
ALTER TABLE date_check ADD COLUMN radar_prev_id UUID;
ALTER TABLE date_check ADD CONSTRAINT ck_date_check_origin CHECK (origin IN ('organizer','radar'));
ALTER TABLE date_check ADD CONSTRAINT ck_date_check_radar_milestone
    CHECK (radar_milestone IS NULL OR radar_milestone IN (2, 7, 14, 30));
ALTER TABLE date_check ADD CONSTRAINT ck_date_check_radar_shape CHECK (
    (origin = 'radar' AND radar_milestone IS NOT NULL AND radar_night IS NOT NULL)
 OR (origin = 'organizer' AND radar_milestone IS NULL AND radar_night IS NULL AND radar_prev_id IS NULL));
ALTER TABLE date_check ADD CONSTRAINT fk_date_check_radar_prev
    FOREIGN KEY (radar_prev_id) REFERENCES date_check (id) ON DELETE SET NULL;
-- One run per baseline: a move back to a night re-runs from the new current check, while two writers from the
-- same previous check collide. NULLs are distinct in both engines, so organizer rows never collide.
ALTER TABLE date_check ADD CONSTRAINT uq_date_check_radar_run
    UNIQUE (event_id, radar_night, radar_milestone, radar_prev_id);
CREATE INDEX ix_date_check_org_origin_created ON date_check (org_id, origin, created_at);
