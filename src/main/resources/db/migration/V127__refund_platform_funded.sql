-- V127__refund_platform_funded.sql
-- Platform-funded refunds, their recovery, and Dashboard-originated refunds. All additive.
--
-- PLATFORM-FUNDED. reverse_transfer=true pulls the refund out of the connected
-- account; when that balance is short (the money already paid out) Stripe answers
-- balance_insufficient. The refund is then retried with reverse_transfer=false, i.e.
-- paid from the PLATFORM balance, and platform_funded records that imin fronted it.
--
-- RECOVERY. imin pulls that money back with a real transfer reversal on the destination
-- transfer of the refunded charge, BEFORE the next payout reads the available balance.
-- Withholding the debt from a payout instead would only move a number: the money stays in
-- the connected balance while the debt is marked settled, leaving imin permanently short.
-- recovered_at + recovery_reversal_id record that movement, and recovered_at is the claim
-- that stops the same debt being reversed twice.
ALTER TABLE refunds ADD COLUMN platform_funded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE refunds ADD COLUMN recovered_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE refunds ADD COLUMN recovery_reversal_id VARCHAR(64);

CREATE INDEX refunds_platform_funded_idx ON refunds (platform_funded, recovered_at);

-- A refund Stripe reports for a payment intent we know but a refund id we do not
-- (organizer refunded from the Stripe Dashboard) is materialized with no initiating
-- user. NULL is that fact; a fixed "system user" row would be a fabricated actor.
ALTER TABLE refunds ALTER COLUMN initiated_by_user_id DROP NOT NULL;
