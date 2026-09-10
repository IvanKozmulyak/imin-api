-- V124__event_outcomes_unknown_capacity.sql
-- Clear the invented facts on outcome rows frozen with a capacity of 0 (predictor-edge-4).
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
