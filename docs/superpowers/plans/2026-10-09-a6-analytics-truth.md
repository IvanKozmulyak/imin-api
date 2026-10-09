# A6 — Analytics truth (attribution visitors + net revenue)

Repo: imin-api only. Backlog: workspace `docs/superpowers/plans/2026-10-06-dashboard-follow-ups.md` A6.

## Problem

1. `GET /api/v1/analytics/attribution` and `/untagged` report `visits` as `count(fe)` over
   `event_funnel_events` (`FunnelEventRepository.java:58-119`), so one buyer reloading the page,
   or a page view followed by a checkout start, counts two or more times.
2. Channel revenue (`OrderRepository.sumRevenueByUtmSource`, `OrderRepository.java:238-243`)
   sums `orders.total_minor` with no refund netting and no `test_mode` filter, so refunded money
   and test-mode checkouts are shown as revenue. The webapp copy says "refunds not taken off".

## Decisions

- **Visits become visitors.** Every count on both endpoints becomes `count(distinct fe.anonId)`
  (anon_id is NOT NULL, `FunnelEvent.java:41`). Per source: distinct visitors that had at least
  one beacon carrying that source. A visitor seen on two sources counts once in each row.
- **untaggedPct** = distinct visitors with at least one untagged beacon / distinct visitors
  overall (same client slice), from one extra aggregate query per slice; it is no longer derived
  from summing per-source rows (which would double count people seen on two sources).
- **Untagged list** (`/untagged`) counts distinct visitors per referrer host, ordered by that.
- JSON field names stay (`visits`); this is a semantic change, reported to the webapp follow-up.
- **Revenue net** per order, same rule as the payout net (`DashboardRevenue.net`,
  `OrderRepository.settlementRowsByEventId`): `max(0, total_minor - sum(SUCCEEDED refunds.amount_minor))`,
  live-mode orders only (`o.testMode = false`), so a test-mode order and its refunds are out.
  Partial refunds: all SUCCEEDED refunds of the order are summed and subtracted; REQUESTED,
  PENDING, FAILED, CANCELED are ignored. Disputes are not netted (not in scope).
- **Currency:** a refund is in its order's currency, so netting per order is same-currency.
  The per-source sum adds minor units across orders as before (no conversion); unchanged.
- Query returns one row per tagged live order `[utmSource, totalMinor, refundedMinor]`; the
  clamp and the per-source sum happen in Java. ponytail: one row per tagged order; move the
  clamp into SQL if an org's tagged-order count makes this read slow.

## Affected files

- `src/main/java/com/imin/iminapi/repository/FunnelEventRepository.java`
- `src/main/java/com/imin/iminapi/repository/OrderRepository.java`
- `src/main/java/com/imin/iminapi/service/analytics/AttributionService.java`
- `src/main/java/com/imin/iminapi/dto/analytics/AttributionResponse.java` (javadoc only)
- `src/main/java/com/imin/iminapi/dto/analytics/UntaggedLinksResponse.java` (javadoc only, if it says visits)
- `src/test/java/com/imin/iminapi/service/analytics/AttributionServiceTest.java`

## Ordered steps

1. Tests first in `AttributionServiceTest` (`@IminIntegrationTest`, existing class):
   - repeated beacons from one anon_id (page view x2 + checkout start, same source) count once
     in the channel row; untagged list counts one anon with two untagged beacons once;
     untaggedPct over distinct visitors (one visitor tagged+untagged, one tagged only → 50%).
   - revenue: order 4000 fully refunded (SUCCEEDED 4000) → 0; order 5000 with SUCCEEDED 1000 +
     SUCCEEDED 500 + PENDING 2000 + FAILED 1000 → 3500; channel and attributedRevenueMinor agree.
   - test-mode order (and its SUCCEEDED refund) excluded from channel revenue.
   Run them against the unfixed code and record red.
2. Repository queries: distinct-anon per source (3 slices), per-slice totals
   `[distinct anon, distinct untagged anon]` (3 slices), distinct-anon untagged by host;
   `revenueRowsByUtmSource(orgId)` with the live-mode + SUCCEEDED-refund left join.
3. Service: wire the new queries; untaggedPct from the totals; revenue clamp per order.
4. Javadoc on DTOs: visits = distinct visitors; revenue = net of succeeded refunds, live mode.
5. Update existing tests in the class whose fixtures relied on rows-as-visits only if they now
   assert a different number (they use distinct anon ids, so none expected).

## Verification commands

- `docker info >/dev/null && ./mvnw -q test -Dtest='Attribution*Test,Analytics*Test,Funnel*Test,SpringContextGuardTest'`

## Webapp follow-up (separate task, 4 locales)

- `analytics.colVisits` "Tracked visits" → "Visitors"; `trackedVisitNote` → a visitor is one
  browser seen on the event page, counted once per source; `untaggedShare` → share of visitors
  with an untagged visit; `revenueTaggedWindow` drop "refunds not taken off", say refunds taken
  off and test payments not counted; `sourceWithoutVisitsNote` noun to visitors; update
  `AnalyticsPage.test.tsx` expectations.
