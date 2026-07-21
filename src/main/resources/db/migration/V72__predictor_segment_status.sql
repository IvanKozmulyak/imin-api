-- V72: Per-segment accuracy status + automatic downgrade tripwires (predictor spec §5, §7.3;
-- task 86cav477c). Written by the monthly PredictionScoringJob; read at scoring time so a
-- tripped segment speaks one language tier lower (or drops numeric ranges) on its NEXT render.
--
-- Segment key (kept deliberately simple per the Score-phase brief): genre_family × capacity_band,
-- encoded "<genre_family>|<capacity_band>" — relaxation-aware city/season segmentation can come
-- later without a schema change (new rows, new key shape).
--
-- TRIPWIRES (evaluated only once scored_count >= 20; spec §5):
--   * brier >= base_rate_brier         -> language_tier_override DROP_ONE       (sell-out classification
--                                         not beating the base-rate predictor)
--   * mape  > 0.25                     -> language_tier_override QUALITATIVE    (attendance error too
--                                         high for numeric ranges)
--   * both                             -> DROP_ONE_QUALITATIVE
--
-- DOWNGRADES ARE AUTOMATIC; UPGRADES ARE MANUAL ONLY (spec §5). The job never clears an
-- override, even if metrics recover — it logs a WARN suggesting review instead. To manually
-- restore a segment after founder review:
--
--   UPDATE predictor_segment_status
--      SET language_tier_override = NULL, downgraded_at = NULL,
--          reason = 'manual upgrade <date> by <who>: <justification>'
--    WHERE segment_key = '<genre>|<band>';

CREATE TABLE predictor_segment_status (
    segment_key            VARCHAR(160) PRIMARY KEY,   -- '<genre_family>|<capacity_band>'
    scored_count           INT NOT NULL DEFAULT 0,     -- ledger rows with a joined outcome in this segment
    brier                  NUMERIC(8,6),               -- mean brier_component over scored rows
    base_rate_brier        NUMERIC(8,6),               -- brier of the segment base-rate predictor
    mape                   NUMERIC(10,6),              -- mean APE over scored rows (fraction, 0.25 = 25%)
    language_tier_override VARCHAR(32),                -- NULL | DROP_ONE | QUALITATIVE | DROP_ONE_QUALITATIVE
    downgraded_at          TIMESTAMP WITH TIME ZONE,
    reason                 TEXT,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
