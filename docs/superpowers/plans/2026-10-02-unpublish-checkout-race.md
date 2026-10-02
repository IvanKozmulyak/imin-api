# Unpublish and checkout never both win (api)
unpublish-checkout-race · Subagent · Notion: not supplied

## Goal and scope
**Goal:** an organizer's unpublish and a buyer's reserve can no longer both succeed. Either the reserve commits first and unpublish then refuses (409), or unpublish commits first and the reserve then refuses (404 `Event`). After this change, no DRAFT event can get a new hold, and so no paid order on a hidden page.

**The race today (verified on `origin/master` at f9d053b2):**
- **Unpublish:**
- `EventService.unpublish` (EventService.java:361-388) takes the event row lock (`loadOwnedForWrite` → `EventRepository.lockActiveForWrite`, EventRepository.java:367-380). This is a no-op UPDATE, not FOR UPDATE.
- It then reads tiers with a plain `findByEventIdOrderBySortOrderAsc` (:368) and checks `sold > 0` (:369) and `reserved > 0` (:377) with **no tier lock**, then sets DRAFT (:382).
- **Checkout:**
- Every path checks event status only in `priceIt`: `findPublic` excludes DRAFT (EventRepository.java:120-131) and `PublicTierEligibility.loadBuyableTier` excludes PAST/CANCELLED (StripeCheckoutService.java:560,566).
- All of that happens before `InventoryService.reserve`, which locks only the tier (`findByIdForUpdate`, InventoryService.java:82) and never looks at the event.
- **Interleavings that lose:**
- (a) checkout passes `priceIt` → unpublish reads `reserved = 0` and commits DRAFT → reserve commits a HELD hold on a DRAFT event.
- (b) reserve commits after unpublish's plain read but before unpublish's commit. Same result.
- **Fulfilment** (`PaidCheckoutService.java:170`, `events.findById`, no status check) then issues the order on the hidden event.

**Entry paths into reserve.** All of them reach `InventoryService.reserve`, which is the single place the fix goes.

| # | Path | Call site |
|---|---|---|
| 1 | Hosted Checkout (paid) | `StripeCheckoutService.createCheckout` → `reserveAndBuildMetadata` (StripeCheckoutService.java:360) → `reserve` (:667) |
| 2 | Native PaymentIntent (paid) | `StripePaymentIntentService.create` → `priceIt` (:154) → `reserveAndBuildMetadata` (:165) → `reserve` (:667) |
| 3 | Free order | `StripeCheckoutService.createCheckout` (:315) → `FreeCheckoutService.issueFreeOrder` → `reserve` (FreeCheckoutService.java:156) |

**Product decision (kept as is):** a checkout that already holds seats blocks unpublish. The existing 409 says to retry after the session expires. A paid PaymentIntent that succeeds is always fulfilled, because money has moved (InventoryService.java:251-273, PaidCheckoutService.java:170). Only *new* reserves on a non-LIVE event are refused.

**Scope:**
- **In:** `EventService.unpublish` locking and counting; a status re-check in `InventoryService.reserve`; the tests listed below.
- **Out:**
- Visibility flips during a checkout (open question).
- The documented `[OVERSOLD]` late-success-after-release edge (see Risks).
- Any webapp or public change.

**Is it worth the money-path risk?** Yes.
- **Checkout side:** one primary-key scalar read under a tier lock checkout already takes. It fails closed before any Stripe call, so there is nothing to undo at Stripe.
- **Unpublish side:** organizer-only, and it takes locks in the order `EventService.patch` already uses (EventService.java:259).

## Repos in ship order
1. `api` (imin-api, base `master`), worktree `/Users/ivan/imin/imin-api/.claude/worktrees/unpublish-checkout-race`, branch `fix/unpublish-checkout-race`.

## Affected files (per repo)
**imin-api** (9 files)

| # | File | Change |
|---|---|---|
| 1 | `src/main/java/com/imin/iminapi/repository/TicketTierRepository.java` | Add `@Query("SELECT t.id FROM TicketTier t WHERE t.eventId = :eventId") List<UUID> findIdsByEventId(UUID)`. Add `@Query("SELECT COUNT(t) > 0 FROM TicketTier t WHERE t.eventId = :eventId AND t.sold > 0") boolean existsSoldByEventId(UUID)`. Add `existsReservedByEventId` (same, `t.reserved > 0`). All are scalar, so none returns a cached entity. |
| 2 | `src/main/java/com/imin/iminapi/repository/EventRepository.java` | Add `@Query("SELECT e.status FROM Event e WHERE e.id = :id AND e.deletedAt IS NULL") Optional<EventStatus> findActiveStatus(UUID id)`. It is a scalar, so it reads the committed row even when an `Event` entity for that id is already in the persistence context. Javadoc: one line saying why it is scalar. |
| 3 | `src/main/java/com/imin/iminapi/service/event/EventService.java` | In `unpublish`, after the `!= LIVE` check: `tierService.lockForWrite(e.getId(), tiers.findIdsByEventId(e.getId()));`. Then replace the entity read and both `anyMatch` checks (:368-381) with `tiers.existsSoldByEventId(e.getId())` and `tiers.existsReservedByEventId(e.getId())`. Messages stay byte-identical. Update the comment at :373-376 (1–2 lines): the counts are read under every tier lock, and reserve re-checks status, so the two cannot both pass. |
| 4 | `src/main/java/com/imin/iminapi/service/event/InventoryService.java` | Inject `EventRepository events` (constructor gains a parameter). In `reserve`, right after `findByIdForUpdate` (:82) and before the capacity check: `if (events.findActiveStatus(tier.getEventId()).orElse(null) != EventStatus.LIVE) throw ApiException.notFound("Event");`. Comment (1–2 lines): read after the tier lock, so an unpublish holding that lock has committed. Update `@throws` on `reserve` ("or its event is not live") and the class concurrency javadoc (:35-41). |
| 5 | `CLAUDE.md` (imin-api, Conventions → "Row locks" bullet, line 247) | Append one sentence: "Unpublish holds every tier lock of the event while it checks sold/reserved, and `reserve` re-reads the event status as a scalar under the tier lock." |
| 6 | `src/test/java/com/imin/iminapi/service/event/InventoryServiceTest.java` | Constructor gains a mocked `EventRepository`. `setUp` stubs `findActiveStatus(any())` → `Optional.of(LIVE)` (plain `mock()`, not strict). New tests are under Test impact. |
| 7 | `src/test/java/com/imin/iminapi/service/event/EventServiceTest.java` | `unpublish_blocked_when_any_tier_has_sold_tickets` (:259) stubs `tiers.existsSoldByEventId(id)` → true instead of the entity list. `unpublish_blocked_when_a_checkout_is_in_flight` (:298) stubs `existsReservedByEventId` → true. Both keep their existing asserts. Add the lock-order test below. |
| 8 | `src/test/java/com/imin/iminapi/payout/PayoutTestModeExclusionTest.java` | `newFreeOrdersCarryTheRunningKeyMode` (:273) calls the real `issueFreeOrder` on `endedEvent()`, which is `EventStatus.PAST` (:434). After the fix that is 404. Give that test a LIVE event: set status LIVE and save before the calls, or add a `liveEvent()` helper. Expected values are unchanged: `testMode` true, then false. |
| 9 | `src/test/java/com/imin/iminapi/service/event/UnpublishCheckoutRacePostgresTest.java` (new) | Postgres 17 Testcontainers race test, 3 orderings. Setup copied from `TierInventoryRacePostgresTest`: LIVE public event, two tiers inserted by SQL, `@AfterEach` cleanup by org. |

**Need no change (one line each):**
- `StripeCheckoutService`: the catch at :668-673 already rethrows a non-CONFLICT `ApiException`, so a 404 from `reserve` reaches the buyer unchanged.
- `StripePaymentIntentService`: goes through the same `reserveAndBuildMetadata` (:165).
- `FreeCheckoutService`: `issueFreeOrder` is `@Transactional`, so a 404 from `reserve` rolls back the whole order. The caller at StripeCheckoutService.java:317-322 rethrows non-409 as is.
- `PaidCheckoutService` / `StripeWebhookService`: fulfilment deliberately completes paid PIs (money moved). With the fix, no new hold can exist on a DRAFT event.
- `ReservationSweeper`, `InventoryService.releaseReservation` / `confirmSold`: they take the tier lock and never write the event row, so the lock order is unaffected.
- `RefundService`: locks tiers in UUID order only (:442-456) and never writes the event row.
- `TicketTierService`: `create`, `patch` and `delete` already lock event then tier (`loadOwnedEvent` :267-273, `lockForWrite` :168,189), the same order as the new unpublish.
- `EventServiceAuditIntegrationTest`: Mockito mocks; the new boolean queries default to false, so the unpublish happy path still passes. `findByEventIdOrderBySortOrderAsc` is still read by `detail()`. It reads no fixture this plan changes.
- `TierInventoryRacePostgresTest`, `InventoryConcurrencyTest`, `FreeCheckoutConcurrencyTest`, `AdsConsentWriteTest`, `MarketingOptInWriteTest`, `NeverSoftOptInGuardTest`: each builds exactly one event and sets it LIVE (grep: one `new Event()` and one `setStatus(EventStatus` per file). Reserve still passes.
- `EventStatusRevertPostgresTest`: its unpublish tests still pass. Unpublish now also takes the uncontended tier lock, and the PAST case 409s before reaching it.
- `FreeCheckoutIdempotencyTest`, `StripeCheckoutServiceTest`: `InventoryService` is a mock there (:116, :86).

## Ordered steps
1. **Repositories.** Add `TicketTierRepository.findIdsByEventId`, `existsSoldByEventId`, `existsReservedByEventId` and `EventRepository.findActiveStatus` as specified in Affected files #1–#2.
2. **Reserve re-check.** In `InventoryService.reserve`, after `tiers.findByIdForUpdate(tierId)` and before computing `available`, call `events.findActiveStatus(tier.getEventId())`. Anything other than `Optional.of(LIVE)` throws `ApiException.notFound("Event")` before any `tiers.save` or `reservations.save`.
 - This one guard covers all three entry paths in the Goal table: hosted (:667), native (via :165 → :667) and free (FreeCheckoutService.java:156).
 - No scheduler, reconciler or admin endpoint creates a hold. The `.reserve(` grep over `src/main/java` finds only those two call sites. So no other path needs the guard.
3. **Unpublish locks.** In `EventService.unpublish`:
 1. `loadOwnedForWrite` (unchanged).
 2. The `!= LIVE` → 409 check (unchanged).
 3. `tierService.lockForWrite(e.getId(), tiers.findIdsByEventId(e.getId()))`.
    - `lockForWrite` is `Propagation.MANDATORY` and joins `unpublish`'s `@Transactional`.
    - It sorts the ids by UUID (TicketTierService.java:222-225), the order RefundService uses.
    - The id list is complete. Any tier insert or delete holds the event lock (TicketTierService.java:269), which unpublish already holds.
 4. `existsSoldByEventId` → existing 409 sold message.
 5. `existsReservedByEventId` → existing 409 checkout message.
 6. Set DRAFT and save (unchanged).
4. **Comments.** Update the comment and javadocs named in Affected files #3–#4: 1–2 lines each, no ticket ids. That includes rewording the "events-19" mention in the unpublish comment so it carries no id.
5. **Docs.** Add the CLAUDE.md row-locks sentence (#5).
6. **Unit tests.** Update and add tests in `InventoryServiceTest`, `EventServiceTest` and `PayoutTestModeExclusionTest` (Test impact).
7. **Postgres race test.** Add `UnpublishCheckoutRacePostgresTest` (Test impact).
8. **Guard proofs.** For each:
 1. Remove the guard line.
 2. Run the named tests and confirm they go red.
 3. Restore the line.
 4. Record the red test names in the commit message.

 The guards and the tests each one must turn red:

 | Removed guard | Tests that must go red |
 |---|---|
 | (a) The `findActiveStatus` check in `reserve` | `reserve_refusesWhenEventNotLive` (all 3 cases), `reserve_refusesWhenEventDeletedOrMissing`, `unpublishHoldsTiers_reserveWaitsThenIsRefused` |
 | (b) The `lockForWrite` call in `unpublish` | `unpublish_locksEveryTierAfterTheEventAndBeforeCounting`, `reserveHoldsSecondTier_unpublishWaitsThen409CheckoutInProgress`, `freeOrderHoldsTier_unpublishWaitsThen409Sold`, `unpublishHoldsTiers_reserveWaitsThenIsRefused` (reserve no longer waits, reads uncommitted-invisible LIVE and succeeds) |
 | (c) Move the status read **above** `findByIdForUpdate` | `reserve_readsEventStatusOnlyAfterTheTierLock` (InOrder) and `unpublishHoldsTiers_reserveWaitsThenIsRefused` |
9. **Gates.** Confirm Docker is up, then run the full gate (Verification commands). A run that reports skipped Testcontainers tests is red.

## Verification commands
- **Docker check:** `docker info > /dev/null`. This must succeed before any api test run. Testcontainers tests skip silently without Docker (`disabledWithoutDocker = true`).
- **Baseline first, on untouched `origin/master`:** Run the gate once before editing:
`/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test`
- **Targeted loop** (comma-separated selector; judge only by this run's own `Tests run:` line):
`/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=InventoryServiceTest,EventServiceTest,UnpublishCheckoutRacePostgresTest,PayoutTestModeExclusionTest,TierInventoryRacePostgresTest,EventStatusRevertPostgresTest,FreeCheckoutConcurrencyTest,InventoryConcurrencyTest,EventServiceAuditIntegrationTest`
- **Full gate (repo check command):** `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test`
- **Skipped check:** `grep -l 'skipped="[1-9]' target/surefire-reports/TEST-*PostgresTest.xml` must print nothing.

## Test impact
**Branches of the changed logic:**

| Logic | Branches |
|---|---|
| `reserve` (new) | B1 status LIVE → proceeds (existing happy-path tests); B2 DRAFT / PAST / CANCELLED → 404; B3 deleted or missing (`Optional.empty`) → 404; B4 the status read happens after the tier lock |
| `unpublish` | U1 not LIVE → 409 (existing); U2 sold → 409; U3 reserved → 409; U4 none → DRAFT; U5 tier locks taken after the event lock and before the counts, for every tier of the event |

**`InventoryServiceTest`** (Mockito, edited):
- `reserve_refusesWhenEventNotLive`: `@ParameterizedTest` over `EventStatus` DRAFT, PAST, CANCELLED (B2). Asserts status 404, code `NOT_FOUND`, message `"Event not found"`, `tiers.save` never called, `reservations.save` never called.
- `reserve_refusesWhenEventDeletedOrMissing`: `findActiveStatus` → `Optional.empty()` (B3). Same asserts.
- `reserve_readsEventStatusOnlyAfterTheTierLock` (B4): `InOrder(tiers, events)` verifies `findByIdForUpdate` and then `findActiveStatus(t.getEventId())`.
- Existing `reserve_*` tests stay as they are and cover B1 through the `setUp` LIVE stub. `reserve_insufficient_throwsConflictWithFields` still sees LIVE, so the 409 fields are unchanged.

**`EventServiceTest`** (Mockito, edited):
- `unpublish_blocked_when_any_tier_has_sold_tickets` covers U2 with the `existsSoldByEventId` stub. `unpublish_blocked_when_a_checkout_is_in_flight` covers U3 with the `existsReservedByEventId` stub. Existing asserts kept: code `INVALID_STATE`, message contains "checkout", `events.save` never called.
- `unpublish_live_with_no_sold_tickets_transitions_to_draft` covers U4 (unchanged; the booleans default to false).
- New `unpublish_locksEveryTierAfterTheEventAndBeforeCounting` (U5):
- Stub `findIdsByEventId` → `[idB, idA]`.
- `InOrder(events, tierService, tiers)` verifies `lockActiveForWrite`, then `tierService.lockForWrite(eventId, [idB, idA])` (sorting is `lockForWrite`'s job), then `existsSoldByEventId`, then `existsReservedByEventId`.

**`PayoutTestModeExclusionTest`** (edited): `newFreeOrdersCarryTheRunningKeyMode` runs on a LIVE event. Expected values are unchanged.

**`UnpublishCheckoutRacePostgresTest`** (new):
- **Setup:** `@SpringBootTest`, Postgres 17 Testcontainers, `@MockitoBean StripeProductService`. LIVE public event, tiers A and B inserted by SQL with B's id greater than A's.
- **Calls:** every call goes through Spring beans (`eventService.unpublish`, `inventoryService.reserve`, `freeCheckoutService.issueFreeOrder`), never through a method called directly on an instance.
- **Concurrency harness:** each test is `@Timeout(60)` and uses 2-thread pools, latches waited up to 15 s, and `Thread.sleep(500)` before each `isDone()` "waits" assertion.

The three tests:
1. **`unpublishHoldsTiers_reserveWaitsThenIsRefused`** (ordering A, reserve loses):
 - Thread 1, in a `TransactionTemplate`: `eventService.unpublish(...)`, signal, wait for release.
 - Thread 2, in a `TransactionTemplate`: `Event cached = events.findById(eventId)`, which reads LIVE because the DRAFT is not committed yet. Then `inventoryService.reserve(tierA, 2, …)`.
 - Assert thread 2 is not done after 500 ms (it waits on the tier lock).
 - Release. Assert thread 2 failed with `ApiException` 404 `NOT_FOUND`, and `cached.getStatus() == LIVE`: the caller's entity is stale, so the refusal came from the scalar read.
 - Assert, by JDBC: `status = 'DRAFT'`, tier A `reserved = 0`, and 0 HELD rows on tier A.
2. **`reserveHoldsSecondTier_unpublishWaitsThen409CheckoutInProgress`** (ordering B, unpublish loses; also proves every tier is locked, not only the first):
 - Thread 1, in a tx: `reserve(tierB, 2, …)`, signal, wait for release.
 - Thread 2: `unpublish`. Assert not done after 500 ms. Release.
 - Assert thread 2's `ApiException`: 409 `INVALID_STATE` with message exactly `"Cannot unpublish: a checkout is in progress. Try again once the checkout session expires."`.
 - Assert, by JDBC: `status = 'LIVE'`, tier B `reserved = 2`, 1 HELD row.
3. **`freeOrderHoldsTier_unpublishWaitsThen409Sold`** (free entry path; also proves the free path and unpublish do not deadlock):
 - The order INSERT takes FOR KEY SHARE on `events` while unpublish holds the no-op-UPDATE lock. A deadlock would surface as an exception from Postgres's deadlock detector (default `deadlock_timeout` 1 s) and fail the test.
 - Thread 1, in a tx: `freeCheckoutService.issueFreeOrder(eventFromRepo, tierA(price 0), 1, "race@example.test", null, false, false, CheckoutAttribution.NONE, null)`, signal, wait.
 - Thread 2: `unpublish`. Assert not done after 500 ms. Release. Assert thread 1 completed.
 - Assert thread 2's 409 message is exactly `"Cannot unpublish: tickets have been sold. Cancel the event and refund buyers first."`.
 - Assert, by JDBC: `status = 'LIVE'`, tier A `sold = 1`, 1 order row for the event.
 - `@AfterEach` also deletes tickets, orders, ticket_reservations, milestones, audit_logs, tiers, events, users and org, scoped by org. Tear down in FK order and copy the pattern in TierInventoryRacePostgresTest.java:145-155, extended with `tickets` and `orders`.

## Live-test
After `/ship-imin` deploys api (Railway, about 3 minutes):
1. On a disposable prod test org (see OPEN_QUESTIONS), with a LIVE free event:
 - Send `POST /api/v1/public/events/{id}/checkout` with a free tier → expect 200 `kind: "order"`. This proves the new re-check does not refuse a LIVE event.
2. Unpublish that event from the dashboard:
 - It now has sold > 0, so expect the existing 409 "tickets have been sold".
 - Unpublish a second LIVE test event with no sales → 200 DRAFT.
3. Send `POST /api/v1/public/events/{draftId}/checkout` → 404 `NOT_FOUND`, the same envelope as before.

The race itself cannot be timed by hand in prod. The Postgres tests are the evidence for it.

## Contract impact
None. There is no new path, schema or field. `reserve` refusing a non-LIVE event answers the existing 404 `NOT_FOUND` `"Event not found"` envelope, which every checkout path already returns for a draft event (`findPublic`, EventRepository.java:120-131). imin-public already treats a checkout 404 as "event/tier unbuyable" (imin-public `lib/api/public-events.ts:253`). No OpenAPI marker, no `types.ts` change, no `PUBLIC_PAGE_API.md` change.

## i18n impact
None. No string is added or changed.

**Copy ledger** (strings reused on a path that now reaches them differently):

| String | Field(s) behind it | Meaning, scope, range | Rendered next to it |
|---|---|---|---|
| "Cannot unpublish: tickets have been sold. Cancel the event and refund buyers first." | `TicketTierRepository.existsSoldByEventId` (new), called from `EventService.unpublish` (replacing the check at EventService.java:369-371) | Boolean, per event, across all tiers: some tier has `sold > 0`, read under every tier lock of the event, so it includes a free order committed while unpublish waited. Exact, not approximate. | Webapp toasts the raw `err.message` (imin-webapp `src/features/events/EventDetailPage.tsx:117-119`, English only, already the case before this change). |
| "Cannot unpublish: a checkout is in progress. Try again once the checkout session expires." | `TicketTierRepository.existsReservedByEventId` (new), called from `EventService.unpublish` (replacing the check at EventService.java:377-381) | Boolean, per event: some tier has `reserved > 0` (HELD hosted or native holds), read under the tier locks. "Checkout session expires" is accurate for hosted; a native hold expires through the sweeper at the same TTL (StripeCheckoutService.java:662). | Same toast as above. |
| "Event not found" (404 `NOT_FOUND`) | `EventRepository.findActiveStatus` (new), called from `InventoryService.reserve` | Scalar status of the tier's event; anything other than LIVE and not deleted refuses. The message is the same one `findPublic` already gives for a draft event. | imin-public maps a checkout 404 to its own unbuyable copy (`lib/api/public-events.ts:253`); the server string is not shown verbatim. |

## Blast radius
- **Money path (checkout):** `InventoryService.reserve` is shared by hosted, native and free checkout. The change adds one primary-key scalar SELECT under a tier lock checkout already holds, and adds a refusal before any write or Stripe call. Failure mode if wrong: every checkout 404s. Covered by:
- the LIVE happy path in existing integration tests that use the real bean: `FreeCheckoutConcurrencyTest`, `InventoryConcurrencyTest`, `TierInventoryRacePostgresTest`, `AdsConsentWriteTest`, `MarketingOptInWriteTest`, `NeverSoftOptInGuardTest`;
- the live-test step 1.
- **LIVE→PAST sweep:** a reserve racing it is now refused. That matches `loadBuyableTier`, which already refuses PAST.
- **Organizer path:** `unpublish` now also row-locks the event's tiers until commit. A concurrent buyer of that event waits for the length of one unpublish transaction (milliseconds; there is no remote I/O inside it). Lock order event → tiers (UUID) matches `EventService.patch` (:259) and `TicketTierService` (:168,189). Checkout never locks the event row, so no cycle is possible.
- **Writers of the same rows:** no new writer. Unpublish still writes the event with the existing full save under the event lock; tiers are only locked, not written. Reserve only adds a read.
- **Webhook, sweeper and refund paths:** unchanged; they never write the event row while holding a tier lock (verified by grepping `events.save` / `lockActiveForWrite` under `stripe/`, `refund/` and `service/ticket/`, with no hits).
- **No Flyway migration, no `/api/v1` contract change, no shared module, no auth or Stripe call change.**

## Risks
- **Fail-closed regression:** a wrong predicate would 404 every checkout. Mitigations: the predicate is exactly LIVE and not deleted (`EventStatus` has only DRAFT, LIVE, PAST and CANCELLED, and `priceIt` already lets only LIVE through), the live-test smoke, and the LIVE-path integration suites.
- **H2 vs Postgres:** the lock-ordering behaviour is proven on Postgres only. The H2 suites exercise the code paths, not the waits.
- **Residual edge, not fixed:** a hosted session paid right at expiry whose webhook lands after the sweeper released the hold. Unpublish can pass in between, and the late `payment_intent.succeeded` takes the documented `[OVERSOLD]` path (InventoryService.java:251-273) and issues an order on the DRAFT event. This exists today, needs Stripe to accept a payment at the expiry boundary, and the money has moved, so fulfilling is correct. It is monitored on the `[OVERSOLD]` log.
- **Visibility flip during a checkout:** not covered (open question).
- **Size:** 9 files, one concern. No split.

## Definition of done
- [ ] All four repository methods, the reserve re-check and the unpublish lock-and-count change are in place, with comments of 1–2 lines and no ticket or milestone ids.
- [ ] CLAUDE.md row-locks bullet updated.
- [ ] All three guard proofs in step 8 recorded (red without each guard).
- [ ] Docker up; the full `./mvnw test` gate is green through test-serial; no Postgres test skipped.
- [ ] Baseline gate result on untouched `origin/master` recorded.
- [ ] Live-test steps 1–3 recorded under Live-test evidence after deploy.

## Decisions (main session)
- Plan accepted as written.
- OPEN_QUESTION 1: no known disposable prod org. Live-test is limited to step 3: a draft event's checkout returns 404, and a known LIVE free event's checkout still prices without error, through the public GET. Steps 1–2 stay with Ivan.
- OPEN_QUESTION 2 (visibility flip during checkout): out of scope. Not queued: a PRIVATE event is still sellable by link, so a sale after the flip is not wrong.
- Never use git stash in this worktree.

## Live-test evidence

## Review rounds

### Implement, 2026-10-02: guard proofs
Each guard was removed in the worktree, the named tests run, and the file restored from a backup (`cmp` byte-exact).

| Removed guard | Run | Red |
|---|---|---|
| (a) `findActiveStatus` check in `reserve` | `-Dtest=InventoryServiceTest,UnpublishCheckoutRacePostgresTest`: 29 run, 6 failures | `reserve_refusesWhenEventNotLive` [DRAFT, PAST, CANCELLED], `reserve_refusesWhenEventDeletedOrMissing`, `reserve_readsEventStatusOnlyAfterTheTierLock`, `unpublishHoldsTiers_reserveWaitsThenIsRefused` (:171, reserve succeeded on the DRAFT event) |
| (b) `tierService.lockForWrite` in `unpublish` | `-Dtest=EventServiceTest,UnpublishCheckoutRacePostgresTest`: 47 run, 4 failures | `unpublish_locksEveryTierAfterTheEventAndBeforeCounting`, `reserveHoldsSecondTier_unpublishWaitsThen409CheckoutInProgress` (:208 "unpublish waits"), `freeOrderHoldsTier_unpublishWaitsThen409Sold` (:243 "unpublish waits"), `unpublishHoldsTiers_reserveWaitsThenIsRefused` (:167 "reserve waits") |
| (c) status read moved above `findByIdForUpdate` (event id taken from a plain `findById`) | `-Dtest=InventoryServiceTest,UnpublishCheckoutRacePostgresTest`: 29 run, 6 failures | `reserve_readsEventStatusOnlyAfterTheTierLock` (InOrder), `unpublishHoldsTiers_reserveWaitsThenIsRefused` (:171, reserve waited, then succeeded on the stale LIVE read). The 4 refusal tests also went red, from the mutation's own `findById` stub gap, not from ordering. |
