# W7-1 API: order filters, order detail, CSV export

Spec: workspace `docs/superpowers/specs/2026-10-09-w7-dashboard-features.md` § W7-1, API items 1-4,
and Decisions (export open to any org member, still audited). Webapp half is a later card.

## Current behaviour (cited)

- `GET /api/v1/events/{eventId}/orders` takes only `limit`: default 100, max 500
  (`EventOrdersController.java:41-42,81`), newest first, unfiltered page
  (`EventOrdersController.java:82-83`).
- Row status is derived in Java (`EventOrdersController.java:120-141`): `inactive` = tickets in
  `refunded`/`revoked`; `paid` when none inactive (also an order with zero tickets), `refunded`
  when all inactive, else `partially_refunded`; overridden to `disputed` when the governing dispute
  is in `DisputeWithholding.STATUSES` = OPEN, LOST (`DisputeWithholding.java:45-46`). The governing
  dispute ranks OPEN, then LOST, then the rest (`EventOrdersController.java:49-57`), so "governing is
  OPEN/LOST" is the same as "any dispute on the order is OPEN/LOST".
- `shortCode` = first 8 chars of the order UUID text (`EventOrdersController.java:150`).
- `TicketRow` has id, tierName, priceMinor, state (`OrderRowResponse.java:29-34`); `Ticket.redeemedAt`
  exists (`Ticket.java:59-60`); `tickets.state` is NOT NULL (`Ticket.java:56`).
- `Order.promoCodeId` (`Order.java:91-92`) has no FK (`V24__orders_and_tickets.sql:20`) and promo codes
  are hard-deleted (`PromoCodeService.java:147`), so a deleted code resolves to `null`.
- `orders.email` is NOT NULL (`V24__orders_and_tickets.sql:17`).
- CSV escaping with the formula-injection guard is `AttendeeExportService.csv` (`AttendeeExportService.java:76-85`),
  private. The attendee export audits with a row count after the CSV is built
  (`SalesDashboardController.java:56-58,66-70`) and is ADMIN-gated (`:54-55`); the orders export is not
  (Decisions).
- LIKE escaping convention: `'!'` escape char (`SourceSyncDates.java:79-81`).
- Disputes have no `order_id` index (`V128__disputes.sql:36-37`, `V131__…:9`); the existing list already
  looks them up by order id (`DisputeRepository.java:79`).

## Design

One status definition, in SQL: new `OrderStatusSearch` (`@Repository`, `NamedParameterJdbcTemplate`) computes
each order's status with a `CASE` over `EXISTS` subqueries bound from the same constants the Java code used
(`Ticket.STATE_REFUNDED/STATE_REVOKED`, `DisputeWithholding.STATUSES` as wire values). The list, the detail
and the export all take the status from it; `toRow` stops deriving and receives it. Filtering by status is
a `WHERE` on the computed column, so `LIMIT` applies after the filter.

`q`: trimmed; blank = absent. Absent and present are two different SQL strings (no nullable String bound into
`lower`/`like`). Present: `lower(o.email) LIKE '%'||:q||'%' ESCAPE '!'` OR `left(o.id::text, 8) LIKE :q||'%' ESCAPE '!'`,
with `q` lowercased (Locale.ROOT) and `! % _` escaped in Java.

`status` param: absent/blank = all; otherwise one of the four wire values; anything else is 400 FIELD_INVALID.

Detail `GET /orders/{orderId}`: event lookup + org check as the list (404 Event); the search restricted to
`o.id = :orderId AND o.event_id = :eventId`; empty → 404 Order. Cross-org and other-event both 404.

Export `GET /orders/export` (`produces text/csv`): same filters, no cap, any org member; rows built by the
same code as the list, then CSV; then `AuditActions.ORDERS_EXPORTED` on target `event`/eventId with
"Orders CSV exported (N row(s))". Columns: order_ref (shortCode), order_id, buyer_email, created_at
(`Instant.toString()`, UTC ISO), status, tickets, tickets_refunded (= `refundedTicketCount`), total (major units,
zero-decimal aware via `MoneyFormat.isZeroDecimal`), currency (upper-case), promo_code. No buyer name.
Route ordering: Spring's PathPattern ranks the literal `/export` above `/{orderId}`; the export test proves it.

CSV escaping is extracted to `util/CsvCell.escape` and `AttendeeExportService.csv` delegates to it (no change
in output).

New fields: `TicketRow.redeemedAt` (Instant, null until scanned), `OrderRowResponse.promoCode` (String, null when
no code or the code was deleted). Promo codes batch-loaded with one `findAllById` per page.

## Affected files

- `src/main/java/com/imin/iminapi/repository/OrderStatusSearch.java` (new)
- `src/main/java/com/imin/iminapi/controller/order/EventOrdersController.java`
- `src/main/java/com/imin/iminapi/controller/order/dto/OrderRowResponse.java`
- `src/main/java/com/imin/iminapi/controller/order/OrdersCsv.java` (new)
- `src/main/java/com/imin/iminapi/util/CsvCell.java` (new)
- `src/main/java/com/imin/iminapi/service/event/AttendeeExportService.java`
- `src/main/java/com/imin/iminapi/service/audit/AuditActions.java`
- `src/main/resources/db/migration/V178__disputes_order_status_index.sql` (new, review follow-up: index for the per-order dispute `EXISTS`)
- `src/test/java/com/imin/iminapi/controller/order/EventOrdersFilterTest.java` (new)
- `src/test/java/com/imin/iminapi/controller/order/EventOrdersExportTest.java` (new)
- `src/test/java/com/imin/iminapi/controller/order/EventOrdersControllerTest.java` (detail + new fields)

## Ordered steps

1. Tests first (integration, `@IminIntegrationTest`, `IminFixtures`, `AuditRows`):
   - per-status parameterized: fixtures paid, partially refunded, all refunded, all revoked, OPEN, LOST,
     WON-on-paid; for each status filter, returned ids == the fixtures whose unfiltered row has that status
     (SQL and row agree), and `status=disputed` includes OPEN/LOST, excludes WON.
   - q: mixed-case email substring; shortCode prefix in upper case; q absent → 200; q `%` and `_` match literally
     only.
   - limit after filter: newest non-matching orders outnumber the limit, matching one still returned.
   - unknown status → 400.
   - detail: own order 200 with promoCode + redeemedAt; cross-org 404; order of another event in same org 404.
   - export: 200 text/csv with header, a JPY order exported as `,1500,JPY,`, audit row with count, `=`-email cell starts `'=`, filters apply, MEMBER role allowed.
2. `OrderStatusSearch`, `CsvCell`, `AuditActions.ORDERS_EXPORTED`, DTO fields, controller, `OrdersCsv`.
3. Guard red proofs in a scratch copy of the worktree, one guard removed at a time.

## What does not get a test

DTO mapping beyond the two new fields asserted once, `CsvCell` already covered by the attendee export tests,
OpenAPI annotations.

## Verification commands

- `docker info`
- `./mvnw -q test -Dtest='EventOrders*Test,AttendeeExport*Test,SpringContextGuardTest'` (full `clean test` runs at ship)
