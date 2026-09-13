-- Stripe test -> live cutover — PRE-FLIGHT REPORT (READ ONLY)
--
-- What it is: everything scripts/stripe-live-cutover.sql is about to change, counted and
-- listed before anything is written. Every statement is a SELECT or a psql meta-command;
-- there is no transaction and no temp table. Safe to run against production with a
-- read-only role, and that is the intended way to use it.
--
--   psql "$DATABASE_URL" -f scripts/stripe-live-cutover-preflight.sql
--
-- READ IT BEFORE RUNNING THE RESET. The production database has never been queried for
-- this task, so every count below could be 0 or in the hundreds; the reset is written to
-- be correct either way, but two sections can say "stop":
--   §7  refunds still PENDING/REQUESTED. Reported, NEVER changed — flipping them in SQL
--       would skip the two side effects RefundService performs in the same transaction
--       (deleting the refund_tickets claim and sending the failure email) and leave a
--       UNIQUE(ticket_id) row that 409s every future refund of that ticket. A human
--       decides what to do with these.
--   §8  open disputes. The reset closes them as withdrawn_reinstated, but it CANNOT
--       restore the tickets DisputeIngestService revoked. Any non-zero revoked count here
--       is manual work — see docs/STRIPE_LIVE_CUTOVER.md.
--
-- §2 is the post-cutover worklist: clearing stripe_price_id does NOT self-heal on the next
-- checkout, so each tier listed there has to be re-saved before it can sell again.
--
-- REQUIRES THE V129/V130 COLUMNS (organizations.stripe_livemode, orders/payout_runs/disputes
-- .test_mode). Deploy the build first — §0 of docs/STRIPE_LIVE_CUTOVER.md — or every section
-- reading them errors.

\pset null '(null)'
\echo ''
\echo '==================================================================='
\echo ' STRIPE LIVE CUTOVER — PRE-FLIGHT  (read-only, nothing is written)'
\echo '==================================================================='

\echo ''
\echo '--- §1  organizations holding a Stripe account --------------------'
SELECT stripe_connect_state,
       stripe_payouts_enabled,
       stripe_details_submitted,
       count(*) AS orgs
  FROM organizations
 WHERE stripe_account_id IS NOT NULL
 GROUP BY stripe_connect_state, stripe_payouts_enabled, stripe_details_submitted
 ORDER BY stripe_connect_state;

SELECT id, name, stripe_account_id, stripe_connect_state, stripe_livemode,
       stripe_payouts_enabled, stripe_payout_schedule_manual, stripe_connect_status_updated_at
  FROM organizations
 WHERE stripe_account_id IS NOT NULL
 ORDER BY name;

\echo ''
\echo '--- §2  tiers carrying Stripe ids, and which ones must be re-synced first ---'
-- "Sellable" = LIVE, published, not deleted, not ended. status is written uppercase by the
-- JPA enum mapping; upper() guards against the lowercase default V6 declared.
SELECT count(*)                            AS tiers_with_stripe_ids,
       count(*) FILTER (WHERE sellable)   AS tiers_on_sellable_events
  FROM (
    SELECT (upper(ev.status) = 'LIVE'
             AND ev.published_at IS NOT NULL
             AND ev.deleted_at IS NULL
             AND (ev.ends_at IS NULL OR ev.ends_at > now())) AS sellable
      FROM ticket_tiers t
      JOIN events ev ON ev.id = t.event_id
     WHERE t.stripe_product_id IS NOT NULL OR t.stripe_price_id IS NOT NULL
  ) s;

\echo '    tiers that must be re-saved before they can sell again:'
SELECT ev.id AS event_id, ev.name AS event_name, t.id AS tier_id, t.name AS tier_name,
       t.stripe_product_id, t.stripe_price_id
  FROM ticket_tiers t
  JOIN events ev ON ev.id = t.event_id
 WHERE (t.stripe_product_id IS NOT NULL OR t.stripe_price_id IS NOT NULL)
   AND upper(ev.status) = 'LIVE'
   AND ev.published_at IS NOT NULL
   AND ev.deleted_at IS NULL
   AND (ev.ends_at IS NULL OR ev.ends_at > now())
 ORDER BY ev.name, t.sort_order;

\echo ''
\echo '--- §3  HELD reservations, by kind --------------------------------'
-- async_processing_at IS NULL  = 30-minute card hold, the sweeper drains it anyway.
-- async_processing_at NOT NULL = V125 async hold, up to 7 days; only the reset frees it.
SELECT count(*) FILTER (WHERE async_processing_at IS NULL)     AS card_holds,
       count(*) FILTER (WHERE async_processing_at IS NOT NULL) AS async_holds,
       count(*)                                               AS held_total
  FROM ticket_reservations
 WHERE status = 'HELD';

\echo ''
\echo '--- §4  the reserved re-derive §4 of the reset will apply ---------'
-- The reset releases the holds first and then RE-DERIVES reserved from what is still HELD,
-- i.e. 0 for every tier listed here. `drift` is how far the counter had strayed from the
-- HELD rows before the cutover; a non-zero value is pre-existing and was never real stock.
SELECT t.id AS tier_id, t.name AS tier_name, t.reserved AS reserved_now,
       COALESCE(h.held, 0)          AS held_qty,
       0                            AS reserved_after,
       t.reserved - COALESCE(h.held, 0) AS drift
  FROM ticket_tiers t
  JOIN (SELECT tier_id, SUM(qty) AS held
          FROM ticket_reservations WHERE status = 'HELD' GROUP BY tier_id) h
    ON h.tier_id = t.id
 ORDER BY drift DESC, t.name;

\echo ''
\echo '--- §5  non-terminal payout runs (parked blocked by the reset) ----'
SELECT id, org_id, event_id, status, amount_minor, currency, stripe_account_id,
       stripe_payout_id, attempt, submitted_at
  FROM payout_runs
 WHERE status IN ('planned', 'submitted', 'retrying')
 ORDER BY created_at;

\echo ''
\echo '--- §6  unrecovered platform-funded refunds (imin is owed) --------'
SELECT r.id, r.order_id, r.status, r.amount_minor, r.currency,
       r.stripe_charge_id, r.created_at
  FROM refunds r
 WHERE r.platform_funded = TRUE AND r.recovered_at IS NULL
 ORDER BY r.created_at;

\echo ''
\echo '--- §7  refunds in PENDING / REQUESTED — REPORTED, NOT CHANGED ----'
SELECT status, count(*) AS refunds
  FROM refunds
 WHERE status IN ('PENDING', 'REQUESTED')
 GROUP BY status;

SELECT r.id, r.order_id, r.status, r.amount_minor, r.stripe_refund_id, r.created_at
  FROM refunds r
 WHERE r.status IN ('PENDING', 'REQUESTED')
 ORDER BY r.created_at;

\echo ''
\echo '--- §8  open disputes + the revoked tickets SQL cannot restore ----'
SELECT d.id, d.stripe_dispute_id, d.org_id, d.event_id, d.order_id,
       d.amount_minor, d.currency, d.opened_at,
       (SELECT count(*) FROM tickets tk
         WHERE tk.order_id = d.order_id AND tk.state = 'revoked') AS revoked_tickets_on_order
  FROM disputes d
 WHERE d.status = 'open'
 ORDER BY d.opened_at;

\echo ''
\echo '--- §9  KEEP counts — the size of what is deliberately untouched --'
SELECT (SELECT count(*) FROM orders)                   AS orders_kept,
       (SELECT count(*) FROM tickets)                  AS tickets_kept,
       (SELECT count(*) FROM settlements)              AS settlements_kept,
       (SELECT count(*) FROM processed_webhook_events) AS webhook_events_kept;

\echo ''
\echo '--- §10  rows about to be flagged test_mode (V130) ----------------'
-- Kept rows, but their money never existed: flagged so a test-era event can never feed a
-- live payout, and so no test-era payout run or dispute shrinks a live net. Anything already
-- TRUE is a previous run of the reset.
SELECT count(*) FILTER (WHERE test_mode = FALSE) AS orders_to_flag,
       count(*) FILTER (WHERE test_mode = TRUE)  AS orders_already_flagged,
       COALESCE(SUM(total_minor) FILTER (WHERE test_mode = FALSE), 0) AS gross_minor_to_exclude
  FROM orders;

SELECT count(*) FILTER (WHERE test_mode = FALSE) AS payout_runs_to_flag,
       count(*) FILTER (WHERE test_mode = TRUE)  AS payout_runs_already_flagged
  FROM payout_runs;

SELECT count(*) FILTER (WHERE test_mode = FALSE) AS disputes_to_flag,
       count(*) FILTER (WHERE test_mode = TRUE)  AS disputes_already_flagged
  FROM disputes;
