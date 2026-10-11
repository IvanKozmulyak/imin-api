# Refund-request token burn and wrong-mode approve backstop

Repo: `imin-api` · base `origin/master` 36960605 · worktree `.claude/worktrees/refund-request-token`

## Defects

1. **Token burn rolled back.** `RefundRequestService.submitByToken` (`@Transactional`, RefundRequestService.java:306)
   sets `consumedAt` on the link token and saves it when no ticket is refundable, then throws a 409
   `ApiException` (RefundRequestService.java:325-332). `ApiException` is a `RuntimeException`
   (security/ApiException.java), so the transaction rolls back and the burn never lands.
   Intent, RefundRequestService.java:326: "Burn the token so a retry doesn't hit lookup-then-409 again."
   So the token is meant to be consumed exactly when the order has nothing refundable left — a
   permanent state for this link (tickets redeemed or already refunded never come back). It is NOT
   meant to be consumed on the other refusals, which are transient or fixable for the buyer:
   wrong Stripe mode (:322, a key cutover can fix it), an already-open request (:335, the organizer may
   reject it and the buyer can submit again), a reference collision (503 "please retry", :413).
2. **Wrong-mode approve backstop proposes a refund.** `approveRequest` with `confirm:false`
   (RefundRequestService.java:661-674) answers 400 `REFUND_APPROVAL_NOT_CONFIRMED` carrying
   `proposedRefund` amounts even when the order was paid in the other Stripe mode, which the running
   key cannot refund (`RefundService.matchesStripeMode`, RefundService.java:410; `createRefund` answers
   409 `ORDER_NOT_REFUNDABLE`, RefundService.java:159). The previews already gate on
   `eligibility.modeMatches()` (getRequest :454, listRequests :549; buyer form/submit :216/:322 throw
   409 `ORDER_NOT_REFUNDABLE`).

## Decisions

- Burn through a new `RefundRequestTokenRepository.markConsumed(id, at)`: `@Modifying` JPQL update
  guarded by `consumedAt is null`, `@Transactional(propagation = REQUIRES_NEW)` — the precedent is
  `EmailVerificationCodeRepository.incrementAttempts`. The outer transaction has only read the token
  row (no lock), so the inner update cannot wait on it. The managed entity is no longer mutated.
- Approve: after the confirm:true resume path (an earlier refund under this request's key) and before
  the refundable check, `if (!eligibility.modeMatches())` throw 409 `ORDER_NOT_REFUNDABLE` with no
  fields — the code the buyer surface and `createRefund` already use; organizer-facing message. Applies
  to both `confirm` values so neither computes numbers for a refund that cannot happen.

## Affected files

- `src/main/java/com/imin/iminapi/refund/RefundRequestTokenRepository.java`
- `src/main/java/com/imin/iminapi/refund/RefundRequestService.java`
- `src/test/java/com/imin/iminapi/refund/PublicRefundRequestControllerTest.java`
- `src/test/java/com/imin/iminapi/refund/RefundRequestControllerTest.java`

## Tests (integration, `@IminIntegrationTest`, MockMvc)

- Public: order whose only ticket is redeemed → submit 409 `NO_REFUNDABLE_TICKETS`; DB `consumed_at`
  not null; a second submit and the form both 410 `REFUND_TOKEN_EXPIRED_OR_CONSUMED`. Red on base.
- Public, parameterized over wrong-mode and already-open: submit 409 with that code; DB `consumed_at`
  null; once the cause is cleared, the same token submits 201. Green on base (guards against an
  over-eager burn).
- Organizer, parameterized `confirm` false/true on a wrong-mode pending request: 409
  `ORDER_NOT_REFUNDABLE`, no `error.fields`, request still PENDING, no refund row. `confirm=false` red
  on base (400 with `proposedRefund`).

## Ordered steps

1. Write the tests; run against base; record red.
2. Add `markConsumed`; use it in the no-refundable branch.
3. Add the mode check in `approveRequest`.
4. Run verification.

## Verification commands

`./mvnw test -Dtest='RefundRequest*Test,PublicRefundRequest*Test,SpringContextGuardTest'` (Docker up).
Full `./mvnw test` runs at ship.
