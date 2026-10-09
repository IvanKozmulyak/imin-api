# Fix: attendee CSV leaks the buyer's order token

## Defect
`GET /api/v1/events/{id}/attendees/export` writes `orders.token` into the `order_ref` column
(`TicketRepository.java:108` selects `o.token`; `AttendeeExportService.java:45,49` writes it).
`orders.token` is the bearer credential for `/order/{token}` and its tickets (`Order.java:19-20,236`;
`OrderRepository.java:30-32`), so anyone holding an exported file can open every buyer's tickets.

## Fix
- `order_ref` = the order short code the dashboard already shows: `o.getId().toString().substring(0, 8)`
  (`EventOrdersController.java:150`, `EventRefundPlanService.java:90`, `EventRefundRowResponse.java:33`,
  `RefundConfirmationEmailer.java:100`). Same expression, no new derivation.
- `attendeeRows` selects `o.id` instead of `o.token`; the token is no longer read for export.
- Header and column order unchanged. CSV escaping untouched (another worktree is extracting it).

## Same-class leak fixed
- DSAR export (`DsarService.exportRecords`, owner/admin) returns `MetaCapiRecord.orderToken` raw
  (`DsarScopeService.java:122-124`, value written by `MetaCapiOutboxWriter.java:83` = `order.getToken()`).
  The ticket token in the same export is already hashed (`DsarScopeService.java:108`). Hash the order
  token the same way and rename the field `orderTokenSha256` (mirrors `TicketRecord.tokenSha256`).
  Contract change: webapp `api:sync` at ship (only `generated-types.ts` references it).

## Affected files
- `src/main/java/com/imin/iminapi/repository/TicketRepository.java`
- `src/main/java/com/imin/iminapi/service/event/AttendeeExportService.java`
- `src/main/java/com/imin/iminapi/audience/dto/DsarRecords.java`
- `src/main/java/com/imin/iminapi/audience/service/DsarScopeService.java`
- `src/test/java/com/imin/iminapi/controller/event/AttendeeExportOrderRefTest.java` (new)
- `src/test/java/com/imin/iminapi/audience/AudienceDsarScopeTest.java`

## Tests (integration, `@IminIntegrationTest`)
- Export via MockMvc: `order_ref` of each row equals the order's short code; the order token appears
  nowhere in the body. Proven red against base before the fix.
- DSAR: `metaCapiEvents[0].orderTokenSha256` is the SHA-256 of the order token, and the raw token is
  absent. Proven red against base before the fix.

## Ordered steps
1. Write both tests; run them on unfixed code; record red.
2. Apply the fix; run them green.

## Verification commands
- `docker info`
- `./mvnw test -Dtest='AttendeeExport*Test,AudienceDsarScopeTest,SpringContextGuardTest'`

## Reported, not fixed
See the worker report (organizer/staff-visible token surfaces that are not this class of leak).
