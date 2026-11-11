# DSAR rectify and spam complaint re-read the member under its row lock
`dsar-rectify-lock` · fix · Backlog: "DsarService.rectify revert race"

## Goal and scope
Two writers save the whole `Membership` row from a copy read before they took the row lock. An Art.17 erase that commits in between gets written back: `status` returns to `active`, `erase_at` to NULL and `consent_status` to its earlier value. The erase is cancelled, the person can be mailed again, and `AudienceErasureJob` never deletes them. `Membership` has no `@Version` or `@DynamicUpdate` (Membership.java:17-23), so each UPDATE writes every column.
- **Rectify** (DsarService.java:266-276): `require` (:473-476) is an unlocked `findByIdAndOrgId`, followed by `membershipRepo.save(m)` (:273).
- **Spam complaint branch** (ResendWebhookProjector.java:150-175): the member is loaded unlocked before the lock on both entry paths:
  - `projectTaggedComplaint` loads it at :208-211.
  - Every complaint calls `SuppressionService.addMarketingIfAbsent`, which loads it through `requireMembership` (SuppressionService.java:99, :184-187).
  - All of it runs in one transaction and persistence context: `project` is a self-call from :213, `addMarketingIfAbsent` is `@Transactional` REQUIRED (:97), and the webhook controller is `@Transactional` (ResendWebhookController.java:81).
  - The native `lockByIdAndOrgId` (MembershipRepository.java:41-43) then hands back that cached copy without re-reading it (imin-api CLAUDE.md:369 "Row locks"), and `save(m)` at :165 writes it back.

**Fix**
- `rectify` takes the same lock-and-re-read that `requestErase` already uses (DsarService.java:305-308). Both share one private helper.
- The complaint branch refreshes the entity it gets back from the lock. One line covers both complaint paths.
- Not doing "don't load the entity in `projectTaggedComplaint`": SuppressionService.java:185 loads it again before the lock, so the race would stay open.

**Out of scope**
- Whether `rectify` should refuse an erase_pending member. It is allowed today and stays allowed.
- Other Membership writers (see Risks; none can undo an erase today).

## Repos in ship order
| key | base | worktree | verification command |
|---|---|---|---|
| api | master | /Users/ivan/imin/imin-api/.claude/worktrees/dsar-rectify-lock | `docker info >/dev/null && ./mvnw test` |

## Affected files (per repo)
**api, changed**
- `src/main/java/com/imin/iminapi/audience/service/DsarService.java`
  - Add a private `lockMember(UUID orgId, UUID membershipId)` next to `require` (:473). It does `entityManager.flush()`, then `membershipRepo.lockByIdAndOrgId(membershipId, orgId).orElseThrow(() -> ApiException.notFound("Membership"))`, then `entityManager.refresh(m)`, and returns `m`. One-line javadoc: "Row-locked and re-read under the lock: a copy loaded earlier in this transaction would hide a write committed since."
  - `requestErase` :304-308 becomes `Membership locked = lockMember(orgId, membershipId);`. Keep the :303 comment; drop :304, which the helper's javadoc replaces.
  - `rectify` :269 becomes `Membership m = lockMember(orgId, membershipId);`.
  - Javadoc :265 becomes: "Art.16 rectification — display_name/city/notes, written under the row lock so an erase committed meanwhile is kept. Audited."
- `src/main/java/com/imin/iminapi/marketing/webhook/ResendWebhookProjector.java`
  - Import `jakarta.persistence.EntityManager`.
  - Add the field and a last constructor parameter (:65-87). `grep -rn 'new ResendWebhookProjector(' src` finds nothing, so only Spring builds it.
  - The first statement of the :163 lambda is `entityManager.refresh(m);`, with the comment "The lock hands back the copy loaded above (SuppressionService, projectTaggedComplaint); re-read it so a committed erase stays."
  - No flush: nothing changes the member before :163.
- `src/test/java/com/imin/iminapi/audience/MembershipEraseRaceTest.java` — new (see Test impact).
- `docs/superpowers/plans/2026-11-04-dsar-rectify-lock.md` — this plan.

**api, read but unchanged**
- `audience/controller/AudienceController.java:299-305` — the only caller of `rectify`; its signature and 404 stay the same.
- `marketing/webhook/ResendWebhookController.java:81, :164, :170` — both complaint entry points reach the fixed line :163.
- `audience/service/SuppressionService.java:97-108, :184-187` — its unlocked read stays; the refresh after the lock cancels its effect.
- `audience/service/ConsentService.java:364-370` — already does flush, lock, refresh.
- test `AudienceDsarTest.java` — the rectify tests (:257-282) and the cross-org 404 test (:842-848) behave the same, since `lockByIdAndOrgId` with a foreign org is empty and gives 404. The test at :371-400 (`firstEraseCommitsBetweenTheLoadAndTheLock`) now also guards the helper's refresh.
- test `DsarServiceLockOrderTest.java:48-67` — uses a mock EntityManager; the helper still calls the stubbed `lockByIdAndOrgId(mid, orgId)`, and the lock-before-fan_features order is unchanged.
- tests `MembershipNpsClearTest.java:76-97`, `ResendWebhookProjectorTest.java`, `ResendWebhookControllerTest.java` — complaint outcome unchanged; they autowire the bean.

## Ordered steps
1. Write `MembershipEraseRaceTest` against the unmodified main code, then run `./mvnw test -Dtest=MembershipEraseRaceTest`. Expect tests 1 and 3 red (status `active`, expected `erase_pending`) and test 2 green. Keep the log as `<scratchpad>/gate-api-dsar-rectify-lock-red.log`.
2. Make the DsarService edits (helper, `requestErase`, `rectify`, javadoc).
3. Make the ResendWebhookProjector edits (EntityManager plus the refresh line).
4. Run the targeted set; it must be green.
5. Prove each guard by mutation. Revert each mutation; afterwards `git diff` shows only steps 1-3.
   - a. Put `rectify` back on `require(orgId, membershipId)` → test 1 goes red.
   - b. Delete the projector's `entityManager.refresh(m)` → test 3 goes red.
   - c. Delete `entityManager.refresh(m)` inside `lockMember` → `AudienceDsarTest#a_concurrent_first_erase_is_seen_under_the_lock[firstEraseCommitsBetweenTheLoadAndTheLock]` goes red.
   - d. Test 2 needs both of these edits to go red: `requestErase`'s `lockMember` swapped for `require`, and ConsentService.requireMembership :365-368 swapped for a plain `findByIdAndOrgId`. The erase then reads the old name before the rectify commits and writes it back. Either edit alone stays green, because the other path still re-reads under the lock.
6. Run the full gate.

## Verification commands
```
cd /Users/ivan/imin/imin-api/.claude/worktrees/dsar-rectify-lock
docker info >/dev/null
./mvnw test -Dtest=MembershipEraseRaceTest,AudienceDsarTest,DsarServiceLockOrderTest,MembershipNpsClearTest,MembershipConsentLockTest,ResendWebhookProjectorTest,ResendWebhookControllerTest > <scratchpad>/gate-api-dsar-rectify-lock-targeted.log 2>&1
./mvnw test > <scratchpad>/gate-api-dsar-rectify-lock.log 2>&1
```
- Judge each run by its own `Tests run:` line. `-Dtest` takes a comma list, not `+` (CLAUDE.md:373).
- Any skipped Testcontainers test means red.

## Test impact
New file `src/test/java/com/imin/iminapi/audience/MembershipEraseRaceTest.java`, annotated `@IminIntegrationTest` and nothing else on the class.

**Helpers reused**
- `PgFaults.pauseWrites` and `Pause.awaitBlocked`/`release` (support/PgFaults.java:84-161).
- `PgLocks.awaitLockWait` (support/PgLocks.java:15-36, bounded at 30 s).
- `AsyncDrain` on `taskExecutor` and `FanFeatureExecutors.LIVE` (as in MembershipConsentLockTest.java:53-60).
- `IminFixtures`.
- Same shape as MembershipConsentLockTest.java:96-110.

**Setup**
- `fx.org()`.
- Owner principal `new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID())` (AudienceDsarTest.java:96).
- Member via `AudienceOrderProjector.upsertMembership(orgId, email, email)` with `email = fx.email(tag)`, then `consentService.capture(orgId, mid, "explicit", "manual", "Organizer recorded consent", "email", null)`, so a revert also shows on `consent_status` (MembershipConsentLockTest.java:142-147). The capture runs before the pause opens.
- Test 3 also saves a sent Campaign for the org (MembershipNpsClearTest.java:80-91).

**`@AfterEach`**
- Drain both executors.
- Delete `marketing_optouts` and `suppression_entries` for the org, then the org's `memberships`.
- `CampaignRows.delete(jdbc, orgIds)`, `OrgRows.delete(jdbc, orgIds)` (MembershipNpsClearTest.java:52-63).

Assertions read the row with jdbc.

**Shared fields for all three tests**
- Kind: integration.
- Guards: "consent, DSAR, erasure" and "concurrency/locks — Postgres only" (imin-api CLAUDE.md:150-155).

**Test 1 — `rectifyReadBeforeAnEraseCommits_keepsTheErase`**
- How the ordering is forced:
  1. `pauseWrites(dataSource, "consent_records", "membership_id", mid)` holds an async `requestErase` inside its transaction, after it has taken the row lock. `awaitBlocked(10 s)`.
  2. Start `rectify(…, "Rectified name", null, null, owner)` async.
  3. `PgLocks.awaitLockWait(jdbc, "memberships", …)`. Today the rectify's commit UPDATE is the statement waiting; after the fix its FOR UPDATE waits. The held erase is waiting on an INSERT into consent_records, so it doesn't match.
  4. `release`, then join both futures within 10 s.
- Asserts: `status` erase_pending, `erase_at` not null, `consent_status` unsubscribed, `display_name` "Rectified name".
- Bug it catches: an organizer corrects a name just as the fan's erase lands, and the erase is cancelled. The fan is active and subscribed again, and is never deleted after 30 days.
- Red on today's code: yes (status `active`).

**Test 2 — dropped after review round 1.** It was green on unfixed code and went red only under mutation 5d, at its lock-wait setup, not at a lost write. The rectify-first ordering is already covered by AudienceDsarTest `firstEraseCommitsBetweenTheLoadAndTheLock` (:371-400) and MembershipConsentLockTest.

**Test 3 — `spamComplaintReadBeforeAnEraseCommits_keepsTheErase`**
- How the ordering is forced: as in test 1, but the writer is `projector.projectTaggedComplaint(campaignId, email, Instant.now())` and the wait regex is `"memberships|suppression_entries"`. The complaint first waits inside its suppression INSERT's foreign-key check on the locked member (`suppression_entries.membership_id REFERENCES memberships`, V50__audience_suppression.sql:16).
- Asserts:
  - `status` erase_pending, `erase_at` not null, `consent_status` unsubscribed, `objected_profiling` true.
  - A `suppression_entries` row (scope marketing, reason spam) exists for mid. That is the complaint's own effect, so a complaint that did nothing cannot pass.
- Bug it catches: a fan asks to be erased, marks a campaign email as spam, and is put back to active and subscribed.
- Red on today's code: yes.

**Not planned**
- Complaint-first ordering. Every membership column the complaint writes (`objected_profiling`, `nps`) is also written by the erase's own unsubscribe (ConsentService.java:253-258), and the complaint's other effect lives in another table. No change to either writer can turn it red.
- A test that `rectify`'s refresh matters. The lock is `rectify`'s first load (the controller isn't transactional, and open-in-view is false at application.yaml:11). The shared helper's refresh is already guarded by AudienceDsarTest.java:371-400.

Existing tests: none edited.

**Reproduction red output** (origin/master 3a49692d, unfixed; two runs identical; logs `gate-api-dsar-rectify-lock-red.log`, `-red-run2.log` in the session scratchpad)
```
[ERROR] Tests run: 3, Failures: 2, Errors: 0, Skipped: 0 -- in MembershipEraseRaceTest
rectifyReadBeforeAnEraseCommits_keepsTheErase:
  [status] expected: "erase_pending" but was: "active"; [erase_at] Expecting actual not to be null; [consent_status] expected: "unsubscribed" but was: "subscribed"
spamComplaintReadBeforeAnEraseCommits_keepsTheErase:
  [status] expected: "erase_pending" but was: "active"; [erase_at] Expecting actual not to be null; [consent_status] expected: "unsubscribed" but was: "subscribed"
rectifyHoldingTheRowFirst_keepsTheNameAndTheErase: green (dropped after review, see Test 2)
```

## Live-test
None. This is lock ordering inside the API, and reproducing it needs concurrent writes to a real member in prod.

Read-only checks after deploy: Railway serves the commit, `/actuator/health` answers 200, and `/v3/api-docs` is unchanged.

## Contract impact
None. No endpoint, schema or status changes, so there is no marker.

## i18n impact
None.

## Blast radius
- **Art.16 rectify** (`POST /api/v1/audience/members/{id}/rectify`, AudienceController.java:299-305): the request now holds the membership row lock (SELECT … FOR UPDATE). It waits, for milliseconds, behind any in-flight writer of that member: an erase, a consent change, an order projection. It takes only that one lock, so there is no new deadlock cycle; the audit write runs in its own transaction (REQUIRES_NEW).
- **Art.17 `requestErase`** (AudienceController.java:314, and `eraseAddress` at DsarService.java:431): refactor only; the same three calls in the same order.
- **Resend webhook spam complaints**: both paths go through ResendWebhookProjector.java:163 — the recipient-row path (ResendWebhookController.java:164) and the tagged campaign with no row (:170). Each complaint costs one extra SELECT (the refresh) inside the webhook transaction.
- No Flyway, money, auth or contract change.

## Risks
**Other `Membership` writers that save the whole row.** I checked all five; none can undo an erase today. These are backlog candidates, not fixed here.

1. **ConsentService.applyGrant, save at :206** (called from `capture` :136 and `confirmPending` :175).
   - First load: `requireMembership` :364-370 (flush, lock, refresh).
   - Can undo an erase today: no.
2. **ConsentService.unsubscribe, save at :261** (load at :235).
   - First load: the same `requireMembership`.
   - Can undo an erase today: no.
3. **AudienceOrderProjector.projectMembership, save at :376.**
   - First load: `lockOrCreateMembership` :134-141 (FOR UPDATE).
   - Each entry point gets a fresh transaction: `onTicketsIssued` (REQUIRES_NEW, :104), AudienceRedeemProjector :79 (REQUIRES_NEW), `upsertMembership` (own transaction, :230-300), `backfillMembership` (own transaction, :313).
   - Callers inside a transaction read the member under the lock first: PublicOptInGrant :93/:116, SurveyService :214/:249.
   - AudienceImportService :276-277 reads unlocked, but outside any transaction: `importContacts` is not `@Transactional` (:144-146) and open-in-view is false, so its copy is detached.
   - Can undo an erase today: no. Latent risk: `lockOrCreateMembership` never refreshes (:136), so a future caller that loads the member earlier in the same transaction would undo an erase. Candidate fix: refresh there.
4. **SmsConsentService.upsertMembership, save at :109.**
   - First load: `lockOrCreateMembership` :106 is the first membership read in `submit` (:53-54).
   - Can undo an erase today: no.
5. **MembershipProjector.recompute :115-130.**
   - It modifies the instance handed in by `projectMembership` and has no save of its own.
   - Can undo an erase today: no (covered by item 3).

**Other risks**
- Test 2 only goes red with two edits (mutation 5d). It guards the pair of re-reads, not a single line.
- Bounded waits: `Pause.awaitBlocked` waits at most 10 s and `PgLocks` at most 30 s. A missing block fails fast and never hangs.

## Definition of done
- Steps 1-6 done; the step-1 red log and the four mutation results are recorded in the commit message.
- Targeted run and full `./mvnw test` green, with no skipped Testcontainers tests.
- The diff touches only DsarService, ResendWebhookProjector, the new test and this plan.
- At ship, the BACKLOG.md item "DsarService.rectify revert race" is deleted. The `lockOrCreateMembership` refresh candidate is added if Ivan wants it.

## Live-test evidence

## Review rounds
- round 1 → APPROVE (0 CRITICAL/HIGH). MEDIUM applied: test 2 deleted as a weak duplicate. LOW not applied: the lock-wait regexes match any waiting statement, which is deterministic under serial execution. LOW to backlog: `AudienceOrderProjector.lockOrCreateMembership` never refreshes.

## Implementation evidence
- Repro red → green: tests 1 and 3 failed `status expected "erase_pending" but was "active"` on 3a49692d; green after the fix.
- Mutations: a → test 1 red; b → test 3 red; c → AudienceDsarTest `firstEraseCommitsBetweenTheLoadAndTheLock` red (erase_at moved).
- Targeted 105/0/0/0; full gate 8583/0/0/0, skipped 0 (before the test 2 deletion); after the deletion `MembershipEraseRaceTest,AudienceDsarTest` 54/0/0/0.
