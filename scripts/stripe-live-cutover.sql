-- Stripe test -> live cutover: reset every stored test-mode Stripe reference.
--
-- Production has run on sk_test_ since day one, so every acct_/prod_/price_ in the
-- database is a test-mode object. Under a live key they do not exist: checkout 502s on
-- the stored product id, the Connect mirror freezes on an unreadable 404 and keeps
-- reporting an org as payable, and re-onboarding is impossible while the org still holds
-- a test acct_. This script clears those references and parks the in-flight rows that
-- would otherwise be swept against Stripe with a dead id.
--
-- RUN IT EXACTLY LIKE THIS (the transaction comes from the runner, not from a BEGIN in
-- this file — that keeps the file pure SQL so StripeLiveCutoverScriptTest can execute
-- this artefact itself rather than a stripped copy of it):
--
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 --single-transaction \
--        -f scripts/stripe-live-cutover.sql
--
-- Run scripts/stripe-live-cutover-preflight.sql first and read it; run
-- scripts/stripe-live-cutover-postcheck.sql after the commit. Full sequence:
-- docs/STRIPE_LIVE_CUTOVER.md.
--
-- Every statement is idempotent through its WHERE clause or by re-deriving what it writes:
-- a second run changes nothing. Statement order is load-bearing — §4 re-derives `reserved`
-- from the holds §3 has already released.
--
-- NOT TOUCHED, deliberately: tickets, settlements, processed_webhook_events, refunds in
-- PENDING/REQUESTED, and every column of orders / payout_runs / disputes other than the
-- statuses below and the §8 test_mode flag. See the runbook.

-- §1  ticket_tiers — drop the platform Product/Price ids.
-- A live key cannot resolve a test prod_, and StripeCheckoutService puts the stored id
-- straight into the line item. Cleared, the tier answers a leak-safe 404 until the
-- organizer re-saves it (TicketTierService.syncTier is the only re-sync path).
UPDATE ticket_tiers
   SET stripe_product_id = NULL,
       stripe_price_id   = NULL
 WHERE stripe_product_id IS NOT NULL
    OR stripe_price_id IS NOT NULL;

-- §2  organizations — back to never-connected.
-- stripe_details_submitted is sticky in StripeConnectStatusMirror.applyTo: left true it
-- would derive RESTRICTED/ACTIVE instead of ONBOARDING on the first live sync.
-- A NULL stripe_connect_status_updated_at means "never synced", which is what makes the
-- first checkout read refresh from Stripe. stripe_livemode goes back to unknown (V129):
-- there is no account left for it to describe.
UPDATE organizations
   SET stripe_account_id                  = NULL,
       stripe_connect_state               = 'NOT_STARTED',
       stripe_payouts_enabled             = FALSE,
       stripe_details_submitted           = FALSE,
       stripe_payout_schedule_manual      = FALSE,
       stripe_requirements_currently_due  = '[]',
       stripe_requirements_past_due       = '[]',
       stripe_disabled_reason             = NULL,
       stripe_connect_status_updated_at   = NULL,
       stripe_livemode                    = NULL
 WHERE stripe_account_id IS NOT NULL
    OR stripe_connect_state <> 'NOT_STARTED'
    OR stripe_payouts_enabled
    OR stripe_details_submitted
    OR stripe_payout_schedule_manual
    OR stripe_disabled_reason IS NOT NULL
    OR stripe_connect_status_updated_at IS NOT NULL
    OR stripe_requirements_currently_due <> '[]'
    OR stripe_requirements_past_due <> '[]'
    OR stripe_livemode IS NOT NULL;

-- §3  ticket_reservations — release every hold.
-- Both kinds: the 30-minute card holds the sweeper would drain anyway, and the V125
-- async holds that would otherwise sit for up to seven days waiting on a payment the
-- live key can never see. The matching test-mode PaymentIntent is NOT cancelled — it is
-- unreachable under the live key, and ReservationSweeper already treats a failed cancel
-- as a warning.
UPDATE ticket_reservations
   SET status         = 'RELEASED',
       released_at    = now(),
       release_reason = 'TEST_MODE_CUTOVER'
 WHERE status = 'HELD';

-- §4  ticket_tiers.reserved — RE-DERIVE the held-seat count, which is 0 once §3 has
-- released every hold. Re-derived, never decremented: a checkout committing between the
-- pre-flight and this script adds a HELD row that a subtraction reading its own snapshot
-- can miss while §3 still releases it, leaving that tier permanently short of stock — and
-- a second run of a subtraction would double-count. `reserved` is derived bookkeeping over
-- the HELD rows (InventoryService), so recomputing it is both the truth and idempotent, and
-- it cannot go negative, which is what the old clamp was for. Scoped to the tiers THIS
-- cutover released, so unrelated pre-existing drift is left for a human. Correlated
-- subquery, not UPDATE ... FROM: the latter is PostgreSQL-only.
UPDATE ticket_tiers t
   SET reserved = (SELECT COALESCE(SUM(r.qty), 0)
                     FROM ticket_reservations r
                    WHERE r.tier_id = t.id
                      AND r.status = 'HELD')
 WHERE EXISTS (SELECT 1
                 FROM ticket_reservations r
                WHERE r.tier_id = t.id
                  AND r.release_reason = 'TEST_MODE_CUTOVER');

-- §5  payout_runs — park every non-terminal run.
-- planned/submitted/retrying are the only non-terminal statuses (stored lowercase by
-- PayoutRunStatusConverter). A submitted run is re-read from Stripe off the ROW's own
-- acct_/po_, and a retrying run is REPLAYED with its original idempotency key — i.e. a
-- real payouts().create against a test account. 'blocked' rather than 'failed' on purpose:
-- blocked is terminal for both sweeps, whereas 'failed' would let the event re-candidate.
-- These rows are flagged test_mode in §8, so they do NOT park a mixed event's live sales
-- forever — existsBlockedNeedingAHuman reads live runs only, and the live net of such an
-- event excludes every test-era order anyway.
UPDATE payout_runs
   SET status         = 'blocked',
       failure_reason = 'TEST_MODE_CUTOVER',
       updated_at     = now()
 WHERE status IN ('planned', 'submitted', 'retrying');

-- §6  refunds — close the unrecovered platform-funded debt.
-- Left open, PostEventPayoutService.recoverPlatformFundedRefunds calls
-- charges().retrieve(<test ch_>) for each of these every night once the org re-onboards:
-- an ERROR line per refund, forever, against a debt that can never clear. The predicate
-- is a deliberate superset of RefundRepository.findUnrecoveredPlatformFundedByOrgId
-- (which also requires SUCCEEDED) so a still-PENDING platform-funded row cannot join the
-- recovery set later.
UPDATE refunds
   SET recovered_at         = now(),
       recovery_reversal_id = 'TEST_MODE_CUTOVER'
 WHERE platform_funded = TRUE
   AND recovered_at IS NULL;

-- §7  disputes — close the open ones.
-- An open dispute is never polled, but it blocks EVERY payout for its org and reduces
-- the event net, and no live charge.dispute.closed will ever arrive for a test du_.
-- withdrawn_reinstated is the only closed status excluded from both effects.
-- This CANNOT restore the tickets DisputeIngestService revoked when the dispute opened —
-- restoreTickets has per-ticket logic with no honest SQL equivalent. The pre-flight
-- counts them; the runbook restores them by hand.
UPDATE disputes
   SET status     = 'withdrawn_reinstated',
       closed_at  = now(),
       updated_at = now()
 WHERE status = 'open';

-- §8  test-era flags (V130) — orders, payout_runs and disputes.
-- These rows are KEPT: an order holds the only record of who owns a ticket and
-- disputes.order_id references it. But their money never reached a real balance, so without
-- the flag a test-era event would later enter findPayoutCandidates under a live key and its
-- fake gross would be disbursed from the organizer's real connected balance, while a test-era
-- payout run or lost dispute would silently shrink a live net. Every row that exists at
-- cutover time is by definition test-era; rows created after the key swap are stamped at
-- creation from the running key's prefix.
UPDATE orders
   SET test_mode = TRUE
 WHERE test_mode = FALSE;

UPDATE payout_runs
   SET test_mode = TRUE
 WHERE test_mode = FALSE;

UPDATE disputes
   SET test_mode = TRUE
 WHERE test_mode = FALSE;
