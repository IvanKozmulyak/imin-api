# Publish never holds the event lock during a Stripe call (api)
publish-stripe-outside-lock · Subagent · Notion: (card id from main session)

## Goal and scope

**What happens today (read in worktree `3eaebc2d`):**
- `EventService.publish` is `@Transactional` (EventService.java:326). It takes the event row lock first: `loadOwnedForWrite` → `events.lockActiveForWrite`, which is a no-op `UPDATE events SET updated_at = updated_at` held until commit (EventService.java:329, :410-413; EventRepository.java:367-380). It then calls `requireStripeIfPaid` (:334, :396-407).
- `requireStripeIfPaid` does nothing when no tier has `priceMinor > 0` (:397-399). Otherwise it calls `stripeConnect.getStatus(p, p.orgId())` and throws 422 `STRIPE_NOT_READY` unless `readyToReceivePayments` (:401-406). That flag is `organizations.stripe_payouts_enabled` (StripeConnectService.java:487-495).
- `getStatus` is `@Transactional` (StripeConnectService.java:354), so it joins publish's transaction. It reads the local Connect mirror. When `shouldRefresh` is true it calls `mirror.syncFromStripe` (:368-371). `shouldRefresh` is true for any state other than `ACTIVE`, and for an `ACTIVE` mirror older than 5 min (:457-461).
- `syncFromStripe` is `@Transactional` and also joins. It makes a live `stripeClient.v2().core().accounts().retrieve(...)` call (StripeConnectStatusMirror.java:50-69). When the org first turns `ACTIVE` it then calls `payoutScheduleService.ensureManual` (:90-92), a `REQUIRES_NEW` transaction that makes a second Stripe call (StripePayoutScheduleService.java:66, :82).
- So the javadoc at EventService.java:393 ("no cached column exists") is stale. A local mirror exists, but this path refreshes it from Stripe under the lock for any org that is not ACTIVE, or that is ACTIVE but stale.

**Stripe timeouts (pinned stripe-java 32.1.0):**
- The bean is `new StripeClient(key)` (StripeConfig.java:50), which builds from `StripeClientBuilder` defaults: `connectTimeout = Stripe.DEFAULT_CONNECT_TIMEOUT` (30 000 ms), `readTimeout = Stripe.DEFAULT_READ_TIMEOUT` (80 000 ms), `private int maxNetworkRetries;` (= 0). Source: `~/.m2/.../stripe-java-32.1.0-sources.jar!com/stripe/StripeClient.java:1040-1042` and `Stripe.java:9-10`.
- The global `Stripe.maxNetworkRetries = 2` (Stripe.java:29) does not apply to `StripeClient`.
- Worst case per call is therefore about 110 s, and about 220 s on the publish that also triggers `ensureManual`.

**Who waits on the lock:** every caller of `lockActiveForWrite` on the same event:
- `EventService.patch` (autosave, :251) and `unpublish` (:364)
- `TicketTierService` (:241)
- `PromoCodeService` (:158)
- `MediaUploadService` (:242)
- the draft-delete `UPDATE` (`softDeleteNeverPublishedDraft`)

The LIVE→PAST sweep is not affected: its bulk `UPDATE … WHERE e.status = LIVE` (EventRepository.java:416-425) never matches a row being published, because publish refuses `LIVE` at EventService.java:330. The brief's sweep concern does not hold, but autosave and tier/promo/media writes do stall.

**Goal:** publish never calls Stripe while it holds the event row lock. The readiness decision keeps today's freshness, today's error codes and today's error order.

**Considered and rejected: mirror-only (never call Stripe on publish).** `getStatus` refreshing at publish time is what heals an org whose v2 webhook was lost. Without it, a just-onboarded org gets 422 until the `StripeConnectStatusSweeper` (fixedDelay 5 min, StripeConnectStatusSweeper.java:64) or a dashboard status read refreshes the mirror. Keeping the refresh before the lock costs one Stripe call outside any lock, exactly as today, so it is kept.

**Out of scope:**
- `getStatus` itself still makes its Stripe call inside its own short `@Transactional` and holds a pooled connection (no row lock). This is the same as `GET /stripe/status` today.
- The checkout path (`getStatusLive`) is untouched.

## Repos in ship order
1. `imin-api` (base `master`). There is no frontend change and no contract change.

## Affected files (per repo)

**imin-api** (9 files: 5 main, 4 test; 2 new)

| File | Change |
|---|---|
| `src/main/java/com/imin/iminapi/service/event/EventPublishService.java` (new) | `@Service`, deliberately not `@Transactional`. `publish(p, id)`: `if (eventService.precheckPublish(p, id)) stripeConnect.getStatus(p, p.orgId());` then `return eventService.publish(p, id);`. Class comment (2 lines): the Stripe refresh commits before the locked publish starts, and the locked publish reads only the mirror. |
| `src/main/java/com/imin/iminapi/service/event/EventService.java` | (a) New `@Transactional(readOnly = true) public boolean precheckPublish(AuthPrincipal p, UUID id)`: `loadOwned` (404), then `requirePublishable(e)`, then returns `hasPaidTier(e.getId())`. It takes no lock. (b) Extract `private void requirePublishable(Event e)` from :330-333: LIVE → 409 `INVALID_STATE` "Already published", then `validator.validateForPublish(e)`. (c) Extract `private boolean hasPaidTier(UUID eventId)` from :397-398, with the same predicate. (d) `publish` (:326-353) keeps `@Transactional` and `@CacheEvict`. It calls `requirePublishable(e)` in place of :330-333. (e) `requireStripeIfPaid` calls `stripeConnect.getStatusCached(p.orgId())` instead of `getStatus`. Its javadoc (:388-395) is replaced: "Reads only the local Connect mirror, so no Stripe call is made under the event lock; EventPublishService refreshes the mirror before the lock." |
| `src/main/java/com/imin/iminapi/controller/event/EventController.java` | Inject `EventPublishService`. `publish` (:91-94) calls `eventPublish.publish(p, id)`. Signature, path and response are unchanged. |
| `src/main/java/com/imin/iminapi/stripe/StripeConnectService.java` | New `public StatusResult getStatusCached(UUID orgId)` with no `@Transactional`, so it joins the caller's transaction. Body: `orgs.findById(orgId)`, else 404 `"Organization"`. `!hasAccount` → `notStarted()`. `isModeMismatch` → `logModeMismatch` + `notStarted()`. Else `toStatusResult(org)`. It never calls `mirror`. Javadoc (2 lines): "Mirror only, no Stripe call: safe under a row lock. Same not-connected answers as getStatus." |
| `CLAUDE.md` (worktree) | Stripe Connect section, after the `getStatusLive` sentence at :205, add: "Publishing a paid event refreshes the mirror via `getStatus` before taking the event lock (`EventPublishService`), then decides under the lock from the mirror alone (`getStatusCached`), so Stripe is never called while the event row is locked." |
| `src/test/java/com/imin/iminapi/service/event/EventPublishServiceTest.java` (new) | Mockito tests B1–B3 (see Test impact). |
| `src/test/java/com/imin/iminapi/service/event/EventServiceTest.java` | Edit `publish_paid_event_blocked_when_stripe_not_ready` (:556-583) and `publish_paid_event_allowed_when_stripe_ready` (:585-614): stub `getStatusCached(eq(p.orgId()))` instead of `getStatus(eq(p), eq(p.orgId()))` and add `verify(stripeConnect, never()).getStatus(any(), any())`. Add precheck tests C1–C5. |
| `src/test/java/com/imin/iminapi/stripe/StripeConnectServiceStatusTest.java` | Add E1–E4 for `getStatusCached`, built like the existing tests (`new StripeConnectService(stripeClient, orgs, props, null, mirror)`). |
| `src/test/java/com/imin/iminapi/service/event/EventPublishStripeLockPostgresTest.java` (new) | Postgres 17 Testcontainers test P1. |

**Tests that need no edit** (each checked by grep):
- `EventControllerTest` (:46, :243-248): a `@SpringBootTest` with `@MockitoBean EventService`. The real `EventPublishService` gets the mock. `precheckPublish` returns the Mockito default `false`, so no Stripe call, and the stubbed `publish` returns `sample()`. Stays green.
- `CrossOrgScopingTest` (:234-236): a foreign event now 404s in `precheckPublish → loadOwned`, before any Stripe call. Same 404 envelope.
- `EventSoftDeleteRaceScenarios` (:122-124): calls `EventService.publish` directly. Its event has no tier (no tier/price reference in the file), so `requireStripeIfPaid` returns before any Stripe read. The lock behaviour it tests is unchanged.
- `EventServiceAuditIntegrationTest` (:105-147): publishes with no tiers stubbed, so it never touches `stripeConnect`.

## Ordered steps
1. Baseline. In the worktree: `docker info`, then `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test` on the untouched base. Record any red or skipped tests as pre-existing.
2. `StripeConnectService.getStatusCached` (shape above). Add E1–E4 and run `-Dtest=StripeConnectServiceStatusTest`.
3. `EventService`:
 - Extract `requirePublishable` and `hasPaidTier`.
 - Add `precheckPublish`.
 - Switch `requireStripeIfPaid` to `getStatusCached` and replace its javadoc.
 - The guard order inside `publish` stays: 404 → 409 → 422 validation → 422 `STRIPE_NOT_READY`.
 - Edit the two paid-publish tests and add C1–C5. Run `-Dtest=EventServiceTest`.
4. Add `EventPublishService` with B1–B3 tests. Point `EventController.publish` at it.
5. Add `EventPublishStripeLockPostgresTest` (P1, setup under Test impact).
6. Do the guard proofs (Test impact). Revert each mutation, then confirm `git diff` shows only the intended hunks.
7. Add the `CLAUDE.md` sentence.
8. Run the full gate (Verification commands). Read the `Tests run:` / `Skipped:` lines: any skipped Testcontainers test means the run is red.

Comment rules: 1–2 lines each, no ticket ids, no milestone ids.

## Verification commands
```
cd /Users/ivan/imin/imin-api/.claude/worktrees/publish-stripe-outside-lock
docker info >/dev/null && echo docker-up
/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=EventPublishServiceTest,EventServiceTest,StripeConnectServiceStatusTest,EventPublishStripeLockPostgresTest,EventControllerTest,CrossOrgScopingTest,EventSoftDeleteRacePostgresTest
/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test
```
- The targeted list is comma-separated, never `+`. Judge a targeted run only by its own `Tests run:` line.
- The full `./mvnw test` is the gate. A report that shows `EventPublishStripeLockPostgresTest` or `EventSoftDeleteRacePostgresTest` as skipped is red.

## Test impact

**Branch map, and one test per branch:**

`EventPublishService.publish`, in `EventPublishServiceTest` (Mockito; `EventService` and `StripeConnectService` mocked):
- **B1** `precheckPublish` throws an `ApiException` (422 `PUBLISH_VALIDATION_FAILED`): it propagates, and `getStatus` and `eventService.publish` are never called.
- **B2** precheck returns `false`: `getStatus` is never called and `publish` is called once.
- **B3** precheck returns `true`: `InOrder` shows `precheckPublish`, then `getStatus(p, p.orgId())`, then `publish(p, id)`. The returned DTO is the one `publish` returned.

`EventService.precheckPublish`, in `EventServiceTest`:
- **C1** Event of another org: 404 `NOT_FOUND`, and `verify(events, never()).lockActiveForWrite(any(), any())`.
- **C2** LIVE event with a blank name: 409 `INVALID_STATE`, not 422. This pins the 409-before-validation order.
- **C3** DRAFT with a blank name: 422 `PUBLISH_VALIDATION_FAILED`, and `tiers` is never read.
- **C4** Valid DRAFT with tiers priced `[0, 1500]`: returns `true`.
- **C5** Valid DRAFT with one tier priced `0`: returns `false`.
- C1–C5 all assert `verifyNoInteractions(stripeConnect)`.

`EventService.publish` (locked), in `EventServiceTest`:
- **D1** (edited :556-583): paid tier, `getStatusCached` gives NOT_STARTED / not ready → 422 `STRIPE_NOT_READY`, `events.save` never called, `getStatus` never called.
- **D2** (edited :585-614): paid tier, `getStatusCached` gives ACTIVE / ready → `live`, `getStatus` never called.
- D3 (`publish_free_event_skips_stripe_check`, :616-640), the LIVE test (:227-236) and the validation test (:787-797) are unchanged.

`StripeConnectService.getStatusCached`, in `StripeConnectServiceStatusTest`:
- **E1** Org missing: 404.
- **E2** No `stripeAccountId`: `NOT_STARTED`, ready `false`.
- **E3** `stripeLivemode = true` with a fresh `StripeProperties` whose secret is `sk_test_x`, a new instance and not a shared bean: `NOT_STARTED`, and `mirror` never called.
- **E4** `ONBOARDING`, `updatedAt` 1 hour old (would refresh under `shouldRefresh`), `payoutsEnabled = false`: returns the mirror values (`ONBOARDING`, ready `false`), and `verify(mirror, never()).syncFromStripe(any())`.

**P1**, `EventPublishStripeLockPostgresTest`: `@SpringBootTest`, `@Testcontainers(disabledWithoutDocker = true)`, `postgres:17-alpine`. The datasource overrides are copied from `TierInventoryRacePostgresTest` (:62-78). It replaces `StripeConnectStatusMirror` with `@MockitoBean`.

Seed:
- Org: `stripeAccountId = "acct_pub_" + random`, `ONBOARDING`, `payoutsEnabled = false`, `stripeConnectStatusUpdatedAt = null`, `stripeLivemode = null`.
- MEMBER principal.
- Valid DRAFT event with name, slug, start, end, street, city, postal code and description.
- One tier priced 1500.
- Delete the committed rows in `@AfterEach`. Fixture dates are relative to now.

Mirror mock answer:
- It blocks only on the thread named `publish-under-test`, set by the pool's `ThreadFactory`. Every other caller, such as a `StripeConnectStatusSweeper` tick once the context is over 60 s old, returns at once.
- On that thread it signals `syncEntered`, waits on `gate` (15 s max), then does what the real mirror does in the caller's transaction: `orgs.findByStripeAccountId(acct)`, set `ACTIVE`, `payoutsEnabled = true`, `stripeConnectStatusUpdatedAt = now`, then `orgs.save`.

Test `publish_stripeBlocked_eventLockFree`, with `@Timeout(60)`:
1. Submit `eventPublishService.publish(principal, eventId)` to the pool.
2. `syncEntered.await(15 s)` is true.
3. Inside a `TransactionTemplate`, `SELECT id FROM events WHERE id = ? FOR UPDATE NOWAIT` succeeds.
4. `eventService.patch(principal, eventId, null, <name-only patch "Renamed">)` finishes within 5 s. Build the body the way `TierInventoryRacePostgresTest.eventPatch` does.
5. `publishFuture.isDone()` is still false.
6. `gate.countDown()`. Then `publishFuture.get(10 s).status()` is `"live"`.
7. The DB row has `status = 'LIVE'` and `name = 'Renamed'`, so the autosave was not lost.
- `finally`: count down the gate and shut the pool down.

**Guard proofs:** remove or revert each line once, see the named test go red, then restore.
- In `requireStripeIfPaid`, swap `getStatusCached(p.orgId())` back to `getStatus(p, p.orgId())`. P1 fails at the `NOWAIT` step (the lock is held during the blocked sync), and D1/D2 fail on `never().getStatus`.
- Make `EventController.publish` call `eventService.publish` directly, with no refresh first. P1 times out on `syncEntered`, and the publish fails 422 because the mirror stays `ONBOARDING`. This proves the refresh before the lock is what keeps freshness.
- In `EventPublishService`, drop the `if`. B2 fails.
- Swap the two calls. B3 fails (`InOrder`).
- Add `events.lockActiveForWrite(id, p.orgId())` to `precheckPublish`. C1 fails.
- In `requirePublishable`, put validation before the LIVE check. C2 fails.
- In `getStatusCached`, add `if (mirror != null && shouldRefresh(org)) mirror.syncFromStripe(...)`. E4 fails.
- Drop the mode-mismatch branch. E3 fails.

**Test files:** `EventPublishServiceTest` (new), `EventServiceTest`, `StripeConnectServiceStatusTest`, `EventPublishStripeLockPostgresTest` (new).

## Live-test
`/live-test api` after deploy (`/ship-imin` deploys on push):
1. Prod OpenAPI (`curl -s https://imin-api-production.up.railway.app/v3/api-docs.yaml`) still lists `POST /api/v1/events/{id}/publish` with the same response schema. There is no new marker: the contract is unchanged, so liveness is shown by the commit SHA on the Railway deploy.
2. In an internal test org: publish a free draft → 200 `status: live`.
3. Publish a draft with a paid tier for an org with no Stripe account → 422 `STRIPE_NOT_READY`. The dashboard shows `StripeNotReadyDialog`.
4. Railway logs for that request show no Stripe error. Do not publish a paid event in a live-mode Connect org just to test this.

## Contract impact
None. The path, request, response, error codes (`NOT_FOUND`, `INVALID_STATE` 409, `PUBLISH_VALIDATION_FAILED` 422, `STRIPE_NOT_READY` 422) and their order are unchanged. There is no `types.ts` edit and no `PUBLIC_PAGE_API.md` change, because `/api/v1/public` is not touched.

## i18n impact
None. No user-facing string is added or changed.

**Copy ledger** (reused string only):

| String | Field behind it | Field meaning, scope, range | What the view shows |
|---|---|---|---|
| "Connect and finish Stripe onboarding before publishing a paid event." (`ApiException` message, EventService.java:403-405, code `STRIPE_NOT_READY`) | `StatusResult.readyToReceivePayments`, from `organizations.stripe_payouts_enabled` (StripeConnectService.java:487-495). Forced `false` by `notStarted()` (:482-485) when there is no account or the mode does not match. | Per org, boolean. It is the mirror value after the refresh that runs before the lock. A refresh that fails keeps the last known value (StripeConnectStatusMirror.java:66-69). Same meaning as today. | The webapp keys on the code, not this text: `StripeNotReadyDialog` (imin-webapp `origin/main` `src/features/events/EventDetailPage.tsx:137`, `CreateEventPage.tsx:391`, `EventEditPage.tsx:129`) with localized `copy.errors.stripeNotReady`. |

## Blast radius
- **Publish flow, money-adjacent (Connect readiness).** `EventService.publish` is the only path that sets `LIVE`: it is the only `setStatus(EventStatus.LIVE)` in `src/main` (EventService.java:335), and `EventController.java:93` is its only production caller. So the readiness guard is enforced on every path that reaches LIVE. There are no schedulers, reconcilers or admin endpoints that publish.
- **Freshness.** Same as today, because the same `getStatus` / `shouldRefresh` rules run before the lock, and the locked read sees the committed mirror (a new transaction, so a new persistence context). New window: a webhook or sweeper write landing between the refresh and the lock is read under the lock, which is fresher, not staler. A tier made paid between the pre-check and the lock reads the mirror without a refresh (see OPEN_QUESTIONS).
- **Atomicity change.** The mirror refresh now commits in its own transaction instead of rolling back with a failed publish. That is correct: the mirror records Stripe's state, not publish's.
- **`ensureManual`** (the second Stripe call, StripePayoutScheduleService.java:82) now also runs outside the event lock.
- **Locked writers that benefit:**
- `EventService.patch`, which also changes `publish`'s own file (:251)
- `unpublish` (:364): no edit, because it never calls Stripe
- `TicketTierService` (:241), `PromoCodeService` (:158), `MediaUploadService` (:242): no edit, they only wait on the lock this change shortens
- `DraftEventDeletionService` (`softDeleteNeverPublishedDraft`): no edit, same reason
- **Shared module `StripeConnectService`.** The change only adds a method. `getStatus`, `getStatusLive` and `PayoutService` (:187) are untouched.
- **Webapp.** No change. The error codes are unchanged (`src/shared/api/humanizeError.ts:29`, the three pages above).
- **Tests.** The publish tests in `EventControllerTest`, `CrossOrgScopingTest`, `EventSoftDeleteRaceScenarios` and `EventServiceAuditIntegrationTest` stay green unedited, for the reasons listed under Affected files.
- No Flyway migration, no config value, no env var.

## Risks
- **The pre-check reads without the lock**, so it can answer from slightly older state (for example a 422 for a field an autosave fixes a moment later). The locked phase re-runs every check, so it can never publish what the locked checks would refuse. A pre-check refusal is what the same request would have got a moment earlier.
- **Double mode-mismatch ERROR log per paid publish** (from `getStatus` and `getStatusCached`). Sentry groups them. This is acceptable: the refusal still needs a visible signal.
- **`getStatus` still holds a pooled connection during its Stripe call** (its own `@Transactional`, StripeConnectService.java:354). There is no row lock. This matches `GET /stripe/status` today and is a separate card if wanted.
- **P1 shares the Spring context cache.** It has its own context because of its own `@MockitoBean`. Gating the mock on the thread name keeps a sweeper tick from blocking.
- **Size:** 9 files, one concern, no split needed.

## Definition of done
- Steps 1–8 are done. The full `./mvnw test` is green, with no skipped Testcontainers tests, after the final rebase.
- Every guard proof was seen red once and then restored.
- `git diff` touches only the 9 files listed.
- Comments are 1–2 lines, with no ticket or milestone ids.
- The `CLAUDE.md` sentence and the `requireStripeIfPaid` javadoc are in the same diff.
- Live-test steps 1–4 are recorded below after deploy.

## Decisions (main session)
- Plan accepted. OPEN_QUESTION 1: keep the pre-lock getStatus refresh (freshness for lost webhooks). OPEN_QUESTION 2: accepted (rare 422, retry fixes it).
- Live-test: only step 1 (OpenAPI unchanged) plus deploy/health run automatically; steps 2–4 need a test org and stay with Ivan.

## Live-test evidence

## Review rounds

### Round 0: implementer guard proofs (2026-10-03)
Each mutation was applied to a scratch copy of the worktree (`rsync`, no `target`/`.git`), run through `test-serial.sh ./mvnw -q test -Dtest=<list>`, then restored byte-exact (`cmp` against the saved original). The worktree itself was never mutated, and after the eight runs `diff -rq` showed the scratch copy identical to it.

| # | Mutation | Run | Red |
|---|---|---|---|
| M1 | `requireStripeIfPaid` back to `getStatus(p, p.orgId())` | EventServiceTest,EventPublishStripeLockPostgresTest | D1 `publish_paid_event_blocked_when_stripe_not_ready:582` (never getStatus); D2 `publish_paid_event_allowed_when_stripe_ready:613` (NPE: the reverted `getStatus` is unstubbed and returns null). **P1 stayed green**, see note |
| M2 | `EventController.publish` calls `eventService.publish` directly | EventPublishStripeLockPostgresTest | P1 `:162` "publish reached the Stripe refresh" (syncEntered timed out at 15 s) |
| M3 | drop the `if` in `EventPublishService` | EventPublishServiceTest | B2 `free_event_publishes_without_refreshing_stripe:52` |
| M4 | `publish` before `getStatus` | EventPublishServiceTest | B3 `paid_event_refreshes_stripe_before_the_locked_publish:66` (InOrder) |
| M5 | `events.lockActiveForWrite` in `precheckPublish` | EventServiceTest | C1 `:648`, and C2–C5 (all assert no lock) |
| M6 | validation before the LIVE check in `requirePublishable` | EventServiceTest | C2 `precheckPublish_live_event_is_409_before_validation:661`, and `publish_already_live_throws_INVALID_STATE:235` |
| M7 | `shouldRefresh` → `syncFromStripe` in `getStatusCached` | StripeConnectServiceStatusTest | E4 `cached_returns_stale_mirror_without_refreshing:299` |
| M8 | drop the mode-mismatch branch in `getStatusCached` | StripeConnectServiceStatusTest | E3 `cached_not_started_on_mode_mismatch_without_touching_mirror:273` |

Note on M1: the plan expected P1 to fail at `NOWAIT`. It cannot. The pre-lock `getStatus` refresh is what blocks, and it leaves the org ACTIVE with a fresh timestamp, so the reverted locked `getStatus` never calls the mirror. M1 is caught by D1/D2 only.
Deviation: P1 publishes through `EventController.publish` (the bean), not `EventPublishService.publish`, so that M2 can reach it. The controller delegates to `EventPublishService`, so P1 covers both.

### Review-fix round 1 (2026-10-04)
Review verdict CLEAN, 3 LOWs, all applied:
1. Added P2 `publish_orgStaysOnboarding_refusedWithoutAStripeCallUnderTheLock` in `EventPublishStripeLockPostgresTest`. Stripe keeps the org ONBOARDING: the mirror mock on `publish-under-test` changes nothing on its first call and would block on the gate on a second one. The test expects 422 `STRIPE_NOT_READY` within 5 s, `verify(mirror, times(1)).syncFromStripe(accountId)`, one publish-thread call, and the event still DRAFT. It first sets `stripe_connect_status_updated_at = now()`, so the Connect sweeper (15-min staleness) cannot add a call while `getStatus` still refreshes the non-ACTIVE org.
   Guard proof: M1 (`requireStripeIfPaid` back to `getStatus`) in a refreshed scratch copy turns P2 red at `:216`: `publish.get(5 s)` threw a timeout instead of `ExecutionException`, because the second sync blocked under the lock. P1 stayed green there. The copy was restored byte-exact (`cmp`) and `diff -rq` showed it identical to the worktree.
2. `EventServiceTest.publishableDraft` now dates the event 10 days after `Instant.now()`. It was the only new fixture with literal dates; the `Instant.parse` literals elsewhere in the file are older code.
3. `CLAUDE.md` "Key mode is recorded" now lists `getStatusCached` with `getStatus` and `getStatusLive` as refusing a mode-mismatched org.
