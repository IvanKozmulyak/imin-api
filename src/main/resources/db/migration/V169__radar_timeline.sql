-- Per-event Radar mute; each radar run keeps the verdict and risk of its baseline and of itself as they were when it ran.
ALTER TABLE events ADD COLUMN radar_muted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE date_check ADD COLUMN radar_prev_verdict VARCHAR(16);
ALTER TABLE date_check ADD COLUMN radar_prev_risk SMALLINT;
ALTER TABLE date_check ADD COLUMN radar_verdict VARCHAR(16);
ALTER TABLE date_check ADD COLUMN radar_risk SMALLINT;

-- Runs made before this migration: best available, read from today's rows (prod has none while the flag is off).
UPDATE date_check SET
    radar_verdict = (SELECT d.verdict FROM date_check_date d
                      WHERE d.date_check_id = date_check.id AND d.candidate_date = date_check.radar_night),
    radar_risk = (SELECT d.risk_score FROM date_check_date d
                   WHERE d.date_check_id = date_check.id AND d.candidate_date = date_check.radar_night)
WHERE origin = 'radar';
UPDATE date_check SET
    radar_prev_verdict = (SELECT d.verdict FROM date_check_date d
                           WHERE d.date_check_id = date_check.radar_prev_id AND d.candidate_date = date_check.radar_night),
    radar_prev_risk = (SELECT d.risk_score FROM date_check_date d
                        WHERE d.date_check_id = date_check.radar_prev_id AND d.candidate_date = date_check.radar_night)
WHERE origin = 'radar' AND radar_prev_id IS NOT NULL;

-- Same value sets as V162 ck_date_check_date_verdict / ck_date_check_date_risk.
ALTER TABLE date_check ADD CONSTRAINT ck_date_check_radar_verdicts CHECK (
    (radar_prev_verdict IS NULL OR radar_prev_verdict IN ('good', 'adjust', 'move', 'not_enough_data'))
    AND (radar_verdict IS NULL OR radar_verdict IN ('good', 'adjust', 'move', 'not_enough_data')));
ALTER TABLE date_check ADD CONSTRAINT ck_date_check_radar_risks CHECK (
    (radar_prev_risk IS NULL OR radar_prev_risk BETWEEN 0 AND 10)
    AND (radar_risk IS NULL OR radar_risk BETWEEN 0 AND 10));
ALTER TABLE date_check ADD CONSTRAINT ck_date_check_radar_snapshot_shape CHECK (
    ((radar_prev_verdict IS NULL AND radar_prev_risk IS NULL)
        OR (radar_prev_verdict IS NOT NULL AND radar_prev_risk IS NOT NULL))
    AND ((radar_verdict IS NULL AND radar_risk IS NULL) OR (radar_verdict IS NOT NULL AND radar_risk IS NOT NULL))
    AND (origin = 'radar' OR (radar_prev_verdict IS NULL AND radar_verdict IS NULL)));
