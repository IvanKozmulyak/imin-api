# Organizer tier saves never overwrite held or sold tickets (api)
tier-inventory-lost-update · Subagent · Notion: (card id not supplied)

## Goal and scope
Goal: no organizer write to a `ticket_tiers` row can write back `reserved`/`sold` values that are older than what checkout, the webhooks, the sweeper or a refund committed meanwhile. A quantity cut or a delete is also checked against the counters as they are at the moment of the write.

Facts (worktree on origin/master 8b193182):
- **No concurrency control on the entity.** `TicketTier` has no `@Version`, no `@DynamicUpdate`, and no `updatable = false` on `quantity`, `sold` or `reserved` (`model/TicketTier.java:10-61`). Every save is a full-row UPDATE.
- **Inventory writers lock the row.** They take `SELECT … FOR UPDATE` (`TicketTierRepository.java:71-72`) before reading:
- reserve: `InventoryService.java:81-91`
- release: `:137-156`
- confirm: `:223-266`
- refund: `RefundService.java:443-456`, which sorts by `UUID.compareTo` through `Map.Entry.comparingByKey()`.
- **Organizer writers do not lock the tier.** They take only the event lock (`TicketTierService.java:252-258`, `EventService.java:408-411`), then:
- `patch`: `TicketTierService.java:165` loads with `findByIdAndEventId`, `:167` validates against those counts, `:171` saves.
- `delete`: `:185` loads, `:186,197` checks `sold`/`reserved`, `:206` deletes. `ticket_reservations` cascades on delete, per the comment at `:191-195`.
- `reconcileEmbedded`, update branch: `:232` loads, `:236` validates, `:239` saves. It is reached only from `EventService.patch:272-274`.
- **The quantity check reads the same unlocked counts.** `TicketTierValidator.java:115-123` compares the new quantity with `existing.getSold() + existing.getReserved()`.
- **No outer persistence context.** Open-in-view is off (`application.yaml:11`). Checkout is not `@Transactional` (`StripeCheckoutService.java:204`). So every inventory transaction loads a fresh entity under its lock.
- **Today's checkout inline sync is another unlocked writer.** `StripeCheckoutService.java:823` calls `StripeProductService.syncTier`, which calls `tiers.save(tier)` (`StripeProductService.java:91`). That merges the detached copy `priceIt` loaded before `reserve` (`StripeCheckoutService.java:566`, `:667`), which writes the old `reserved` back. Part A of stripe-sync-outside-lock (`fix/stripe-sync-outside-lock`, not merged) removes that save. It makes `stripe_product_id`/`stripe_price_id` `updatable = false` and writes them only through `updateStripeIdsIfPriceUnchanged`. This plan rebases onto part A and does not touch those two columns.

**Money worked example: the oversell (no formula changes).**
- Start: quantity 10, sold 8, reserved 0.
- The organizer loads the tier (reserved 0).
- Buyer A reserves 2 and commits: reserved 2.
- The organizer's rename commits and writes reserved 0.
- Buyer B reserves 2. Available is 10 − 0 − 8 = 2, so it succeeds: reserved 2.
- Both pay:
- The first `confirmSold` (`InventoryService.java:234-241`) decrements by min(2, 2) = 2: reserved 0, sold 10.
- The second decrements by min(0, 2) = 0 and logs drift: sold 12.
- Result: 12 sold against a capacity of 10.

**Lost seats.**
- Start: quantity 10, reserved 2 (hold H).
- The organizer loads (reserved 2).
- H expires and is released: reserved 0.
- The organizer's save writes reserved 2.
- H is now RELEASED, and the sweeper only handles HELD rows (`ReservationSweeper.java:73`). So 2 seats stay unsellable for good.

**Stale quantity check.**
- Start: quantity 10, sold 7. A buyer is holding 2, not yet committed.
- The organizer cuts quantity to 8. The check reads 7 committed, so it passes.
- Result: quantity 8 against 9 committed seats.

Decision: option (a). All organizer paths that update or delete an existing tier row-lock it before loading it. The lock returns only the id (scalar), and a normal load follows. This keeps clear of the first-level-cache trap in the api CLAUDE.md "Row locks" rule. In all three paths the lock comes before any tier load in the transaction: in `TicketTierService` nothing before it loads anything but the event, and in `EventService.patch` it sits before `applyPatch`, which reads tiers only through the count query `existsSyncedStripePrice` (`EventService.java:502`). Precedent for the scalar lock: `UserRepository.java:28-29`, `select id from users where id = :id for update`, returning `Optional<UUID>`.

Rejected:
- **(b)** `reserved`/`sold` `updatable = false`, with targeted UPDATEs for all inventory writes:
- It rewrites 5 money-path writes (`InventoryService` ×4, `RefundService` ×1).
- 30 test files touch `setSold`/`setReserved`, and seed-then-update tests would silently stop persisting.
- A quantity cut or a delete would still need the lock.
- **(c)** `@DynamicUpdate`:
- It does not fix the stale quantity check or the delete check.
- It overlaps the lock, so removing the lock would not turn a rename-race test red. That breaks the guard proofs.

Out of scope:
- `EventService.unpublish` reads counters without the tier lock (`:364-376`). It writes no tier, and the gap is that checkout never re-checks event status (OPEN_QUESTION 2).
- Moving the Stripe sync after commit (part B of stripe-sync-outside-lock).
- Repairing drift that already exists in prod.

## Repos in ship order
1. `api` (imin-api, base `master`). This is the only repo. Ship after stripe-sync-outside-lock part A is on origin/master.

## Affected files (per repo)
imin-api (worktree `/Users/ivan/imin/imin-api/.claude/worktrees/tier-inventory-lost-update`). 9 files:

| # | File | Change |
|---|---|---|
| 1 | `src/main/java/com/imin/iminapi/repository/TicketTierRepository.java` | Add `@Query(value = "SELECT id FROM ticket_tiers WHERE id = :id AND event_id = :eventId FOR UPDATE", nativeQuery = true) Optional<UUID> lockForWrite(@Param("id") UUID id, @Param("eventId") UUID eventId);`. Javadoc (2 lines): row-locks one tier of the event until commit; returns only the id, so no cached entity is mistaken for a fresh one. Plain `FOR UPDATE` works on both H2 and Postgres. |
| 2 | `src/main/java/com/imin/iminapi/service/event/TicketTierService.java` | (i) New `@Transactional public void lockForWrite(UUID eventId, Collection<UUID> tierIds)`: `tierIds.stream().filter(Objects::nonNull).sorted().forEach(id -> tiers.lockForWrite(id, eventId))`. Javadoc: "Row-locks these tiers in UUID order (RefundService's order) before anything loads them, so a full save cannot write back reserved/sold that changed meanwhile." (ii) `patch`: call `lockForWrite(eventId, List.of(tierId))` between `loadOwnedEvent` (164) and `loadOwnedTier` (165). (iii) `delete`: the same between 184 and 185. (iv) `reconcileEmbedded` javadoc (213-216): add "Caller must already hold the tier locks via lockForWrite (EventService.patch does)." `create` is unchanged: it inserts a row no other transaction can see until commit. |
| 3 | `src/main/java/com/imin/iminapi/service/event/EventService.java` | In `patch`, after `ifMatch.requireMatch` (256) and before `applyPatch` (258): `if (body != null && body.tiers() != null) tierService.lockForWrite(e.getId(), body.tiers().stream().map(TicketTierEmbeddedPatch::id).toList());`. Comment (1 line): "Tier locks before the event flush: a slug change takes a lock that blocks a checkout already holding a tier." (`Stream.toList` allows nulls; the helper filters them.) |
| 4 | `src/main/java/com/imin/iminapi/service/event/InventoryService.java` | Docs only, class javadoc 35-40: add one sentence saying organizer tier writes take the same lock (`TicketTierService.lockForWrite`) before loading. |
| 5 | `src/main/java/com/imin/iminapi/refund/RefundService.java` | Comment only at 443-444: "UUID.compareTo order; TicketTierService.lockForWrite locks in the same order." |
| 6 | `CLAUDE.md` (imin-api) | Extend the Conventions "Row locks" bullet (line 247) with one sentence: every writer of a `ticket_tiers` row holds the tier row lock from read to commit, and organizer paths take it through `TicketTierService.lockForWrite` (UUID order) before loading. |
| 7 | `src/test/java/com/imin/iminapi/service/event/TicketTierServiceTest.java` | Add U1–U3. Existing tests need no edit: the unstubbed `tiers.lockForWrite` returns `Optional.empty()` by Mockito default, the code ignores the result, and no test in the file uses `verifyNoMoreInteractions` (grepped). |
| 8 | `src/test/java/com/imin/iminapi/service/event/EventServiceTest.java` | Add U4. `patch_skips_reconcileEmbedded_when_tiers_null` (515, `verifyNoInteractions(tierService)`) stays green, because the lock call sits inside the `tiers != null` branch. The embedded-create test (494) uses only `verify(...)`, which tolerates the extra call. |
| 9 | `src/test/java/com/imin/iminapi/service/event/TierInventoryRacePostgresTest.java` (new) | P1–P10 on Postgres 17 Testcontainers. Harness copied from `EventStatusRevertPostgresTest.java:72-106`: `@SpringBootTest`, `@Testcontainers(disabledWithoutDocker = true)`, `@MockitoBean StripeProductService` (used as the pause point), `@DynamicPropertySource`. `@AfterEach` deletes ticket_reservations, ticket_tier_milestones, audit_logs, ticket_tiers, events, users and the org by org id. Fixtures are dated from `Instant.now()`. |

Files that read the changed code but need no edit:
- `EventStatusRevertPostgresTest.java`: its TIER_PATCH/TIER_DELETE writers now also take the tier lock after the event lock. The sweep never locks tiers, so the assertions are unaffected.
- `EventTierControllerTest`, `CrossOrgScopingTest`, `DashboardCacheTest`, `EventServiceAuditIntegrationTest`, `EventSlugConflictTest` (H2, real repositories): they now run the native lock on H2. The `UserRepository.java:28-29` precedent shows H2 accepts plain `FOR UPDATE`.
- `InventoryConcurrencyTest`: inventory code is unchanged.
- `StripeCheckoutServiceTest`, `FreeCheckoutIdempotencyTest`: they never reach organizer paths.

## Ordered steps
1. Wait until stripe-sync-outside-lock part A is on origin/master. Then `git fetch` and `git rebase origin/master` in this worktree. Expect a trivial `TicketTierRepository.java` conflict at the end of the interface; keep both methods. Confirm afterwards that `StripeProductService` no longer calls `tiers.save` (`grep -n "tiers.save" src/main/java/com/imin/iminapi/stripe/StripeProductService.java` → 0 hits).
2. Run `docker info`. Then run the full gate on the untouched worktree and record the `Tests run:` totals and 0 skipped. If the base is red, stop and report.
3. File 1: the repository lock.
4. File 2: `lockForWrite` and the two call sites in `patch`/`delete`, plus the `reconcileEmbedded` javadoc. Comments 1–2 lines, no ids.
5. File 3: the early lock in `EventService.patch`, before `applyPatch` and the event flush (262-265).
6. Files 4–6: docs and comments.
7. Tests U1–U4 and P1–P10. Every Postgres test:
 - runs both sides through Spring beans (`TicketTierService`, `EventService`, `InventoryService`, `TicketTierRepository`), with `TransactionTemplate` used only as the outer "holder" transaction;
 - carries `@Timeout(60)`, with every `Future.get` at 10 s and every latch `await` at 15 s;
 - releases its latch in `finally` and calls `pool.shutdownNow()` then `awaitTermination(15 s)` there.
8. Guard proofs (table under Test impact): remove or move each guard once, confirm the named tests go red, then restore. For P7: if it stays green with the delete lock removed, Hibernate flushed the DELETE before the pause. In that case, pause with a `@MockitoSpyBean AuditLogger` whose `record` blocks on the latch (`delete` calls it after `tiers.delete`, `TicketTierService.java:208`) and repeat the proof.
9. Run the full gate. Read each targeted run's own `Tests run:` line and confirm 0 skipped. Re-run the full gate after any rebase.

## Verification commands
From `/Users/ivan/imin/imin-api/.claude/worktrees/tier-inventory-lost-update`:
- `docker info` must succeed first. A run that skips the Testcontainers tests counts as red.
- Targeted (comma-separated): `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=TierInventoryRacePostgresTest,TicketTierServiceTest,EventServiceTest,EventStatusRevertPostgresTest,InventoryConcurrencyTest,EventTierControllerTest,EventSlugConflictTest,CrossOrgScopingTest`
- Full gate: `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test`

## Test impact
Branches of the changed logic:

| B | Branch |
|---|---|
| B1 | `TicketTierService.patch` locks the tier after the event lock and before the load |
| B2 | `TicketTierService.delete` locks the tier after the event lock and before the load |
| B3 | `lockForWrite` skips null ids (embedded creates) and locks the rest one by one in `UUID.compareTo` order |
| B4 | `EventService.patch` with `tiers != null` locks every embedded id before `applyPatch` and the event flush |
| B5 | `EventService.patch` with `tiers == null` makes no tier call (existing test 515) |
| B6 | A quantity cut or delete check reads counters committed by a transaction that held the lock first |
| B7 | A tier id not under the event: the lock matches 0 rows and the existing 400 / 404 stands (existing tests `reconcileEmbedded_rejects_id_pointing_to_other_event_with_400`, `patch_returns_404_when_tier_not_under_event`, `delete_returns_404_when_tier_not_found`) |

`create` and the embedded create branch take no lock: they insert a row no other transaction can see until commit. There is no race, so there is no ordering test.

New tests (14: U1–U4 unit, P1–P10 Postgres):

| # | File | Test | Setup → assertion | Covers |
|---|---|---|---|---|
| U1 | TicketTierServiceTest | `patch_locksTheTierBeforeLoadingIt` | InOrder(events, tiers): `lockActiveForWrite` → `tiers.lockForWrite(tierId, eventId)` → `findByIdAndEventId` | B1 |
| U2 | TicketTierServiceTest | `delete_locksTheTierBeforeLoadingIt` | same InOrder through `delete` | B2 |
| U3 | TicketTierServiceTest | `lockForWrite_locksNonNullIdsInUuidOrder` | ids `a = 10000000-0000-4000-8000-000000000001` and `b = f0000000-0000-4000-8000-000000000001`, passed as `[a, null, b]` → InOrder `lockForWrite(b)`, `lockForWrite(a)`, times(2) in total. (`b` sorts first in Java, because its high bits are a negative long.) | B3 |
| U4 | EventServiceTest | `patch_locksEmbeddedTiersBeforeTheEventFlush` | body tiers `[existing id, create]` → InOrder(tierService, events): `lockForWrite(eventId, [id, null])` → `events.flush()` → `reconcileEmbedded` | B4 |
| P1 | TierInventoryRacePostgresTest | `tierPatch_organizerHoldsTier_reserveWaitsAndItsHoldSurvives` | Tier: quantity 10, sold 0. The `syncTier` mock blocks on a latch. A = `tierService.patch(name "Renamed")`, waiting in `syncTier`. B = `inventoryService.reserve(tier, 2, now + 30 min, null)` is not done after 500 ms. Release. Expect reserved 2, name "Renamed", 1 HELD reservation. | B1 |
| P2 | same | `tierPatch_reserveHoldsTier_organizerWaitsAndKeepsTheHold` | B = `TransactionTemplate { reserve 2; latch }`. A = rename, not done after 500 ms. Release B. Expect reserved 2, name "Renamed". | B1, B6 |
| P3 | same | `tierPatch_quantityCutBelowConcurrentHold_refusedWithLockedCount` | Tier: quantity 10, sold 7. B holds reserve 2 uncommitted. A = patch quantity 8. Release B. A fails with `ApiException` 400 `INVALID_REQUEST`, errors `quantity = "must be ≥ sold + checkouts in progress (9)"`. Row stays quantity 10, sold 7, reserved 2. (7 + 2 = 9 > 8.) | B6 |
| P4 | same | `embeddedPatch_organizerHoldsTier_reserveWaitsAndItsHoldSurvives` | As P1, through `eventService.patch(p, eventId, null, body with tiers [{id, name "Renamed"}])` | B4 |
| P5 | same | `embeddedPatch_reserveHoldsTier_organizerWaitsAndKeepsTheHold` | As P2, through `eventService.patch` | B4, B6 |
| P6 | same | `embeddedPatch_quantityCutBelowConcurrentHold_refused` | As P3, through `eventService.patch`. 400, same field message, row unchanged. | B4, B6 |
| P7 | same | `tierDelete_organizerHoldsTier_reserveFindsNoTierAndNoHoldIsLeft` | A = `TransactionTemplate { tierService.delete; latch }`. B = reserve, not done after 500 ms. Release. B fails with `ApiException` 404. `SELECT count(*) FROM ticket_reservations WHERE tier_id = ?` = 0, and the tier is gone. | B2 |
| P8 | same | `tierDelete_reserveHoldsTier_deleteRefused409` | B holds reserve 2 uncommitted. A = delete, not done after 500 ms. Release. A fails with 409 `INVALID_STATE` "Cannot delete a tier with checkouts in progress — disable it instead (set enabled=false)". Tier present, reserved 2, 1 HELD reservation. | B2, B6 |
| P9 | same | `embeddedPatchOfTwoTiers_lockedInRefundOrder_noDeadlock` | Two tiers inserted by JDBC with ids `a`/`b` as in U3. B = `TransactionTemplate { tiers.findByIdForUpdate(b); latch; tiers.findByIdForUpdate(a) }`, the same order as `RefundService.java:443-447`. A = `eventService.patch` with tiers `[a, b]` (body order = Postgres order). Start A, wait 500 ms, release B. Both complete with no `deadlock detected`, and both names change. | B3 |
| P10 | same | `slugChangeWithTierPatch_vsCheckoutHoldingTier_noDeadlock` | B = `TransactionTemplate { jdbc "SELECT id FROM ticket_tiers WHERE id=? FOR UPDATE"; latch; jdbc "SELECT id FROM events WHERE id=? FOR KEY SHARE" }`. That second query is the FK check that `orders.event_id` (`V24__orders_and_tickets.sql:15`) runs inside the webhook transaction, after `confirmSold` (`StripeWebhookService.java:488-497`). A = `eventService.patch(slug "new-…", tiers [{id, name}])`, where slug belongs to `uq_events_org_slug` (`V6__events.sql:36`). Start A, wait 500 ms, release B. Both complete, and slug and name are persisted. | B4 placement |

Count check: 3 (U1–U3) + 1 (U4) + 10 (P1–P10) = 14.

Guard proofs. Remove each guard once and expect the named tests to go red:

| Guard | Remove or move | Goes red |
|---|---|---|
| lock in `TicketTierService.patch` | delete the `lockForWrite` line | U1, P1, P2, P3 |
| lock in `TicketTierService.delete` | delete the line | U2, P7, P8 |
| early lock in `EventService.patch` | delete the line (`reconcileEmbedded` does not lock, so it is the only lock on that path) | U4, P4, P5, P6, P10 |
| placement before the event flush | move the call after `events.flush()` (265) | U4, P10 (deadlock) |
| `UUID.compareTo` sort | drop `.sorted()` | U3, P9 (deadlock) |
| null filter | drop `.filter(Objects::nonNull)` | U3 (NPE in sort) |

Existing tests that read changed code: listed under Affected files rows 7–8 and "need no edit". Grep of the fixtures confirms that none of them stub or verify `lockForWrite`, and none use `verifyNoMoreInteractions` on `tiers` or `tierService`.

## Live-test
`/live-test api` after deploy (prod Stripe is live; use a test org and free tiers only).
1. Read-only drift check before and after, through `railway psql`: `SELECT t.id, t.reserved, COALESCE(SUM(r.qty) FILTER (WHERE r.status = 'HELD'), 0) AS held FROM ticket_tiers t LEFT JOIN ticket_reservations r ON r.tier_id = t.id GROUP BY t.id, t.reserved HAVING t.reserved <> COALESCE(SUM(r.qty) FILTER (WHERE r.status = 'HELD'), 0);` Record the rows. Repairing them is not part of this task (OPEN_QUESTION 3).
2. On a test org draft event with a free tier (quantity 5):
 - rename the tier in the dashboard (standalone patch → 200);
 - autosave a tier change through the event editor (embedded → 200);
 - try to cut quantity below sold + held → 400 with the count;
 - delete an unused tier → 204.
3. Make a free checkout of 1 on the tier. Then `SELECT quantity, sold, reserved FROM ticket_tiers WHERE id = '<id>'` should show sold 1, reserved 0.
4. Logs from deploy through the test: no `deadlock detected`, no `[OVERSOLD]`.
5. Clean up the draft.

## Contract impact
none. No endpoint, schema or status code changes. The 400/409 bodies are the same strings, now computed from locked counts. `TicketTierDto` is unchanged. No OpenAPI marker, webapp `types.ts` edit or `PUBLIC_PAGE_API.md` change is needed.

## i18n impact
none. Server-side only. No string is added or edited; the strings in the copy ledger are reused unchanged and are EN API error text today.

Copy ledger (4 strings reused, none added):

| String | Field(s) behind it | Meaning, scope, range | Rendered next to it |
|---|---|---|---|
| "must be ≥ sold + checkouts in progress (N)" | `TicketTierValidator.java:116,120-121`: `existing.getSold() + existing.getReserved()` | Per tier. Exact integer ≥ 0, read after the tier row lock (after this change). Shown only when reserved > 0. | API 400 `errors.quantity`. The webapp shows it at the quantity field; not verified in this repo, and the FE is unchanged. |
| "must be ≥ sold (N)" | `TicketTierValidator.java:116,122`: `existing.getSold()` | Per tier. Exact integer ≥ 0, locked read. Shown when reserved = 0. | same |
| "Cannot delete a tier with sold tickets — disable it instead (set enabled=false)" | `TicketTierService.java:186-189`: `tier.getSold() > 0` | Per tier, locked read | API 409 message |
| "Cannot delete a tier with checkouts in progress — disable it instead (set enabled=false)" | `TicketTierService.java:197-201`: `tier.getReserved() > 0` | Per tier, locked read | API 409 message |

## Blast radius
Money: checkout inventory and oversell. Shared modules: `TicketTierService`, `EventService.patch`.

Every writer of a `ticket_tiers` row (12) and the columns it writes:

| # | Writer | Columns | Lock after this change |
|---|---|---|---|
| 1 | `TicketTierService.create` `:149` | INSERT of all columns (sold/reserved default 0) | none needed: new row |
| 2 | `TicketTierService.patch` `:171` | full UPDATE (Stripe ids excluded after part A) | tier lock before load (new) |
| 3 | `TicketTierService.delete` `:206` | DELETE, cascading reservations and milestones | tier lock before load (new) |
| 4 | `reconcileEmbedded` create `:228` | INSERT | none needed: new row |
| 5 | `reconcileEmbedded` update `:239` | full UPDATE | tier lock taken by `EventService.patch` (new) |
| 6 | `InventoryService.reserve` `:90-91` | full UPDATE (intends `reserved`) | already locked `:81` |
| 7 | `InventoryService.releaseReservation` `:155-156` | full UPDATE (`reserved`) | already locked `:137` |
| 8 | `confirmSold`, HELD branch `:240-242` | full UPDATE (`reserved`, `sold`) | already locked `:223` |
| 9 | `confirmSold`, RELEASED branch `:265-266` | full UPDATE (`sold`) | already locked `:223` |
| 10 | `RefundService.releaseInventoryAndMarkTickets` `:455-456` | full UPDATE (`sold`) | already locked `:447`, UUID order |
| 11 | `StripeProductService.doSync` `:89-91` | today: full merge; after part A: targeted UPDATE of the two Stripe ids only | part A; this plan depends on it |
| 12 | `scripts/stripe-live-cutover.sql:33-37,88-…` | raw ops SQL (Stripe ids; `reserved` re-derived) | one-off, run in a cutover window; no change |

Writers 6–10 write every column from the copy they read under the lock. That is safe only because, after this change, every other writer of `quantity`, `sold` and `reserved` holds the same lock from read to commit. Dirty-checking writers: grep of the setters (`setName`, `setPriceMinor`, `setQuantity`, `setSold`, `setReserved`, `setEnabled`, `setSortOrder`, `setSaleStartsAt`, `setSaleClosesAt`, `setStripe*`) on a tier finds only the files above.

Entry paths into the inventory state machine, all of which already lock through `InventoryService` or `RefundService`:
- hosted and native checkout: `StripeCheckoutService.java:667` (reached from `StripePaymentIntentService.java:165`);
- free checkout: `FreeCheckoutService.java:156-157`;
- webhooks: `StripeWebhookService.java:489,554,602,628,631,661,666`;
- sweeper: `ReservationSweeper.java:73`;
- fulfilment reconciler: `PaidFulfilmentReconciler.java:109`;
- the checkout rollback: `StripeCheckoutService.java:804`;
- `StripePaymentIntentService.java:335`;
- refunds: `RefundService.java:295`, reached from `createRefund`, the webhook handler and `RefundRequestService` approvals.

Organizer entry paths:
- `EventTierController.java:37,48,56` → `TicketTierService` create, patch, delete;
- `EventController.java:81` → `EventService.patch` → `reconcileEmbedded`, its only caller (grepped).

No scheduler or admin endpoint writes tiers.

Lock order:
- Organizer paths: event (`UPDATE … SET updatedAt = updatedAt`, `EventRepository.java:371-380`, which in Postgres is a FOR NO KEY UPDATE tuple lock) → tiers in UUID order.
- Checkout and webhooks: tier only, then the order insert's FK check (FOR KEY SHARE on the event). That is compatible with NO KEY UPDATE, so the two paths cannot deadlock.
- A slug change upgrades the event lock to FOR UPDATE at `events.flush()` (`EventService.java:265`). The tier locks are now taken before that flush, which removes an existing deadlock (P10).
- Refund vs a multi-tier organizer patch: both lock in `UUID.compareTo` order (P9). A SQL `ORDER BY id` would use Postgres byte order instead, which differs for ids with the high bit set.
- The sweep (`markLivePast`) and promo/media writes never lock tiers.

Readers of `sold`/`reserved` (dashboards, availability, milestones, predictor, marketing; 18 files by grep): only their accuracy improves. No payout or refund amount reads the tier counters.

## Risks
- **Tier lock held during the synchronous Stripe sync.** After part A, `syncTier` still runs inside the organizer transaction (`TicketTierService.java:174,240`). While an organizer saves a paid tier, buyers' reserve and confirm calls on that tier wait for the tier's 1–2 Stripe calls: under 1 s normally. In a Stripe stall it is up to 30 s connect / 80 s read per attempt × 3 attempts (stripe-java 32.1.0 `Stripe.java:9-10,29`; `StripeConfig.java:50` sets no overrides). Each waiting buyer holds one of 20 pooled connections (`application-prod.yaml:10`).
- Today the event lock already spans those calls.
- Today a multi-tier autosave already holds earlier tier rows locked across later tiers' Stripe calls, because the query in the next loop iteration flushes the earlier tier's UPDATE.
- Part B (sync after commit) removes this. Interim acceptance or a buyer-side `lock_timeout` is OPEN_QUESTION 1.
- **Dependency on part A.** Shipped without it, checkout's inline `syncTier` (`StripeCheckoutService.java:823` → `StripeProductService.java:91`) stays an unlocked writer that reverts `reserved`. Step 1 checks for it.
- **First-level cache.** The fix relies on no tier entity being loaded earlier in the same transaction. That holds on all three paths today, as the Goal and scope section explains. A future caller of `reconcileEmbedded` must call `lockForWrite` first; the javadoc says so.
- **Existing prod drift** is not repaired here. The live-test only reads it. A repair re-derives counters from HELD reservations, never by delta (api CLAUDE.md rule).
- **`unpublish`** still reads counters without the lock; it writes no tier (OPEN_QUESTION 2).
- **Size:** 9 files (6 main/docs, 3 tests), one concern. No split.

## Definition of done
- The base gate was green. After the change, `./mvnw test` passes through `test-serial.sh` with 0 skipped Testcontainers tests, and passes again after the rebase onto part A.
- All 14 new tests pass, and every guard in the guard table was removed or moved once and its tests went red.
- `grep -n "lockForWrite" src/main/java` shows the repository method, the `TicketTierService` helper and its 2 internal calls, and the single `EventService.patch` call.
- Javadocs and comments (rows 2–5) and the CLAUDE.md row-lock sentence are updated in the same change. Comments are 1–2 lines with no ticket or milestone ids.
- Live-test evidence is recorded below.

## Decisions (main session)
- Option (a) accepted. Ship strictly after stripe-sync-outside-lock part A is on origin/master (step 1).
- OPEN_QUESTION 1: accept the interim cost. The event lock already spans the Stripe calls today, and buyers wait only while an organizer saves that same paid tier. No buyer-side lock_timeout in this task. Part B (sync after commit, plus sweeper) is queued and removes the cost.
- OPEN_QUESTION 2 (unpublish reads the counters unlocked; checkout never re-checks event status): queued as its own card.
- OPEN_QUESTION 3: read-only check on prod 2026-10-02 returned 0 tiers whose reserved ≠ HELD sum and 0 tiers with sold > quantity, so no repair card is needed. Re-run the same query in the live-test.

## Live-test evidence

## Review rounds

### Implement round 1 (2026-10-02): guard proofs
Run in a scratch copy of the worktree (the worktree itself was never mutated), Postgres 17 Testcontainers, 0 skipped.

| Guard | Mutation | Red (reason) |
|---|---|---|
| lock in `TicketTierService.patch` | line removed | U1 (InOrder: lockForWrite not invoked); P1 (reserve not blocked); P2 (reserved expected 2, was 0); P3 (no exception, cut accepted) |
| lock in `TicketTierService.delete` | line removed | U2 (InOrder); P7 (reserve not blocked; AuditLogger fallback not needed); P8 (no 409, delete went through) |
| early lock in `EventService.patch` | block removed | U4 (lockForWrite not invoked); P4 (reserve not blocked); P5 (reserved expected 2, was 0); P6 (no exception); P10 (deadlock detected) |
| placement before the event flush | block moved after `events.flush()` | U4 (InOrder); P10 (deadlock detected) |
| `UUID.compareTo` sort | `.sorted()` dropped | U3 (InOrder); P9 (deadlock detected) |
| null filter | `.filter(Objects::nonNull)` dropped | U3 (NPE in sort) |

