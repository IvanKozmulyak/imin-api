-- V110__payout_runs_remaining_minor.sql
-- Track B: make a CLAMPED payout recoverable.
--
-- PostEventPayoutService pays min(perEventNet, availableBalance). Before this
-- column the clamped run reconciled to 'paid' and the per-event candidate guard
-- (status IN planned/submitted/paid) then excluded the event forever, so an
-- organizer whose balance was short at payout time was permanently owed the
-- remainder with no alert. remaining_minor records what the clamp left behind so
-- the payout.paid reconciliation can land the run on 'partial' instead of 'paid'
-- — 'partial' is not in the candidate exclusion, so the next sweep tops it up.
--
-- Forward-only, additive, H2/PG-compatible. Existing rows default to 0 (=fully
-- covered), which reproduces today's behaviour for everything already written.

ALTER TABLE payout_runs
  ADD COLUMN remaining_minor BIGINT NOT NULL DEFAULT 0;
