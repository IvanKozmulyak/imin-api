-- V44__org_payout_schedule_manual.sql
-- Track B (manual payouts) Phase 1: per-org flag recording that the connected
-- account's Stripe payout schedule has been flipped to MANUAL via the
-- account-scoped Balance Settings API. Auditable guarantee the Phase 2 payout
-- job trusts before moving money; also gates the onboarding hook + backfill.
--
-- Stripe-FIRST, flag-only-on-success: the column is written true ONLY after
-- Stripe accepts the manual-schedule update (StripePayoutScheduleService),
-- never on failure, so a failed flip is retried by the backfill / next ACTIVE
-- transition. Default FALSE => inert on deploy until STRIPE_PAYOUT_SCHEDULE_MANUAL
-- is enabled. H2/PG-compatible plain BOOLEAN (mirrors V38 brand_logo_on_posters).

ALTER TABLE organizations
  ADD COLUMN stripe_payout_schedule_manual BOOLEAN NOT NULL DEFAULT FALSE;
