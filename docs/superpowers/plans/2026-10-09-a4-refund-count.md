# A4: cheap pending refund-request count

## Problem
The dashboard "Waiting on you" row calls `GET /orgs/{orgId}/refund-requests?status=pending&limit=100`
(imin-webapp `WaitingOnYou.tsx`) only to count rows per event, and the list runs the per-row mapper
(order lookup, eligibility, refund maths) for each of up to 100 rows. It also under-counts past 100.

## Change
- `GET /api/v1/orgs/{orgId}/refund-requests/pending-count` -> `{ total, events: [{ eventId, eventName, count }] }`,
  events newest-pending first (the order the webapp groups the list in). Same org gate as the list (other org -> 404).
- `RefundRequestRepository.pendingCountsByEvent`: one grouped aggregate query. Its WHERE clause is the same
  string constant as `page`/`pageSearch` use (`ORG_EVENT_STATUS`), so "pending" cannot drift from the list.
- `RefundRequestService.pendingCounts` sums the total from the groups.

## Affected files
- imin-api: refund/RefundRequestRepository, RefundRequestService, RefundRequestController,
  refund/dto/RefundRequestPendingCountResponse (new), test RefundRequestControllerTest.

## Tests (@IminIntegrationTest, in RefundRequestControllerTest)
1. Count equals the list's pending set: two events, several pending, plus approved/rejected/withdrawn rows
   (excluded), per-event split, event names.
2. Another org's id -> 404; another org's pending rows never counted.

## Verification
`./mvnw test -Dtest='RefundRequest*Test,Dashboard*Test,SpringContextGuardTest'`

## Follow-up (not in this change)
After deploy: `npm run api:sync` in imin-webapp, then WaitingOnYou switches to this endpoint and drops the
100-row truncation note.
