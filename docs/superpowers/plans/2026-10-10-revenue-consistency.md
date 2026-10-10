# Organizer money and sales: live orders only; LOST chargebacks off attributed revenue

Base: `origin/master` b8e37473. Repo: `imin-api` only.

## Problem

1. Every organizer money and sales figure outside the payout and attribution reads all Stripe
   modes. V130 (`V130__test_mode_flags.sql:13`) deliberately left "organizer revenue, analytics,
   tickets" unfiltered; attribution and campaigns now exclude test mode
   (`OrderRepository.java:197-224`). The live cutover (`scripts/stripe-live-cutover.sql:149-158`)
   flagged every pre-2026-09-18 order and dispute `test_mode = true`, so the org home and event
   views count money that never existed.
2. Attributed revenue (`NetOrderRevenue.sumByKey`, NetOrderRevenue.java:16-23) nets SUCCEEDED
   refunds but not LOST chargebacks, while the payout takes LOST money off
   (`DisputeWithholding.lostShare`, DisputeWithholding.java:125-133, predicate
   `d.status = :lost`, DisputeRepository.java:190-194).

Rule: organizer-facing money and sales counts are live orders only, unless the figure is
explicitly about test activity. None of the figures below is about test activity.

## Figures (before → after)

| # | figure (wire) | computed at | before | after |
|---|---|---|---|---|
| F1 | org home `cycle.revenueMinor`, `cycle.deltas.revenuePct`, `business.totalRevenueMinor` | DashboardRevenue.java:43-49 ← DashboardService.java:139-161 | all-mode orders (OrderRepository.java:180-189), refunds (RefundRepository.java:88-98), OPEN/LOST disputes (DisputeRepository.java:134-148) | live orders, their refunds, live disputes |
| F2 | org home `cycle.ticketsSold`, `cycle.deltas.ticketsPct` | TicketRepository.java:155-165 | tickets on all-mode orders | tickets on live orders |
| F3 | `lastEvent.metrics.avgTicketMinor` | DashboardRevenue.java:52-63 ← DashboardService.java:133-137 | all-mode net / all-mode tickets (TicketRepository.java:168-173) | live net / live tickets |
| F4 | org home `now.nextEvent.sold/revenueMinor`, `now.pct`, `lastEvent.event.sold/revenueMinor`, `lastEvent.metrics.attended` | DashboardService.java:86-104, :117-131 | `tier.sold` − revoked in OPEN/LOST disputes; gross − refunds − withheld, all modes | `EventSalesTotals.forEvent` (same formula, live) — removes the third copy of the formula |
| F5 | events list/detail `sold`, `revenueMinor` | EventSalesTotals.java:37-62 | `tier.sold` − revoked; gross − refunds − withheld, all modes | `tier.sold` − tickets held on test orders − tickets revoked by live disputes; live gross − live refunds − live withheld |
| F6 | Overview `metrics.sold`, `revenueMinor` | EventOverviewService.java:88-94 | as F5, all modes | `EventSalesTotals.forEvent` |
| F7 | Overview `revenueAfterFeesMinor` | EventOverviewService.java:100-105 | all-mode fee (OrderRepository.java:80-81), fee refunds (RefundRepository.java:76-81), organizer share | live |
| F8 | Overview `disputedCount`, `disputedMinor` | EventOverviewService.java:88-89; DisputeRepository.java:83-90 | all-mode OPEN/LOST disputes | live disputes |
| F9 | Overview sales-velocity chart `points` | EventVelocityService.java:81-93; OrderRepository.java:142-149; RefundRepository.java:107-113 | all-mode orders and refunds | live |
| F10 | Sales tab tiles `ticketsSold`, `grossRevenueMinor`, `checkedIn`, `capacityPct`, `checkInRatePct`; tier rows `sold/redeemed/grossRevenueMinor/sellThroughPct`; `topConverting` | SalesDashboardService.java:65-70, :101-134; TicketRepository.java:57-67 | tickets on all-mode orders | tickets on live orders (new `liveTierAggregates`; `tierAggregates` keeps its predictor caller, EventOutcomeService.java:218) |
| F11 | Sales tab `netRevenueMinor` | SalesDashboardService.java:72-76 | all modes | live |
| F12 | Sales funnel `PAYMENTS_COMPLETED` and its drop-off | SalesDashboardService.java:150 | `countByEventId` (all modes; also used by DraftEventDeletionService.java:49, EventOutcomeService.java:276) | new `countByEventIdAndTestModeFalse` |
| F13 | campaign revenue `revMinor`, hub `attributedRevMinor`, channel `revenueMinor` | NetOrderRevenue.java:16-23; OrderRepository.java:197-224 | live total − SUCCEEDED refunds | live total − SUCCEEDED refunds − LOST disputes on the order, clamped at 0 |

Test-mode disputes are filtered on `d.testMode = false` — the payout's own predicate
(DisputeRepository.java:121-122) — in `withholdingRowsByEventIds`, `withholdingRowsByOrgOrderWindow`,
`countDistinctOrderIdByEventIdAndStatusIn` and `countRevokedInDisputedOrdersByEventIds`. Without it
a test dispute on an excluded test order would still come off a live figure.

F5 sold: `tier.sold` counts every non-refunded ticket of both modes (InventoryService.java:253,277;
RefundInventoryRelease.java:65), revoked ones included. Live sold = `tier.sold` − non-refunded tickets
on test orders − tickets revoked by live OPEN/LOST disputes. Tickets are only created with an order
(PaidCheckoutService.java:247, FreeCheckoutService.java:206) and only disputes revoke
(DisputeIngestService.java:319).

F13: only LOST (money gone), not OPEN (at risk) — the attribution figure is what the channel brought
in, and the payout's LOST predicate is `d.status = :lost` with `DisputeStatus.LOST`
(DisputeRepository.java:190-194). OPEN stays in the dashboard/payout withholding set as before.
Per order `max(0, total − refunded − lost)`; for one order this equals the payout's
`DisputeShare.of(...).grossWithheldMinor` cap followed by the clamp.

Unchanged on purpose: capacity (tier quantity); Tickets tab remaining (`tier.sold`, inventory);
Overview `recentPurchases` and pulse `lastSale` (lists of orders, not figures); payout paths.

## Design

- In-place `o.testMode = false` on the order/refund aggregates whose only callers are the views
  above (callers checked by grep of `src/main`): `sumTotalMinorByEventId(s)`,
  `sumApplicationFeeMinorByEventId`, `sumTotalAndApplicationFeeByOrgInWindow`,
  `findCreatedAtAndTotalSince`, the four refund sums and `findSucceededRefundUpdatedAtAndAmountSince`,
  `countSoldByEventId`, `countSoldByOrgInWindow`. Javadoc says "live orders".
- New: `TicketRepository.countHeldOnTestOrdersByEventIds`, `liveTierAggregates`;
  `OrderRepository.countByEventIdAndTestModeFalse`.
- `DisputeWithholding.disputedTicketCount(UUID)` and `TicketRepository.countRevokedInDisputedOrders`
  lose their callers (F4, F6) and are deleted.
- `DashboardService` and `EventOverviewService` read sold/revenue from `EventSalesTotals.forEvent`.
- Attribution rows become `[key, total, succeededRefund, lostDisputed]` via correlated subqueries
  (two left joins would multiply rows); `NetOrderRevenue.sumByKey` subtracts the 4th column.
- `DashboardResponse` `@Schema` (:25-32, :49) and `docs/superpowers/API_CONTRACT.md:419,423`
  replace "test mode included"/"all modes" with "live orders".

## Affected files

- `src/main/java/com/imin/iminapi/repository/OrderRepository.java`
- `src/main/java/com/imin/iminapi/refund/RefundRepository.java`
- `src/main/java/com/imin/iminapi/repository/TicketRepository.java`
- `src/main/java/com/imin/iminapi/dispute/DisputeRepository.java`
- `src/main/java/com/imin/iminapi/dispute/DisputeWithholding.java`
- `src/main/java/com/imin/iminapi/service/event/EventSalesTotals.java`
- `src/main/java/com/imin/iminapi/service/event/EventOverviewService.java`
- `src/main/java/com/imin/iminapi/service/event/SalesDashboardService.java`
- `src/main/java/com/imin/iminapi/service/event/EventVelocityService.java` (javadoc)
- `src/main/java/com/imin/iminapi/service/dashboard/DashboardService.java`
- `src/main/java/com/imin/iminapi/service/dashboard/DashboardRevenue.java`
- `src/main/java/com/imin/iminapi/service/analytics/NetOrderRevenue.java`
- `src/main/java/com/imin/iminapi/service/analytics/AttributionService.java` (comment)
- Javadoc only: `dto/analytics/AttributionResponse.java`, `marketing/dto/CampaignDto.java`,
  `marketing/dto/MarketingHubMetricsDto.java`, `marketing/service/CampaignAttributionService.java`
- `src/main/java/com/imin/iminapi/dto/dashboard/DashboardResponse.java`
- `docs/superpowers/API_CONTRACT.md`
- tests below, plus `DashboardServiceTest` and `EventSalesTotalsTest` (unit, constructor/stub changes)
  and `DisputeWithholdingIntegrationTest.the_payout_figure_leaves_out_test_mode_disputes`, which pinned
  the old all-mode `organizerShareMinor` (2000) and now expects the live 1000

## Tests (integration, `@IminIntegrationTest`), written first and run red on b8e37473

1. `DashboardRevenueTest.test_mode_orders_refunds_disputes_and_tickets_count_nowhere` — live A
   4398/398 (2 tickets) + test T 5349/349 (1 ticket, refund 1000 SUCCEEDED, test LOST 1000):
   window = (4000, 2), `netForEvent` = 4000, `ticketsForEvent` = 2. The existing worked example
   expected (4000, 3) "test mode included"; it now expects (4000, 2) and is renamed.
2. `EventServiceSalesFiguresTest.test_mode_orders_and_their_disputes_and_refunds_are_left_out`
   — tier.sold 4; live 2000 (2 issued); test 2000 (1 issued, 1 revoked, test OPEN 1000, refund 500):
   sold 2, revenue 2000.
3. `EventOverviewServiceTest.test_mode_orders_count_on_neither_this_tab_nor_the_org_home` — live
   + test orders with fee, refund, dispute: sold, revenueMinor, revenueAfterFeesMinor,
   disputedCount, disputedMinor live only; `dashboard.build(...).now().nextEvent()` sold/revenue equal.
4. `SalesDashboardServiceTest.test_mode_orders_are_left_out_of_tiles_tiers_and_funnel`.
5. `EventVelocityServiceTest.test_mode_orders_and_refunds_are_left_out_of_the_buckets`.
6. `AttributionServiceTest` and `CampaignAttributionServiceTest`: parameterized over
   LOST (netted) / OPEN / WON / WITHDRAWN_REINSTATED (not netted), plus LOST beyond the
   unrefunded remainder clamps at 0.

Each guard (order filter, refund filter, dispute filter, test-held subtraction) is removed once
after the fix and the covering test goes red.

## Ordered steps

1. Write tests 1-6; run red on base.
2. Repository changes; services; DTO schema + contract doc.
3. Fix the two unit tests' constructors/stubs; drop `now_card_revenue_is_net_of_refunds_and_disputes`
   (the formula's owner is now EventSalesTotals, covered by EventSalesTotalsTest and test 3).
4. Guard proofs; verification.

## Verification commands

- `docker info`
- `./mvnw test -Dtest='Dashboard*Test,Attribution*Test,CampaignAttribution*Test,SalesDashboard*Test,EventOverview*Test,EventSalesTotals*Test,EventServiceSalesFigures*Test,EventVelocity*Test,DisputeWithholding*Test,TicketAggregateQuery*Test,SpringContextGuardTest'`

## Follow-up (imin-webapp, not in this change)

- Org home Now card "Remaining" = `ticketsTotal − sold` (DashboardHero.tsx:98-102) becomes untrue for
  an event whose test-era tickets still hold seats: sold is now live only, inventory is not. Needs an
  inventory field (tier quantity − tier.sold) from the API, or the figure dropped.
- Revenue labels may add "test payments and lost chargebacks excluded" in EN/ES/FR/UK.

## Addendum: `now.ticketsRemaining` (owner decision 2026-10-10)

Sold is now live only, but test-era tickets in `TicketTier.sold` still hold physical seats, so the
webapp's `ticketsTotal − sold` (DashboardHero.tsx:98-102) would overstate what can be sold.

- `TicketTierRepository.sumRemainingOnEnabledTiersByEventId`: Σ enabled tiers `max(0, quantity − sold − reserved)`, all modes.
- `DashboardResponse.Now.ticketsRemaining` (int, 0 with no next event), documented in `@Schema` and
  `docs/superpowers/API_CONTRACT.md`. On `Now`, next to `ticketsTotal`, rather than on the shared `EventDto`.
- `DashboardService` takes `TicketTierRepository` again; `DashboardServiceTest` constructor updated.
- Test: `EventOverviewServiceTest.now_card_tickets_remaining_counts_the_seats_left_on_enabled_tiers`
  (enabled filter, held seats, per-tier clamp, test-era tickets still taken).
- Comments that claimed organizer reads are unfiltered: `model/Order.java` testMode javadoc,
  `dispute/Dispute.java` testMode javadoc (the migration file stays untouched).

## Review fixes (2026-10-10)

1. `countHeldOnTestOrdersByEventIds` `t.state <> 'refunded'` gets a guard: the events-list test-mode
   case adds a redeemed (held) and a refunded (not in tier.sold) test ticket; tier.sold 5, sold 2.
2. `findCreatedAtAndTotalSince` also feeds `MomentumEvaluator.java:117`; it goes back to all modes, and
   `EventVelocityService` reads a new `findLiveCreatedAtAndTotalSince`. Momentum is not changed here.
3. Now card: webapp switch ships separately after `api:sync`.
4. `now.ticketsRemaining` doc says "not yet sold or held", sale window not considered.
5. The LOST subqueries in `revenueRowsByUtm*` also require `d.testMode = false`.
