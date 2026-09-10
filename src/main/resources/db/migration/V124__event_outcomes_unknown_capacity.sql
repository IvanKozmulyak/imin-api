-- V124__event_outcomes_unknown_capacity.sql
-- Two corpus-hygiene fixes to event_outcomes, both internal columns (nothing renders them):
--   1. clear the invented facts on rows frozen with a capacity of 0 (predictor-edge-4);
--   2. rewrite city / genre_family to their merge keys (predictor-edge-3).
--
-- WHY
-- EventValidator.validateForPublish requires no ticket tier, so a free / RSVP-style event
-- publishes with zero tiers. TicketTierRepository.sumQuantityByEventId is
-- `SELECT COALESCE(SUM(t.quantity), 0)`, so the publish-freeze stored capacity = 0 and
-- CapacityBand.of(0) stamped capacity_band = 'LE100'. From then on the row was a member of the
-- <=100 segment of EVERY other organizer's comparable corpus (the three segment queries in
-- EventOutcomeRepository match capacity_band by equality) and of the LE100 pacing curves, and
-- the finalize pass recorded sell_out = false for it: a definitive "did not sell out" for an
-- event whose capacity was never stated.
--
-- Zero capacity is UNKNOWN capacity, not a tiny one. EventOutcomeService now stores NULL for a
-- non-positive tier sum and leaves sell_out NULL when capacity is unknown (the same rule
-- refund_rate already followed for "nothing issued"); this migration applies that to the rows
-- written before it, so they drop out of the capacity-banded segments instead of sitting in the
-- wrong one. Nothing renders these columns — they are corpus inputs only.
--
-- Idempotent: re-running matches nothing, because capacity = 0 no longer occurs.

UPDATE event_outcomes
   SET capacity      = NULL,
       capacity_band = NULL,
       sell_out      = NULL
 WHERE capacity = 0;

-- ---------------------------------------------------------------------------
-- Merge keys for the comparable-corpus segment columns (predictor-edge-3).
--
-- event_outcomes.city and genre_family are matched by equality in the three segment queries and
-- are the free-text components of PacingCurveService's segment keys, but they were frozen from
-- the event's DISPLAY strings — where EventNormalization deliberately PRESERVES case, because
-- both frontends print them verbatim. So `Techno` and `techno`, `Metz` and `METZ` were separate
-- segments: exactly the fragmentation V82 fixed for the buyer facets, and here it shrinks
-- clusterSize, which is what picks the §5 language tier and what the >=5 privacy floor tests.
--
-- EventOutcomeService now freezes EventNormalization.cityKey/genreKey; this applies the same rule
-- to rows written before it, so old and new rows land in one cluster instead of two. The rules
-- match com.imin.iminapi.util.EventNormalization and V82 character for character:
-- `lower(trim(regexp_replace(x, '\s+', ' ', 'g')))`. Do NOT switch to `[[:space:]]` — H2 runs the
-- Java regex engine, where that POSIX class is not a class at all and silently mangles the value
-- (see the V82 header).
--
-- Nothing renders these columns; the display spelling lives on the events row.

UPDATE event_outcomes
   SET city = lower(trim(regexp_replace(city, '\s+', ' ', 'g')))
 WHERE city IS NOT NULL;

UPDATE event_outcomes
   SET genre_family = lower(trim(regexp_replace(genre_family, '\s+', ' ', 'g')))
 WHERE genre_family IS NOT NULL;

-- pacing_curves is a derived cache whose primary key embeds the city/genre spelling, and
-- PacingCurveService.rebuildAll REPLACES the whole table daily. Rows keyed off the old spellings
-- can no longer be looked up (the lookup now builds its key from the merge keys), so they are
-- dead weight either way; dropping them keeps the next rebuild the single source of truth
-- instead of a table holding two key spellings. Until that rebuild runs, a re-forecast falls
-- back to the EXPLICITLY-LABELLED Stage 0 interim — the same behaviour as any segment below the
-- curve threshold, never a silently mis-keyed projection.
DELETE FROM pacing_curves;

-- predictor_segment_status is left alone on purpose: its key also embeds the genre spelling, but
-- the monthly PredictionScoringJob re-derives a row for every segment from event_outcomes, so a
-- row under an old spelling simply stops matching and its successor is written on the next pass.
-- Rewriting the keys here could collide with an already-lower-cased row (segment_key is the PK).
