# Event saves never revert the status (api)
event-status-revert · Subagent · Notion: not provided

## Goal and scope
`Event` (model/Event.java:12-228) has no `@Version` and no `@DynamicUpdate`. Hibernate's UPDATE for a managed entity therefore writes every updatable column, including `status` (Event.java:34-36). The twice-daily sweeper (`EventStatusSweeper.sweep`, EventStatusSweeper.java:55-66) runs one bulk `markLivePast` (EventRepository.java:404-414) that flips LIVE → PAST. Here is how the race goes:

- **Stale save after the sweep:** a request transaction loads the event (sees LIVE), the sweep commits PAST, and the request's flush then writes `status='LIVE'` back.
- **Sweep during the save:** the sweep runs between the load and the flush, before any write lock is held. The same thing happens.

Effects:
- A reverted LIVE comes back until the next tick, up to 12 h later. In that window the event shows as on sale and is excluded from PAST-only flows (TierAvailability, PublicTierEligibility, DateVerdictFeedbackService, NotifyReleaseSender, InviteOnPublishService all read `EventStatus.PAST`).
- `unpublish` has its own variant. It checks `status == LIVE` on a stale read (EventService.java:361) and writes DRAFT over PAST (EventService.java:378-380). The sweep never touches DRAFT, so that one is permanent.

**Fix:** every path that loads an Event and then saves it takes the existing row lock **before** it loads, exactly like `patch`/`publish` already do (`loadOwnedForWrite`, EventService.java:407-411).

**Why this works (Postgres READ COMMITTED):**
- **Sweep first:** the writer's lock UPDATE waits for the sweep to commit. Its following SELECT then reads PAST. Save paths keep PAST, and unpublish answers 409.
- **Writer first:** the sweep's UPDATE waits on the writer's row lock. After the writer commits, the sweep re-checks `status = LIVE AND ends_at < now` on the new row version and still marks it PAST.

**Approach choice:**
- **(a) lock before load — chosen.** One line per helper, the same mechanism the codebase already uses for this race (EventService.java:251, 325; EventSoftDeleteRaceScenarios pins it), and it also fixes `unpublish`'s check-then-act.
- **(b) `status` `updatable = false` — rejected.** `publish` and `unpublish` legitimately write status through save (EventService.java:331-334, 378-380). It would silently stop both.
- **(b') targeted `UPDATE … SET updatedAt` in `bumpEventUpdatedAt` — rejected.** This replaces the full save at TicketTierService.java:269-273 and PromoCodeService.java:167-170. It covers 6 of the 9 paths but not media (which writes real fields) or unpublish (which writes status). That leaves two mechanisms for one race.
- **`@DynamicUpdate` on Event — rejected.** It changes every Event UPDATE in the app. It does not help a path that writes status itself (unpublish still turns PAST into DRAFT). And it is no guard for a merge of a detached entity.

**Out of scope:** reordering MediaUploadService's R2 put (see Risks), and adding `@Version` (it would change the PATCH ETag contract).

## Repos in ship order
1. `api` (imin-api, base `master`). Worktree `/Users/ivan/imin/imin-api/.claude/worktrees/event-status-revert`, branch `fix/event-status-revert` on `origin/master` bbc3e6fa.

## Affected files (per repo)
### api
| # | File | Change |
|---|---|---|
| 1 | `src/main/java/com/imin/iminapi/service/event/TicketTierService.java` | `loadOwnedEvent` (252-256): first line `events.lockActiveForWrite(eventId, p.orgId());`, before `findActive`. One-line comment. This covers `create` (134), `patch` (164) and `delete` (184). |
| 2 | `src/main/java/com/imin/iminapi/service/event/PromoCodeService.java` | `loadOwnedEvent` (156-160): the same lock line before `findActive`. Covers `create` (85), `patch` (110) and `delete` (142). |
| 3 | `src/main/java/com/imin/iminapi/service/event/MediaUploadService.java` | `loadOwned` (190-194): the same lock line before `findActive`. Covers `upload` (71) and `delete` (146). |
| 4 | `src/main/java/com/imin/iminapi/service/event/EventService.java` | `unpublish` (360): `loadOwned(p, id)` → `loadOwnedForWrite(p, id)`. Update the comment on `loadOwnedForWrite` (407) so it also names the sweep (1–2 lines). |
| 5 | `src/main/java/com/imin/iminapi/model/Event.java` | Comment only on `status` (34-36), e.g. "The sweep flips LIVE→PAST in bulk; load-then-save paths take EventRepository.lockActiveForWrite before loading." No behaviour change. |
| 6 | `src/test/java/com/imin/iminapi/service/event/EventStatusRevertPostgresTest.java` | **New.** Postgres 17 Testcontainers class (see Test impact). |

Files from Blast radius that need no edit (one line each):
- `repository/EventRepository.java`: `lockActiveForWrite` (366-380) and `markLivePast` (404-414) already have the semantics we need; the fix only adds callers.
- `service/event/EventStatusSweeper.java`: the bulk writer is correct; it now waits on held row locks, which it has to.
- `EventService.patch`/`publish`: already call `loadOwnedForWrite` (251, 325).
- `EventService.createDraft`: INSERT of a DRAFT (198-207); there is no prior row to revert.
- `audienceplan/service/DoorOptInService.java`, `SurveyService.java`: targeted `updateDoorOptin`/`updateSurvey` (EventRepository.java:316-337). The entity is cleared afterwards (`clearAutomatically`), the setters at DoorOptInService:121-122 and SurveyService:128-129 act on a detached object, and the columns are `updatable=false`. Status is never written.
- `service/event/VenueGeocodingListener.java`: targeted `updateVenueCoordinates` (303-313) only.
- `service/event/DraftEventDeletionService.java`: bulk `softDeleteNeverPublishedDraft` (351-365) writes `deleted_at`/`updated_at` with a `status = DRAFT` guard in WHERE; it does not write status.
- `predictor/service/RadarTimelineService` (via `updateRadarMuted`, EventRepository.java:342-345): targeted, does not write status.
- Existing tests `TicketTierServiceTest`, `MediaUploadServiceTest`, `EventServiceTest`, `EventServiceAuditIntegrationTest` mock `EventRepository` (`mock(EventRepository.class)` at TicketTierServiceTest:40, MediaUploadServiceTest:29, EventServiceTest:32, EventServiceAuditIntegrationTest:42). `lockActiveForWrite` is `void`, so the mock does nothing. The grep shows none of them uses `verifyNoMoreInteractions(events)` or `InOrder` on `events`, and the plan changes no fixture, constant or data file. They stay green unedited.
- `CrossOrgScopingTest`: other-org tier/promo/media/unpublish requests are still 404. The lock's `org_id` filter matches 0 rows, then `findActive`/the org check throws 404 as before.
- `EventSoftDeleteRaceScenarios`/`EventSoftDeleteRacePostgresTest`/`EventSoftDeleteRaceTest`: they exercise `patch`/`publish`, which are unchanged.

## Ordered steps
1. **TicketTierService.** In `loadOwnedEvent` (TicketTierService.java:252), insert `events.lockActiveForWrite(eventId, p.orgId());` before `events.findActive(eventId)`. Comment (≤2 lines): "Lock before reading so a full-entity save cannot write back a status the sweep changed meanwhile."
2. **PromoCodeService.** The same insertion in `loadOwnedEvent` (PromoCodeService.java:156), with the same comment.
3. **MediaUploadService.** The same insertion in `loadOwned` (MediaUploadService.java:190), with the same comment.
4. **EventService.unpublish.** Replace `loadOwned(p, id)` with `loadOwnedForWrite(p, id)` (EventService.java:360). Extend the comment at 407 to "Lock first, then read: a draft delete or a LIVE→PAST sweep that committed while we waited is what we read."
5. **Event.java.** Add the one-line comment on `status`.
6. **Write `EventStatusRevertPostgresTest`** as specified under Test impact.
7. **Prove each guard.** For each of steps 1–4, delete that lock line, run `./mvnw test -Dtest=EventStatusRevertPostgresTest`, and confirm the expected invocations go red (table under Test impact). Then restore the line. Record the four runs in the review notes.
8. **Run the full gate** (Verification commands). Make sure no Testcontainers test was skipped.

**Every entry path to the `events.status` state machine, and whether it enforces the guard:**

| Path | Loads + saves Event? | Status after this task |
|---|---|---|
| `EventService.createDraft` (EventService.java:197) | insert only | no guard needed |
| `EventService.patch` (250) | yes | already locked (251) |
| `EventService.publish` (324) | yes, writes status | already locked (325) |
| `EventService.unpublish` (359) | yes, writes status | **step 4** |
| `TicketTierService.create/patch/delete` (133/163/183) | yes, via `bumpEventUpdatedAt` (269-273) | **step 1** |
| `PromoCodeService.create/patch/delete` (84/109/141) | yes, via `bumpEventUpdatedAt` (167-170) | **step 2** |
| `MediaUploadService.upload/delete` (68/145) | yes (128, 170) | **step 3** |
| `TicketTierService.reconcileEmbedded` (218) | no event save; runs inside the already-locked `patch` | covered |
| `EventStatusSweeper.sweep` → `markLivePast` | bulk writer (the only one of `status`) | waits on any held lock |
| Door/survey/radar-mute/geocode/soft-delete updates | targeted UPDATEs, never `status` | n/a |

`EventRepository` is `@RepositoryRestResource(exported = false)` (EventRepository.java:20), so Spring Data REST exposes no save path. The grep `\b(events|eventRepo|eventRepository)\.(save|saveAll|saveAndFlush)\(` over `src/main` finds exactly the 8 sites above (EventService 207/262/334/380, TicketTierService 272, PromoCodeService 169, MediaUploadService 128/170). `setStatus(EventStatus…)` appears only at EventService.java:331 and 378. No admin endpoint, scheduler or reconciler other than the sweeper writes `events.status`.

## Verification commands
```
docker info >/dev/null && echo docker-up
cd /Users/ivan/imin/imin-api/.claude/worktrees/event-status-revert
/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=EventStatusRevertPostgresTest
/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test
```
Read the surefire report for `EventStatusRevertPostgresTest`, `EventSoftDeleteRacePostgresTest` and the other `*PostgresTest` classes. Any skipped test means red, even if Maven prints BUILD SUCCESS. Re-run the full gate after any rebase.

## Test impact
**Branches of the changed logic:**
- B1: lock taken before load (tier, promo, media helpers, and unpublish). Ordering "sweep first": the writer waits, then reads PAST.
- B2: the same lock. Ordering "writer first": the sweep waits for the writer's commit, then re-checks and still marks PAST.
- B3: `unpublish` after waiting on a committed sweep reads PAST → `status != LIVE` → 409 `INVALID_STATE` (EventService.java:361-363).

**New file `src/test/java/com/imin/iminapi/service/event/EventStatusRevertPostgresTest.java`:**
- Setup:
  - `@SpringBootTest`, `@Import(TestRateLimitConfig.class)`. This supplies the `InMemoryMediaStorage` bean (TestRateLimitConfig.java:30-32).
  - `@MockitoBean StripeProductService`, so tier sync never reaches Stripe.
  - `@Testcontainers(disabledWithoutDocker = true)`, `@Container @ServiceConnection PostgreSQLContainer<>("postgres:17-alpine")`, and the `@DynamicPropertySource` block copied from EventSoftDeleteRacePostgresTest.java:10-28.
  - Postgres only: the result depends on READ COMMITTED re-checking WHERE after a lock wait, and H2's handling of that wait is unverified.
- Fixture per test:
  - An org and a MEMBER user (no role guard on these services).
  - One LIVE event: `startsAt = now-5h`, `endsAt = now-1h`, `publishedAt` set, `posterUrl = https://test-media.invalid/events/{id}/poster-x.png`.
  - One free tier (`priceMinor 0`, `quantity 10`, `sold 0`, `reserved 0`) and one promo code.
- `@AfterEach` deletes `audit_logs`, `promo_codes`, `ticket_tiers`, `events`, `users` and `organizations` for the org.
- The sweep is driven through the `EventRepository` bean's `markLivePast(now, now)`, not through `EventStatusSweeper.sweep()`. ShedLock's `lockAtLeastFor = "PT10S"` (EventStatusSweeper.java:56) would skip a second tick within 10 s in the same class.
- Writers (8, one parameter each), each called through the Spring bean, with the effect it must leave:
  - `tierService.create(p, id, new TicketTierCreateRequest("Late", 0, 5, …))`: 2 tiers exist.
  - `tierService.patch(p, id, tierId, name "Renamed")`: tier name is `Renamed`.
  - `tierService.delete(p, id, tierId)`: 0 tiers.
  - `promoCodeService.create(p, id, code "LATE", 10 %)`: 2 promos.
  - `promoCodeService.patch(p, id, promoId, discountPct 20)`: discount is 20.
  - `promoCodeService.delete(p, id, promoId)`: 0 promos.
  - `mediaUploadService.upload(p, id, POSTER, realPng(40,50), "image/png", "p.png")`: `poster_url` equals the returned url.
  - `mediaUploadService.delete(p, id, POSTER)`: `poster_url` is null.
- Tests:
  1. `@ParameterizedTest savePathWaitingOnAnUncommittedSweep_leavesTheEventPast(writer)` (B1, 8 runs).
     - Thread A: `TransactionTemplate` → `markLivePast` returns 1 → count down `swept` → await `release`.
     - Thread B: run the writer. After 500 ms, assert B is not done.
     - Release A, then `get` both. Assert the writer returned normally, `status = 'PAST'` (JDBC), and the writer's effect.
  2. `@ParameterizedTest sweepWaitingOnASavePath_stillMarksThePast(writer)` (B2, 8 runs).
     - Thread A: `TransactionTemplate` → writer → count down `written` → await `release`.
     - Thread B: `markLivePast(now, now)` (its own `@Transactional`). After 500 ms, assert B is not done ("the sweep waits for the writer's row lock").
     - Release. Assert the sweep returned 1, `status = 'PAST'`, and the writer's effect.
  3. `unpublishWaitingOnAnUncommittedSweep_is409AndStaysPast` (B3).
     - Event fixture without the tier's `sold`/`reserved` blocking (both 0). Thread A as in test 1. Thread B runs `eventService.unpublish(p, id)`.
     - Assert B fails with `ApiException` 409 `INVALID_STATE` and that `status = 'PAST'`.
  4. `sweepWaitingOnAnUnpublish_leavesTheDraft` (behaviour pin for the B2 ordering of unpublish).
     - Thread A runs `unpublish` inside a held `TransactionTemplate`. Thread B runs `markLivePast` and is not done after 500 ms.
     - After release, the sweep returned 0 and `status = 'DRAFT'`.
- Total: 4 test methods, 18 runs (8 + 8 + 1 + 1).

**Proving each guard (step 7). Expected red when its lock line is removed:**

| Line removed | Runs that must go red | Why |
|---|---|---|
| TicketTierService lock (step 1) | test 1 × 3 tier writers, test 2 × 3 tier writers = 6 | test 1: writer reads LIVE, its flush UPDATE waits, then writes LIVE back. test 2: no lock is held before commit (`bumpEventUpdatedAt` only `save`s; AuditLogger writes in its own `REQUIRES_NEW`, AuditLogger.java:47,103), so the sweep is not blocked and the writer's commit reverts it |
| PromoCodeService lock (step 2) | 3 + 3 = 6 | same |
| MediaUploadService lock (step 3) | 2 + 2 = 4 | same; `storage.put` is in-memory and does not flush |
| EventService.unpublish lock (step 4) | test 3 = 1 | without the lock, unpublish reads LIVE and returns 200 with DRAFT |

That is 17 runs that prove a guard. Test 4 stays green without the guard: `unpublish`'s trailing `detail()` → `findActive` JPQL auto-flushes the DRAFT UPDATE (EventService.java:383 → 414) before the test releases. So it pins the correct outcome but is not counted as a guard test. If any of the 17 stays green without its line, investigate the flush timing; do not weaken the assertion.

No existing test file changes (see Affected files for why each named test stays green).

## Live-test
The race cannot be reproduced against prod on demand: it needs the sweep, which runs only at 00:00/12:00 UTC. After deploy, smoke-test on a test org's own event with `/live-test api`:
- Tier create + delete, promo create + delete, poster upload + delete, then publish → unpublish on a draft with no sales.
- Each must answer 2xx with the same body shape as before. `unpublish` on a non-LIVE event must still answer 409 `INVALID_STATE`.
- After the next 00:00/12:00 UTC tick, check the Railway logs for `EventStatusSweeper: marked … PAST` or the debug no-op, with no lock-timeout errors.

## Contract impact
none. No path, schema or OpenAPI marker changes. Behaviour note: `POST /api/v1/events/{id}/unpublish` can now answer its existing 409 `INVALID_STATE` "Event is not published" when the sweep wins the race. Before, that race returned 200 and stored DRAFT over PAST.

**Copy ledger** (strings reused, none added):

| String | Field behind it | Meaning, scope, range | Rendered next to it |
|---|---|---|---|
| "Event is not published" (EventService.java:362) | `e.getStatus()` (EventService.java:361), read after `lockActiveForWrite` | the one event's committed status at lock time; one of DRAFT/PAST/CANCELLED when shown | API 409 error envelope `message`; webapp rendering unchanged by this task |

## i18n impact
none (api only, no user-facing string added or changed).

## Blast radius
- **Shared entity `Event`, organizer write paths (`/api/v1/events/{id}/tiers…`, `/promo-codes…`, `/media…`, `/unpublish`).** Each now holds the event row lock (`FOR NO KEY UPDATE` equivalent via a no-op UPDATE, EventRepository.java:366-380) from load to commit:
  - Same-event writers queue behind each other instead of overwriting each other. This also fixes lost organizer updates between these paths and `PATCH`; for example, a media upload loaded before a PATCH can no longer write back the old name.
  - Other-org or soft-deleted ids lock 0 rows and 404 as before.
- **Money/Stripe.** `TicketTierService` calls `StripeProductService.syncTier` (TicketTierService.java:246-248) while it holds the event lock. `EventService.patch` already does this through `reconcileEmbedded` (EventService.java:273). Checkout is not blocked: order and reservation inserts take only `FOR KEY SHARE` on the event via FK, which does not conflict with `FOR NO KEY UPDATE` (EventRepository.java:367-368). Inventory locks tier rows, never the event row, and no checkout path saves an Event (grep above). Lock order stays event → tier, the same as `patch`, so no new deadlock pair.
- **Sweeper.** `markLivePast` now blocks on any held event lock while holding the locks it already took on other ended events. It runs twice a day, so the wait is bounded by the longest organizer transaction (see Risks).
- **Status consumers** that read PAST (TierAvailability, PublicTierEligibility, NotifyReleaseSender, DateVerdictFeedbackService, InviteOnPublishService, the EventRepository PAST queries at 100-120 and 443-477): no edit; they now get the PAST they were missing.
- No Flyway migration, no `/api/v1` contract change, no shared module beyond `Event.java` (comment only).

## Risks
- **Lock hold during media upload.** `MediaUploadService.upload` calls `storage.put` (R2, up to 60 MB video) inside the transaction after the save (MediaUploadService.java:128-132). With the lock, a PATCH autosave or the sweep on that event waits for the upload. Accepted for this fix; moving the put out of the transaction is OPEN_QUESTION 1.
- **Future save paths.** A new `events.save(` that loads without `lockActiveForWrite` reintroduces the bug. Mitigated by the comment on `Event.status`; a mechanical guard is OPEN_QUESTION 2.
- **Docker dependency.** The new class is skipped without Docker, and a skip counts as red per the workspace gate rule.
- **Size.** 6 files, one concern; no split needed.

## Definition of done
- Steps 1–5 are applied; each new comment is 1–2 lines and names no ticket.
- `EventStatusRevertPostgresTest`: all 18 runs green on Postgres 17. The four removal runs show the 17 expected red runs (table above) and are recorded in the review notes.
- The full `./mvnw test` is green through `test-serial.sh` with Docker up and zero skipped Testcontainers tests.
- No other file changed (`git diff --stat` shows the 6 files).

## Decisions (main session)
- Approach (a) accepted: lock before load on all four helpers.
- OPEN_QUESTION 1: accept the lock hold during media upload for now; moving `storage.put` out of the transaction is a queued follow-up card.
- OPEN_QUESTION 2: mechanical guard for new `events.save(` sites is a queued follow-up card, not this task.

## Live-test evidence

## Review rounds
### Implement: guard proofs (step 7), 2026-10-02
Each run: the line removed in the working tree, `test-serial.sh ./mvnw test -Dtest=EventStatusRevertPostgresTest` on Postgres 17, the file restored from a scratch copy right after (`cmp` confirmed). With every line in place: 18/18 green.

| Line removed | Red runs | Where they fail |
|---|---|---|
| TicketTierService lock | 6 of 18: test 1 [1-3], test 2 [1-3] | test 1:189 `expected "PAST"` (writer wrote LIVE back); test 2:215 "the sweep waits for the writer's row lock" (sweep not blocked) |
| PromoCodeService lock | 6 of 18: test 1 [4-6], test 2 [4-6] | same lines |
| MediaUploadService lock | 4 of 18: test 1 [7-8], test 2 [7-8] | same lines |
| EventService.unpublish `loadOwnedForWrite` → `loadOwned` | 1 of 18: test 3 | 249 "Expecting code to raise a throwable" (unpublish returned 200) |

17 red runs in total, matching the table under Test impact; test 4 stayed green in every run, as planned. Test 2 goes red at the "sweep waits" assertion, before its status check: without the lock the sweep finishes at once.
