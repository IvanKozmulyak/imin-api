# Stripe tier sync never holds the event lock (api)
stripe-sync-outside-lock · Subagent · Notion: (card id not supplied)

## Goal and scope
Goal: no Stripe HTTP call runs while a transaction holds the `events` row lock, so a slow or hung Stripe cannot stall organizer autosaves, other writes on the event, or the LIVE→PAST sweep (`EventRepository.markLivePast`).

Facts established (file:line, worktree on origin/master 8b193182):
- **What `syncTier` does** (`StripeProductService.java:58-107`). When either id is missing or the price changed, it calls `products().create` with `default_price_data`, which creates a Product plus a Price in one call, and stores both ids (89-91). Otherwise it calls `products().update` with the name, the description "Ticket for <event name>" and metadata (98-105). To decide whether the price changed it calls `prices().retrieve` (113-126); a failed retrieve counts as changed. It never archives old prices or products; a price change orphans the old Product, as the class doc says (24-27).
- **Write-back**: `tiers.save(tier)` is a full-entity save (91). `TicketTier` has no `@DynamicUpdate`, no `@Version` and no `updatable=false` (`TicketTier.java:10-60`).
- **Idempotency**: none. There is no `RequestOptions` on create or update.
- **Failure handling**: `StripeException` and `RuntimeException` are logged and swallowed (46-56). The tier save never rolls back because of Stripe.
- **Where it runs under the event lock**:
- `TicketTierService.create` and `patch` (`TicketTierService.java:134,152,164,174`) lock through `loadOwnedEvent` → `events.lockActiveForWrite` (254).
- `reconcileEmbedded` (229, 240) runs inside `EventService.patch`, which locks through `loadOwnedForWrite` (`EventService.java:251,273,408-409`).
- Every tier in an autosave body gets 1–2 Stripe calls under that lock.
- The pinned stripe-java 32.1.0 defaults are a 30 s connect timeout, an 80 s read timeout (`com/stripe/Stripe.java:9-10`) and `maxNetworkRetries = 2` (`Stripe.java:29`). `StripeConfig` builds `new StripeClient(key)` with no overrides (`StripeConfig.java:50`).
- **Checkout's use of the ids**:
- The ticket line is priced inline from `tier.priceMinor` (`StripeCheckoutService.java:368-374`).
- The stored price id is only a readiness gate: a null id gives a 404 (632-637). The native PaymentIntent flow shares this through `reserveAndBuildMetadata`.
- The product id is used for coupon `applies_to.products` scoping and as the line's `product`. When a promo needs a product id that is missing, checkout syncs inline (817-831).
- Checkout is deliberately not `@Transactional` (204-212), and open-in-view is off (`application.yaml:11`). So the inline sync's `tiers.save` merges a detached snapshot taken in `priceIt` before `reserve`, which overwrites the `reserved` value that `reserve` just committed.
- **The DTO**: `TicketTierDto` carries no Stripe fields (grep of `dto/event/TicketTierDto.java` finds nothing), so the API contract does not change.
- **Tests pinning today's behaviour**: none. No test covers `StripeProductService`. `TicketTierServiceTest` uses the constructor without Stripe (45) or passes `null` for it (160). Spring tests replace it with `@MockitoBean` and assert nothing about it (CrossOrgScopingTest:86, WithinOrgRoleGateTest:65, DashboardCacheTest:64, EventStatusRevertPostgresTest:93). `StripeCheckoutServiceTest` stubs `syncTier` with `doAnswer` and verifies the call (480, 489-491).

Decision: option (a), with two refinements: (1) syncs for the same tier are merged and run one at a time on a single thread; (2) `syncTier` keeps its public signature for checkout, but its write becomes a targeted conditional UPDATE.

Out of scope:
- a reconciler or sweeper (OPEN_QUESTION 1);
- the organizer-path lost update of `sold`/`reserved` (OPEN_QUESTION 2);
- `requireStripeIfPaid` under the lock (OPEN_QUESTION 3);
- the stale "events-4" comment at `EventService.java:495-498`. It says checkout mixes the stored Price into the Session, which has not been true since the line became inline. This change does not make it any more wrong, so it is left alone.

## Repos in ship order
1. `api` (imin-api, base `master`). This is the only repo.

## Affected files (per repo)
imin-api (worktree `/Users/ivan/imin/imin-api/.claude/worktrees/stripe-sync-outside-lock`):

| # | File | Change |
|---|---|---|
| 1 | `src/main/java/com/imin/iminapi/model/TicketTier.java` | Add `updatable = false` to `stripe_product_id` and `stripe_price_id`. Rewrite both field javadocs: written only by `TicketTierRepository.updateStripeIdsIfPriceUnchanged`, after commit. |
| 2 | `src/main/java/com/imin/iminapi/repository/TicketTierRepository.java` | Add `@Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional int updateStripeIdsIfPriceUnchanged(id, productId, priceId, priceMinor, currency)`. JPQL: `UPDATE TicketTier t SET t.stripeProductId=:productId, t.stripePriceId=:priceId WHERE t.id=:id AND t.priceMinor=:priceMinor AND EXISTS (SELECT 1 FROM Event e WHERE e.id=t.eventId AND LOWER(e.currency)=:currency)`. `events.currency` is `NOT NULL` (`V6__events.sql:25`), and `:currency` is never null, so the H2/Postgres null-bytea trap cannot occur. |
| 3 | `src/main/java/com/imin/iminapi/stripe/StripeProductService.java` | `syncTier` returns `SyncOutcome {SYNCED, UPDATED, STALE, FAILED}` (nested enum). Create passes `RequestOptions.builder().setIdempotencyKey(key)`; signature `ProductService.create(ProductCreateParams, RequestOptions)`, stripe-java 32.1.0 `ProductService.java:166`; builder `RequestOptions.java:291`. Key = `"tier-product-" + tierId + "-" + sha256hex(name, description, currency, unitAmount, eventId, orgId)`, each joined by `\u001f`. The Stripe write-back becomes the call from #2: on 1 row, set both fields on the passed instance and return SYNCED; on 0 rows leave the instance alone, log at INFO and return STALE. `tiers.save` is removed. Rewrite the class javadoc and the "Callers should invoke this AFTER…" line. |
| 4 | `src/main/java/com/imin/iminapi/stripe/TierStripeSyncRequested.java` (new) | `record TierStripeSyncRequested(UUID tierId)`. |
| 5 | `src/main/java/com/imin/iminapi/stripe/TierStripeSyncListener.java` (new) | `@TransactionalEventListener(phase = AFTER_COMMIT)` with no `@Transactional`. Spring 7.0.6 `RestrictedTransactionalEventListenerFactory` allows only REQUIRES_NEW or NOT_SUPPORTED there; this listener needs neither, since it does no DB work on the commit thread. `request(tierId, attempt)` adds the tier to a `ConcurrentHashMap.newKeySet()` of pending ids. If the id was new, it calls `executor.execute(task)`. On `RejectedExecutionException` it removes the id and logs WARN. The task removes its tier id first, then loads `tiers.findById` and `events.findActive(tier.eventId)` and skips when either is missing. It calls `syncTier`; on `STALE` with attempt 0 it calls `request(tierId, 1)`. It catches every `RuntimeException` and logs WARN with the throwable. Constructor: `(TicketTierRepository, EventRepository, StripeProductService, @Qualifier("tierStripeSyncExecutor") Executor)`. |
| 6 | `src/main/java/com/imin/iminapi/config/AsyncConfig.java` | New bean `tierStripeSyncExecutor`: core 1, max 1, queue 500, prefix `tier-stripe-sync-`, default AbortPolicy (the listener needs the exception to clear its pending id), `setWaitForTasksToCompleteOnShutdown(true)`, `setAwaitTerminationSeconds(20)`. Javadoc: one thread so the syncs for a tier run in commit order. |
| 7 | `src/main/java/com/imin/iminapi/service/event/TicketTierService.java` | Replace `syncStripeProduct(tier, event)` (152, 174, 229, 240) with `publishSyncRequested(tier.getId())`, which uses the existing nullable `eventPublisher` and publishes for every saved tier, as today. Delete `syncStripeProduct` (245-248), the `stripeProductService` field (37-41), its import, and the 5-arg and 6-arg constructors (unused: grep finds only 4-arg and 7-arg constructions). The primary constructor becomes `(tiers, events, validator, clock, auditLogger, eventPublisher)`. |
| 8 | `CLAUDE.md` (imin-api) | Stripe "State model" paragraph (line 201): one sentence saying the tier ids are written only by `updateStripeIdsIfPriceUnchanged`, after commit, on the single-thread `tierStripeSyncExecutor`, and never under the event lock. |
| 9 | `src/test/java/com/imin/iminapi/stripe/StripeProductServiceTest.java` (new) | Unit tests, mocked StripeClient and repository (tests 1–7). |
| 10 | `src/test/java/com/imin/iminapi/stripe/StripeProductServicePersistenceTest.java` (new) | `@DataJpaTest` with the real repository and a mocked StripeClient (test 8). |
| 11 | `src/test/java/com/imin/iminapi/service/event/TicketTierRepositoryPersistenceTest.java` | Add tests 9–13. |
| 12 | `src/test/java/com/imin/iminapi/stripe/TierStripeSyncListenerTest.java` (new) | Unit tests with a manual capturing executor (tests 14–21). |
| 13 | `src/test/java/com/imin/iminapi/service/event/TicketTierServiceTest.java` | Line 160 constructor becomes `new TicketTierService(tiers, events, validator, clock, null, publisher)`. `standaloneTierPatch_publishesPredictorReactivityEvent` currently uses `verify(publisher).publishEvent(cap.capture())`, which means exactly 1 call; it now gets 2. Change it to `times(2)` and assert exactly one `EventMutated(eventId)` and one `TierStripeSyncRequested(tierId)` (test 22). Add tests 23–24. |
| 14 | `src/test/java/com/imin/iminapi/stripe/TierStripeSyncAfterCommitTest.java` (new) | H2 `@SpringBootTest` with `@MockitoBean StripeClient` (tests 25–26). |
| 15 | `src/test/java/com/imin/iminapi/service/event/EventStatusRevertPostgresTest.java` | Add tests 27–28. `stripeProductService` is already a `@MockitoBean` (93). |

Files that read the changed code but need no edit:
- `StripeCheckoutService.java`: still calls `syncTier(tier, event)` and ignores the return value. After a landed sync it still reads the product id from the instance (824).
- `StripeCheckoutServiceTest.java`: stubs with `doAnswer(...)` (489-491), which works for a non-void method, and `verify` (480). Grep finds no `doNothing()` on `productService`.
- `FreeCheckoutIdempotencyTest.java:121`: uses `mock(StripeProductService.class)`, unchanged. Line 375 sets a price id on a mocked repository's entity, which `updatable` does not affect.
- `StripeLiveCutoverScriptTest.java:143-144`: sets the ids before the first `save`, so they go in through the INSERT, which `updatable = false` still allows. The cutover SQL is raw.
- `CrossOrgScopingTest`, `WithinOrgRoleGateTest`, `DashboardCacheTest`: `@MockitoBean StripeProductService`; the listener now calls the mock on the executor thread, and nothing asserts on it.
- `EventService.java`: calls `reconcileEmbedded`, whose signature is unchanged. The `existsSyncedStripePrice` guard at 501 is unchanged; see Blast radius.
- `StripePaymentIntentService.java`: only reads the price id gate through `reserveAndBuildMetadata`.

## Ordered steps
1. Before any change, run `docker info`, then the full gate on the untouched worktree. Record the `Tests run:` totals and confirm 0 skipped Testcontainers tests. If the base is red, stop and report it.
2. `TicketTier.java`: add `updatable = false` to both stripe columns and rewrite their javadocs (1–2 lines each).
3. `TicketTierRepository.java`: add `updateStripeIdsIfPriceUnchanged` (file 2). Javadoc: "Only writer of the tier's Stripe ids; lands only while the price and currency still match what Stripe was sent."
4. `StripeProductService.java`:
 - Add the `SyncOutcome` enum.
 - `doSync` returns an outcome. The create path builds `ProductCreateParams` exactly as today, plus the idempotency-key `RequestOptions`, then calls `updateStripeIdsIfPriceUnchanged(tier.getId(), product.getId(), product.getDefaultPrice(), tier.getPriceMinor(), currency)`. On 1 row, set the two fields on the instance and return SYNCED; on 0 rows return STALE.
 - The update path is unchanged and returns UPDATED.
 - Both catch blocks return FAILED.
 - Remove `tiers.save`. Rewrite the class javadoc so it says the ids are written by a targeted update and that callers must not hold the event lock.
5. `TierStripeSyncRequested.java` and `TierStripeSyncListener.java`, as in files 4–5. Comments: "Runs after commit on one thread, so no event lock is held during the Stripe call." and "Pending ids merge queued repeats; the task removes its id first, so a commit during a run queues another."
6. `AsyncConfig.java`: add the `tierStripeSyncExecutor` bean.
7. `TicketTierService.java`: swap the four sync calls for `publishSyncRequested`, remove the Stripe field, the import and the two constructors, and update the class and field javadocs. The paths covered are `create`, `patch`, the create and update branches of `reconcileEmbedded`, and through it `EventService.patch`. Checkout's inline sync stays on the request thread: it holds no transaction and no event lock (204-212). `delete` does not sync. Grep confirms these are all the entry paths: `syncTier` is called only at `TicketTierService.java:247` and `StripeCheckoutService.java:823`.
8. Tests 1–28 (see Test impact). After each guard test is green, remove its guard once and confirm the test goes red (see the guard table).
9. `CLAUDE.md`: add the state-model sentence.
10. Run the full gate (Verification commands). Read each targeted run's own `Tests run:` line and confirm 0 skipped.

## Verification commands
From `/Users/ivan/imin/imin-api/.claude/worktrees/stripe-sync-outside-lock`:
- `docker info` must succeed first. Otherwise the Postgres tests are skipped, and a skipped run counts as red.
- Targeted runs (comma-separated): `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=StripeProductServiceTest,StripeProductServicePersistenceTest,TicketTierRepositoryPersistenceTest,TierStripeSyncListenerTest,TicketTierServiceTest,TierStripeSyncAfterCommitTest,EventStatusRevertPostgresTest,StripeCheckoutServiceTest,FreeCheckoutIdempotencyTest`
- Full gate: `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test`
- Re-run the full gate after any rebase.

## Test impact
Branches of the changed logic, numbered B1–B19:

| B | Branch |
|---|---|
| B1 | Ids missing → create → row lands → instance fields set, outcome SYNCED |
| B2 | Create → 0 rows (price or currency moved, tier gone) → instance untouched, outcome STALE |
| B3 | Price changed → create again |
| B4 | Nothing changed → product update only, no DB write |
| B5 | StripeException → swallowed, FAILED, no DB write |
| B6 | RuntimeException → swallowed, FAILED |
| B7 | Idempotency key is stable for identical inputs and differs when an input changes |
| B8 | Listener: tier gone → no Stripe call |
| B9 | Listener: event gone or soft-deleted → no Stripe call |
| B10 | Listener: request → task loads fresh tier and event → `syncTier` |
| B11 | A repeat while queued is merged |
| B12 | A repeat while running queues again |
| B13 | Executor rejects → pending id cleared, no throw |
| B14 | STALE on attempt 0 retries once; STALE on attempt 1 stops |
| B15 | Task throws → pending id cleared |
| B16 | TicketTierService create, patch and reconcile (create and update) publish one request per tier and make no inline sync |
| B17 | Publisher null → no publish, no NPE (already covered by the existing 4-arg tests such as `create_persists_tier_and_returns_dto`) |
| B18 | UPDATE condition: match / price moved / currency moved / both orderings / stale full save afterwards / full save before |
| B19 | Rollback → no sync; commit → the listener thread's write commits; event lock not held during the Stripe call |

Tests (28: 27 new, 1 edited):

| # | File | Test | Covers |
|---|---|---|---|
| 1 | StripeProductServiceTest | `missingIds_createsProduct_writesIdsThroughTargetedUpdate_setsInstance` (verify `tiers.save` never called) | B1 |
| 2 | StripeProductServiceTest | `rowMovedAfterCreate_leavesInstanceUntouched_returnsStale` | B2 |
| 3 | StripeProductServiceTest | `changedPriceAmount_createsAgainWithNewAmount` | B3 |
| 4 | StripeProductServiceTest | `unchangedPrice_updatesProductOnly_noDbWrite` | B4 |
| 5 | StripeProductServiceTest | `stripeException_isSwallowed_returnsFailed_noDbWrite` | B5 |
| 6 | StripeProductServiceTest | `runtimeException_isSwallowed_returnsFailed` | B6 |
| 7 | StripeProductServiceTest | `createKey_equalForSameInputs_differsWhenAmountOrNameChanges` (captures `RequestOptions.getIdempotencyKey()`) | B7 |
| 8 | StripeProductServicePersistenceTest | `syncOfStaleSnapshot_keepsReservedAndSoldCommittedMeanwhile` (insert tier reserved=0, detach, set reserved=3 and sold=2 via JDBC, `syncTier(snapshot)`, assert 3/2 and ids written) | B1, checkout regression |
| 9 | TicketTierRepositoryPersistenceTest | `updateStripeIds_landsWhenPriceAndCurrencyMatch` | B18 |
| 10 | TicketTierRepositoryPersistenceTest | `updateStripeIds_noOpWhenEventCurrencyMoved` | B18 |
| 11 | TicketTierRepositoryPersistenceTest | `oldPriceSyncThenNewPriceSync_newIdsKept` (old-price write: 0 rows; new-price write: 1) | B18, ordering 1 |
| 12 | TicketTierRepositoryPersistenceTest | `newPriceSyncThenOldPriceSync_newIdsKept` | B18, ordering 2 |
| 13 | TicketTierRepositoryPersistenceTest | `staleFullSaveAfterTargetedUpdate_keepsIds_andTargetedUpdateAfterFullSaveLands` (both writer orderings; read back with native SQL) | B18 |
| 14 | TierStripeSyncListenerTest | `request_runsSyncOnFreshlyLoadedTierAndEvent` | B10 |
| 15 | TierStripeSyncListenerTest | `repeatWhileQueued_isMergedIntoOneRun` | B11 |
| 16 | TierStripeSyncListenerTest | `repeatDuringRun_queuesAnotherRun` | B12 |
| 17 | TierStripeSyncListenerTest | `rejectedByExecutor_clearsPending_nextRequestQueues` | B13 |
| 18 | TierStripeSyncListenerTest | `staleOutcome_retriesOnce_thenStops` | B14 |
| 19 | TierStripeSyncListenerTest | `tierGone_noStripeCall` | B8 |
| 20 | TierStripeSyncListenerTest | `eventGone_noStripeCall` | B9 |
| 21 | TierStripeSyncListenerTest | `syncThrows_pendingCleared_noThrowOutOfTask` | B15 |
| 22 | TicketTierServiceTest (edited) | `standaloneTierPatch_publishesPredictorReactivityEvent`, now `times(2)`: one EventMutated and one TierStripeSyncRequested | B16 (patch) |
| 23 | TicketTierServiceTest | `create_publishesSyncRequestForNewTier` | B16 (create) |
| 24 | TicketTierServiceTest | `reconcileEmbedded_publishesOneRequestPerCreatedAndUpdatedTier` | B16 (reconcile) |
| 25 | TierStripeSyncAfterCommitTest | `committedTierCreate_listenerThreadWritesIds` (poll JDBC up to 5 s; mocked `products().create`) | B19 |
| 26 | TierStripeSyncAfterCommitTest | `rolledBackEventPatch_neverCallsStripe` (second tier invalid → 400; `verify(productService, after(500).never()).create(...)`) | B19 |
| 27 | EventStatusRevertPostgresTest | `sweepCommitsWhileEmbeddedTierSyncIsBlocked` (patch returns within 5 s while `syncTier` is blocked on a latch; `markLivePast` returns 1 within 5 s; release in `finally`) | B19 |
| 28 | EventStatusRevertPostgresTest | `sweepCommitsWhileStandaloneTierCreateSyncIsBlocked` | B19 |

Count check: 7 + 1 + 5 + 8 + 3 + 2 + 2 = 28.

Guard proofs. Remove each guard once and expect the named test to go red:

| Guard | Remove | Test that goes red |
|---|---|---|
| `updatable = false` | the attribute | 13 |
| `priceMinor` condition | the clause | 11 |
| currency condition | the clause | 10 |
| targeted UPDATE instead of `save` | restore `tiers.save(tier)` | 8 (and 1) |
| AFTER_COMMIT + executor | call `syncTier` inline in TicketTierService | 27, 28 |
| `@TransactionalEventListener` | change to `@EventListener` | 26 |
| pending-id merge | drop the set | 15 |
| retry cap | drop the `attempt == 0` check | 18 |
| idempotency key | drop `RequestOptions` | 7 |

Tests 27–28 require Docker. If a run reports them skipped, the run is red.

## Live-test
`/live-test api` after deploy. Prod Stripe is live, and creating a Product moves no money.
1. On a test org's draft event, add a paid tier through the dashboard.
2. Within about 5 s, `railway psql` should show `SELECT stripe_product_id, stripe_price_id FROM ticket_tiers WHERE id = '<id>'` with both non-null.
3. Rename the tier. The Stripe dashboard product name follows, and no second product appears for that `tierId` in its metadata.
4. Change the price. A new product is created and the ids change.
5. Autosave PATCH latency no longer depends on how many tiers the body carries.
6. Logs show no `tier-stripe-sync` WARN.
7. Clean up: delete the tier and draft.

## Contract impact
none. `TicketTierDto` exposes no Stripe fields, and no endpoint, schema or `/api/v1/public` contract changes. No OpenAPI marker or webapp `types.ts` edit is needed.

## i18n impact
none. Server-side only, no user-facing strings. Copy ledger: no rows, because the plan adds or reuses no strings.

## Blast radius
Money and Stripe, a shared module (`StripeProductService` is used by organizer tier writes and buyer checkout).
- **Hosted and native checkout gate** (`StripeCheckoutService.java:632`): a brand-new paid tier returns 404 at checkout from its commit until its after-commit sync lands. That is normally under a second; during a Stripe stall it is as long as the stall. Today the organizer's save itself hangs that long, and a failure leaves the same state, so this is no worse. Existing tiers keep their ids through renames and price changes, so they stay sellable without a gap.
- **Charged amount**: no change. The ticket line uses inline `price_data` from `priceMinor` (368-374). The worked-example money rule does not apply because no money formula changes.
- **Promo inline sync** (817-831): behaviour unchanged, and the write can no longer revert `reserved`/`sold`. If the targeted UPDATE misses, the checkout gets the existing 503 `UPSTREAM_UNAVAILABLE`, after `releaseQuietly`.
- **Currency guard** (`EventService.java:501`, `existsSyncedStripePrice`): the window before a tier is first synced is now slightly wider. A sync whose Stripe call overlaps a currency change misses the UPDATE condition and retries once with the fresh currency.
- **Publish** (`requireStripeIfPaid`, `EventService.java:384-395`): it never read tier ids, so it is unaffected.
- **Fulfilment, webhooks, refunds, payouts**: none read `stripe_product_id`/`stripe_price_id` (grep: the readers are `StripeCheckoutService`, `StripeProductService` and `TicketTierRepository.existsSyncedStripePrice` only).
- **Live cutover script**: raw SQL that clears the ids, unaffected.
- **Every writer of the `ticket_tiers` row**:
- `TicketTierService` create, patch and reconcile: full saves, which after step 2 can no longer write the ids.
- `InventoryService` reserve, release and confirm (`InventoryService.java:81-91,137-156,223-266`) and `RefundService` (`RefundService.java:447-456`): full saves under `findByIdForUpdate`, which after step 2 can no longer write the ids either.
- Tier delete: a sync for a deleted tier skips or matches 0 rows.
- The new targeted UPDATE is the only writer of the two id columns. Both writer orderings are tested (13).
- **Entry paths that sync a tier** (all of them): `TicketTierService.create`, `patch`, `reconcileEmbedded` (create and update branches, reached through `EventService.patch`), and `StripeCheckoutService.resolveTicketProductId`. All but checkout move to after commit. Checkout already runs with no transaction and no event lock. No scheduler, reconciler or admin endpoint calls `syncTier`.
- **Connection pool**: the listener does no DB work on the commit thread, which avoids the REQUIRES_NEW-inside-AFTER_COMMIT pattern that holds two Hikari connections per request. The executor thread uses one short connection per read and per write.

## Risks
- **Lost queue**: a deploy, crash or full queue (500) drops pending syncs. A new tier then stays unsellable until its next save. That is the same as a Stripe failure today, and every save requests a sync again. A sweeper would close it (OPEN_QUESTION 1).
- **Single thread**: a Stripe stall delays all syncs on that replica, but it no longer blocks any organizer write or the sweep. The idempotency key and conditional UPDATE keep replicas safe.
- **Name order across replicas**: two replicas syncing renames of the same tier can finish out of order and leave Stripe showing the older name until the next save. That is cosmetic on the hosted Checkout line. Amounts are unaffected.
- **Idempotency key reuse**: a key is reused only within Stripe's 24 h window with identical parameters, and then returns the original Product, whose default Price matches. Different parameters give a different hash, so no `idempotency_error`.
- **Orphaned Products** from a missed UPDATE are harmless, like today's orphans from price changes. No archiving is added.
- **Existing `sold`/`reserved` lost update** on organizer tier saves (OPEN_QUESTION 2): this change makes it less likely but does not fix it.
- **Size**: 15 files (8 main/docs, 7 tests), at the threshold. One concern, so no split proposed. If a split is wanted: (A) steps 2–4 plus tests 1–13 (the targeted write and `updatable = false`, which fixes the checkout revert); (B) the listener, executor and TicketTierService plus tests 14–28.

## Definition of done
- Part A (Decisions): the clearAutomatically red proof and the idempotency-key guard are not part of the DoD.
- The gate was green on the untouched base. After the change: `./mvnw test` green through `test-serial.sh`, 0 skipped Testcontainers tests, re-run after any rebase.
- All 28 tests pass, and each guard in the guard table was removed once and its test went red.
- grep confirms: no `stripeProductService` in `TicketTierService`, no `tiers.save` in `StripeProductService`, `syncTier` called only from `TierStripeSyncListener` and `StripeCheckoutService`.
- Javadocs (TicketTier fields, StripeProductService class, TicketTierService) and the `CLAUDE.md` state-model sentence updated in the same change. Comments are 1–2 lines with no ticket ids.
- Live-test evidence recorded below.

## Decisions (main session)
- **SPLIT. This task ships part A only**: Ordered steps 1–4, plus the javadoc parts of steps 8–10 that apply to A. Files 1, 2, 3, 9, 10, 11 (and file 8 CLAUDE.md, limited to "ids are written only by updateStripeIdsIfPriceUnchanged; full-entity saves cannot write them"). Tests 1–6 and 8–13 (test 7 dropped with the key, see below), plus a Postgres 17 test of the UPDATE. Guards: `updatable = false` → 13, priceMinor clause → 11, currency clause → 10, targeted UPDATE instead of save → 8 (and 1).
  - `syncTier` stays synchronous and is still called from TicketTierService exactly where it is today. Its return value is ignored there. TicketTierService is not edited. There is no listener, executor or event, and TicketTierServiceTest is not changed.
  - The SyncOutcome enum stays, because checkout's promo path needs to know whether a write was STALE. On STALE, checkout keeps its existing behaviour (the product id is missing → existing 503 path).
- Part B (files 4–7, 12–15, tests 14–28) is NOT in this task. It is re-planned together with a sweeper for null stripe_price_id (OPEN_QUESTION 1). Reason: async-only sync turns a deploy or crash between commit and sync into a silently unsellable tier, which a synchronous sync does not do today.
- **Part A runs the targeted UPDATE inside the organizer's transaction** (create, patch, and reconcileEmbedded within EventService.patch). So `clearAutomatically = true` would detach the Event and tiers that EventService.patch is still modifying after reconcileEmbedded (EventService.java:262-273), and those later changes would be silently lost.
  - Use `@Modifying(flushAutomatically = true)` WITHOUT clearAutomatically. After 1 row, set the ids on the passed instance; this is safe because the columns are `updatable = false`, so a later flush never writes them.
  - Add a test that runs EventService.patch with an embedded new paid tier plus an event field change, and asserts both the field change and the tier ids are persisted. No red proof is required for clearAutomatically: on today's callers it cannot go red (see Review rounds), so leaving it off is defensive only.
- **No explicit idempotency key in part A** (review round 1). Stripe stores the first result for a key, a 500 included, for 24 h, so a stable key would block every retry of a failed create for a day. Part A is synchronous, so the organizer's next save retries with a fresh request; stripe-java's own per-request retry key still applies. Part B (async, retried) must re-decide this, e.g. a time-bucketed or per-attempt salted key.
- OPEN_QUESTION 2 (TicketTier sold/reserved lost update on organizer saves): queued as its own high-priority card.
- OPEN_QUESTION 3 (publish calls Stripe getStatus under the event lock): queued.

## Live-test evidence

## Review rounds

### Implement round 1 (part A only)
- Baseline (untouched origin/master 8b193182, Docker up): `Tests run: 6999, Failures: 0, Errors: 0, Skipped: 3`. Skips: `AudiencePlanInvitationWebTest` x2 (H2 assumption, Postgres-only scenarios; the Postgres twin ran 30/0 skipped), `SimulatorEvalTest` x1 (`@Disabled`). No Testcontainers skips.
- Guard proofs (mutate once, run, restore from a saved copy and `cmp`):

| Guard | Mutation | Test | Result |
|---|---|---|---|
| `updatable = false` (product id) | attribute removed | 13 `staleFullSaveAfterTargetedUpdate_…` | red |
| `updatable = false` (price id) | attribute removed | 13 | red |
| priceMinor clause | `t.priceMinor = :priceMinor` → `:priceMinor = :priceMinor` | 11 `oldPriceSyncThenNewPriceSync_newIdsKept` | red (expected 0, was 1) |
| currency clause | `LOWER(e.currency) = :currency` → `LOWER(:currency) = :currency` | 10 `updateStripeIds_noOpWhenEventCurrencyMoved` | red (expected 0, was 1) |
| targeted UPDATE instead of save | set ids + `tiers.save(tier)` | 8 `syncOfStaleSnapshot_…` / 1 `missingIds_…` | both red (reserved expected 3, was 0; update never invoked) |
| idempotency key | `create(params, RequestOptions.builder().build())` | 7 `createKey_…` | red (key null) |
| key hashes the amount | amount left out of the hash | 7 | red (keys equal) |
| no `clearAutomatically` | `clearAutomatically = true` added | `EventPatchStripeSyncPersistenceTest` | **stays green** |

- The clearAutomatically proof does not go red, and cannot on today's callers: `EventService.patch` flushes the event (`events.flush()`, EventService.java:264) before `reconcileEmbedded` (271-273), the bulk update flushes pending tier edits before any clear, and no caller mutates a managed entity after `syncTier` returns (TicketTierService.java:149-158, 171-179, 219-241; each embedded update re-loads its tier). Leaving it off is defensive only. Removing `flushAutomatically` is also green: Hibernate's AUTO flush already flushes `ticket_tiers` before the bulk UPDATE.
- Targeted run (`-Dtest=StripeProductServiceTest,StripeProductServicePersistenceTest,TicketTierRepositoryPersistenceTest,EventPatchStripeSyncPersistenceTest,TicketTierServiceTest,EventStatusRevertPostgresTest,StripeCheckoutServiceTest,FreeCheckoutIdempotencyTest`): `Tests run: 111, Failures: 0, Errors: 0, Skipped: 0`.
- Full gate after the change (Docker up): `Tests run: 7013, Failures: 0, Errors: 0, Skipped: 3`, BUILD SUCCESS. The 3 skips are the same as on the baseline. 7013 = 6999 + 14 new tests (7 + 1 + 5 + 1).

### Review-fix round 1 (part A)
- HIGH fixed: the explicit idempotency key and `RequestOptions` were removed from the create call; test 7 and the key helper were deleted. Reason: Stripe caches the first result per key (500s included) for 24 h, and part A is synchronous, so the next save is the retry. Part B must re-decide the key (see Decisions).
- LOW fixed: `toLowerCase(Locale.ROOT)` for the currency; javadocs and CLAUDE.md now say no full-entity update can write the ids (an INSERT still can); the price-id javadoc no longer ties it to the current `priceMinor`.
- LOW accepted: no `clearAutomatically` is defensive only; it cannot go red on today's callers, and its red proof is dropped from the DoD.
- Added `TicketTierStripeIdsPostgresTest` (Postgres 17, `disabledWithoutDocker`): lands with stored `EUR` vs sent `eur`; 0 rows when the price moved. Red proofs: `LOWER(e.currency)` → `e.currency` turns the first test red (expected 1, was 0); price clause → `:priceMinor = :priceMinor` turns the second test red (expected 0, was 1).
- Full gate after review-fix round 1 (Docker up): `Tests run: 7014, Failures: 0, Errors: 0, Skipped: 3`, BUILD SUCCESS; same 3 non-Testcontainers skips as the baseline. 7014 = 7013 − 1 (test 7) + 2 (Postgres test).
