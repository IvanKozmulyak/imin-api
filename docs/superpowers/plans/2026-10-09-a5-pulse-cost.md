# A5 — Dashboard pulse query cost

Backlog: workspace `docs/superpowers/plans/2026-10-06-dashboard-follow-ups.md` item A5.
Base: `origin/master` 27a41607.

## Problem

`GET /dashboard/pulse` is polled every 5 s per open dashboard while an event is on sale.

1. `DashboardPulseService.lastSale` (`src/main/java/com/imin/iminapi/service/dashboard/DashboardPulseService.java:67-77`)
   calls `events.findActive(o.getEventId())` (`:72`) once per scanned order that still holds a live ticket,
   until it finds one whose event is not deleted. Orders on deleted events, and every order in event scope
   (whose event was already loaded at `:49`), each cost one extra `SELECT` — up to 32 per poll
   (`ORDER_SCAN_LIMIT`, `:31`).
2. The org-scope order scan `OrderRepository.findByOrgIdOrderByCreatedAtDesc`
   (`src/main/java/com/imin/iminapi/repository/OrderRepository.java:46-48`) is
   `WHERE org_id = ? ORDER BY created_at DESC LIMIT 32`. The only usable index is `idx_orders_org_id (org_id)`
   (`V24__orders_and_tickets.sql:27`); `idx_orders_org_utm_*` (`V62:27-28`) are `(org_id, utm_*)`. Postgres must
   read every order of the org and top-N sort them on each poll. The event-scope scan
   `findByEventIdOrderByCreatedAtDesc(eventId, Pageable)` (`OrderRepository.java:43`) has the same shape against
   `idx_orders_event_id (event_id)` (`V24:26`) only.

## Change

- `EventRepository`: add `findActiveByIds(Collection<UUID> ids)` — `SELECT e FROM Event e WHERE e.id IN :ids AND
  e.deletedAt IS NULL`. Same predicate as `findActive` (`EventRepository.java:27-28`), no org check, so output
  is identical even if an order's `org_id` disagreed with its event's. (`findActiveByOrgAndIds`, `:31-32`, adds
  an org filter and is therefore not a drop-in.)
- `DashboardPulseService.lastSale` takes the active events up front:
  - event scope: `Map.of(e.getId(), e)` from the event already loaded and org-checked at `:49-50` — no query;
  - org scope: one `findActiveByIds` over the distinct event ids of the scanned orders that hold a live ticket
    (none → no query).
  The loop then looks the event up in the map; absent = skipped, exactly as `Optional.empty()` was.
  Iteration order, refunded filtering, tier-name order and counts are untouched.
- `V181__orders_org_event_created_at_index.sql`:
  `CREATE INDEX IF NOT EXISTS idx_orders_org_created_at ON orders (org_id, created_at);`
  `CREATE INDEX IF NOT EXISTS idx_orders_event_created_at ON orders (event_id, created_at);`
  A backward index scan returns the newest 32 and stops. The event index is included because the event-scope
  pulse polls with the identical query shape. `idx_orders_org_id` / `idx_orders_event_id` become prefix-redundant
  but are left in place (dropping is a separate decision).
  - V181 is free: highest on `origin/master` is V180; no worktree under `imin-api/.claude/worktrees/*` has V181+.
  - Lock: plain `CREATE INDEX` takes a SHARE lock on `orders` (blocks writes, not reads) for the build. Prod
    `orders` was 67 rows on 2026-08-12 (`V86__orders_email_normalized.sql:19-20`) and is still low thousands at
    most, so the build is milliseconds. `CONCURRENTLY` cannot run inside the transaction Flyway wraps a migration
    in, and the repo has no non-transactional migration pattern (no `*.sql.conf`, `V86:21-23` made the same call),
    so plain is used.

## Affected files

- `src/main/java/com/imin/iminapi/service/dashboard/DashboardPulseService.java`
- `src/main/java/com/imin/iminapi/repository/EventRepository.java`
- `src/main/resources/db/migration/V181__orders_org_event_created_at_index.sql`
- `src/test/java/com/imin/iminapi/service/dashboard/DashboardPulseServiceTest.java` (stubs move from
  `findActive` per order to `findActiveByIds`; assertions unchanged)
- `src/test/java/com/imin/iminapi/service/dashboard/DashboardPulseCostTest.java` (new, `@IminIntegrationTest`)

## Tests

Integration, `@IminIntegrationTest`, real `DashboardPulseService` bean on Postgres, data via `IminFixtures`:

1. **Equivalence (org scope)** — newest→oldest: order on a deleted event with a live ticket (skipped), fully
   refunded order on an active event (skipped), order with no tickets (skipped), order on an active event with
   tickets `Late` issued / `Early Bird` redeemed / `Late` issued / `VIP` refunded (the hit), an older live order
   (never reached). Asserts the whole `DashboardPulseResponse` equals the expected record (`onSale`, count,
   `at`, `eventId`, `eventName`, `["Late","Early Bird"]`, `3`). Green on base and on the fix.
2. **Equivalence, every order skipped** — only deleted-event / refunded orders → `lastSale` null.
3. **Equivalence (event scope)** — event's own orders; newest fully refunded, next live → that order.
4. **Query bound** — org scope with 10 orders on 10 distinct deleted events, each with a live ticket, plus one
   older live order. Hibernate `Statistics.getPrepareStatementCount()` (enabled around the call, restored in
   `finally`, as `BulkMaterializeTest` on `c9ef1d17` does) must be ≤ 4 (on-sale count, orders, tickets, events).
   Event scope bound ≤ 4 (event, count, orders, tickets). Base: 3 + 11 = 14 and 5 → red.

No migration test: V181 changes no data (`imin-api/CLAUDE.md` § Testing). No EXPLAIN test: the repo has none.

## Ordered steps

1. Write `DashboardPulseCostTest`; run against base: equivalence green, query bound red (paste counts).
2. Add `findActiveByIds`, rewrite `lastSale`, update unit-test stubs.
3. Add V181.
4. Run verification.

## Verification commands

- `docker info >/dev/null && ./mvnw test -Dtest='Dashboard*Test,*Migration*Test,SpringContextGuardTest'`
  (full clean `./mvnw test` runs at ship).
