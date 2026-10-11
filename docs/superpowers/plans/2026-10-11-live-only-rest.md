# Live-only: the remaining organizer and predictor reads

Owner decision (2026-10-10): organizer money and sold figures count LIVE orders only; test-mode
orders (`orders.test_mode`, pre-cutover `sk_test`) are excluded. Seat inventory (`TicketTier.sold`,
`reserved`) and the door still count them. 36960605 applied this to the dashboard, events list and
detail, Overview metrics, Sales, velocity and attribution. This plan covers what is left.

## Rule reused

- Sold for one event: `EventSalesTotals` — `tier.sold − tickets held on test orders − tickets
  revoked by live chargebacks`. New `EventSalesTotals.soldForEvent(UUID)` runs the same private
  sold computation as `forEvents` without the money queries. No copy of the formula.
- Per-tier live tickets: `TicketRepository.liveTierAggregates` (36960605).
- Live orders: `testMode = false` on the order (join or derived predicate), as in 36960605.

## Paths (before → after)

| # | Path | Before | After |
|---|---|---|---|
| 1 | `EventOutcomeService.finalize` (outcome corpus: predictor learning and calibration) | `tierAggregates` all modes: sold_total, sold_per_tier, gross, redeemed → attendance; `findLastSoldCreatedAt`, `countByEventIdAndState('refunded')` and `orders.countByEventId` all modes | `liveTierAggregates`; last-sold, refund count and funnel_paid over live orders only, so sold, refund rate, sell-out and attendance describe one population |
| 2 | `SalesTrajectoryService.materialize` (event_sales_daily → pacing curves, reforecast curve and velocity) | `findSoldTierAndCreatedAt` all modes | live orders only. `findDistinctEventIdsWithSoldTickets` stays all modes so an event with only test sales is still re-materialized, which clears its stale rows |
| 3 | `MomentumEvaluator.evaluateOne` | sold = `SUM(tier.sold)`; velocity from `findCreatedAtAndTotalSince` (all modes); spark from `findSoldCreatedAtSince` (all modes) | sold = `EventSalesTotals.soldForEvent`; velocity from `findLiveCreatedAtAndTotalSince`; spark over live orders (the spark must never contradict the sold scalar beside it) |
| 4 | `SubjectVariantsService.urgency` | "N of M left (P% sold)", both from `tier.sold` | "N of M tickets left" only: seats (`capacity − tier.sold`, all modes); the sold share is dropped (review round 1) |
| 5 | `ReforecastService.recompute` | currentSold = `SUM(tier.sold)`; revenue range weighted by `tier.sold` | currentSold = `soldForEvent`; revenue-range weights are each tier's live ticket count from `liveTierAggregates` × the tier price (price basis unchanged) |
| 6 | `ReforecastJob.run` "with sales" gate | `SUM(tier.sold) > 0` | `soldForEvent > 0` |
| 7 | `ReforecastTriggerService.onTicketsIssued` | newSold = `SUM(tier.sold)` | `soldForEvent` (the ledger's `soldNow` it compares against is now live too) |
| 8 | `EventOverviewService` recentPurchases | `findByEventIdOrderByCreatedAtDesc` (paged) all modes | `findByEventIdAndTestModeFalseOrderByCreatedAtDesc` |
| 9 | `DashboardPulseService` lastSale | org: `findByOrgIdOrderByCreatedAtDesc`; event: paged `findByEventIdOrderByCreatedAtDesc`; both all modes; `@Schema` says "test mode included" | org: `findLiveByOrgIdOrderByCreatedAtDesc`; event: `findByEventIdAndTestModeFalseOrderByCreatedAtDesc`; `@Schema` says live orders only |

Dead after the switch, removed: `TicketRepository.tierAggregates`, `OrderRepository.findCreatedAtAndTotalSince`,
the paged `OrderRepository.findByEventIdOrderByCreatedAtDesc(UUID, Pageable)` and `TicketRepository.countByEventIdAndState`.
Javadocs that link them move to the live variants.

## Not changed (checked)

- `TicketTierDto.sold`, `TicketTierValidator`, `TierAvailability`, `AlmostGone`, `PublicEventService`,
  `InventoryService`, `RefundInventoryRelease`: seat inventory.
- `SalesMilestoneNotifier` / `InventoryService.confirmSold` milestones: tier fill; the 100% crossing
  is the tier closing (also the reforecast tier-transition trigger), so it must follow seats. Reported.
- `MetaConnectionService.funnel` (`orders.countByOrgIdSince`, MetaConnectionService.java:144): reconciled
  against `capiEvents.countSentByCreatedAtSince`, which has no mode filter; filtering one side alone
  would show a fake signal gap. Reported.
- `DraftEventDeletionService` (`orders.countByEventId`): a guard, all modes is right.
- `FanFeatureProjector` per-fan order history: audience features, not money/sold. Reported.
- Payouts: unchanged by decision.

## Docs moved in the same edit

`DashboardPulseResponse.lastSale` `@Schema`; `docs/superpowers/API_CONTRACT.md` Overview note on `recentPurchases`;
javadoc on every changed repository method and service.

## Ordered steps

1. Write the integration tests below; run them against the unfixed code; record red.
2. Add `EventSalesTotals.soldForEvent`; repository variants; switch each path; delete dead methods; javadoc/`@Schema`.
3. Update the tests that named the removed or renamed methods (`DashboardPulseServiceTest`, `DashboardPulseQueriesTest`,
   `TicketAggregateQueryTest`, `SubjectVariantsServiceTest`, `ReforecastServiceTest`, `ReforecastTriggerServiceTest`,
   `MomentumEvaluatorPlanTargetTest`) — constructor args and stubs only; comment refs in `MomentumReadModelTest`, `SalesDashboardServiceTest`.
4. Run the verification command.

## Tests (`@IminIntegrationTest`, one per path; a test-mode order does not count or appear)

1. `EventOutcomeServiceTest.finalize_counts_live_orders_only` — live order 2 tickets (1 redeemed, 1 refunded extra),
   test order 3 tickets (1 redeemed, 1 refunded) + later created_at; assert sold 2, gross, redeemed→attendance 1,
   refund count 1, funnel_paid 1, per-tier json, sellOut against capacity 2 and time-to-sell-out from the live last sale.
2. `SalesTrajectoryServiceTest.materialize_counts_live_orders_only` — test-order tickets on another day are absent.
3. `MomentumEvaluatorTest.test_mode_orders_count_in_neither_sold_velocity_nor_spark` — tier.sold 100/100 with 95 held on a
   test order inside the spark window plus test orders in the 7-day window: launch_push fires (not sold_out), snapshot
   sold 5, velocity7d of the 3 live orders, spark without the test tickets.
4. `SubjectVariantsUrgencyTest` — prompt line "Ticket sales: 40 of 100 tickets left" with 55 of tier.sold 60 test-held.
5. `ReforecastLiveOnlyTest.recompute_sizes_sold_and_revenue_from_live_tickets` — ledger soldNow is live sold, revenue range
   uses live weights (hand-computed).
6. `ReforecastLiveOnlyTest.daily_job_skips_an_event_whose_only_sales_are_test_mode` — via the bean, shedlock expired; the
   live-sales event gets a ledger row (proves the tick ran), the test-only one none.
7. `ReforecastLiveOnlyTest.milestone_trigger_reads_live_sold` (parameterized) — 55 test-held + 5 live: no recompute;
   30 live: recompute.
8. `EventOverviewServiceTest.recent_purchases_leave_out_test_mode_orders` — the newer test order is absent.
9. `DashboardPulseCostTest.last_sale_skips_test_mode_orders` (parameterized org/event scope).

## Review round 1 (FIX_REQUIRED) — changes

- **Momentum seats.** `MomentumMetrics.seatsLeft = max(0, capacity − SUM(tier.sold))` (all modes), written to the
  snapshot. SOLD_OUT fires on `capacity > 0 && seatsLeft <= 0`; `remaining()` (URGENCY headline "LAST N TICKETS",
  URGENCY pace "N of M left") and `triggerContext` read seats; the SOLD_OUT "X of M gone" lines read `capacity − seatsLeft`.
  `sold`, `sellThroughPct`, velocity, spark stay live. Snapshots written before this change have no `seatsLeft`;
  `MomentumService.parseMetrics` falls back to `capacity − sold`, which was the all-mode seat count then.
  Note: SOLD_OUT used to fire on a rounded `sellThroughPct >= 100` (e.g. 995/1000); it now needs every seat gone.
- **Subject lines.** Urgency grounding is "N of M tickets left" (seats); no sold share.
- **Outcomes.** V184 adds `event_outcomes.corpus_excluded` (default false; schema only). `finalize` on an event with
  orders but no live order sets `corpus_excluded = true` and `finalized_at`, writes no figures, and returns: the row
  leaves `findDueForFinalize` (no page starvation). The three segment queries, `PredictionLedgerRepository.findJoinable`
  and `PacingCurveService.rebuildAll` skip excluded rows. An event with no orders at all is still real zero demand.
- **Reforecast job test** runs `ReforecastJob.recomputeWithLiveSales(own events)`, the pass body `run()` now delegates to.

## Review round 2 (FIX_REQUIRED) — changes

- Sell-out in the outcome is seats: `SUM(tier.sold) >= capacity` (every mode), and time-to-sell-out is publish → the
  last seat sold in any mode (`TicketRepository.findLastSoldCreatedAt` is back to all modes). `sold_total`, gross,
  attendance, refunds and `funnel_paid` stay live.
- The test-only branch clears every post-event figure, so a row finalized before V184 loses its all-mode figures on re-run.
- Tests: Momentum `capacity > 0` sold-out guard (quantity-0 event → no suggestion); sold-out card pace
  "100 of 100 gone"; `MomentumService.parseMetrics` fallback for a snapshot without `seatsLeft` (headline + pace);
  parameterized finalize over {no orders, test-only, mixed}; re-finalize clears figures.

## Proposed backfill (NOT shipped — needs the owner's OK for the prod write)

Prod (2026-10-11, read-only): 11 finalized `event_outcomes` rows belong to events with test-mode orders. Re-deriving
them through the code is safer than hand-written figures: un-finalize them so the finalize job recomputes each one
(live figures and seat sell-out for mixed events, `corpus_excluded` with cleared figures for test-only ones), and un-join
their scored renders so scoring re-joins against the new figures (excluded ones never re-join).

Order: deploy this build first (V184 must exist). Run the write any time after deploy; the nightly chain then
does the rest in order — 03:45 SalesTrajectoryJob re-materializes live-only trajectories, 04:30 EventOutcomeFinalizeJob
re-finalizes the reset rows (they are due: ended more than `finalize-grace-days`, default 3, ago), 05:30 Europe/Amsterdam
PacingCurveJob rebuilds curves. Re-join of the un-joined renders waits for the MONTHLY PredictionScoringJob
(05:00 on the 1st), so calibration and `predictor_segment_status` lag until then.

Deleted and CANCELLED events are never re-finalized (`findDueForFinalize` excludes them): resetting theirs only takes
them out of the corpus, which is where they belong (predictor-edge-10). Their stale figures stay on the row.

### Read-only pre-checks

```sql
-- 1. The rows, split by event state (expect 11 in total).
SELECT (e.deleted_at IS NOT NULL) AS deleted, e.status, count(*)
  FROM event_outcomes eo JOIN events e ON e.id = eo.event_id
 WHERE eo.finalized_at IS NOT NULL
   AND EXISTS (SELECT 1 FROM orders o WHERE o.event_id = eo.event_id AND o.test_mode)
 GROUP BY 1, 2;

-- 2. Test-only vs mixed among them.
WITH affected AS (
  SELECT eo.event_id, bool_or(NOT o.test_mode) AS has_live
    FROM event_outcomes eo JOIN orders o ON o.event_id = eo.event_id
   WHERE eo.finalized_at IS NOT NULL
   GROUP BY eo.event_id
  HAVING bool_or(o.test_mode))
SELECT has_live, count(*) FROM affected GROUP BY has_live;

-- 3. Ledger renders the write would un-join.
SELECT l.surface, count(*) FROM prediction_ledger l
 WHERE l.outcome_joined_at IS NOT NULL
   AND EXISTS (SELECT 1 FROM orders o WHERE o.event_id = l.event_id AND o.test_mode)
 GROUP BY l.surface;

-- 4. Segment statuses these outcomes feed, and any language-tier override on them (key = genre_family|capacity_band).
SELECT s.segment_key, s.scored_count, s.language_tier_override, s.downgraded_at
  FROM predictor_segment_status s
 WHERE s.segment_key IN (
        SELECT coalesce(eo.genre_family, '?') || '|' || coalesce(eo.capacity_band, '?')
          FROM event_outcomes eo
         WHERE eo.finalized_at IS NOT NULL
           AND EXISTS (SELECT 1 FROM orders o WHERE o.event_id = eo.event_id AND o.test_mode));
```

An override in check 4 was set from scores that included test orders; decide with the owner whether to keep it before
the monthly job recomputes that segment (a segment left with no joined render keeps its row as is).

### Write

```sql
BEGIN;
UPDATE prediction_ledger l
   SET outcome_joined_at = NULL, actual_sold = NULL, actual_attendance = NULL,
       brier_component = NULL, ape = NULL
 WHERE l.outcome_joined_at IS NOT NULL
   AND EXISTS (SELECT 1 FROM orders o WHERE o.event_id = l.event_id AND o.test_mode);

UPDATE event_outcomes eo
   SET finalized_at = NULL
 WHERE eo.finalized_at IS NOT NULL
   AND EXISTS (SELECT 1 FROM orders o WHERE o.event_id = eo.event_id AND o.test_mode);
COMMIT;
```

### Post-checks (after the next 04:30 run)

```sql
-- 0 rows: every reset outcome of a live, non-deleted event that is past the grace is finalized again.
SELECT eo.event_id FROM event_outcomes eo JOIN events e ON e.id = eo.event_id
 WHERE eo.finalized_at IS NULL
   AND e.deleted_at IS NULL AND e.status <> 'CANCELLED'
   AND e.ends_at < now() - interval '3 days'
   AND EXISTS (SELECT 1 FROM orders o WHERE o.event_id = eo.event_id AND o.test_mode);

-- Test-only ones are excluded with no figures; mixed ones are not excluded and carry figures.
WITH affected AS (
  SELECT eo.event_id, eo.corpus_excluded, (eo.sold_total IS NULL) AS no_figures, bool_or(NOT o.test_mode) AS has_live
    FROM event_outcomes eo JOIN events e ON e.id = eo.event_id JOIN orders o ON o.event_id = eo.event_id
   WHERE eo.finalized_at IS NOT NULL AND e.deleted_at IS NULL AND e.status <> 'CANCELLED'
   GROUP BY eo.event_id, eo.corpus_excluded, eo.sold_total
  HAVING bool_or(o.test_mode))
SELECT has_live, corpus_excluded, no_figures, count(*) FROM affected GROUP BY 1, 2, 3;
-- expect only (false, true, true) and (true, false, false)
```

Forward check: the next live-only event to finalize reads `corpus_excluded = false` with figures set.

## Verification commands

```
./mvnw test -Dtest='EventOutcome*Test,Momentum*Test,SubjectVariants*Test,Reforecast*Test,EventOverview*Test,DashboardPulse*Test,SalesTrajectory*Test,TicketAggregateQueryTest,PacingCurve*Test,SpringContextGuardTest'
```
(Docker up; the full `./mvnw clean test` runs at ship.)

## OpenAPI

Only `DashboardPulseResponse.lastSale` description text changes; no schema shape change. `api:sync` will show a
description diff only.
