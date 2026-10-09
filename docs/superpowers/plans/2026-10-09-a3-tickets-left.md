# A3: almost-gone data on the organizer events list

## Goal
`GET /api/v1/events` rows return `almostGone` and `ticketsLeft` so poster cards can show the chip (the row has `tiers: null`).

## Rule (mirror of imin-webapp `almostGone.ts` / `tierSaleState.ts` / `isOnSaleNow`)
- Event on sale now: status LIVE, startsAt in the future, onSaleAt null or <= now, saleClosesAt null or > now.
- Tier counts when: quantity > 0, sold < quantity, enabled, tier saleStartsAt null or <= now, tier saleClosesAt null or > now.
- left = max(0, quantity - sold - reserved); tier is almost gone when 1 <= left <= min(30, ceil(0.2 * quantity)).
- Per event: `almostGone` = any tier almost gone; `ticketsLeft` = left of the scarcest almost-gone tier (the webapp's first chip). Null when not almost gone. Not a total across tiers.

## Affected files
- `service/event/AlmostGone.java` (new, pure rule)
- `dto/event/EventDto.java` (two nullable fields, summary only)
- `service/event/EventService.java` (list: one batched tier query for the page)
- `controller/event/EventControllerTest.java` (integration test)

## Steps
1. AlmostGone.scarcestLeft(event, tiers, now) -> OptionalInt.
2. EventDto gains `Boolean almostGone, Integer ticketsLeft`; summary takes them, detail leaves them null.
3. EventService.list loads tiers via findByEventIdInOrderBySortOrderAsc (one query), groups by event.
4. Test: threshold edges (at, one above), cap 30 on large tier, not on sale (draft, tier disabled, tier window), sold out, multiple tiers pick scarcest.

## Verification
`./mvnw test -Dtest='Event*Test,SpringContextGuardTest'`

## Follow-up
After deploy: `npm run api:sync` in imin-webapp, add `almostGone`/`ticketsLeft` to the hand-written Event type, chip on poster cards.
