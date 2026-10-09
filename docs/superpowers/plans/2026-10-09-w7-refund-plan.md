# W7-2 (API half): `GET /api/v1/events/{eventId}/refund-plan`

Spec: workspace `docs/superpowers/specs/2026-10-09-w7-dashboard-features.md` § W7-2 and § Decisions.
Repo: `imin-api` only. Webapp half is a later card.

## Why

The dashboard's "Refund all" builds its plan client-side from the Orders tab list, which is
capped at the newest 100 orders (`EventOrdersController.java:41,81`), keeps `redeemed` tickets
that the server then rejects for the whole order (`RefundService.java:143-149`), and sums face
prices instead of what the server charges (`RefundService.java:475-491`). The server must hand
the dialog the exact plan `createRefund` will accept, for every order on the event.

## Current behaviour (cited; line numbers are origin/master 8632624d)

- Org scoping on event endpoints: `events.findActive(eventId)` then 404 on org mismatch
  (`EventOrdersController.java:78-79`, `EventRefundsController.java:47-48`). No role gate on
  refunds (`RefundService.java:96-100`, kept by decision).
- `createRefund` refusals, in order:
  - OPEN/LOST dispute on the order → 409 `ORDER_DISPUTED` (`RefundService.java:115-118`,
    predicate `DisputeRepository.hasOpenOrLostByOrderId`, `DisputeRepository.java:353-355`).
  - No Stripe payment intent → 409 `ORDER_NOT_REFUNDABLE` (`RefundService.java:125-128`).
  - Any selected ticket already in `refund_tickets` (any refund status that still holds a
    claim — PENDING/REQUESTED/SUCCEEDED; FAILED/CANCELED release theirs at
    `RefundService.java:346`) → 409 `TICKET_ALREADY_REFUNDED` (`RefundService.java:136-142`).
  - Any selected ticket `redeemed` → 409 `TICKET_REDEEMED` (`RefundService.java:143-149`).
  - Computed amount ≤ 0 → 409 `ORDER_NOT_REFUNDABLE` (`RefundService.java:151-155`).
- Amount: `computeRefundAmountMinor(order, selected)` = round(total × selectedFace / orderFace),
  clamped to total − active refunds (`RefundService.java:475-491`). Already reused by
  `RefundRequestService` (`RefundRequestService.java:227,449,548,620`).
- `createRefund` does NOT reject `revoked` tickets (`RefundRequestService.java:277` documents
  this as deliberate). Revoked tickets come from chargebacks (`DisputeIngestService.java:318-319`).
- Unpaged event order list exists: `OrderRepository.findByEventIdOrderByCreatedAtDesc(UUID)`
  (`OrderRepository.java:41`). Short code = first 8 chars of the order id
  (`EventOrdersController.java:150`).

## Design

1. Extract the refusal predicates out of `createRefund` into package-private members of
   `RefundService`, and make `createRefund` call them (no behaviour change):
   - `boolean isBlockedByDispute(UUID orderId)` — wraps `disputes.hasOpenOrLostByOrderId`.
   - `static boolean hasStripePayment(Order)` — the `:125` check.
   - `Set<UUID> claimedTicketIds(Collection<UUID>)` — wraps `refundTickets.findRefundedTicketIds`
     (empty input → empty set, no query).
   - `static boolean isRedeemed(Ticket)` — the `:144` check.
   - `boolean matchesStripeMode(Order)` — new refusal, see below.
   `computeRefundAmountMinor` is already package-private and is called as-is.
2. New `EventRefundPlanService` (package `refund`, `@Transactional(readOnly = true)`), for every
   order of the event (unpaged, newest first):
   - `isBlockedByDispute` → skip, `skipped.disputedOrders++`.
   - `!hasStripePayment` → skip silently (createRefund would 409; no counter in the schema).
   - candidates = order tickets minus `claimedTicketIds`, minus tickets in state
     `refunded`/`revoked` (plan-only rule from the spec: those tickets are no longer live, and a
     plan must not resurrect a chargeback's revocation); then redeemed ones are removed and
     counted in `skipped.redeemedTickets`.
   - no candidates left → omit. amount = `computeRefundAmountMinor(order, candidates)`;
     amount ≤ 0 → omit (createRefund would 409).
   - totals: `totalAmountMinor` = Σ amounts, `ticketCount` = Σ planned tickets.
   - each planned order carries its own `currency` (`Order.currency`, the currency createRefund
     refunds in, `RefundService.java:166` on base). Nothing guarantees one currency per event, so the
     top-level `currency`/`totalAmountMinor` are returned only when every planned order shares
     one currency (case-insensitive); otherwise 409 `INVALID_STATE` — the dialog must never sum
     EUR with USD. With nothing planned, `currency` falls back to the event's.
   - Stripe mode: checkout stamps `orders.test_mode = !stripeProps.isLiveKey()`
     (`PaidCheckoutService.java:193`, `FreeCheckoutService.java:174`); `StripeProperties.isLiveKey()`
     (`StripeProperties.java:99-101`) is the app's one source for the running key's mode. A new
     shared predicate `RefundService.matchesStripeMode(order)` (`order.testMode != isLiveKey`)
     makes createRefund answer 409 `ORDER_NOT_REFUNDABLE` (after the replay short-circuit and the
     PaymentIntent check) and makes the plan skip the order silently.
   - ponytail: ~5 small queries per order; fine to a few thousand orders per event — batch
     tickets/claims/sums if an event outgrows that.
3. New `EventRefundPlanController` `GET /api/v1/events/{eventId}/refund-plan`: event lookup +
   org check exactly as `EventRefundsController.java:47-48` (404 `NOT_FOUND`), any org member.
4. New DTO `refund/dto/EventRefundPlanResponse` (records; nested names `EventRefundPlanOrder`,
   `EventRefundPlanSkipped` so the OpenAPI schema names do not collide):
   ```
   EventRefundPlanResponse { currency: string, totalAmountMinor: long, ticketCount: int,
     orders: [{ orderId: uuid, shortCode: string, ticketIds: uuid[], amountMinor: long, currency: string }],
     skipped: { disputedOrders: int, redeemedTickets: int } }
   ```
Read-only: no writes anywhere on this path.

## Affected files

- `src/main/java/com/imin/iminapi/refund/RefundService.java` (extract predicates)
- `src/main/java/com/imin/iminapi/refund/EventRefundPlanService.java` (new)
- `src/main/java/com/imin/iminapi/refund/EventRefundPlanController.java` (new)
- `src/main/java/com/imin/iminapi/refund/dto/EventRefundPlanResponse.java` (new)
- `src/test/java/com/imin/iminapi/refund/EventRefundPlanControllerTest.java` (new)
- `src/test/java/com/imin/iminapi/refund/RefundServiceTest.java`, `RefundControllerTest.java`,
  `RefundRequestControllerTest.java` — constructor arg / orders stamped `test_mode` the way
  checkout does under the suite's `sk_test` key, so the new mode refusal does not trip them.
- this plan

## Tests (integration, `@IminIntegrationTest`, MockMvc, Stripe faked via the shared `StripeClient` mock)

1. Redeemed ticket excluded, rest of the order planned, `skipped.redeemedTickets` = 1.
2. OPEN-dispute order skipped and counted in `skipped.disputedOrders`; an undisputed order on the
   same event still planned.
3. Ticket claimed by a PENDING refund excluded; its sibling planned.
4. Promo-discounted order (3 × 1000 face, paid 2000, one redeemed): plan amount 1333
   (round(2000 × 2000 / 3000)); POST `/orders/{id}/refund` with the plan's ticket ids sends 1333
   to Stripe — the plan equals what createRefund charges.
5. 101 orders → all 101 planned, `ticketCount` 101, total = 101 × 1500.
6. Another org's principal → 404 `NOT_FOUND`.
7. No PaymentIntent, a revoked and a refunded ticket on an undisputed order, and an order a
   PENDING refund covers in full (one unclaimed ticket left, amount 0) → all absent from
   `orders` and `ticketCount`.
8. Order from the other Stripe mode (test order under a live key, via `PropertyFlips`) → not
   planned, and `POST /orders/{id}/refund` answers 409 `ORDER_NOT_REFUNDABLE` with Stripe untouched.
9. Orders in EUR and USD → 409 `INVALID_STATE`.

What does not get a test: DTO mapping, the extracted predicates by themselves (createRefund's
existing tests already cover each refusal).

Red-proof: each guard in `EventRefundPlanService` (dispute, claim, redeemed) is removed once in a
scratch copy of the worktree and the matching test must go red; the 100-cap is proven by paging
to 100 in the scratch copy; the org check by deleting it.

## Ordered steps

1. Extract predicates in `RefundService`; `createRefund` uses them.
2. Write `EventRefundPlanControllerTest` (red: endpoint missing).
3. DTO, service, controller.
4. Targeted run, red-proofs in scratch copies, full gate.

## Verification commands

- `docker info`
- `./mvnw test -Dtest=EventRefundPlanControllerTest,RefundServiceTest,RefundControllerTest`
- `./mvnw test` (full gate; skipped Testcontainers tests = red)
