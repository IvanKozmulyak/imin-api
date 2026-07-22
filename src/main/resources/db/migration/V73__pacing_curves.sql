-- V73: Persisted pacing curves (predictor spec §7 Stage 1, task 86cav479d).
-- The deterministic pacing engine builds, per (relaxed) comparable segment, a median + P25/P75
-- spread of NORMALIZED completed-event trajectories (% of final sales vs days-to-event). Those
-- curves are recomputed in the daily job (PacingCurveService.rebuildAll) and READ by the live
-- re-forecast to project an in-flight event's final sold range + sell-out ETA. Projections read
-- the persisted curve; they never rebuild it on the request path.
--
-- PRIVACY (spec §6.4, gate item 5): a curve is a cross-organizer aggregate of %-SHAPES only,
-- built exclusively from segments with >= imin.predictor.min-curve-events (default 12) completed
-- events. It carries no attendance/revenue figures and no event ids — nothing here is
-- attributable to any single foreign event. See PacingCurveService's javadoc for the full
-- privacy argument.
--
-- segment_key encodes the relaxation granularity so the three rungs never collide:
--   NONE            -> 'NONE|<city>|<genre>|<band>|<season>'
--   CITY_TO_COUNTRY -> 'CITY_TO_COUNTRY|<country>|<genre>|<band>|<season>'
--   DROP_SEASON     -> 'DROP_SEASON|<country>|<genre>|<band>'
-- (GENRE_TO_FAMILY is identity today — event_outcomes stores only the family — so no separate
-- key is persisted for it; a projection's ladder walk falls straight through to DROP_SEASON,
-- matching ComparableCorpusService.)
--
-- Rebuilt with replace-all semantics each run (delete + reinsert in one transaction), so it is
-- idempotent and a segment that drops below the floor simply disappears.

CREATE TABLE pacing_curves (
    segment_key   VARCHAR(200) PRIMARY KEY,
    relaxation    VARCHAR(32)  NOT NULL,   -- NONE | CITY_TO_COUNTRY | DROP_SEASON
    points_json   TEXT         NOT NULL,   -- [{daysOut, medianPct, p25Pct, p75Pct}] sorted daysOut DESC
    events_count  INT          NOT NULL,   -- completed events the curve was built from (>= floor)
    computed_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
