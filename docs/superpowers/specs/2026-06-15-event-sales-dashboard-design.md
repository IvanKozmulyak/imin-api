# Organizer per-event live sales dashboard — design

- **Date**: 2026-06-15
- **Status**: approved, pre-implementation
- **Repos touched** (synchronized change): `imin-api`, `imin-webapp`, `imin-public`
- **Owner of contract**: `imin-api` (publishes `/api/v1` + `/api/v1/public`)

## Problem

Organizers have no real-time visibility into how a single event is selling. The
existing `/events/:id/overview` tab is a static project-summary; the org-level
dashboard is portfolio-wide. There is no per-event surface that updates live as
purchases and check-ins happen, no tier-level breakdown, no conversion funnel,
and no attendee export.

This feature delivers an organizer-facing, **per-event**, **live** sales
dashboard with four pillars:

1. **Live tiles** — tickets sold, revenue, capacity %, check-in rate; update
   within seconds of a purchase or check-in, no manual refresh.
2. **Tier breakdown** — donut + stacked-by-tier, plus top-converting tiers;
   reconciles exactly with the order source of truth.
3. **Conversion funnel** — page views → checkout starts → payments completed,
   each stage independently instrumented, drop-off shown per stage.
4. **Attendee CSV export** — full attendee list with tier and check-in status;
   CSV matches on-screen counts.

This is distinct from the internal org-level project-tracking dashboard.

## Decisions (settled during brainstorming)

| Decision | Choice | Rationale |
|---|---|---|
| Transport for "live" | **Polling ~5s** (TanStack Query `refetchInterval`) | Zero new infra; matches existing `PaymentsTab` polling precedent; satisfies "within seconds". SSE/WS rejected as unjustified infra for one-directional tile updates. |
| Dashboard home | **Rebuild existing `EventAnalyticsTab`** (`/events/:id/analytics`) | The tab already exists and declares a funnel shape but has no backend. Single home, no new route. |
| Page-view signal | **Client beacon → public `track` endpoint** | Counts real human views, not bots/prefetches/API pokes. |
| Scope | **All four pillars, all three repos, one feature** | Per the cross-repo-sync rule, the `/api/v1/public` contract change ships with its consumers. |
| Funnel "payments" unit | **Completed orders** (order source of truth), while the two upper stages count distinct browser sessions | Conventional e-commerce funnel. Threading `anonId` through Stripe redirect→webhook to make all stages session-based is fragile; rejected. |

## Architecture

```
imin-public (buyer site)
  buyer event-detail page  --(beacon PAGE_VIEW)-->  POST /api/v1/public/events/{id}/track
  buy/checkout click       --(beacon CHECKOUT_START)-->  (same endpoint)

imin-api
  POST /api/v1/public/events/{id}/track   -> FunnelTrackingService -> event_funnel_events (append-only)
  GET  /api/v1/events/{id}/sales/live     -> SalesDashboardService  -> { tiles, tiers, funnel }   [polled 5s]
  GET  /api/v1/events/{id}/attendees/export -> AttendeeExportService -> text/csv                   [on demand]

imin-webapp
  EventAnalyticsTab  -> useSalesDashboard(eventId) [refetchInterval 5000] -> GET /events/{id}/sales/live
                     -> "Export attendees (CSV)"   -> authed fetch -> blob download
```

Everything the dashboard shows comes from **one** `sales/live` response, so
tiles and the tier breakdown are computed in a single snapshot and cannot drift
from each other within a render.

## Backend (imin-api)

### Data model — `V41__event_funnel_events.sql`

Append-only event log. No rollup table (chosen for simplicity + exact
recomputability; revisit only if write/read volume demands).

```sql
create table event_funnel_events (
    id         uuid primary key,
    event_id   uuid not null,
    stage      varchar(32) not null,   -- 'PAGE_VIEW' | 'CHECKOUT_START'
    anon_id    varchar(64) not null,   -- per-session id from the buyer's sessionStorage
    created_at timestamptz not null
);
create index ix_funnel_event_stage on event_funnel_events (event_id, stage);
```

Only the two top funnel stages are stored here. `PAYMENTS_COMPLETED` is **not**
written here — it is derived from the canonical `orders` table at read time, so
webhook replays cannot double-count it.

`FunnelEvent` JPA entity + `FunnelEventRepository` with:
- insert (via `save`)
- `countDistinctAnonByStage(eventId)` → `[(stage, distinctCount)]`

### `POST /api/v1/public/events/{id}/track` — funnel instrumentation (public)

- **Auth**: none (unauthenticated, like all `/api/v1/public/*`). Add an explicit
  `permitAll` for `POST /api/v1/public/events/*/track` in `SecurityConfig`.
- **Body**: `{ "stage": "PAGE_VIEW" | "CHECKOUT_START", "anonId": "<=64 chars" }`.
- **Behavior**: `FunnelTrackingService` validates the event exists and is
  publicly visible/live; on success inserts one row. **Always returns `204`** —
  including for unknown / non-public events (no-leak, matching the public
  contract's no-leak 404 behavior). Unknown stage → `204` no-op.
- **Abuse**: rows are cheap; `anon_id` length-capped; no per-IP limit in v1
  (documented as a known gap).

### `GET /api/v1/events/{id}/sales/live` — dashboard snapshot (organizer)

Authorized **identically to the existing `GET /events/{id}/overview`** (same
org-ownership resolution). Returns one snapshot:

```jsonc
{
  "currency": "EUR",
  "tiles": {
    "ticketsSold": 412,            // count of tickets in sold states (issued|redeemed)
    "netRevenueMinor": 1648000,    // SUM(order.total_minor) - succeeded refunds
    "grossRevenueMinor": 1730000,  // SUM(ticket.price_minor) over sold tickets (reconciles with tiers)
    "capacity": 600,               // SUM(tier.quantity)
    "capacityPct": 68.7,           // ticketsSold / capacity
    "checkedIn": 0,                // count of tickets in 'redeemed'
    "checkInRate": 0.0             // checkedIn / ticketsSold (0 when none sold)
  },
  "tiers": [
    { "tierId": "...", "name": "GA",  "sold": 300, "redeemed": 0,
      "grossRevenueMinor": 900000, "quantity": 400, "sellThroughPct": 75.0 },
    { "tierId": "...", "name": "VIP", "sold": 112, "redeemed": 0,
      "grossRevenueMinor": 830000, "quantity": 200, "sellThroughPct": 56.0 }
  ],
  "topConvertingTiers": [ /* tiers sorted by sellThroughPct desc, same objects */ ],
  "funnel": {
    "stages": [
      { "stage": "PAGE_VIEW",          "count": 5200 },
      { "stage": "CHECKOUT_START",     "count": 890  },
      { "stage": "PAYMENTS_COMPLETED", "count": 405  }
    ],
    "dropOff": [
      { "from": "PAGE_VIEW",      "to": "CHECKOUT_START",     "lostCount": 4310, "lostPct": 82.9 },
      { "from": "CHECKOUT_START", "to": "PAYMENTS_COMPLETED", "lostCount": 485,  "lostPct": 54.5 }
    ]
  }
}
```

**Computation** (all from the order/ticket source of truth):

- **Tiles + tier breakdown** are computed from the **`tickets`** table so they
  reconcile by construction:
  - sold-state set = `state IN ('issued','redeemed')` (excludes `revoked`,
    `refunded`).
  - `tiles.ticketsSold` = COUNT(sold tickets).
  - `tiles.grossRevenueMinor` = SUM(`ticket.price_minor`) over sold tickets.
  - per-tier `sold`/`grossRevenueMinor`/`redeemed` = the same aggregates grouped
    by `tier_id`.
  - **Invariant (tested):** `Σ tiers[].sold == tiles.ticketsSold` and
    `Σ tiers[].grossRevenueMinor == tiles.grossRevenueMinor`.
  - per-tier `quantity` from `ticket_tiers.quantity`; `sellThroughPct` =
    `sold / quantity` (0 when quantity 0).
- **`tiles.netRevenueMinor`** = `SUM(order.total_minor)` − succeeded refunds
  (`RefundRepository.sumSucceededRefundMinorByEventId`). This is the
  money-actually-collected figure. It can differ from `grossRevenueMinor` when
  promo discounts or refunds apply — gross is the tier-reconciliation figure;
  net is the collected figure. Both are surfaced so neither is ambiguous.
- **`capacity`** = `TicketTierRepository.sumQuantityByEventId`.
- **`checkedIn`** = COUNT(tickets `state='redeemed'`); `checkInRate` =
  `checkedIn / ticketsSold`.
- **Funnel**:
  - `PAGE_VIEW`, `CHECKOUT_START` = COUNT(DISTINCT `anon_id`) per stage from
    `event_funnel_events`.
  - `PAYMENTS_COMPLETED` = COUNT(orders) for the event (order source of truth).
  - `dropOff[i]` = `stage[i].count - stage[i+1].count`, `lostPct` relative to
    `stage[i].count` (0 when the upstream count is 0; never negative — if a
    downstream count exceeds upstream due to the session-vs-order unit mismatch,
    clamp `lostCount`/`lostPct` to 0 and document).

`SalesDashboardService` **shares the revenue/sold aggregation helper with
`EventOverviewService`** (extract a common method/component) so the overview tab
and the sales tab never report different numbers for the same event.

### `GET /api/v1/events/{id}/attendees/export` — CSV (organizer)

- Same authorization as `sales/live`.
- `Content-Type: text/csv; charset=utf-8`,
  `Content-Disposition: attachment; filename="attendees-<eventSlug>.csv"`.
- One row per ticket in the **same sold-state set** as the tiles
  (`issued|redeemed`), ordered by `purchased_at`.
- Columns: `order_ref, buyer_email, tier, status, checked_in_at, price, purchased_at`
  - `status` = `Checked-in` when `state='redeemed'` else `Issued`.
  - `checked_in_at` = `redeemed_at` (blank when not redeemed).
  - `tier` = `ticket.tier_name` (snapshot, survives tier deletion).
  - `order_ref` = order short code/token; `buyer_email` = `order.email`.
  - `price` = `ticket.price_minor` rendered in major units with `currency`.
- **Invariants (tested):** CSV data-row count == `tiles.ticketsSold`; rows with
  `status=Checked-in` == `tiles.checkedIn`.

**Data-model limitation (flagged):** the schema has **no per-attendee name** — a
ticket carries only the buyer's `Order.email`. So "attendee" = ticket, keyed by
buyer email + tier + token. The CSV is buyer-email-keyed, not per-person-named.
Out of scope to add per-attendee names here.

## Frontend (imin-webapp)

- **`useSalesDashboard(eventId)`** — `useQuery({ queryKey: ['events', id, 'sales-live'],
  queryFn: () => apiFetch('/events/'+id+'/sales/live'), refetchInterval: 5000,
  refetchIntervalInBackground: false })`.
- **Rebuilt `EventAnalyticsTab.tsx`**:
  - 4 `MetricCard` tiles: Tickets sold, Net revenue, Capacity %, Check-in rate.
  - Tier breakdown: Recharts **donut** (share of sold) + **stacked bar** by tier;
    a "top-converting tiers" list (sorted by `sellThroughPct`) showing
    `sold/quantity` and %.
  - Funnel: the existing `FunnelChart` for the 3 stages, with per-stage drop-off
    labels.
  - "Export attendees (CSV)" button — authed fetch of `/attendees/export` → blob
    → client download (cannot be a plain `<a href>` because the endpoint needs
    the Bearer header).
  - A subtle "updated just now / Xs ago" indicator driven by `dataUpdatedAt`.
  - The old stub fields `nps` / `vsPredictedMinor` are **dropped** (out of scope).
- **Types**: hand-write the `sales/live` + tier + funnel types in
  `src/shared/api/types.ts` now; run `npm run api:sync` **after** the backend is
  deployed (api:sync pulls the production OpenAPI).
- Charts use the existing `useChartColors` token bridge; no Tailwind (CSS
  modules + CSS variables).

## Public beacons (imin-public)

- **`trackFunnel(eventId, stage)`** util in `lib/`:
  - reads/creates an `anonId` in `sessionStorage` (`crypto.randomUUID`).
  - posts `{ stage, anonId }` to `${NEXT_PUBLIC_API_BASE}/api/v1/public/events/${eventId}/track`
    via `navigator.sendBeacon` (falls back to `fetch(..., {keepalive:true})`) so
    it survives navigation.
- **`PAGE_VIEW`** fires once per session per event on the buyer event-detail page
  (sessionStorage-deduped: one human view = one count).
- **`CHECKOUT_START`** fires on the buy/checkout click, immediately before the
  Stripe redirect.
- Update **`imin-public/docs/PUBLIC_PAGE_API.md`** with the new public endpoint.

## Testing

**imin-api** (H2, external services mocked):
- Reconciliation: `Σ tiers[].sold == tiles.ticketsSold`,
  `Σ tiers[].grossRevenueMinor == tiles.grossRevenueMinor` for a seeded
  multi-tier event with refunds.
- Net vs gross: a promo-discounted + partially-refunded order yields the
  expected `netRevenueMinor` and the tiers still reconcile on gross.
- Check-in: redeeming N tickets moves `checkedIn`/`checkInRate` accordingly.
- Funnel: distinct-`anon_id` counting per stage; `PAYMENTS_COMPLETED` ==
  order count; drop-off math (incl. clamp-at-0).
- Track endpoint: public; `204` on unknown/non-public event (no-leak); row
  inserted only for valid public event; unknown stage no-ops.
- CSV: data-row count == `ticketsSold`; `Checked-in` rows == `checkedIn`;
  tier + status columns present; refunded/revoked tickets excluded.

**imin-webapp**: `useSalesDashboard` polling config; an `EventAnalyticsTab`
render test against a mocked snapshot (tiles, donut, funnel, export button).

## Scope boundaries (YAGNI)

- No funnel date-range / time-window filter (all-time per event).
- No NPS / prediction in this tab.
- No per-attendee names (data-model limitation).
- No SSE/WebSocket (polling).
- No cross-event rollup (remains the separate `/analytics` page's future job).
- No per-IP rate limit on `track` in v1 (documented gap).

## Rollout / sequencing

1. Merge + deploy `imin-api` (migration + 3 endpoints + SecurityConfig).
2. `imin-webapp`: rebuild tab + `api:sync` (after the api deploy).
3. `imin-public`: beacons + `PUBLIC_PAGE_API.md`.

Steps 2 and 3 are independent of each other but both depend on step 1's deploy.
Until the public beacons ship, the funnel's two upper stages read 0 — the tab
still renders (tiles, tiers, payments stage) without error.
