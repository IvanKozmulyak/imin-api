# Fix refund durability: no second Stripe refund on rollback, no stuck claims
`fix-refund-durable` · Mode: implement · Notion: none

## Goal and scope

**Defect, all cited from `origin/master` 5ce1fd39.**

- `RefundService.createRefund` is one `@Transactional` method (`refund/RefundService.java:87`). Inside it, it:
  - inserts the REQUESTED row (`:172-182`);
  - claims the tickets with `refundTickets.saveAllAndFlush` against `UNIQUE(refund_tickets.ticket_id)` (`:185-192`, `V28__refunds.sql:59-60`);
  - calls Stripe with `stripeIdempotencyKey(orderId, clientKey, sortedTicketIds, amount, suffix)` (`:200-205`, `:544-557`);
  - then saves the outcome (`:223-227`) and releases inventory when the status is SUCCEEDED (`:235-237`).
- If Stripe succeeds and the transaction then rolls back (pod restart, a flush/commit/lock failure, or the ticket/tier writes in `releaseInventoryAndMarkTickets` at `:472-499`), the row and the claims are gone.
- Any retry whose inputs differ then gets a different Stripe key and moves money a **second** time. Inputs can differ in three ways:
  - a new client key: the webapp mints a fresh UUID on every submit (`imin-webapp` origin/main `RefundOrderDialog.tsx` "Fresh key per submission", `RefundAllDialog.tsx` `newUuid()` per order);
  - another ticket set;
  - another amount: the cap is `total − sumActiveAmountByOrderId` (`:522-526`), and a webhook-materialized partial row lowers it (`:425`, `:441-446`).
- The comment at `:209-213` assumes a retry replays the same inputs. The webapp never does.
- **Stuck claims.** If Stripe synchronously returns a Refund with status `failed`/`canceled`, the row is saved FAILED with its claims still in place (`:225-227`). The webhook then returns early on the equal status (`:359`) and never reaches `refundTickets.deleteByRefundId` (`:382`). Those tickets stay unrefundable forever.
- **Wrong error code.** `idempotency_error` maps to `422 STRIPE_REFUND_FAILED` (`:319-321`, which falls through for 400). Yet the SDK raises `IdempotencyException` only for a 400/404 whose type is `idempotency_error`, which proves an earlier request with that key reached Stripe (`stripe-java 32.1.0 LiveStripeResponseGetter.exceptionFromStatus`, lines 343-352 of the sources jar).
- `mapStripeFailure` sends connection errors, status 0 and 429 to `UPSTREAM_UNAVAILABLE` (`:313-318`), which the webapp copy presents as "retry". A retry with a fresh key is exactly the double refund.

**Goal.** No client behaviour (new key, changed ticket set, changed amount, concurrent requests) can produce a second Stripe refund for the same tickets. Nothing is lost when a transaction rolls back after Stripe succeeded. Claims are released only when Stripe has said no refund exists.

**In scope (imin-api only):**
- two-phase `createRefund`;
- server-derived Stripe key and `imin_refund_id` metadata;
- the reconciler;
- webhook adoption by metadata;
- release of claims on a synchronous FAILED/CANCELED;
- `REFUND_IN_PROGRESS` for every unknown outcome, including `idempotency_error`;
- the `approveRequest` retry fix;
- V179 (columns plus repair of stuck claims);
- docs.

**Out of scope:**
- the webapp follow-up (listed under Contract impact);
- holding payouts while a refund is unresolved (see Risks);
- the pre-existing race where two requests on disjoint ticket sets of the same order read the same remaining cap (`:522-526`). Stripe refuses an over-refund, and this change makes REQUESTED rows visible sooner, which narrows that race.

### Options and decision

| Option | What it does | Trade-offs |
|---|---|---|
| (a) Two-phase durable attempt | Commit the claim and a REQUESTED row before Stripe; call Stripe; record the outcome in a second transaction. A reconciler resolves rows whose outcome is unknown. Retries on the same tickets hit the committed claim. | Removes both failure modes. Adds a job, a state that persists for minutes (REQUESTED, unsent or unknown), and a new error code the webapp must learn. Two DB connections per approve call (outer plus REQUIRES_NEW). |
| (b) Derive the key from durable server state only | Key = `refund_<rowId>`. Only safe if the row is durable, so it is meaningless without (a). | Within 24 h Stripe replays the same key (`imin-api/CLAUDE.md:302`: keys dropped after ~24 h). After that, a key alone does not stop a second create. |
| (c) Stripe metadata `imin_refund_id` | Lets the webhook and the reconciler map a Stripe refund to its row even before `stripe_refund_id` is written. Lets the reconciler prove whether Stripe has the refund after keys expire. | One extra param per create (`RefundCreateParams.Builder.putMetadata(String,String)`, stripe-java 32.1.0 `RefundCreateParams.java:273`). Rows created before deploy carry no metadata and keep the stripe-id lookup. |
| (d) Status quo plus "clients must reuse keys" | No server change. | Not enforceable: the webapp deliberately rotates keys, and a pod restart loses the row whatever the client does. Rejected. |
| (e) List Stripe refunds before every create | Detects an earlier refund at request time. | A rollback still erases the claim, so a concurrent request still double-refunds before the list catches up. Adds a Stripe round trip to every refund. Rejected as the main mechanism; kept inside the reconciler. |

**Decision: (a) with (b) and (c) built in.**
- The key is `refund_<rowId>` for the first create and `refund_<rowId>:platform` for the `reverse_transfer=false` retry.
- Every create carries `metadata.imin_refund_id=<rowId>`.
- The reconciler always lists the PaymentIntent's refunds and matches the metadata before it re-sends. That makes a re-send safe after the 24 h key expiry as well.

**Outcome classification** (new `StripeRefundOutcome.classify(Throwable)`, applied to every create):
- **DEFINITIVE** (Stripe processed and refused; no refund exists): `InvalidRequestException` 400/404 with a type other than idempotency, `CardException` 402, `AuthenticationException` 401, `PermissionException` 403.
- **BALANCE_INSUFFICIENT**: an `InvalidRequestException` with code `balance_insufficient` (today's special case, `:214-217`).
- **UNCERTAIN**, everything else:
  - `ApiConnectionException` or status 0;
  - status ≥ 500;
  - status 409 (the SDK maps it to a plain `ApiException`, `exceptionFromStatus` `default:` branch);
  - 429 `RateLimitException`, kept as uncertain because Stripe's `lock_timeout` also returns 429;
  - `IdempotencyException`;
  - any non-Stripe `RuntimeException` thrown during the call.
- The live request's own read can take up to 110 s: `new StripeClient(key)` at `stripe/StripeConfig.java:50` uses the builder defaults, `connectTimeout` 30 000 ms, `readTimeout` 80 000 ms and `maxNetworkRetries` 0 (stripe-java 32.1.0 `StripeClient.java:1040-1042`). The reconciler's minimum age is therefore 5 min.

### Writers audit (refunds / refund_tickets), existing

| # | Writer | Table / columns | file:line | Interaction with the new writers |
|---|---|---|---|---|
| W1 | `createRefund` insert | refunds (all) | `refund/RefundService.java:182` | Replaced by `RefundAttemptStore.open` (REQUIRES_NEW). |
| W2 | `createRefund` claim | refund_tickets insert | `RefundService.java:192` | Moves into `open`. |
| W3 | `createRefund` full-entity save of the outcome (status, stripe ids, `platformFunded` set at `:217`) | refunds | `RefundService.java:223-227` | **Removed.** Replaced by conditional updates. A full save after a webhook transition would otherwise revert SUCCEEDED to PENDING. |
| W4 | webhook conditional status flip `updateStatusIfCurrent` | refunds.status | `RefundService.java:366`, `RefundRepository.java:120-130` | Kept. Races the new `recordOutcome` on REQUESTED rows. Both are conditional on the current status, and exactly one wins (T7 tests both orderings). |
| W5 | webhook FAILED reload plus full save of failure fields | refunds (all mapped columns) | `RefundService.java:385-388` | Runs only on a row it has just moved to terminal FAILED, which no new writer touches (all are conditional on `status='REQUESTED'`). The new columns are `updatable=false`, so W5 cannot revert them. |
| W6 | webhook claim release `deleteByRefundId` | refund_tickets delete | `RefundService.java:382`, `RefundTicketRepository.java:38` | Kept. The same call is reused by `recordOutcome` and `recordRefusal`. |
| W7 | `materializeUnknownRefund` insert | refunds | `RefundService.java:441` | Kept for refunds without metadata. Rows we created now resolve by metadata first, so they never reach it. |
| W8 | `materializeUnknownRefund` claim | refund_tickets insert | `RefundService.java:461` | Kept. Already skips tickets that are claimed (`:453-455`), including those held by REQUESTED rows. |
| W9 | `RefundRecoveryMarker.markRecovered` full save | refunds (`recovered_at`, `recovery_reversal_id`, the rest written back) | `payout/RefundRecoveryMarker.java:44-56` | Only touches SUCCEEDED platform-funded rows (`RefundRepository.java:164-172`), which are terminal. No new writer touches them. Its full save cannot revert the new `updatable=false` columns or `platform_funded`, which becomes `updatable=false`. |
| W10 | ops cutover script | refunds `recovered_at` | `scripts/stripe-live-cutover.sql:121-124` | One-off. Not affected. |
| W11 | demo seed | refunds, refund_tickets insert | `scripts/demo/seed-audience-demo.sql:318,328` | New columns are nullable or defaulted, so it needs no change. |
| W12 | test cleanup | delete both | `src/test/.../support/OrgRows.java:26-37` | Not affected. |

New writers, all in `RefundAttemptStore`, REQUIRES_NEW (rule `imin-api/CLAUDE.md:302`):
- `open`: inserts row and claims;
- `switchToPlatform`: `UPDATE … SET platform_funded=true, stripe_attempt_at=:now, stripe_attempts=stripe_attempts+1 WHERE id=:id AND status='REQUESTED' AND stripe_refund_id IS NULL AND platform_funded=false`;
- `recordRefusal`: conditional on REQUESTED with a null stripe id → FAILED, failure fields, `idempotency_key='failed:'||id`, then deletes the claims;
- `recordOutcome`: conditional on REQUESTED → stripe ids and status, then the side effects if it won;
- `claimForReconcile`: a compare-and-set on `stripe_attempt_at`.

In `RefundService.handleWebhookStatusChange`, which joins the webhook transaction:
- `adoptStripeRefund`: `UPDATE … SET stripe_refund_id, stripe_charge_id WHERE id=:id AND stripe_refund_id IS NULL`.

In V179:
- deletes stuck claims.

### Owner decisions (2026-10-09)
- Unknown Stripe outcome answers HTTP 409 `REFUND_IN_PROGRESS` (not 202).
- After a definitive Stripe refusal the row stays FAILED and its client key is freed (`failed:<id>`), not deleted.
- Payout net with unresolved REQUESTED refunds: out of scope, follow-up card.
- Ships as one card.

## Repos in ship order
1. `api` (`imin-api`, base `master`), the only repo in this task.
2. Follow-up card, not part of this task: `webapp` (`imin-webapp`, base `main`), after this is live in prod (see Contract impact).

## Affected files (per repo)

**imin-api** (worktree `/Users/ivan/imin/imin-api/.claude/worktrees/fix-refund-durable`)

| File | Change |
|---|---|
| `src/main/resources/db/migration/V179__refund_attempts.sql` (new) | Adds `stripe_attempt_at TIMESTAMPTZ NULL` and `stripe_attempts INTEGER NOT NULL DEFAULT 0`, with `CONSTRAINT ck_refunds_stripe_attempts CHECK (stripe_attempts >= 0)`. Adds the partial index `refunds_unresolved_attempt_idx ON refunds (stripe_attempt_at) WHERE status='REQUESTED' AND stripe_refund_id IS NULL AND stripe_attempt_at IS NOT NULL`. Repair: `DELETE FROM refund_tickets rt USING refunds r, tickets t WHERE rt.refund_id=r.id AND t.id=rt.ticket_id AND r.status IN ('FAILED','CANCELED') AND t.state <> 'refunded'`. The status is stored as the enum name, `Refund.java:55-57`; ticket state values come from `model/Ticket.java:50-53`. The number is re-picked against fresh origin before review (`imin-api/CLAUDE.md:332`). |
| `refund/Refund.java` | New fields `stripeAttemptAt` and `stripeAttempts`, both `@Column(updatable=false)`. `platformFunded` (`:70-71`) becomes `updatable=false`; it is written only on insert and by `switchToPlatform`. Class javadoc (`:12-20`) updated: the client key is replay-only and the Stripe key is derived from the row. |
| `refund/RefundRepository.java` | New `@Modifying` queries: `recordOutcome(id, sid, chargeId, status, failureCode, failureMessage)` conditional on `status=REQUESTED AND (stripeRefundId IS NULL OR stripeRefundId=:sid)`; `recordRefusal(id, code, message)` conditional on REQUESTED with a null stripe id; `switchToPlatform(id, now)`; `claimForReconcile(id, seenAttemptAt, now)`; `adoptStripeRefund(id, sid, chargeId)`. New query `findUnresolvedAttempts(cutoff, testMode, limit)`, which joins `orders.test_mode`, orders by `stripe_attempt_at` and takes 25. |
| `refund/RefundAttemptStore.java` (new) | REQUIRES_NEW bean: `open`, `switchToPlatform`, `recordRefusal`, `recordOutcome`, `claimForReconcile`. `recordOutcome` runs the SUCCEEDED and FAILED/CANCELED side effects only when its conditional update returned 1. It truncates `failure_code` to 64 and `failure_message` to 500 (`Refund.java:59-63`). |
| `refund/RefundInventoryRelease.java` (new) | `releaseInventoryAndMarkTickets` plus the publish of `RefundConfirmedEvent`, moved out of `RefundService.java:330-333,472-499` unchanged. No `@Transactional`; it joins its caller (the webhook transaction or `recordOutcome`). |
| `refund/StripeRefundOutcome.java` (new) | Enum `DEFINITIVE`, `BALANCE_INSUFFICIENT`, `UNCERTAIN`, plus a static `classify(Throwable)` implementing the rules under Goal. |
| `refund/RefundService.java` | `createRefund` loses `@Transactional`; validation `:91-170` is unchanged. New flow is described in the Ordered steps. `stripeIdempotencyKey` (`:529-557`) is replaced by `static String stripeKeyFor(UUID refundId, boolean platform)`. `mapStripeFailure` (`:304-322`) is used only for DEFINITIVE. `fundFromPlatformBalance` (`:271-302`) is reworked onto `switchToPlatform`. New public `resolveAttempt(UUID refundId)` is used by the reconciler. `handleWebhookStatusChange` gains `String iminRefundId` and adopts by metadata. Javadocs at `:42-51`, `:209-213`, `:304-309` and `:529-543` are rewritten. The constructor gains `RefundAttemptStore`, `RefundInventoryRelease` and `Clock`. |
| `refund/RefundAttemptReconciler.java` (new) | `@Scheduled(fixedDelay=60_000, initialDelay=120_000)` plus `@SchedulerLock(name="refund_attempt_reconcile", lockAtLeastFor="PT30S", lockAtMostFor="PT10M")`. Calls `refundService.resolveAttempt` per row, one failure per row isolated. Summary log level is judged against the planned rows (`imin-api/CLAUDE.md:331`). |
| `stripe/StripeRefundService.java` | `create(...)` gains `String iminRefundId` and puts `metadata.imin_refund_id` (`:57-82`). New `List<Refund> listByPaymentIntent(String pi)` uses `stripeClient.refunds().list(RefundListParams.builder().setPaymentIntent(pi).setLimit(100L).setStartingAfter(..))` (`RefundListParams.java:204,210,221`; `RefundService.list(RefundListParams)` at `service/RefundService.java:31`). It pages on `hasMore` and stops after 10 pages by throwing, which the reconciler treats as UNCERTAIN. Javadoc updated. |
| `stripe/StripeWebhookService.java` | `onChargeRefundUpdated` (`:721-747`) passes `stripeRefund.getMetadata()==null ? null : getMetadata().get("imin_refund_id")` (`model/Refund.java:106` `Map<String,String> metadata`). |
| `security/ErrorCode.java` | Adds `REFUND_IN_PROGRESS` with a one-line javadoc ("Stripe outcome unknown; tickets stay claimed and the reconciler resolves it. 409."), next to `STRIPE_REFUND_FAILED` (`:48`). |
| `refund/RefundRequestService.java` | `approveRequest` (`:595-660`): (1) after the PENDING check, looks up `refunds.findByOrderIdAndIdempotencyKey(order.getId(), "refund-request-" + rr.getId())`. A REQUESTED row answers 409 `REFUND_IN_PROGRESS` with its `refundId`; a PENDING or SUCCEEDED row is linked and approved without calling `createRefund`; any other status falls through to a new attempt. (2) A `REFUND_IN_PROGRESS` from `createRefund` propagates: the request stays PENDING (superseded in review round 2). |
| `refund/RefundController.java` | Javadoc (`:21-27`) adds the response set: `409 REFUND_IN_PROGRESS` (fields `refundId`), `202` with `status:"requested"` on a same-key replay of an unresolved attempt, and `202` with `status:"failed"` when Stripe answers failed synchronously. |
| `CLAUDE.md` | In § Stripe Connect, after the webhook list (`:220-235`), adds a "Refund attempts" paragraph: two-phase, key format, metadata, `REFUND_IN_PROGRESS`, `RefundAttemptReconciler` (every 60 s, ShedLock `refund_attempt_reconcile`, min age 5 min, 25 per pass, ERROR from 12 attempts), the V179 columns, and "claims are released only after Stripe refuses". |
| `src/test/java/com/imin/iminapi/refund/RefundServiceTest.java` | Constructor call (`:60`) gains mocks for the store, the inventory release and a clock. Nested `StripeIdempotencyKeyStability` (`:597-688`) is deleted: the key it pins no longer exists, and T1/T2/R2 replace it. In `StripeFailureHandling` (`:689-793`), `connectionTimeoutMapsToUpstreamUnavailable` and `rateLimitMapsToUpstreamUnavailable` are deleted, now covered by U1 and T2. The two `balanceInsufficient*` tests are kept and rewritten to assert `store.switchToPlatform` and then a `:platform` key, and `store.recordRefusal` on the second refusal. The `WebhookTerminalFailure` (`:365-420`) and `UnknownStripeRefund` (`:794-878`) calls gain a null `iminRefundId`. The happy-path, promo and clamp tests (`:281-327`, `:465-596`) stub `store.open` to return the row and match the new `create` arity. `concurrent_claim…` (`:328-363`) has `store.open` throw. New test H1. |
| `src/test/java/com/imin/iminapi/refund/RefundControllerTest.java` | `:156-157`: expected key becomes `RefundService.stripeKeyFor(UUID.fromString(id), false)`. Adds an assertion `params.getValue().getMetadata()` contains `imin_refund_id=id`. |
| `src/test/java/com/imin/iminapi/stripe/StripeRefundServiceTest.java` | All `service.create(...)` calls gain `iminRefundId`. `create_passesAmountReasonAndIdempotencyKey` (`:57`) also asserts the metadata. |
| `src/test/java/com/imin/iminapi/stripe/StripeWebhookServiceTest.java` | The five `verify(refundService).handleWebhookStatusChange(...)` calls (`:252,268,287,298` and the `refund.failed` one after `:309`) gain an eighth matcher (`isNull()` for these payloads). New test W1. |
| `src/test/java/com/imin/iminapi/refund/RefundRequestServiceTest.java` | New test A1. The existing approve test (`:730-761`) stays as it is: the new `refunds.findByOrderIdAndIdempotencyKey` stub falls back to Mockito's default `Optional.empty()`. |
| `src/test/java/com/imin/iminapi/refund/EventRefundPlanControllerTest.java` | `ticketClaimedByAPendingRefund_isLeftOut` (`:135`) becomes a `@ParameterizedTest` over {PENDING with a stripe id, REQUESTED with a null stripe id and `stripe_attempts=1`}. The `pendingRefund` helper (`:313-326`) takes the status and the stripe id. |
| `src/test/java/com/imin/iminapi/refund/RefundDurabilityTest.java` (new) | T1–T9 (integration). |
| `src/test/java/com/imin/iminapi/refund/RefundAttemptReconcilerTest.java` (new) | R1–R6 (integration). |
| `src/test/java/com/imin/iminapi/refund/StripeRefundOutcomeTest.java` (new) | U1 (unit). |
| `src/test/java/com/imin/iminapi/migration/RefundAttemptMigrationTest.java` (new) | M1 (Spring-free, `SharedPostgres.migratedDatabase("refund_attempts","178")`, then Flyway `migrate()`, as in `migration/OrderSettlementMigrationTest.java:31-48`). |

These need no change:
- `RefundRecoveryMarker`: its full save cannot revert the `updatable=false` columns (W9).
- `EventRefundPlanService`: it reads claims, which REQUESTED rows hold (`EventRefundPlanService.java:70-77`).
- `PostEventPayoutService`: the payout net reads only SUCCEEDED rows (`OrderRepository.java:88-96`; risk noted).
- `RefundConfirmationEmailer`: still triggered AFTER_COMMIT on `RefundConfirmedEvent` (`RefundConfirmationEmailer.java:68-70`), now after `recordOutcome` commits.
- `EventRefundsController`: lists rows and renders REQUESTED already.
- `StripeWebhookServiceConnectTest`: it constructs the webhook service with a mocked `RefundService` and never verifies refund calls (`StripeWebhookServiceConnectTest.java:119-124`).

## Ordered steps

1. **Migration V179** as in the table. Re-pick the number with `git fetch && git ls-tree origin/master src/main/resources/db/migration/` right before review. The current max is V178.
2. **Entity and repository:** the `Refund` fields and the `RefundRepository` queries. Each `@Modifying` uses `clearAutomatically=true, flushAutomatically=true`, as at `RefundRepository.java:120`, and returns `int`.
3. **`StripeRefundOutcome.classify`**, with U1 written first.
4. **`StripeRefundService`:** the metadata param on `create`, and `listByPaymentIntent`.
5. **Extract `RefundInventoryRelease`.** `handleWebhookStatusChange` calls it at `:372-375`.
6. **`RefundAttemptStore`:**
   - `open(draft, ticketIds, now)`: `saveAndFlush` the row with status REQUESTED, `stripeAttemptAt=now`, `stripeAttempts=1`, then `saveAllAndFlush` the claims.
   - `recordOutcome(id, stripeRefund)`: map the status with `RefundStatus.fromStripe`, call `repo.recordOutcome`. If it won:
     - SUCCEEDED: `inventoryRelease.release(row)`;
     - FAILED/CANCELED: `refundTickets.deleteByRefundId`, store `failure_reason`, and publish `RefundFailedEvent` for FAILED (mirrors `:376-395`).
     - The method returns the fresh row whether it won or not.
   - `recordRefusal`, `switchToPlatform`, `claimForReconcile`.
7. **Rewrite `RefundService.createRefund`:**
   1. Validation as today (`:91-170`). Then `store.open(...)`.
      - On `DataIntegrityViolationException` whose root-cause constraint name is `refunds_order_idem_unique` (`V28__refunds.sql:45-46`): re-read with `findByOrderIdAndIdempotencyKey` and return it as a replay.
      - On any other violation: 409 `TICKET_ALREADY_REFUNDED`, as at `:193-198`.
   2. `attempt(row, order)`, outside any transaction: `stripeRefundService.create(pi, amount, currency, reason, appFee, !platformFunded, stripeKeyFor(id, platformFunded), id.toString())`, then classify.
      - **Success:** call `store.recordOutcome`. If that throws, log ERROR `[REFUND_FINALIZE_FAILED] refundId= stripeRefundId=` with the throwable and throw `REFUND_IN_PROGRESS`.
      - **BALANCE_INSUFFICIENT** with `!platformFunded`: call `store.switchToPlatform`, then make one more attempt with the platform key (the old `:280-302` replaced).
      - **BALANCE_INSUFFICIENT** on the platform attempt, or **DEFINITIVE**: call `store.recordRefusal(id, e.getCode(), e.getMessage())`. Then throw `mapStripeFailure(e)`, or for the platform case `409 ORDER_NOT_REFUNDABLE` with today's message (`:297-300`).
      - **UNCERTAIN:** log WARN and throw `409 REFUND_IN_PROGRESS` with fields `{refundId}` and `stripeCode` when present.
   3. Return the row from `recordOutcome`.
   4. `resolveAttempt(id)` shares `attempt(...)` (step 9).
8. **`handleWebhookStatusChange(..., iminRefundId)`:**
   1. If `findByStripeRefundId` misses and `iminRefundId` parses to the UUID of a row whose `stripePaymentIntentId` equals the event's PI:
      - if that row's `stripeRefundId` is null, call `adoptStripeRefund`, reload, and continue with the existing transition (`:359-396`);
      - if it is non-null and different, log ERROR `[REFUND_SECOND_STRIPE_REFUND] refundId= existing= new=` and fall through to `materializeUnknownRefund`, so the money is visible.
   2. Otherwise, as today.
9. **`RefundAttemptReconciler.reconcile()`:**
   1. Read `findUnresolvedAttempts(clock.instant() − 5 min, !stripeProps.isLiveKey(), 25)`.
   2. Per row, call `refundService.resolveAttempt(id)`, which:
      1. calls `store.claimForReconcile(id, seenAttemptAt, now)`; if that returns 0, skip;
      2. calls `listByPaymentIntent(pi)`; if any refund has `metadata.imin_refund_id == id`, calls `store.recordOutcome(id, it)`, done;
      3. otherwise, if `isBlockedByDispute(orderId)` (`:249-251`), calls `store.recordRefusal(id, "order_disputed", <copy ledger C5>)`, done;
      4. otherwise calls `attempt(row, order)` with the stored amount, fee, reason and variant (never recomputed).
      - A list failure or an UNCERTAIN result leaves the row as it is (claim kept, `stripe_attempts` already incremented by the claim).
   3. Log ERROR per row with `stripe_attempts >= 12`: `[REFUND_UNRESOLVED] refundId= attempts=`.
   4. Log the summary `RefundAttemptReconciler: planned= adopted= resent= refused= unresolved= failed=`: DEBUG when nothing was planned, ERROR when all planned rows failed, WARN when some did, else INFO.
10. **`RefundRequestService.approveRequest`:** steps (1) and (2) from the table.
11. **`ErrorCode`**, the javadocs (`RefundService`, `RefundController`, `Refund`, `StripeRefundService`) and the `CLAUDE.md` paragraph.
12. **Tests** per Test impact. Then the red proofs (each guard removed once, test red, guard restored).

## Verification commands
In `/Users/ivan/imin/imin-api/.claude/worktrees/fix-refund-durable`:
- `docker info` must succeed. Run `./mvnw test` once on the untouched base first, and record whether it was already red.
- Targeted (comma-separated, judged by its own `Tests run:` line): `./mvnw test -Dtest=RefundDurabilityTest,RefundAttemptReconcilerTest,StripeRefundOutcomeTest,RefundAttemptMigrationTest,RefundServiceTest,RefundControllerTest,StripeRefundServiceTest,StripeWebhookServiceTest,RefundRequestServiceTest,EventRefundPlanControllerTest`
- Full gate: `./mvnw test`. A run with any skipped Testcontainers test counts as red. Re-run after every rebase.
- After any Flyway renumber: `./mvnw clean` (`imin-api/CLAUDE.md:314`).

## Test impact

Branches of the changed logic, with the test for each:

| Branch | Test |
|---|---|
| create → Stripe pending | `RefundControllerTest.anyReasonCasing…` (updated) |
| create → Stripe succeeded | T7 |
| create → Stripe failed/canceled synchronously | T3 |
| DEFINITIVE | T5 |
| BALANCE_INSUFFICIENT → platform success | `RefundServiceTest.balanceInsufficientRetriesPlatformFunded` (updated) |
| BALANCE_INSUFFICIENT → platform refused | `RefundServiceTest.balanceInsufficientTwiceStillThrows409` (updated) |
| BALANCE_INSUFFICIENT → platform uncertain | R2 (platform case) |
| UNCERTAIN (transport) | T2 |
| UNCERTAIN (`idempotency_error`) | T4 |
| Stripe success but recording failed | T1 |
| open: idempotency-key race | H1 |
| open: ticket race | `RefundServiceTest.concurrent_claim…` (updated), T6 |
| same-key replay | T2 |
| webhook: found by stripe id | existing `WebhookTerminalFailure` |
| webhook: adopt by metadata | T7 (webhook-first case) |
| webhook: metadata row has another stripe id | T9 |
| webhook: no metadata | existing `UnknownStripeRefund` |
| reconciler: found | R1 |
| reconciler: re-send | R2 |
| reconciler: refused | R3 |
| reconciler: disputed | R4 |
| reconciler: skip | R5 |
| reconciler: uncertain | R6 |
| approve: lost approval | T8 |
| approve: in progress | A1 |
| classification | U1 |
| migration | M1 |
| plan | P1 |
| webhook metadata pass-through | W1 |

All integration tests use `@IminIntegrationTest` with nothing that changes the context. Stripe goes through the shared `StripeClient` mock: `when(stripeClient.refunds()).thenReturn(mock(com.stripe.service.RefundService.class))`, as at `RefundControllerTest.java:121-126`. Data comes from `IminFixtures`. Time uses `MutableClock.advance`. Every money test runs `OrgRows.delete(jdbc, orgIds)` in `@AfterEach`. Reconciler assertions name their own PaymentIntent ids (`argThat(p -> p.getPaymentIntent().equals(pi))`), because the sweep is global (`imin-api/CLAUDE.md:144`). Each direct `reconcile()` call is preceded by `UPDATE shedlock SET lock_until = locked_at WHERE name='refund_attempt_reconcile'` and asserts a positive Stripe interaction for its own row (`imin-api/CLAUDE.md:146,305`).

Every test below guards the "money (refunds, idempotency)" item, except M1, which guards "each Flyway migration that changes data".

| Id | File · test | Kind | Setup (minimal) and assertions | Bug a user/organizer/auditor would notice | Red proof (guard removed → which assertion fails) |
|---|---|---|---|---|---|
| T1 | RefundDurabilityTest · `rollbackAfterStripeSuccess_retryWithNewKey_movesMoneyOnce` | integration | One ticket on a paid order (total 1500). Stripe `create` returns `status=succeeded`. `PgFaults.failWrites(jdbc,"tickets","id",ticket)` makes the outcome transaction fail. POST key K1 → 409 `REFUND_IN_PROGRESS` with `fields.refundId`, and the row is REQUESTED with its claim. POST key K2 → 409 `TICKET_ALREADY_REFUNDED`. `verify(stripeRefunds, times(1)).create`. Close the fault, advance the clock 6 min, stub `list` to return that Stripe refund with its metadata, reconcile → row SUCCEEDED with the stripe id, ticket `refunded`, `tier.sold` down by 1, still one `create`. | The buyer is refunded twice after a pod restart and the organizer loses the money. | Run `open` and `recordOutcome` in one transaction → the K2 POST makes a second `create` and `times(1)` fails. |
| T2 | RefundDurabilityTest · `uncertainAttempt_holdsTicketsAgainstAChangedSelection_andReplaysTheSameKey` | integration | Tickets A and B. Stripe throws `ApiConnectionException`. POST {A,B} with K1 → 409 `REFUND_IN_PROGRESS`. POST {A} with K2 → 409 `TICKET_ALREADY_REFUNDED`. POST {A,B} with K1 → 202 `status:"requested"`, same id. One `create`, whose key is `stripeKeyFor(id,false)` and whose metadata is `imin_refund_id=id`. | Refund all retried after a timeout refunds the same tickets twice. | Release claims on UNCERTAIN → the K2 POST is 202 and a second `create` happens. |
| T3 | RefundDurabilityTest · `syncFailedRefund_releasesItsTickets` | integration | Stripe returns `status=failed`, `failure_reason=expired_or_canceled_card`. POST → 202 `status:"failed"`; the row's `failure_code` is that reason; no `refund_tickets` rows. A second POST with a fresh key → 202 and a second `create`. | A ticket whose refund failed can never be refunded again (409 forever). | Drop `deleteByRefundId` in `recordOutcome`'s FAILED branch → the second POST is 409. |
| T4 | RefundDurabilityTest · `idempotencyError_isRefundInProgress_andKeepsTheClaim` | integration | Stripe throws `new IdempotencyException(msg,"req_1",null,400)`. POST → 409 `REFUND_IN_PROGRESS`, `fields.refundId`; the row is REQUESTED and its claim is present. | The organizer is told the refund failed (422) and issues it again although Stripe already has it. | Map `IdempotencyException` like other 4xx → 422 `STRIPE_REFUND_FAILED`. |
| T5 | RefundDurabilityTest · `definitiveRefusal_freesTicketsAndClientKey` | integration | Stripe throws `InvalidRequestException(msg,null,"req",“charge_already_refunded”,400,null)`. POST with K1 → 422 `STRIPE_REFUND_FAILED` with `fields.stripeCode`. The row is FAILED, its key is `failed:<id>`, and it has no claims. POST with K1 again → a new row and a second `create` with a different Stripe key. | A refused refund blocks the tickets, or the buyer's approved request ends up linked to a failed refund. | (a) Drop the key rename → the second POST is 202 with the old FAILED row and one `create`. (b) Drop the claim delete → 409. |
| T6 | RefundDurabilityTest · `secondRequestDuringFirstStripeCall_isRefusedWithoutStripe` | integration | Request A's Stripe answer starts request B (same ticket, fresh key) on a second thread and waits ≤ 5 s for it. B → 409 `TICKET_ALREADY_REFUNDED` within the wait. One `create`. | Two organizers clicking Refund at once both refund the buyer. | Run `open` in the outer transaction → B blocks on the unique index and the 5 s wait fails. |
| T7 | RefundDurabilityTest · `webhookBeforeOrAfterFinalize_releasesInventoryOnce` | integration, parameterized: webhook-first / finalize-first | Stripe returns `succeeded`. Webhook-first: inside the Stripe answer, call `refundService.handleWebhookStatusChange(sid, SUCCEEDED, …, metadataId)`. Finalize-first: call it after the POST. Both: one refund row for the order, SUCCEEDED, with the stripe id, `tier.sold` down by exactly 1. | Inventory is released twice (sold goes negative or other tickets are freed), or a duplicate refund row appears in the organizer's refund list. | Webhook-first: drop the metadata adoption → materialize writes a second row (row count 2). Finalize-first: make `recordOutcome` unconditional → `sold` drops by 2. |
| T8 | RefundDurabilityTest · `approveRetryAfterLostApproval_linksTheExistingRefund` | integration (`POST /api/v1/orgs/{orgId}/refund-requests/{id}/approve`, `RefundRequestController.java:66`) | A pending request. Stripe returns `pending`. `PgFaults.failWrites(jdbc,"refund_requests","id",rrId)` → the approve answers 5xx and the request stays PENDING, while the refund row is committed as PENDING. Close the fault and approve again → 200 `approved`, `refundId` = that row, one `create`. | A refunded buyer's request stays "pending" forever, and the organizer cannot approve it (409). | Drop the pre-lookup → the second approve is 409 `NO_REFUNDABLE_TICKETS`. |
| T9 | RefundDurabilityTest · `webhookMetadataNamingARowWithAnotherStripeId_materializesTheSecondRefund` | integration | A SUCCEEDED row with `re_A`. Webhook for `re_B` with the same PaymentIntent and `imin_refund_id=row` → the row keeps `re_A`; a second row with `re_B` exists. | A real second refund is invisible in imin's books (auditor). | Adopt without checking the stripe id → a unique violation, or `re_A` is overwritten. |
| R1 | RefundAttemptReconcilerTest · `refundFoundOnStripeByMetadata_isAdoptedWithoutCreate` | integration, parameterized: page 1 / page 2 (`hasMore=true`, then the match) | REQUESTED row from an UNCERTAIN POST. Advance the clock 6 min. `list` returns the match → row PENDING with the stripe id; `create` never called again (one total). | The reconciler refunds a buyer Stripe already refunded. | Read the first page only → the page-2 case re-sends (two `create` calls). |
| R2 | RefundAttemptReconcilerTest · `refundMissingOnStripe_isResentWithTheStoredKeyAmountAndVariant` | integration, parameterized: plain / platform-funded (first call `balance_insufficient`, second `ApiConnectionException`) / cap lowered (two tickets of 1500 on a total of 3000; a dashboard partial of 500 materialized via the webhook before the reconcile) | `list` empty → the second `create` key equals the first; amount = the stored `amount_minor` (1500 in every case); `reverseTransfer`/key suffix `true`/"" or `false`/`:platform`. | A retry sends a different key or amount (a second refund), or uses the connected account after imin already decided to front it. | (a) Recompute the amount → the cap-lowered case sends 1000 instead of 1500. (b) Ignore `platform_funded` → the platform case sends `reverseTransfer=true`. |
| R3 | RefundAttemptReconcilerTest · `resentRefundRefused_marksFailedAndReleasesTickets` | integration | `list` empty; `create` throws `InvalidRequestException` "charge_already_refunded" → row FAILED, claims gone. | Tickets locked forever behind an attempt Stripe refused. | Treat DEFINITIVE as UNCERTAIN → the row stays REQUESTED. |
| R4 | RefundAttemptReconcilerTest · `refundMissingOnStripeOfADisputedOrder_isFailedWithoutCreate` | integration | OPEN dispute on the order (helper as in `EventRefundPlanControllerTest.java:328-339`); `list` empty → row FAILED `order_disputed`, claims released, `list` called once, `create` only the original. | The buyer gets a refund on top of the chargeback. | Drop the dispute check → a second `create`. |
| R5 | RefundAttemptReconcilerTest · `rowsTheReconcilerMustNotTouch_getNoStripeCall` | integration, parameterized: attempt 1 min old / order in the other Stripe mode (`test_mode=false` under the suite's sk_test key) / claim lost (`PgFaults.skipUpdates(jdbc,"refunds","id",rowId)`) | Each case also seeds one eligible row that must get its `list` call (proves the tick ran). The excluded row gets no `list` and no `create`. | A test-mode refund is marked failed by the live key (its PaymentIntent 404s) and its tickets are freed; or two replicas send the same refund at once. | Drop the mode filter → the other-mode row gets a `list` call. |
| R6 | RefundAttemptReconcilerTest · `uncertainResend_keepsTheClaim` | integration, parameterized: `list` throws `ApiConnectionException` / `list` empty and `create` throws `ApiConnectionException` | Row stays REQUESTED, claims present, `stripe_attempts` 2. | The reconciler releases tickets while Stripe may hold the refund, so a later refund pays twice. | Map UNCERTAIN to `recordRefusal` → the row is FAILED and the claims are gone. |
| U1 | StripeRefundOutcomeTest · `classify` | unit, `@ParameterizedTest` | `ApiConnectionException`→U; `ApiException` 500→U; `ApiException` 409→U; `RateLimitException` 429→U; `IdempotencyException` 400→U; `IllegalStateException`→U; `InvalidRequestException` 400 "charge_already_refunded"→D; `InvalidRequestException` 400 "balance_insufficient"→B; `CardException` 402→D; `AuthenticationException` 401→D; `PermissionException` 403→D. | A timeout releases tickets (double refund), or a hard refusal blocks them forever. | Flip any one mapping → that row fails. |
| H1 | RefundServiceTest · `idempotencyKeyRaceOnOpen_replaysTheWinner` | unit (a read-side race a trigger cannot produce, `imin-api/CLAUDE.md:143`) | `store.open` throws `DataIntegrityViolationException` whose cause names `refunds_order_idem_unique`; `findByOrderIdAndIdempotencyKey` returns the winner → the winner is returned and no `stripeRefunds.create`. | A double-clicked submit is shown as "already refunded" instead of the refund it created. | Map every violation to 409 → `TICKET_ALREADY_REFUNDED` is thrown. |
| A1 | RefundRequestServiceTest · `refundInProgress_leavesTheRequestPending` | unit | `refundService.createRefund` throws 409 `REFUND_IN_PROGRESS` → it propagates; the request stays PENDING with no refund linked (review round 2). | A request marked approved while its refund may still be refused. | Catch it and approve → nothing thrown. |
| W1 | StripeWebhookServiceTest · `refundEventMetadata_passesTheIminRefundId` | unit (existing harness) | `refund.updated` payload with `"metadata":{"imin_refund_id":"<uuid>"}` → verify the eighth argument equals that uuid. | The webhook cannot map our refund, so it materializes a duplicate. | Pass null → the verify fails. |
| M1 | RefundAttemptMigrationTest · `v179ReleasesOnlyDeadClaims` | integration (migration, Spring-free) | At V178, seed claims under FAILED (ticket issued), CANCELED (ticket issued), FAILED (ticket refunded), PENDING and SUCCEEDED; migrate → only the first two are deleted; `stripe_attempts` = 0 on existing rows; `stripe_attempts = -1` is rejected by `ck_refunds_stripe_attempts`. | Tickets stuck since a past synchronous failure stay unrefundable after the fix, or a live claim is wiped (double refund). | Drop the status predicate → the PENDING and SUCCEEDED claims are deleted. |
| P1 | EventRefundPlanControllerTest · `ticketClaimedByAPendingRefund_isLeftOut` (parameterized edit) | integration | Add the case REQUESTED with a null stripe id and `stripe_attempts=1` → the ticket is absent from the plan. | Refund all re-plans an in-flight refund. | Filter claims by status in `claimedTicketIds` → the REQUESTED case lists the ticket. |

Existing tests that read changed fixtures or signatures, with their expected new values:
- `RefundControllerTest.anyReasonCasing…`: key = `stripeKeyFor(id,false)`, plus metadata.
- `StripeRefundServiceTest`: arity and metadata.
- `StripeWebhookServiceTest`: eighth matcher `isNull()`.
- `RefundServiceTest`: per the Affected files table.

Tests that save `Refund` entities with no attempt fields still pass unedited, because the new columns are nullable or defaulted: `RefundPlatformFundedTest`, `PostEventPayout*Test`, `DisputeWithholdingIntegrationTest`, `DashboardRevenueTest`, `PayoutTestModeExclusionTest`, `StripeLiveCutoverScriptTest`. I grepped their `setPlatformFunded`/`setStripeRefundId` uses: all are inserts, for example `StripeLiveCutoverScriptTest.java:147-157`. None runs the reconciler.

## Live-test
`/live-test api`, local, with `STRIPE_SECRET_KEY=sk_test_…` and `stripe listen` forwarding to `/api/v1/stripe/webhook/v1`:
1. Refund one ticket of a test order through the dashboard. In the Stripe test dashboard, the refund carries `metadata.imin_refund_id = <row id>`, and the request log shows the key `refund_<row id>`. The row moves REQUESTED → PENDING/SUCCEEDED; `stripe_attempts=1`.
2. Simulate an unknown outcome: start the API with an unroutable `https_proxy` for the Stripe host, refund, and expect 409 `REFUND_IN_PROGRESS`. Restart normally and wait about 6 min. The reconciler log shows `adopted=0 resent=1`, Stripe has exactly one refund with that metadata, and the row is resolved.
3. Refund all on an event with two orders: no duplicate refunds in Stripe.

Prod checks (read-only; prod is on `sk_live_`, so no prod refund is made for the test):
- Before deploy: `select count(*) from refunds where status='REQUESTED' and stripe_refund_id is null;` should be 0. `select count(*) from refund_tickets rt join refunds r on r.id=rt.refund_id join tickets t on t.id=rt.ticket_id where r.status in ('FAILED','CANCELED') and t.state<>'refunded';` gives N, the number of claims V179 releases.
- After deploy: run the second query again; it should be 0. Logs show a `RefundAttemptReconciler` tick, DEBUG when nothing is planned. The first real refund after deploy has `stripe_attempt_at` set and `stripe_attempts=1`, and its Stripe object carries the metadata. This forward invariant is checked on the next write.

## Contract impact
- No path or schema change to `/api/v1`. `ApiError.Body.code` is a free `String` (`security/ApiError.java:18`), so there is no OpenAPI marker to wait for. `api:sync` shows no diff, and `src/shared/api/types.ts` needs no edit: `RefundResponse.status` already includes `'requested'` (webapp origin/main `types.ts:1106`). `PUBLIC_PAGE_API.md` is not touched, because `/api/v1/public` does not move.
- Behaviour changes the webapp must handle. These go in a follow-up webapp card that ships after this api is in prod:
  1. Add `'REFUND_IN_PROGRESS'` to `src/shared/api/errors.ts` (the union at `:28-36`) and to `humanizeError.ts`, with copy in EN/ES/FR/UK.
  2. `RefundAllDialog.tsx`: count `REFUND_IN_PROGRESS` as initiated, not failed. Today any rejection counts as failed, and `reasonOf` shows `e.message` (origin/main `:51-55`, `:96-99`).
  3. `RefundOrderDialog.tsx` `onError`: show the in-progress copy and invalidate the refunds queries, as `onSuccess` does.
  4. `ConfirmRefundModal.tsx`: approve can now answer 409 `REFUND_IN_PROGRESS` (request stays pending). Refund lists can show `requested`; `refundStatusLabels.ts` needs that label in all four locales.
  5. Pre-existing and recommended: a 202 with `status:"failed"` currently shows the success toast.
- Until the webapp card ships, the organizer sees the API's English message (copy ledger C1) in the Refund all and order dialogs.

## i18n impact
- The API adds no localized strings.
- The `REFUND_IN_PROGRESS` message (C1) and the reconciler's stored `failure_message` (C5) are English API text. The webapp currently renders such messages raw (`RefundAllDialog.tsx` `reasonOf`).
- The follow-up webapp card owns the EN/ES/FR/UK copy for `REFUND_IN_PROGRESS` and the `requested` label.

### Copy ledger

| # | String (API) | Field(s) behind it (file:line) | Meaning, scope, range | Rendered next to it |
|---|---|---|---|---|
| C1 | "imin could not confirm this refund with Stripe yet. Its tickets stay reserved for it and imin checks with Stripe again every few minutes, so do not issue it again." | `ErrorCode.REFUND_IN_PROGRESS`, `fields.refundId` (new, thrown in `RefundService.attempt`) | The row is REQUESTED with a null stripe id. Branches that produce it: connection error or status 0, 5xx, 409, 429, `IdempotencyException`, a non-Stripe exception during the call, a failed platform retry with an unknown outcome, and a failure to record a Stripe success (Stripe answered, imin did not record it). "Every few minutes" is the 60 s tick plus the 5 min minimum age. | `RefundAllDialog` distinct-reasons toast and `RefundOrderDialog` error toast (`e.message`) until the webapp card ships. |
| C2 | Stripe's own error message (reused) | `mapStripeFailure` message = `e.getMessage()` (`RefundService.java:312`), `fields.stripeCode` | DEFINITIVE refusal only (Stripe processed the request and refused). No refund exists. | Same toasts. Reused unchanged. |
| C3 | "The connected account's balance is too low to fund this refund and the platform-funded retry failed too. …" (reused) | `RefundService.java:297-300` | Now raised only when the platform attempt is also refused (BALANCE_INSUFFICIENT or DEFINITIVE). An unknown platform outcome gives C1 instead. | Same toasts. |
| C4 | `status:"failed"`, `failureMessage` = Stripe `failure_reason` (reused) | `recordOutcome` stores it as the webhook does (`StripeWebhookService.java:742-743`); exposed by `RefundResponse.failureMessage` (`refund/dto/RefundResponse.java:19`) | Stripe created the refund object and it failed or was canceled synchronously. Its claims are released. | Webapp refund lists (status chip). The success toast is a pre-existing webapp bug (follow-up). |
| C5 | "Stripe has no record of this refund and the order is now disputed, so imin did not send it." | `failure_code='order_disputed'`, `failure_message` (written by `recordRefusal` in `resolveAttempt`) | Only when the Stripe list shows no refund with that metadata and `isBlockedByDispute` is true at reconcile time. | Wherever `failureMessage` is shown for a refund. |

Stripe call outcomes, kept apart in the stored state and in the copy:

| Outcome | Stored state | Copy |
|---|---|---|
| Not run (row committed, process died before the call) | REQUESTED, `stripe_attempts=1`. Indistinguishable from unknown, and resolved the same way by listing. | No response reached the client; C1 only if a later same-key replay returns it, as `status:"requested"`. |
| Ran, with a result | PENDING / SUCCEEDED | Existing copy. |
| Ran, refused | FAILED with Stripe's code | C2 / C3 |
| Ran, unusable or unknown output | REQUESTED | C1 |
| Ran, refund object failed | FAILED / CANCELED with a Stripe id | C4 |

## Blast radius
- **Money and Stripe.** Every refund creation path goes through `createRefund`: `RefundController.java:43-52`, `RefundRequestService.approveRequest` `:638-642`, and the webapp Refund all, which calls POST once per order. The webhook refund transition is touched (`StripeWebhookService.java:290-293,721-747`; `RefundService.java:348-397`). Platform-funded refunds (`:271-302`) keep their two-key behaviour, now with durable `platform_funded`. The `PostEventPayoutService` platform-funded recovery (`:721-725`) reads SUCCEEDED rows only and needs no change. Dispute withholding reads SUCCEEDED sums (`DisputeWithholding.java:128,161,210`) and needs no change.
- **New scheduled job.** `RefundAttemptReconciler` makes Stripe calls (list, and create only when Stripe has no matching refund). It runs only for rows created after deploy (`stripe_attempt_at IS NOT NULL`) and in the running key's mode.
- **Flyway V179.** Additive columns plus a DELETE of dead claims. Prod effect: those tickets become refundable in-product again, which is correct because no money moved for them.
- **Shared module.** `ErrorCode` is shared by every controller. Adding a constant is additive.
- **Guard placed on every path that reaches the refund state machine.** Claims are released only after a refusal in `recordRefusal` (live and reconciler) and `recordOutcome` (live, reconciler, and webhook via `handleWebhookStatusChange`'s existing branch at `:376-382`). The status moves out of REQUESTED only through the conditional `recordOutcome`, `recordRefusal` and `updateStatusIfCurrent`. Webhook, live request and reconciler are the only three paths; no admin endpoint writes refunds (writers audit W1–W12).
- **Every file listed here also appears under Affected files.** Only `EventRefundPlanService`, `PostEventPayoutService`, `RefundRecoveryMarker`, `DisputeWithholding` and `RefundConfirmationEmailer` are left unchanged, for the reasons given there.

## Risks
- **Size.** About 24 files, over the ~15 guideline. Proposed split, if wanted:
  - (1) V179 repair plus releasing claims on a synchronous FAILED in today's single-transaction code (≈5 files, can ship first);
  - (2) two-phase, reconciler, webhook adoption, `REFUND_IN_PROGRESS`, approve fix.
  
  I recommend one task: (1)'s code path is rewritten by (2), so splitting edits the same lines twice.
- **REQUESTED now persists for minutes.**
  - The payout net subtracts only SUCCEEDED refunds (`OrderRepository.java:88-96`), the same pre-existing exposure as PENDING refunds today. Proposed follow-up card: payout waits while an event has REQUESTED or PENDING refunds.
  - Owner decision 2026-10-09: out of scope here; tracked as a follow-up card (payout net with unresolved REQUESTED refunds).
  - Door scans can redeem a ticket whose refund is REQUESTED, as with PENDING today.
- **24 h key expiry.** Safety after 24 h rests on the Stripe list by PaymentIntent being complete and up to date. That is an unverified assumption about Stripe's list consistency, mitigated by the 5 min minimum age (> the 110 s call ceiling) and the 10-page cap, which counts as UNCERTAIN.
- **Approve uses two connections** (outer plus REQUIRES_NEW) while Stripe is called. Pool pressure only under heavy concurrent approvals.
- **Behaviour change for API clients.** A timeout is now 409 `REFUND_IN_PROGRESS`, where it used to be 502/503 `UPSTREAM_UNAVAILABLE` (`RefundService.java:313-318`). Until the webapp card ships, organizers see the English C1 message.
- **Deploy-time in-flight requests** still run the old code path. Pre-flight query 1 confirms there are no legacy REQUESTED rows without a stripe id, and the reconciler ignores legacy rows (`stripe_attempt_at IS NULL`).
- **Rollback of this deploy.** V179 is additive, and the old code ignores the new columns. REQUESTED rows left unresolved at a rollback would keep their claims with no reconciler, so before rolling back, run query 1 and resolve them by hand in the Stripe dashboard.

## Definition of done
- All steps implemented. `./mvnw test` is green with Docker up and no skipped Testcontainers tests, and was re-run after the rebase.
- Each red proof in Test impact was performed once and recorded in the review notes.
- The Flyway version was re-checked against `origin/master` before review and again in `/ship-imin`.
- `CLAUDE.md` and the javadocs are updated in the same diff. No comment names a ticket or milestone.
- Prod pre-flight queries were run and their counts recorded. After deploy, the stuck-claim count is 0 and a reconciler tick is visible in the logs.
- The webapp follow-up card (Contract impact items 1–5) is created on the Notion board from the main session.

## Live-test evidence
_(filled by /live-test)_

## Review rounds

### Round 1 — implementation red proofs
Each guard was removed once in a scratch copy of the worktree (never the worktree itself) and the named test run.

| Test | Guard removed | Result |
|---|---|---|
| T1 | open + recordOutcome in one transaction (createRefund `@Transactional`, store REQUIRED) | red: 500 instead of 409 `REFUND_IN_PROGRESS` (row lost) |
| T2 | claims released on UNCERTAIN | red: K2 POST got `REFUND_IN_PROGRESS`, not `TICKET_ALREADY_REFUNDED` |
| T3 | no claim delete in recordOutcome FAILED | red: claims not empty |
| T4 | `IdempotencyException` classified DEFINITIVE | red: 422 instead of 409 |
| T5a | no client-key rename in recordRefusal | red: key stayed `k1-…` |
| T5b | no claim delete in recordRefusal | red: claims not empty |
| T6 | open in the outer transaction | red: 500, request B blocked past the 5 s wait |
| T7 webhook-first | no metadata adoption | red: 409 (materialized duplicate holds the same unique Stripe id) |
| T7 webhook-first | recordOutcome without the REQUESTED condition | red: sold 8, expected 9 (finalize-first stays green: the webhook's equal-status return guards that order) |
| T8 | no approve pre-lookup | red: 409 instead of 200 |
| T9 | adopt without the Stripe-id check | red: no row for the second refund |
| R1 page 2 | first page only | red: row still REQUESTED |
| R2 | amount recomputed | red: cap-lowered sends 1000 instead of 1500 |
| R2 platform | `platform_funded` ignored | red: key without `:platform` |
| R3 | DEFINITIVE treated as UNCERTAIN | red: row still REQUESTED |
| R4 | no dispute check | red: row still REQUESTED |
| R5 | no mode filter / no cutoff / claim result ignored | red: `list` called for the excluded row (each case) |
| R6 | UNCERTAIN mapped to refusal | red: row FAILED (both cases) |
| U1 | 409 mapped to DEFINITIVE | red: that row |
| H1 | every violation mapped to 409 | red: `TICKET_ALREADY_REFUNDED` |
| W1 | metadata passed as null | red: verify mismatch |
| M1 | status predicate dropped | red: PENDING and SUCCEEDED claims deleted |
| P1 | REQUESTED claims filtered out | red: REQUESTED case lists the ticket |

### Round 2 — review fixes (approve stays PENDING, adoption only onto REQUESTED, claim cutoff, wrapped platform switch, key freed on a failed refund object)

| Test | Guard removed | Result |
|---|---|---|
| `approveWhileRefundInProgress_…` (both) | pre-lookup approves a REQUESTED refund | red: 200 instead of 409 |
| `RefundRequestServiceTest.refundInProgress_leavesTheRequestPending` | approve catches `REFUND_IN_PROGRESS` and approves | red: nothing thrown |
| `RefundRequestServiceTest.earlierFailedRefund_fallsThroughToANewAttempt` | pre-lookup links any status | red: linked the FAILED refund |
| `webhookMetadataThatCannotBeAdopted_…` FAILED/CANCELED | both REQUESTED guards (Java check and the `adoptStripeRefund` condition) | red: Stripe id written onto the refused row; each guard alone is redundant with the other |
| `webhookMetadataThatCannotBeAdopted_…` OTHER_PAYMENT_INTENT | PaymentIntent check | red: Stripe id written onto the row |
| `webhookMetadataThatCannotBeAdopted_…` UNPARSABLE_ID | guarded UUID parse | red: `Invalid UUID string` |
| `rowJustBumpedByAnotherClaimer_isNotClaimedAgain` | cutoff in `claimForReconcile` | red: UNRESOLVED instead of SKIPPED |
| `platformSwitchNotCommitted_…` writeFails | try/catch around `switchToPlatform` | red: 500 instead of 409 |
| `platformSwitchNotCommitted_…` lost race | `switchToPlatform` result ignored | red: platform refund sent (`ORDER_NOT_REFUNDABLE`) |
| `refusalThatCannotBeRecorded_…` | try/catch in `refuse` | red: 500 instead of 422 |
| T3 (same key reused) | `freeClientKey` in recordOutcome | red: key stayed `k1-…` |
| `webhookFailedRefund_freesTheKey_…` (retry and re-approve) | `freeClientKey` in the webhook FAILED/CANCELED branch | red: key stayed `k1-…` / `refund-request-…` |
