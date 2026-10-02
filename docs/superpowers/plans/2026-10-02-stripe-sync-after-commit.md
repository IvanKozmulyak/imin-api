# Stripe tier sync runs after commit, with a recovery sweep (api)
stripe-sync-after-commit · Subagent · Notion: (card id not supplied)

## Goal and scope
Goal: no Stripe HTTP call runs while an organizer transaction holds the `events` row lock or a `ticket_tiers` row lock. A paid tier whose sync was lost (deploy, crash, full queue, Stripe failure) must still become sellable without anyone re-saving it.

Facts on origin/master 23b5e8c7, read in the worktree:
- **The organizer paths call Stripe under both locks today.**
- `TicketTierService.patch` locks the event (`loadOwnedEvent` → `events.lockActiveForWrite`, `TicketTierService.java:267-273`) and then the tier (`lockForWrite`, 168, 222-225). It syncs at 177-178.
- `create` syncs at 154-155 under the event lock.
- `reconcileEmbedded` syncs at 244 (create) and 255 (update), inside `EventService.patch`, which holds the event lock (`EventService.java:251`) and the tier locks (259).
- `InventoryService.reserve` takes the same tier lock (`InventoryService.java:89`, `findByIdForUpdate`), so a buyer on that tier waits for Stripe.
- The helper is at `TicketTierService.java:260-263`.
- **`StripeProductService.syncTier`** (`StripeProductService.java:56-68`) swallows every exception and returns `SyncOutcome {SYNCED, UPDATED, STALE, FAILED}` (49).
- Create path (81-114): one `products().create` with `default_price_data`, then `tiers.updateStripeIdsIfPriceUnchanged` (102-103). 0 rows → STALE (104-108).
- Update path (117-126): name and metadata only.
- The comment at 99 notes there is no explicit idempotency key.
- **The id write** is `TicketTierRepository.java:96-109`: `@Modifying(flushAutomatically = true) @Transactional`, a JPQL conditional UPDATE. Both id columns are `updatable = false` (`TicketTier.java:49-61`).
- **Checkout**:
- It is not `@Transactional` (`StripeCheckoutService.java:204-212`).
- A null or blank `stripePriceId` gives a leak-safe 404 (`StripeCheckoutService.java:632-637`). The native PaymentIntent flow shares this through `reserveAndBuildMetadata`.
- The promo path syncs inline only when the price id is set but the product id is missing (`resolveTicketProductId`, 817-832, call at 823). Because the conditional UPDATE always writes both ids together, only legacy or hand-edited rows can be in that state.
- **Who calls `syncTier`**: only `TicketTierService.java:262` and `StripeCheckoutService.java:823` (grep). Tiers are created only at `TicketTierService.java:141,240` (grep `new TicketTier()`).
- **stripe-java 32.1.0**:
- Without an explicit key, every POST gets a random `Idempotency-Key`, reused across the client's own retries (`com/stripe/net/StripeRequest.java:334-339`).
- It retries on 409 and ≥500 (`com/stripe/net/HttpClient.java:298-309`).
- Defaults: 30 s connect, 80 s read (`com/stripe/Stripe.java:9-10`), `maxNetworkRetries = 2` (29).
- An explicit key would need `ProductService.create(ProductCreateParams, RequestOptions)` (`com/stripe/service/ProductService.java:166`).
- **spring-tx 7.0.6**: a `@TransactionalEventListener` may carry `@Transactional` only as REQUIRES_NEW or NOT_SUPPORTED (`RestrictedTransactionalEventListenerFactory.java:51-53`). The default phase is AFTER_COMMIT (`TransactionalEventListener.java:78`), and `fallbackExecution` defaults to false (110).
- **ShedLock 6.10.0** (`pom.xml:233-241`), JDBC provider on table `shedlock` with DB time (`SchedulingConfig.java:29-40`). House sweeper pattern: `StripeConnectStatusSweeper.java:64-65` and `ReservationSweeper.java:61-62`.
- **Precedent for a single-thread executor**: `AsyncConfig.venueGeocodingExecutor` (`AsyncConfig.java:76-89`).
- **Test config** (`src/test/resources/application.yaml:61-64`) runs with `sk_test_dummy` and says tests must mock every Stripe call. No test property disables `@Scheduled` today, so a new job with a fixed initial delay would tick inside long-lived test contexts.
- **Migrations** end at V170. No worktree under `imin-api/.claude/worktrees` has a V171 or later.

Decisions:
1. **Part B as designed earlier**: an event, an AFTER_COMMIT listener, a single-thread executor with pending-id merge, and one retry on STALE. It is renamed `TierStripeSyncQueue`, because the sweeper feeds it too.
2. **Recovery sweeper.**
 - Schedule: every 60 s (`fixedDelay`), first tick 120 s after boot.
 - Selection: enabled tiers with `price_minor > 0` and a missing or blank product or price id, on non-deleted DRAFT/LIVE events whose `updated_at` is more than 2 min old. Every tier write bumps `events.updated_at` (`TicketTierService.java:286-290`, `EventService.java:264`), so the 2-min settle keeps the sweeper away from syncs the queue is still running.
 - Order: fewest attempts first, 25 per pass.
 - Each pick is claimed with a compare-and-set UPDATE. The claim adds 1 to `stripe_sync_attempts` and sets `stripe_sync_next_at` before the tier is queued.
 - Backoff after the n-th claim: `min(5 min × 2^(n-1), 24 h)`.
 - No hard stop. A permanent stop would need a human to reset it, and a silently unsellable paid tier is exactly the failure this job exists to close. The ceiling holds the load to one call per tier per day, and the tier drops out once its event leaves DRAFT/LIVE.
 - Claims log INFO; from attempt 6 on, ERROR.
 - The sweep only claims and queues, so the shared scheduler pool of 4 never waits on Stripe.
3. **Idempotency: no explicit key; duplicate Products are accepted.**
 - A duplicate needs two syncs of the same tier to overlap: queues on two replicas, sweeper against queue past the 2-min settle, or checkout promo against either.
 - Each duplicate leaves one orphan Product and Price. Both UPDATEs land only at the current price and currency; the last one wins, and either id prices correctly.
 - Nothing reads orphans: the ticket line is priced inline from `priceMinor` (`StripeCheckoutService.java:366-374`), and a coupon scopes to the id stored at checkout time. Price-change orphans of this kind are already accepted (`StripeProductService.java:25-28`).
 - A time-bucketed key would save only the identical-parameters case. Stripe stores the first result per key, 5xx included, so a failed create would block the sweeper's next retry inside the bucket.
 - stripe-java's random per-call key already covers its own network retries.
4. **Checkout's inline promo sync stays unchanged.** It holds no transaction or lock (204-212), and it only runs for rows that have a price id but no product id. The sweeper also selects those rows. Removing it would turn such promo checkouts into 503s until a sweep.
5. **The backoff columns are mapped `insertable = false, updatable = false`.** Full-entity saves (TicketTierService, InventoryService, RefundService) can never write or revert them; only the two JPQL bulk UPDATEs do.

Out of scope:
- Healing a stale but non-null price id after a lost price-change sync. Checkout prices inline, and the next save heals it.
- Re-syncing the product description when the event is renamed (unchanged).
- The `requireStripeIfPaid` Stripe call under the event lock (`EventService.java:396-407`), queued separately.
- An on-demand sync from checkout's 404 branch (see Risks).

## Repos in ship order
1. `api` (imin-api, base `master`). This is the only repo.

## Affected files (per repo)
imin-api (worktree `/Users/ivan/imin/imin-api/.claude/worktrees/stripe-sync-after-commit`):

| # | File | Change |
|---|---|---|
| 1 | `src/main/resources/db/migration/V171__ticket_tier_stripe_sync_backoff.sql` (new) | `ALTER TABLE ticket_tiers ADD COLUMN stripe_sync_attempts INTEGER NOT NULL DEFAULT 0;`, `ADD COLUMN stripe_sync_next_at TIMESTAMP WITH TIME ZONE;`, `ADD CONSTRAINT ck_ticket_tiers_stripe_sync_attempts_nonneg CHECK (stripe_sync_attempts >= 0);`. No partial index, because H2 cannot run one. A 1-line header comment. |
| 2 | `src/main/java/com/imin/iminapi/model/TicketTier.java` | Add `@Column(name="stripe_sync_attempts", insertable=false, updatable=false) private int stripeSyncAttempts;` and `@Column(name="stripe_sync_next_at", insertable=false, updatable=false) private Instant stripeSyncNextAt;`. Javadoc: "Sweep backoff; written only by the sweep claim and the id write in TicketTierRepository." |
| 3 | `src/main/java/com/imin/iminapi/repository/TicketTierRepository.java` | (a) `updateStripeIdsIfPriceUnchanged` (102-104): add `, t.stripeSyncAttempts = 0, t.stripeSyncNextAt = NULL` to the SET clause. Signature unchanged. (b) New `@Query List<Object[]> findStripeSyncSweepCandidates(@Param statuses Collection<EventStatus>, @Param settledBefore Instant, @Param now Instant, Pageable page)`: `SELECT t.id, t.stripeSyncAttempts FROM TicketTier t, Event e WHERE e.id = t.eventId AND t.enabled = true AND t.priceMinor > 0 AND (t.stripePriceId IS NULL OR t.stripePriceId = '' OR t.stripeProductId IS NULL OR t.stripeProductId = '') AND e.deletedAt IS NULL AND e.status IN :statuses AND e.updatedAt < :settledBefore AND (t.stripeSyncNextAt IS NULL OR t.stripeSyncNextAt <= :now) ORDER BY t.stripeSyncAttempts ASC, t.id ASC`. Every parameter is non-null, so the H2/Postgres null-bytea trap does not apply. (c) New `@Modifying @Transactional int claimStripeSyncSweep(id, seenAttempts, nextAt, now)`: `UPDATE TicketTier t SET t.stripeSyncAttempts = t.stripeSyncAttempts + 1, t.stripeSyncNextAt = :nextAt WHERE t.id = :id AND t.stripeSyncAttempts = :seenAttempts AND (t.stripeSyncNextAt IS NULL OR t.stripeSyncNextAt <= :now) AND (t.stripePriceId IS NULL OR t.stripePriceId = '' OR t.stripeProductId IS NULL OR t.stripeProductId = '')`. No `clearAutomatically`: the caller is the non-transactional sweeper. |
| 4 | `src/main/java/com/imin/iminapi/stripe/StripeProductService.java` | Javadoc only. `syncTier` (51-55) now reads: "Callers run this after commit and never while holding a tier or event lock (TierStripeSyncQueue, or checkout, which has no transaction)." No code change. |
| 5 | `src/main/java/com/imin/iminapi/stripe/TierStripeSyncRequested.java` (new) | `public record TierStripeSyncRequested(UUID tierId) {}` |
| 6 | `src/main/java/com/imin/iminapi/stripe/TierStripeSyncQueue.java` (new) | `@Component`. Constructor `(TicketTierRepository, EventRepository, StripeProductService, @Qualifier("tierStripeSyncExecutor") Executor)`. `@TransactionalEventListener(phase = AFTER_COMMIT) public void onCommitted(TierStripeSyncRequested e)` → `request(e.tierId(), 0)`, with no `@Transactional`. `public void request(UUID tierId, int attempt)`: if `pending.add(tierId)` (a `ConcurrentHashMap.newKeySet()`), call `executor.execute(() -> run(tierId, attempt))`. On `RejectedExecutionException`, remove the id and log WARN. Any other `RuntimeException` is caught and logged so nothing reaches `commit()`. `run`: first `pending.remove(tierId)`; then `tiers.findById` and `events.findActive(tier.getEventId())`, skipping when either is empty; then `outcome = syncTier(tier, event)`; `if (outcome == SyncOutcome.STALE && attempt == 0) request(tierId, 1)` (a null outcome from mocks counts as no retry). Catch `RuntimeException` and log WARN with the throwable. |
| 7 | `src/main/java/com/imin/iminapi/stripe/TierStripeSyncSweeper.java` (new) | `@Component`. Constructor `(TicketTierRepository, TierStripeSyncQueue, Clock)`. Constants: `BATCH = 25`, `SETTLE = 2 min`, `BASE = 5 min`, `CEILING = 24 h`, `ERROR_FROM_ATTEMPT = 6`, statuses `List.of(DRAFT, LIVE)`. `@Scheduled(fixedDelay = 60_000, initialDelayString = "${imin.tier-stripe-sync.sweep-initial-delay-ms:120000}") @SchedulerLock(name = "tier_stripe_sync_sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M") public void sweep()`. For each candidate `[id, attempts]`, inside its own try/catch (log ERROR, continue): `n = attempts + 1`; `claimStripeSyncSweep(id, attempts, nextAttemptAt(now, n), now)`; if 1 row, log (INFO, or ERROR when `n >= 6`) and call `queue.request(id, 0)`. A summary line logs `planned= claimed= failed=`. `static Instant nextAttemptAt(Instant now, int n)` is package-private and returns `now + min(BASE << min(n-1, 9), CEILING)`. Class javadoc, 2 lines: "Backstop for syncs the queue lost or Stripe failed; claims with backoff so a failing tier is retried at most daily." |
| 8 | `src/main/java/com/imin/iminapi/config/AsyncConfig.java` | New bean `tierStripeSyncExecutor`: core 1, max 1, queue 500, prefix `tier-stripe-sync-`, default AbortPolicy (the queue catches the rejection and clears its pending id), `setWaitForTasksToCompleteOnShutdown(true)`, `setAwaitTerminationSeconds(10)`. Javadoc: "One thread, so a tier's syncs run in commit order and a Stripe stall never holds a request thread or a lock." |
| 9 | `src/main/java/com/imin/iminapi/service/event/TicketTierService.java` | Replace `syncStripeProduct(tier, event)` at 155, 178, 244 and 255 with `publishSyncRequested(tier.getId())`, which publishes through the existing nullable `eventPublisher`. Delete `syncStripeProduct` (260-263), the `stripeProductService` field and its javadoc (40-44), the import (17), and the 5-arg and 6-arg constructors (63-80). Grep finds no callers: only the 4-arg (TicketTierServiceTest:49) and 7-arg (164) constructors are used. The primary constructor becomes `(tiers, events, validator, clock, auditLogger, eventPublisher)`, and the 4-arg one delegates with `null, null`. Drop the "Best-effort Stripe product sync" comments at 154 and 177. |
| 10 | `CLAUDE.md` (imin-api) | Stripe "State model" paragraph (line 201): after the `updateStripeIdsIfPriceUnchanged` sentence, add that organizer tier writes publish `TierStripeSyncRequested` and sync after commit on the single-thread `tierStripeSyncExecutor` (`TierStripeSyncQueue`: merged per tier, one STALE retry), never under a lock. Add that `TierStripeSyncSweeper` (every 60 s, ShedLock `tier_stripe_sync_sweep`, test property `imin.tier-stripe-sync.sweep-initial-delay-ms`) queues enabled paid tiers of non-deleted DRAFT/LIVE events with a missing id, once the event has been unchanged for 2 min, 25 per pass, backing off 5 min doubling to 24 h (`stripe_sync_attempts`/`stripe_sync_next_at`, V171, reset when the ids land). Add that checkout's promo path still syncs inline. |
| 11 | `docs/STRIPE_LIVE_CUTOVER.md` | §10 (278-281): replace "must re-save each tier… does NOT self-heal… Until the tier is re-saved it cannot sell" with: cleared paid tiers of DRAFT/LIVE events are re-synced by `TierStripeSyncSweeper` within minutes, so run the postcheck immediately after the commit, because rows healed by the sweep carry live ids. |
| 12 | `scripts/stripe-live-cutover.sql` | §1 comment (30-32): replace "until the organizer re-saves it (TicketTierService.syncTier is the only re-sync path)" with "until TierStripeSyncSweeper or an organizer save re-syncs it". Comment lines only, still `--`; ScriptUtils skips them. |
| 13 | `src/test/resources/application.yaml` | Under `imin:`, add `tier-stripe-sync: sweep-initial-delay-ms: 86400000`, with the comment "No Spring test context ticks the sweep; tests call sweep() on the bean." |
| 14 | `src/test/java/com/imin/iminapi/stripe/TierStripeSyncQueueTest.java` (new) | Unit tests with a capturing `Executor` (stores Runnables), a mocked repository and a mocked `StripeProductService` (tests 1–9). |
| 15 | `src/test/java/com/imin/iminapi/stripe/TierStripeSyncSweeperTest.java` (new) | Unit tests with mocked repository and queue and a fixed `Clock` (tests 28–32). |
| 16 | `src/test/java/com/imin/iminapi/stripe/TierStripeSyncSweeperSpringTest.java` (new) | H2 `@SpringBootTest` with `@MockitoBean StripeClient`. Calls `sweep()` through the bean. Rewinds the `tier_stripe_sync_sweep` shedlock row in `@BeforeEach`, as `DisputeAttributionSweeperTest.java:333-349` does (tests 33–34). |
| 17 | `src/test/java/com/imin/iminapi/service/event/TicketTierRepositoryPersistenceTest.java` | Add tests 19–26. Existing tests 128-212 are unaffected: they assert row counts and ids, never the new columns. |
| 18 | `src/test/java/com/imin/iminapi/service/event/TicketTierStripeIdsPostgresTest.java` | Add test 27. The existing 2 tests (107, 116) assert row counts and ids only. |
| 19 | `src/test/java/com/imin/iminapi/service/event/TicketTierServiceTest.java` | Line 164 becomes `new TicketTierService(tiers, events, validator, clock, null, publisher)`. Test 10: line 173 `verify(publisher).publishEvent(...)` (exactly 1 call) becomes `times(2)`, asserting one `EventMutated(eventId)` and one `TierStripeSyncRequested(tierId)`. Add tests 11–12. |
| 20 | `src/test/java/com/imin/iminapi/service/event/EventPatchStripeSyncPersistenceTest.java` | Test 13: the GA tier's ids are asserted right after `patch` returns; now poll JDBC every 50 ms for up to 5 s until `stripe_product_id = 'prod_embedded'`, then assert as before. Add test 14. |
| 21 | `src/test/java/com/imin/iminapi/service/event/TierInventoryRacePostgresTest.java` | Helper 15 `organizerHoldsTierThenReserve` (351-377) pauses inside the organizer through `syncTier` (doAnswer at 133-141). After this change that runs after commit, so `tierPatch_organizerHoldsTier_…` (160) and `embeddedPatch_organizerHoldsTier_…` (181) would go red. Instead, run `organizerWrite` inside an outer `TransactionTemplate`, count down `syncEntered`, then wait for the gate (the delete test's pattern, 204-235). Keep the doAnswer for tests 16–18. |

Files that read the changed code but need no edit:
- `StripeCheckoutService.java`: still calls `syncTier` at 823 and reads the instance's ids, both unchanged. The gate at 632 is unchanged.
- `StripePaymentIntentService.java`: uses the same gate through `reserveAndBuildMetadata`.
- `PublicTierEligibility.java` and quote: they never read the ids (javadoc at 27).
- `EventService.java`: `reconcileEmbedded`'s signature is unchanged, and the currency guard at 503-504 reads `existsSyncedStripePrice`, which is unchanged.
- `InventoryService.java` (89-103, 149-168) and `RefundService.java`: full saves, which cannot write the new columns (`insertable/updatable = false`).
- `scripts/stripe-live-cutover-postcheck.sql`: a one-off that has already run against prod (sk_live since 2026-09-18). The timing note goes in the runbook (file 11).
- `StripeLiveCutoverScriptTest.java`: it runs the script through `ScriptUtils` (473), which drops `--` comments. Its tier inserts omit the new columns, so the DB defaults apply.
- `StripeProductServiceTest.java` and `StripeProductServicePersistenceTest.java`: the code under test and the repository signature are unchanged. The persistence test asserts ids, `reserved` and `sold` only.
- `CrossOrgScopingTest` (86), `WithinOrgRoleGateTest` (65), `DashboardCacheTest` (64), `EventStatusRevertPostgresTest` (93), `UnpublishCheckoutRacePostgresTest` (82): a `@MockitoBean StripeProductService` with no stub or verify (grep). The queue now calls the mock on its own thread, and the mock returns null, which counts as no retry.
- `StripeCheckoutServiceTest.java` and `FreeCheckoutIdempotencyTest.java`: checkout is unchanged.
- `AsyncConfigTest.java`: resolves the `@Primary` default executor. The new bean is qualified and not `@Primary`.

## Ordered steps
Commit 1 (sweeper infrastructure; safe on its own, see Risks):
1. Run `docker info`, then the full gate on the untouched worktree. Record `Tests run:` and confirm 0 skipped Testcontainers tests. If the base is red, stop and report.
2. Add V171 (file 1) and the two `TicketTier` fields (file 2).
3. `TicketTierRepository`: the SET extension and the two new methods (file 3), each with a 1–2 line javadoc.
4. `TierStripeSyncRequested`, the `tierStripeSyncExecutor` bean and `TierStripeSyncQueue` (files 5, 8, 6). Comments: "Runs after commit on one thread, so no lock is held during the Stripe call." and "The task drops its id first, so a commit during a run queues another."
5. `TierStripeSyncSweeper` (file 7) and the test property (file 13).
6. Tests 1–9, 19–34. After each guard test is green, remove its guard once, confirm the named test goes red, and restore (guard table).

Commit 2 (organizer paths stop calling Stripe):
7. `TicketTierService` (file 9). The paths changed are `create` (155), `patch` (178), and the `reconcileEmbedded` create and update branches (244, 255, reached through `EventService.patch:277`). `delete` never synced. Checkout (823) stays inline. The grep must show `syncTier` called only from `TierStripeSyncQueue` and `StripeCheckoutService`.
8. Test edits 10, 13, 15 and new tests 11–12, 14, 16–18, plus their guard proofs.
9. `StripeProductService` javadoc (file 4), `CLAUDE.md`, the runbook and the script comment (files 10–12).
10. Run the full gate. Read every targeted run's own `Tests run:` line, and confirm 0 skipped.

## Verification commands
From `/Users/ivan/imin/imin-api/.claude/worktrees/stripe-sync-after-commit`:
- `docker info` must succeed first. Skipped Postgres tests make the run red.
- Targeted: `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=TierStripeSyncQueueTest,TierStripeSyncSweeperTest,TierStripeSyncSweeperSpringTest,TicketTierRepositoryPersistenceTest,TicketTierStripeIdsPostgresTest,TicketTierServiceTest,EventPatchStripeSyncPersistenceTest,TierInventoryRacePostgresTest,StripeProductServiceTest,StripeProductServicePersistenceTest,StripeCheckoutServiceTest,StripeLiveCutoverScriptTest,EventStatusRevertPostgresTest,UnpublishCheckoutRacePostgresTest`
- Full gate: `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test`. Expect the baseline count + 31, with the same skips as the baseline and none of them Testcontainers.
- Re-run the full gate after any rebase.

## Test impact
Branches:

| B | Branch |
|---|---|
| Q1 | Queue: request → task loads a fresh tier and event → `syncTier` |
| Q2 | Queue: tier gone → no Stripe call |
| Q3 | Queue: event gone or soft-deleted (`findActive` empty) → no Stripe call |
| Q4 | Queue: repeat while queued → merged |
| Q5 | Queue: repeat while running → queued again |
| Q6 | Queue: executor rejects → pending cleared, nothing thrown |
| Q7 | Queue: STALE on attempt 0 → one retry; STALE on attempt 1 → stop |
| Q8 | Queue: FAILED → no retry |
| Q9 | Queue: sync throws → pending cleared, nothing thrown |
| S1–S3 | TicketTierService patch / create / reconcile (create and update) each publish one request per tier and make no inline sync |
| A1 | Commit → ids land from the queue thread |
| A2 | Rollback → no Stripe call |
| L1–L3 | Tier patch / embedded patch / tier create: tier and event locks are free while Stripe is blocked |
| R1 | Sweep selection, each predicate |
| R2 | Sweep order and batch bound |
| C1–C4 | Claim lands / attempts moved / ids landed / not yet due |
| R3 | Id write resets the backoff |
| F1 | Full save cannot write the backoff (both orderings) |
| W1–W5 | Sweeper: nothing due / claim then queue with backoff / lost claim / per-tier isolation / backoff function |
| P1–P2 | Sweep through the bean heals a tier / runs under ShedLock |

Tests (34 rows: 31 new, 2 edited tests, 1 edited helper):

| # | File | Test | Covers |
|---|---|---|---|
| 1 | TierStripeSyncQueueTest | `request_runsSyncOnFreshlyLoadedTierAndEvent` | Q1 |
| 2 | TierStripeSyncQueueTest | `tierGone_noStripeCall` | Q2 |
| 3 | TierStripeSyncQueueTest | `eventGoneOrSoftDeleted_noStripeCall` | Q3 |
| 4 | TierStripeSyncQueueTest | `repeatWhileQueued_isMergedIntoOneRun` | Q4 |
| 5 | TierStripeSyncQueueTest | `repeatDuringRun_queuesAnotherRun` | Q5 |
| 6 | TierStripeSyncQueueTest | `rejectedByExecutor_clearsPending_nextRequestQueues` | Q6 |
| 7 | TierStripeSyncQueueTest | `staleOutcome_retriesOnce_thenStops` | Q7 |
| 8 | TierStripeSyncQueueTest | `failedOutcome_noRetry` | Q8 |
| 9 | TierStripeSyncQueueTest | `syncThrows_pendingCleared_noThrowOutOfTask` | Q9 |
| 10 | TicketTierServiceTest (edited) | `standaloneTierPatch_publishesPredictorReactivityEvent`: `times(2)`, one EventMutated and one TierStripeSyncRequested(tierId) | S1 |
| 11 | TicketTierServiceTest | `create_publishesSyncRequestForNewTier` | S2 |
| 12 | TicketTierServiceTest | `reconcileEmbedded_publishesOneRequestPerCreatedAndUpdatedTier` | S3 |
| 13 | EventPatchStripeSyncPersistenceTest (edited) | `patchWithNewPaidTier_persistsEventFieldTierEditsAndStripeIds`: polls for up to 5 s | A1 |
| 14 | EventPatchStripeSyncPersistenceTest | `rolledBackPatch_neverCallsStripe`. Fixture: committed paid tier with null ids. Patch renames it and adds an invalid tier (400). `verify(products, after(500).never()).create(any())` | A2 |
| 15 | TierInventoryRacePostgresTest (edited helper) | `organizerHoldsTierThenReserve` pauses in an outer transaction; the tests at 160 and 181 keep their assertions | tier lock still held by the organizer's own write |
| 16 | TierInventoryRacePostgresTest | `tierPatch_stripeSyncBlocked_tierAndEventLocksFree`: `syncTier` blocks on the gate; the patch returns within 5 s; `SELECT … FOR UPDATE NOWAIT` on the tier and the event succeeds; `reserveTwo()` returns within 5 s; release in `finally` | L1 |
| 17 | TierInventoryRacePostgresTest | `embeddedPatch_stripeSyncBlocked_tierAndEventLocksFree` | L2 |
| 18 | TierInventoryRacePostgresTest | `tierCreate_stripeSyncBlocked_eventLockFree` | L3 |
| 19 | TicketTierRepositoryPersistenceTest | `sweepCandidates_onlyUnsyncedEnabledPaidTiersOfSettledDraftOrLiveEvents`. Fixture: 4 eligible rows (DRAFT both ids null; LIVE product id null only; price id `''`; `next_at` in the past) and 8 rows each excluded by exactly one predicate (disabled; price 0; both ids set; PAST; CANCELLED; soft-deleted event; event `updated_at` inside the 2 min; `next_at` in the future). Event `updated_at` is set by native UPDATE plus `em.clear()`. Assert the exact id set | R1 |
| 20 | TicketTierRepositoryPersistenceTest | `sweepCandidates_fewestAttemptsFirst_boundedByPage` (attempts 2, 0, 1; page 2 → the 0 and 1 rows) | R2 |
| 21 | TicketTierRepositoryPersistenceTest | `claimSweep_landsWhenDueAndAttemptsUnchanged` (1 row, attempts +1, `next_at` stored) | C1 |
| 22 | TicketTierRepositoryPersistenceTest | `claimSweep_noOpWhenAttemptsMoved` | C2 |
| 23 | TicketTierRepositoryPersistenceTest | `claimSweep_noOpWhenIdsLanded` | C3 |
| 24 | TicketTierRepositoryPersistenceTest | `claimSweep_noOpWhenNotYetDue` | C4 |
| 25 | TicketTierRepositoryPersistenceTest | `updateStripeIds_resetsSweepBackoff` (attempts 3 → 0, `next_at` → null) | R3 |
| 26 | TicketTierRepositoryPersistenceTest | `fullSave_neverWritesSweepBackoff_bothOrderings`: claim then stale full save keeps the backoff; full save then claim lands. Read back with native SQL | F1 |
| 27 | TicketTierStripeIdsPostgresTest | `sweepCandidatesAndClaim_onPostgres` (one eligible and one PAST row; the claim lands once, a second claim with the same seen attempts gets 0 rows) | R1, C1, C2 on PG 17 |
| 28 | TierStripeSyncSweeperTest | `nothingDue_noClaimNoRequest` | W1 |
| 29 | TierStripeSyncSweeperTest | `dueTier_claimedWithBackoffThenRequested`. Verifies statuses `[DRAFT, LIVE]`, `settledBefore = now − 2 min`, page size 25, the claim with `seenAttempts = 3` and `nextAt = now + 40 min`, then `queue.request(id, 0)` | W2 |
| 30 | TierStripeSyncSweeperTest | `lostClaim_notRequested` | W3 |
| 31 | TierStripeSyncSweeperTest | `oneTierThrows_othersStillProcessed` (the throwing tier is listed first) | W4 |
| 32 | TierStripeSyncSweeperTest | `nextAttemptAt_doublesFromFiveMinutes_cappedAtOneDay`: n=1 → +5 min, n=2 → +10 min, n=9 → +1280 min, n=10 → +24 h, n=40 → +24 h (no overflow). Values derive from the plan's own constants | W5 |
| 33 | TierStripeSyncSweeperSpringTest | `sweep_healsUnsyncedTierOfSettledLiveEvent_resetsBackoff` (poll up to 5 s: ids `prod_sweep`/`price_sweep`, attempts 0, `next_at` null) | P1 |
| 34 | TierStripeSyncSweeperSpringTest | `sweep_runsUnderShedLock` (the shedlock row `tier_stripe_sync_sweep` is stamped after the rewind) | P2 |

Count check: 9 + 3 + 2 + 4 + 8 + 1 + 5 + 2 = 34 rows; 34 − 2 edited tests − 1 edited helper = 31 new.

Guard proofs. Remove each guard once; the named test must go red:

| Guard | Mutation | Red test |
|---|---|---|
| AFTER_COMMIT listener | `@TransactionalEventListener` → `@EventListener` | 14 |
| organizer paths do not call Stripe | call `syncTier` inline again in `TicketTierService` | 16, 17, 18 |
| pending-id merge | drop the set check | 4 |
| task drops its id first | move `pending.remove` after `syncTier` | 5 |
| pending cleared on reject | drop the `remove` in the catch | 6 |
| retry cap | drop `attempt == 0` | 7 |
| retry only on STALE | retry on any outcome | 8 |
| selection: `enabled`, `priceMinor > 0`, missing id, status, `deletedAt`, settle, `next_at` | remove each clause in turn | 19 |
| order by attempts | drop the ORDER BY attempts term | 20 |
| claim CAS on attempts | drop `stripeSyncAttempts = :seenAttempts` | 22 |
| claim requires a missing id | drop the id clause | 23 |
| claim respects `next_at` | drop the `next_at` clause | 24 |
| reset on id write | drop the SET extension | 25 |
| `insertable/updatable = false` on the backoff fields | remove the attributes | 26 |
| backoff ceiling | drop `min(…, CEILING)` | 32 |
| per-tier isolation | drop the try/catch | 31 |
| `@SchedulerLock` | remove it | 34 |

Tests 16–18 and 27 need Docker. If they report skipped, the run is red. Not tested: the INFO/ERROR level split at attempt 6, which is logging only.

## Live-test
`/live-test api` after deploy. Prod Stripe is live, and creating a Product moves no money.
1. Before ship, run the backlog query from OPEN_QUESTION 1.
2. On a test org's draft event, add a paid tier in the dashboard. The save returns at its normal latency. Within about 5 s, `railway psql`: `SELECT stripe_product_id, stripe_price_id, stripe_sync_attempts FROM ticket_tiers WHERE id = '<id>'` shows both ids and 0.
3. Rename the tier: the Stripe dashboard Product name follows.
4. Heal (needs the user's OK for a prod write on that test row): `UPDATE ticket_tiers SET stripe_product_id = NULL, stripe_price_id = NULL WHERE id = '<id>'`, and backdate the test event's `updated_at` by 3 minutes. Within 2 minutes, the ids are back and attempts are 0. Logs show a `TierStripeSyncSweeper` claim and a summary line.
5. Logs show no `tier-stripe-sync` WARN and no ERROR claim lines.
6. Clean up: delete the tier and the draft.

## Contract impact
none. `TicketTierDto` has no Stripe fields (grep), the new columns are not exposed, and no endpoint or `/api/v1/public` contract changes. No OpenAPI marker, `types.ts` edit or `PUBLIC_PAGE_API.md` change.

## i18n impact
none. Server-side only. Copy ledger: no rows; the plan adds or reuses no user-facing strings, and log lines are not user-facing.

## Blast radius
Money and Stripe, a Flyway migration, and a shared module: `StripeProductService` and `ticket_tiers` are used by organizer writes, buyer checkout, inventory and refunds.
- **Checkout gate** (`StripeCheckoutService.java:632-637`, used by the hosted flow and native PaymentIntents): a new paid tier, or one whose ids were cleared, returns a leak-safe 404 from commit until its queued sync lands. Normally that is one Stripe create, about 1 s.
- Before this change the organizer's save waited for the sync, so a tier that showed as saved was already sellable unless Stripe had failed.
- A lost queue (deploy, crash, more than 500 queued) is healed by the sweeper within about 2 min settle + ≤60 s tick + queue time.
- A failing Stripe is retried at 5, 10, 20 … min, up to once a day.
- Quote keeps returning 200 during that window, as it does today after a failed sync.
- **Tiers already synced** keep their ids through renames and price changes. Checkout prices inline from `priceMinor` (`StripeCheckoutService.java:366-374`), so the amount charged does not change. No money formula changes.
- **Promo inline sync** (817-832): unchanged. It can now overlap a queued sync and create a duplicate Product (accepted, see Decision 3).
- **Currency guard** (`EventService.java:503-504`): a currency change is now refused only once an id has landed, so the window before the first sync is about 1 s longer. A sync whose Stripe call overlaps a currency change gets STALE and retries once with the fresh currency.
- **First deploy**: the sweeper creates Products for every current candidate (OPEN_QUESTION 1), 25 per minute. The org's Connect readiness still gates any sale.
- **Every writer of the `ticket_tiers` row**:
- Full saves: `TicketTierService` create, patch and reconcile, `InventoryService` (89-103, 149-168), and `RefundService`. They cannot write the id columns (`updatable = false`, part A) or the backoff columns (`insertable/updatable = false`, file 2); test 26 checks both orderings.
- Targeted UPDATEs: `updateStripeIdsIfPriceUnchanged` (ids, plus the backoff reset) and `claimStripeSyncSweep` (backoff only, CAS).
- Raw SQL: the cutover script (ids only).
- A claim waits on a tier lock that an organizer or buyer holds for milliseconds only, since no Stripe call runs under that lock any more.
- **Every path that syncs a tier**:
- `TicketTierService.create`, `patch` and both `reconcileEmbedded` branches → after commit through `TierStripeSyncQueue` (commit 2).
- `TierStripeSyncSweeper` → claim, then the same queue.
- `StripeCheckoutService.resolveTicketProductId` → inline, with no transaction and no lock.
- All of them write through the one conditional UPDATE. No other scheduler, reconciler or admin endpoint calls `syncTier` (grep).
- **Hikari pool**: the listener does no DB work on the commit thread. The queue thread uses one short connection per read and per write; the sweep uses one per query or claim.
- **Scheduler pool** (4 threads): the sweep never calls Stripe, so it finishes in milliseconds.
- **Every Spring test context**: the sweep never ticks (file 13). The queue runs whenever a test writes a tier and calls that context's `StripeProductService`, which is a mock or the `sk_test_dummy` client. Those are the same calls such tests make today, now on another thread.
- **Cutover artefacts**: `scripts/stripe-live-cutover.sql` and `docs/STRIPE_LIVE_CUTOVER.md` are edited (files 11–12). `stripe-live-cutover-postcheck.sql` needs no edit (one-off, already run).

## Risks
- **Size**: 21 files (12 main and docs, 9 test), over the ~15 threshold. One concern, but two separable steps. Proposed split (or two commits in one ship):
- (1) Steps 1–6: migration, repository, queue, executor, sweeper and their tests. Safe on its own: the organizer still syncs inline, and the sweeper only heals failed syncs; its 2-min settle keeps it off saves in flight.
- (2) Steps 7–10: the `TicketTierService` switch, the race-test edits and the docs.
- Shipping (2) without (1) is not allowed; that was the reason part B was deferred.
- **Duplicate Products** when syncs overlap (Decision 3): harmless orphans; there is no archiving.
- **Renames out of order across replicas**: Stripe can show the older name until the next save. Cosmetic.
- **A stale price id after a lost price-change sync** is not healed (the ids are non-null). Amounts are unaffected, since the line is inline, and the next save heals it.
- **One thread per replica**: a Stripe stall (worst case 80 s × 3 per call) delays every sync on that replica but no request or lock. Queue capacity is 500; on overflow the sweeper heals.
- **No index** for the sweep query (H2 has no partial indexes). It scans `ticket_tiers` every 60 s; the table is small. Revisit if it grows.
- **Migration**: additive. `ADD COLUMN … NOT NULL DEFAULT 0` only touches metadata on PG 11 and later. Railway runs with `SPRING_FLYWAY_OUT_OF_ORDER=true`; V171 is free across local worktrees today, so re-check before ship.
- **Considered and rejected**: queueing a sync from checkout's 404 branch. It would bypass the backoff on a persistently failing tier and let unauthenticated buyers trigger Stripe calls; the sweeper bounds the gap instead.

## Definition of done
- The gate was green on the untouched base. After the change, `./mvnw test` is green through `test-serial.sh` with 0 skipped Testcontainers tests, and it is re-run after any rebase.
- All 34 test rows pass, and each guard in the guard table was removed once and its test went red.
- Grep confirms:
- no `StripeProductService` in `TicketTierService`;
- `syncTier` is called only from `TierStripeSyncQueue` and `StripeCheckoutService`;
- `tierStripeSyncExecutor` is injected only by qualifier.
- V171's CHECK `ck_ticket_tiers_stripe_sync_attempts_nonneg CHECK (stripe_sync_attempts >= 0)` holds: attempts only increment from ≥ 0 or reset to 0, and no config value is written to the column.
- Javadocs, `CLAUDE.md` state model, the runbook §10 and the cutover script comment are updated in the same change. Comments are 1–2 lines with no ticket or milestone ids.
- Live-test evidence recorded below.

## Decisions (main session)
- Plan accepted as written. Ship as ONE ship with the two commits from Risks (commit 1 = steps 1–6, commit 2 = steps 7–10), pushed together. The worker does not commit; the main session splits the commits at ship.
- OPEN_QUESTION 1: a prod read on 2026-10-02 found 10 candidate tiers, all on DRAFT events (0 LIVE) across 3 orgs. Accepted: the sweeper creating their live Products moves no money, and an organizer save would create them anyway.
- OPEN_QUESTION 3: live-test step 4 (nulling ids in prod) needs Ivan's approval, so it is NOT run automatically. Run live-test steps 1, 5, and a read of the sweeper's first summary log lines plus the 10 backlog tiers healing.
- Re-check V171 is free on origin/master before ship.

## Live-test evidence

## Review rounds

### Round 0 (implement): guard proofs
Each guard was removed once in the working tree by a scratch harness that copied the file to a backup, applied the mutation, ran only the named test through test-serial.sh, then restored the file and asserted its SHA-256 matched the pre-mutation hash.

| Guard | Mutation | Test | Result |
|---|---|---|---|
| pending-id merge | `pending.add` result ignored | 4 `repeatWhileQueued_isMergedIntoOneRun` | red (assertion) |
| task drops its id first | `pending.remove` moved after `syncTier` | 5 `repeatDuringRun_queuesAnotherRun` | red (assertion) |
| pending cleared on reject | `remove` in the catch dropped | 6 `rejectedByExecutor_clearsPending_nextRequestQueues` | red (assertion) |
| retry cap | `&& attempt == 0` dropped | 7 `staleOutcome_retriesOnce_thenStops` | red (assertion) |
| retry only on STALE | `outcome == STALE &&` dropped | 8 `failedOutcome_noRetry` | red (assertion) |
| selection `enabled` | clause removed | 19 | red (assertion) |
| selection `priceMinor > 0` | clause removed | 19 | red (assertion) |
| selection missing id | clause removed | 19 | red (assertion) |
| selection status | `OR 1 = 1` added | 19 | red (assertion) |
| selection `deletedAt` | clause removed | 19 | red (assertion) |
| selection settle | `OR 1 = 1` added | 19 | red (assertion) |
| selection `next_at` | `OR 1 = 1` added | 19 | red (assertion) |
| order by attempts | `ORDER BY t.id` only | 20 (fixed ids: id order two, one, zero) | red (assertion) |
| claim CAS on attempts | `OR 1 = 1` added | 22 | red (assertion) |
| claim requires a missing id | id clause removed | 23 | red (assertion) |
| claim respects `next_at` | `OR 1 = 1` added | 24 | red (assertion) |
| reset on id write | SET extension removed | 25 | red (assertion) |
| `insertable/updatable = false` | both attributes removed | 26 | red (assertion) |
| backoff ceiling | `min(…, CEILING)` removed | 32 | red (assertion) |
| per-tier isolation | try/catch replaced by empty finally | 31 | red (exception escapes `sweep`) |
| `@SchedulerLock` | annotation removed | 34 | red (assertion) |
| AFTER_COMMIT listener | `@EventListener` instead | 14 | red: "Wanted at most 0 times but was 1" on `products.create` |
| organizer paths do not call Stripe | `TicketTierService` calls `syncTier` inline again | 16, 17, 18 | all 3 red: `TimeoutException` on the organizer write (blocked in Stripe under the locks) |

### Review-fix round 1
Fresh review verdict FIX_REQUIRED; four items applied.
1. HIGH (docs). The claim that "rows healed by the sweep carry live ids" was false: the reset runs under the test key, so the sweeper re-creates test Products before the §7 swap. Changes:
   - `docs/STRIPE_LIVE_CUTOVER.md`:
     - The intro and the §3 worklist line no longer say tiers must be re-saved.
     - §5: run the postcheck before the next sweep tick; after that tick the `ticket_tiers` row reads non-zero with test ids.
     - §8: once `STRIPE MODE: live` shows, clear the tier ids again (and the backoff columns) so the sweeper heals with live ids, then re-run §5.
     - §10: no tier needs re-saving, and the §8 re-clear must not be skipped.
   - `scripts/stripe-live-cutover.sql` §1: comment lines only.
   - `scripts/stripe-live-cutover-preflight.sql`: the header comment, plus the `\echo` label of the §2 worklist (an output string, not SQL).
   - StripeLiveCutoverScriptTest stays green (19/19).
2. LOW. `StripeProductService.doSync` treats a blank product or price id as missing.
   - New tests: `blankProductId_createsInsteadOfUpdating` and `blankPriceId_createsInsteadOfUpdating`.
   - Both were red against the unfixed code ("Tests run: 8, Failures: 2"); green after the fix.
3. LOW.
   - New test `executorThrowsOtherRuntimeException_nothingThrownToCaller`. Guard proof: with the outer catch replaced by an empty `finally`, it was red; the file was restored and its hash checked.
   - The rejection log now reads "rejected (queue full or shutting down)".
4. LOW. Added two lines to the `EventService` currency-guard comment: ids land after commit, so a currency change that races a first sync can store an old-currency price id. That is harmless because checkout prices the ticket line inline.
