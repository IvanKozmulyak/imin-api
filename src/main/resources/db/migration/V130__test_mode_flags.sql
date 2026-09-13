-- V130__test_mode_flags.sql
-- Marks a row as belonging to a Stripe TEST-mode era: orders, payout_runs, disputes.
--
-- Test-era rows are KEPT (an order holds the only record of who owns a ticket, and
-- disputes.order_id references it) but their money never existed. Left unmarked they keep
-- feeding the per-event payout net, so a live payout would disburse fake revenue against
-- the organizer's real connected balance — or, on the other two tables, subtract fake
-- withholdings from a real one:
--   orders      — gross and application fee of the net.
--   payout_runs — the already-triggered amount subtracted from the net; a test-era payout
--                 moved nothing out of a live balance, so it must not reduce a live net.
--   disputes    — the open/lost face value withheld from the net; a test-era chargeback
--                 clawed back no real money.
-- Every other read (organizer revenue, analytics, tickets, the door) is unchanged.
--
-- DEFAULT FALSE = live. Existing rows are flipped to TRUE by the one-off cutover script
-- (scripts/stripe-live-cutover.sql), not here: the flip belongs to the moment the key is
-- swapped, and a migration would also mark rows created after a future staging restore.
ALTER TABLE orders      ADD COLUMN test_mode BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payout_runs ADD COLUMN test_mode BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE disputes    ADD COLUMN test_mode BOOLEAN NOT NULL DEFAULT FALSE;
