# Refund loose ends: stale PENDING, unattempted REQUESTED report, mode-checked previews

Repo: `imin-api` only. Worktree `.claude/worktrees/refund-loose-ends`, base `origin/master` b8e37473.
Money path. One migration (V182; latest on origin/master and in every sibling worktree is V181).

## Problem (as read from the code)

1. **A PENDING refund whose webhook never arrives stays PENDING forever.** Only two writers move a
   PENDING row: the refund webhooks (`StripeWebhookService.java:734-749` → `RefundService.handleWebhookStatusChange`,
   `RefundService.java:449-503`) and an approve re-press that links it (`RefundRequestService.java:627-640`, which
   reads the status, never changes it). `RefundAttemptReconciler` only looks at REQUESTED rows with no Stripe id
   (`RefundRepository.java:314-326`). A PENDING row counts as open in `countLiveOpenByEventId`
   (`RefundRepository.java:158-166`), so it holds the event's payout at step 1c (`PostEventPayoutService.java:291-296`)
   and logs ERROR nightly once it is older than the buffer.
2. **REQUESTED rows with a NULL `stripe_attempt_at`.** `RefundAttemptStore.open` always stamps the attempt
   (`RefundAttemptStore.java:48`), and every pre-V179 `createRefund` set `stripe_refund_id` in the insert's own
   transaction (rolling back on a Stripe failure), so such rows cannot arise. Review checked prod: 0 rows (all 11
   prod refunds SUCCEEDED). **Decision (review): no reconcile pass**; the tick counts them and logs ERROR if one ever
   exists, taking no action.
3. **Refund-request previews ignore the Stripe mode.** `lookupByToken` (`RefundRequestService.java:220-229`),
   `getRequest` (`:449-456`) and `listRequests` (`:545-552`) compute a refund for any order, but `createRefund` refuses
   a wrong-mode order with 409 `ORDER_NOT_REFUNDABLE` (`RefundService.java:159-163`). `eligibilityFor`
   (`RefundRequestService.java:287-297`) restates the claimed (`refundTickets.findRefundedTicketIds`) and redeemed
   (`Ticket.STATE_REDEEMED.equals`) rules that `RefundService.claimedTicketIds` (`:414-416`) and `isRedeemed`
   (`:418-420`) own.

## Decisions

- **One migration, V182:** `refunds.stripe_checked_at TIMESTAMPTZ NULL` = last time the reconciler read this row's
  state from Stripe. It rotates rows that stay PENDING so a cap of 25 never re-reads the same 25 rows each minute.
  `stripe_attempt_at` keeps its meaning ("last Stripe create started"). Entity column `updatable = false` like the other attempt columns, so the FAILED-detail full save at
  `RefundService.java:491-494` cannot revert it. Partial indexes for the PENDING sweep and the unattempted count.
- **Same job, same lock.** `RefundAttemptReconciler.reconcile()` (ShedLock `refund_attempt_reconcile`) runs two
  passes in order, each capped at `BATCH = 25`, each with its own summary line and each isolated in its own
  try/catch so one failing query does not skip the other: (a) existing unresolved attempts, unchanged;
  (b) stale PENDING. Both read only orders in the running key's mode (`o.testMode = !isLiveKey()`). Then
  (c) `countUnattemptedRequested()` (any mode): ERROR `[REFUND_UNATTEMPTED]` when > 0, no action.
- **Tick duration (review LOW): time budget, not a bigger lock.** No new row starts after `BUDGET = 4 min`
  (`System.nanoTime`, unreached rows counted `deferred` and left for the next tick). Raising `lockAtMostFor` to
  the true worst case is not viable: 50 rows × (up to 10 list pages + 2 creates) at the 110 s call ceiling is hours,
  and a crashed node would hold the lock that long. One row's worst common case (one-page list + two creates ≈
  5.5 min) after the budget still ends inside the unchanged `lockAtMostFor` 10 min; a PaymentIntent with > 100
  refunds is the ceiling. Overrunning the lock is safe anyway: every row write is a compare-and-set claim.
- **Stale PENDING (b):** a PENDING row with a Stripe id whose `coalesce(stripe_checked_at, stripe_attempt_at,
  created_at)` is older than `PENDING_RECHECK_AFTER = 1h`, oldest first. Per row: compare-and-set claim of
  `stripe_checked_at` (only while still PENDING and still past the cutoff) through `RefundAttemptStore`, then
  `stripeRefundService.retrieve(stripeRefundId)`, then **`RefundService.applyStripeRefund(refund)`** — the webhook's own
  entry point, extracted from `StripeWebhookService.onChargeRefundUpdated` so the webhook and the reconciler run the
  identical mapping and the status-conditional `handleWebhookStatusChange`. A late or duplicate webhook is then a no-op
  (status already equal / terminal, `RefundService.java:463-468`); a webhook that wins first moves the row out of
  PENDING, so the claim's `status = PENDING` predicate refuses it. Still pending at Stripe → unchanged, re-read no
  sooner than an hour later. One hour: well past webhook latency, and 72 re-reads before the 3-day payout buffer.
- **Mode check in previews:** `RefundEligibility` gains `modeMatches` (= `refundService.matchesStripeMode(order)`).
  `getRequest` proposes nothing and `listRequests` estimates 0 with `currency = null` (the shape both already return
  when nothing is refundable) on a wrong-mode order. The buyer form (`lookupByToken`) and submit answer **409
  `ORDER_NOT_REFUNDABLE`**, message "This order cannot be refunded online. Contact the organizer." (review: a
  distinct code, so the buyer site never shows the "already refunded or used" reason for it), before any ticket
  check, writing nothing. The ticket split itself (`refundable`, `nonRefundableCount`) is
  unchanged and now calls `refundService.claimedTicketIds` / `RefundService.isRedeemed`; the now-unused
  `RefundTicketRepository` constructor dependency is dropped. `approveRequest` is unchanged: its `createRefund` call
  already answers the precise 409 `ORDER_NOT_REFUNDABLE`.
- **Logging:** each pass logs at most one line per row per run (WARN when Stripe could not be read; the shared
  transition logs its own line when it moves a row) plus one summary line,
  with the existing level rule (DEBUG nothing planned, ERROR all bad, WARN some bad, else INFO).

## Affected files

| file | change |
|---|---|
| `src/main/resources/db/migration/V182__refund_stripe_checked_at.sql` (new) | column + partial index |
| `src/main/java/com/imin/iminapi/refund/Refund.java` | `stripeCheckedAt`, `updatable = false` |
| `src/main/java/com/imin/iminapi/refund/RefundRepository.java` | `findStalePending`, `claimPendingCheck`, `countUnattemptedRequested` |
| `src/main/java/com/imin/iminapi/refund/RefundAttemptStore.java` | `REQUIRES_NEW` wrapper for the pending claim |
| `src/main/java/com/imin/iminapi/refund/RefundService.java` | `applyStripeRefund`, javadoc |
| `src/main/java/com/imin/iminapi/refund/RefundAttemptReconciler.java` | pass (b), count (c), time budget, per-pass isolation, javadoc |
| `src/main/java/com/imin/iminapi/stripe/StripeRefundService.java` | `retrieve(stripeRefundId)` |
| `src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java` | `onChargeRefundUpdated` calls `applyStripeRefund` |
| `src/main/java/com/imin/iminapi/refund/RefundRequestService.java` | mode-aware eligibility, shared predicates |
| `src/test/java/com/imin/iminapi/refund/RefundAttemptReconcilerTest.java` | new tests T1–T3 |
| `src/test/java/com/imin/iminapi/refund/RefundRequestControllerTest.java` | T5 |
| `src/test/java/com/imin/iminapi/refund/PublicRefundRequestControllerTest.java` | T6; setUp stamps `test_mode = true` as checkout does under the suite's `sk_test` key |
| `src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java` | constructor + stubs for the shared predicates (existing eligibility tests are the "unchanged otherwise" proof) |
| `src/test/java/com/imin/iminapi/stripe/StripeWebhookServiceTest.java` | verifications move from `handleWebhookStatusChange` to `applyStripeRefund` |
| `CLAUDE.md` | "Refund attempts (two-phase)" paragraph: pass (b), count (c), budget, V182, buyer 409 code |

## Ordered steps

1. Write T1–T6 against the unfixed code; run them; record red.
2. V182 + entity field + repository queries + store claims.
3. `StripeRefundService.retrieve`; `RefundService.applyStripeRefund`; webhook calls it; update webhook unit test.
4. Reconciler pass (b), count (c), time budget.
5. `RefundRequestService` mode-aware eligibility + shared predicates; unit-test stubs; fixture `test_mode`.
6. Docs: CLAUDE.md paragraph, javadocs on the reconciler, `findUnresolvedAttempts`, `resolveAttempt`.
7. Verification commands.

## Tests

All integration tests `@IminIntegrationTest`, shared `StripeClient` mock, ShedLock row expired before each direct
`reconcile()` call, `MutableClock` for ages, orgs deleted in `@AfterEach`.

| id | test | kind | proves |
|---|---|---|---|
| T1 | `stalePending_isSettledFromStripeOnce_andALateWebhookIsANoOp` (param: succeeded / failed) | integration | stale PENDING → SUCCEEDED (ticket refunded, tier `sold` −1) or FAILED (failure code, claims released, client key freed); a late webhook for the same refund and a second pass change nothing (`sold` decremented once, `retrieve` called once) |
| T2 | `pendingRowsTheRecheckMustNotTouch` (param: fresh / other mode / checked recently) | integration | an eligible stale row in the same pass is retrieved exactly once (`verify` default `times(1)`; tick ran) and stays PENDING when Stripe still says pending; the excluded row is `never()` retrieved |
| T3 | `pendingRecheckThatCannotReadStripe_keepsTheRowAndRotatesIt` | integration | retrieve throws → row stays PENDING, `stripe_checked_at` bumped, next pass within the hour does not retry it |
| T5 | `wrongModeOrder_getsNoProposedRefundInDetailOrList` | integration | detail `proposedRefund` null and list `estimatedRefundMinor` 0 / `currency` null for a live order under the test key, while a test-mode request in the same org gets both |
| T6 | `wrongModeOrder_is409OrderNotRefundable_notNoRefundableTickets` (param: form / submit) | integration | 409 `ORDER_NOT_REFUNDABLE` with the exact message, and no request filed |

Not tested (§ What does not get a test): the V182 column (no data change), the entity mapping, log levels (the
`[REFUND_UNATTEMPTED]` count is a log only), the time budget (no observable effect inside a test's single pass).

## Verification commands

From the worktree root, Docker up (`docker info`):

- `./mvnw test -Dtest='Refund*Test,StripeWebhook*Test,PostEventPayout*Test,SpringContextGuardTest'` — judged by its
  own `Tests run:` totals, zero skipped. Base run on b8e37473: 295 run, 0 failures, 0 errors, 0 skipped.
- Before review: `git fetch && git ls-tree origin/master src/main/resources/db/migration/` — renumber V182 if taken.

The full clean `./mvnw test` runs at ship.

## Out of plan

- `imin-public`: buyer copy for 409 `ORDER_NOT_REFUNDABLE` on the refund form/submit, and `PUBLIC_PAGE_API.md`
  §17.2/§17.3 rows for it — paired card owned by the coordinator.
