-- Stripe test -> live cutover: POST-CHECKS. Every row must come back n = 0.
--
-- Separate file, and run AFTER the reset has committed: psql --single-transaction commits
-- at EOF no matter what a SELECT inside the file printed, so a check that lives in the
-- reset file proves nothing about the committed state.
--
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/stripe-live-cutover-postcheck.sql
--
-- One check per UPDATE in scripts/stripe-live-cutover.sql. "Every row is 0" is the whole
-- contract — there is no expected-non-zero row to whitelist. Run it immediately after the
-- reset commits; see docs/STRIPE_LIVE_CUTOVER.md for the rows (the three *_not_marked_test_mode
-- counts) that can legitimately move once the live key is in place and real money arrives.
SELECT 'orgs_with_account' AS check_name, count(*) AS n
  FROM organizations WHERE stripe_account_id IS NOT NULL
UNION ALL
SELECT 'orgs_not_reset', count(*)
  FROM organizations
 WHERE stripe_account_id IS NOT NULL
    OR stripe_connect_state <> 'NOT_STARTED'
    OR stripe_payouts_enabled
    OR stripe_details_submitted
    OR stripe_payout_schedule_manual
    OR stripe_disabled_reason IS NOT NULL
    OR stripe_connect_status_updated_at IS NOT NULL
    OR stripe_requirements_currently_due <> '[]'
    OR stripe_requirements_past_due <> '[]'
    OR stripe_livemode IS NOT NULL
UNION ALL
SELECT 'tiers_with_stripe_ids', count(*)
  FROM ticket_tiers WHERE stripe_product_id IS NOT NULL OR stripe_price_id IS NOT NULL
UNION ALL
SELECT 'tiers_with_negative_reserved', count(*)
  FROM ticket_tiers WHERE reserved < 0
UNION ALL
SELECT 'reservations_held', count(*)
  FROM ticket_reservations WHERE status = 'HELD'
UNION ALL
SELECT 'payout_runs_nonterminal', count(*)
  FROM payout_runs WHERE status IN ('planned', 'submitted', 'retrying')
UNION ALL
SELECT 'refunds_unrecovered_platform_funded', count(*)
  FROM refunds
 WHERE platform_funded = TRUE AND recovered_at IS NULL AND status = 'SUCCEEDED'
UNION ALL
SELECT 'disputes_open', count(*)
  FROM disputes WHERE status = 'open'
UNION ALL
SELECT 'orders_not_marked_test_mode', count(*)
  FROM orders WHERE test_mode = FALSE
UNION ALL
SELECT 'payout_runs_not_marked_test_mode', count(*)
  FROM payout_runs WHERE test_mode = FALSE
UNION ALL
SELECT 'disputes_not_marked_test_mode', count(*)
  FROM disputes WHERE test_mode = FALSE;
