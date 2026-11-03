# Erase-address follow-ups: true erase statuses in OpenAPI, a repeat Erase keeps the first date, true opt-out javadoc
erase-address-followups · subagent (/api/v1 contract docs + DSAR fix) · BACKLOG.md "Ready": "Erase-address follow-ups (api)" (BACKLOG.md:25) + "MarketingOptOutRepository javadoc" (BACKLOG.md:26)

**Plan gate:** approved by Ivan 2026-10-28. A repeat Erase writes no audit row and no consent row; OpenAPI lists 400/403/404 too; rectify race and drawer copy go to BACKLOG; MarketingOptOutRecorder sentence fixed too.

## Goal and scope
Three small imin-api follow-ups from shipping the webapp erase-address card (webapp 1e74e65):

1. **OpenAPI tells the truth about erase statuses.**
   - What the controller returns:
     - `POST /api/v1/audience/erase-address` (`dsarEraseAddress`) answers 204 when the address was erased now and 202 when it belongs to a member and took the member's erase (`AudienceController.java:316-321`).
     - `POST /api/v1/audience/members/{id}/erase` (`dsarErase`) always answers 202 (`AudienceController.java:305-310`).
   - What springdoc publishes for both: only `"200": description: OK`. Evidence is the prod-synced `imin-webapp` `origin/main:docs/openapi.yaml:2890-2892` (dsarErase) and `:3006-3008` (dsarEraseAddress).
   - Fix: add `@ApiResponses` to both. Runtime behaviour is unchanged.
2. **A repeat Erase keeps the first erase date.**
   - The bug: `DsarService.requestErase` (`DsarService.java:294-310`) sets `erase_at = now+30d` on every call. A second Erase on a member who is already `erase_pending` pushes back the date that the copy promises: "removed 30 days after the first erase request" (`imin-webapp` `copy.ts:2009`, also `:2004`).
   - `eraseAddress` already skips a pending member (`DsarService.java:419-422`).
   - Fix: `requestErase` owns the rule. On a pending member it changes nothing (decision: no audit row, see OPEN_QUESTIONS 1). `eraseAddress` drops its own check and calls `requestErase` unconditionally.
3. **`MarketingOptOutRepository` javadoc tells the truth.**
   - It says rows are written only on a DATA_SUBJECT unsubscribe and read only by the account toggle (`MarketingOptOutRepository.java:16-25`).
   - In fact complaints and the reminder one-click also write it, and the consent gate, the reminder checks and the opt-in checks read it. The writer and reader list is under Step 4.

Out of scope, recorded under Risks as follow-ups:
- the `rectify` revert race;
- the made-up 200 on other endpoints (`deleteSegment` 204, `createSegment` 201);
- drawer copy on a repeat erase.

## Repos in ship order
1. **api** — `imin-api`, base `master`, worktree `/Users/ivan/imin/imin-api/.claude/worktrees/erase-address-followups` (origin/master 950d7fa5). All code edits are here.
2. **webapp** — `imin-webapp`, base `main`. `npm run api:sync` only, run by the main session in `/ship-imin` after prod `/v3/api-docs` shows the marker (see Contract impact). No worker edits and no worker worktree.

## Affected files (per repo)

### imin-api (worker)
| # | File | Change |
|---|---|---|
| 1 | `src/main/java/com/imin/iminapi/audience/controller/AudienceController.java` | Add imports `io.swagger.v3.oas.annotations.responses.ApiResponse` and `ApiResponses`. Add `@ApiResponses` on `dsarErase` (:305) and `dsarEraseAddress` (:316), exact text in Step 2. The `dsarEraseAddress` javadoc (:312-315) is still true and is not edited. |
| 2 | `src/main/java/com/imin/iminapi/audience/service/DsarService.java` | Add `private static final String ERASE_PENDING = "erase_pending";`. In `requestErase` (:294-310), lock first, then return early when the member is `erase_pending` (Step 3); `m.setStatus` uses the constant. In `eraseAddress` (:419-422), drop the pre-check and its comment and call `requestErase` unconditionally. Update the class javadoc line :50 and the `requestErase` javadoc line :286 (Step 3). |
| 3 | `src/main/java/com/imin/iminapi/audience/model/Membership.java` | Comment at :147 becomes `/** Art.17 erasure schedule: now()+30d from the first erase request; a repeat never moves it. */` |
| 4 | `src/main/java/com/imin/iminapi/audience/repository/MarketingOptOutRepository.java` | Replace the class javadoc (:16-25) with the 2-line text in Step 4. |
| 5 | `src/main/java/com/imin/iminapi/audience/service/MarketingOptOutRecorder.java` | First javadoc sentence (:12-13) only, exact text in Step 4. Other paragraphs unchanged. |
| 6 | `src/test/java/com/imin/iminapi/audience/AudienceDsarTest.java` | One new test, `request_erase_again_keeps_the_first_erase_date_and_writes_nothing` (Step 1). Add one bullet to the class javadoc list (:42-55): "Art.17 repeat erase keeps the first erase date and writes nothing". |
| 7 | `docs/superpowers/plans/2026-10-28-erase-address-followups.md` | This plan, written by the main session. |

### imin-api: named in Blast radius, no change needed
- `src/test/java/com/imin/iminapi/checkoutreminder/CheckoutReminderErasureTest.java` stays green unedited.
  - Its `addressEraseOfAMemberAlreadyPending` case (:124-131) sets `erase_pending` plus `erase_at` through JDBC, then POSTs erase-address and expects 202 with the date unchanged.
  - After this change `eraseAddress` reaches `requestErase`. There the member is already in the persistence context (loaded at `DsarService.java:416-418` after the JDBC commit), so the lock hands back that snapshot with `erase_pending`, and the guard returns.
  - It reads no fixture, constant or data file this plan changes (grepped).
- `src/test/java/com/imin/iminapi/audience/service/DsarServiceLockOrderTest.java` stays green unedited.
  - The `lockByIdAndOrgId` stub (:57) returns `new Membership()`, whose status defaults to `"active"` (`Membership.java:38`). So `requestErase` passes the guard, reaches `fanFeatureRepo.deleteByMembershipId`, and the in-order verify (:63-65) still holds.
  - The `findByIdAndOrgId` stub (:56) is still used by the `require` after the unsubscribe.
- `src/test/java/com/imin/iminapi/audience/AudienceDsarScopeTest.java` (:268-275, 403 before any load) needs no change: the role check stays first.
- `AudienceDsarTest.another_orgs_member_returns_404[requestErase]` (:785-791) needs no change: the lock query carries `org_id`, so a foreign id gives an empty result and `ApiException.notFound("Membership")`, which is the same message "Membership not found" (`ApiException.java:34-36`).
- `ProfilingObjectionTest.java:79-89` and `EmailChannelSenderErasePendingTest.java:70-87` need no change: both erase an active member, which is the unchanged branch.
- The other writers of the `memberships` row need no change; see the writer table in Step 3. `DsarService.rectify` goes to a follow-up (Risks).
- `AudienceErasureJob` / `MembershipRepository.findErasureDue` (:354-355) need no change: they only read `erase_at`, which now keeps the first date.
- `buyer/service/BuyerAccountDeletionService.java` needs no change. It is a separate state machine and already keeps an existing date on a repeat (:110-120), which is the precedent followed here.
- The API's `CLAUDE.md:133` needs no change: "takes the member's erase (202)" is still true.

### imin-webapp (main session at ship, `api:sync` only)
- `docs/openapi.yaml` and `src/shared/api/generated-types.ts` are regenerated by `npm run api:sync`.
- `src/shared/api/types.ts` needs no edit: it has no hand-written type for either response (grep "erase" on origin/main finds nothing).
- `src/features/audience/api.ts` needs no edit: `useEraseAddress` already branches on 204/202 (:343-353) and `useDsarErase` treats any 2xx as success (:326-339).
- `MemberDrawer.tsx` and `copy.ts` need no edit in this task; see Risks.

## Ordered steps
0. **Baseline.**
   - `docker info` must succeed.
   - Run the full api gate once on the untouched worktree and record the result (Verification commands 1-2). If it is already red, stop and report: that failure is its own card.
   - Optionally record the prod baseline marker (Verification command 7; expected `['200'] ['200']`).
1. **Repro test, red first** (`AudienceDsarTest`, Art.17 section after :340). Write `request_erase_again_keeps_the_first_erase_date_and_writes_nothing`:
   - **Seed.** `UUID mid = seedMembership(orgA, fx.email("eraseagain"));`
   - **First request.** `dsarService.requestErase(orgA, mid, principalA);`
   - **Backdate as if the first request were 18 days old.** This mirrors `CheckoutReminderErasureTest.java:125-127`:
     ```java
     java.sql.Timestamp firstEraseAt = java.sql.Timestamp.from(
             Instant.now().plus(12, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS));
     jdbc.update("UPDATE memberships SET erase_at = ? WHERE membership_id = ?", firstEraseAt, mid);
     ```
   - **Second admin asks again.** `AuthPrincipal otherAdmin = new AuthPrincipal(UUID.randomUUID(), orgA, UserRole.ADMIN, UUID.randomUUID());` then `dsarService.requestErase(orgA, mid, otherAdmin);`
   - **Assert:**
     - `jdbc.queryForObject("SELECT erase_at FROM memberships WHERE membership_id = ?", java.sql.Timestamp.class, mid)` equals `firstEraseAt`;
     - `SELECT status` equals `"erase_pending"`;
     - `consentRepo.findByMembershipId(mid)` filtered on `"dsar_erase".equals(r.getSource())` has size 1;
     - `audit.assertRecorded(orgA, AuditActions.DSAR_ERASE_REQUESTED, "membership", mid)` passes. It asserts exactly one row (`AuditRows.java:27-38`).
   - **Run it before Step 3.** It must fail on the `erase_at` assertion: actual ≈ now+30d, expected now+12d. Paste the failure line into the worker report.
2. **OpenAPI** (`AudienceController.java`). Response texts below. Codes and branches are cited in the copy ledger.
   ```java
   @PostMapping("/members/{id}/erase")
   @ApiResponses({
           @ApiResponse(responseCode = "202", description = "Erase requested: data goes 30 days after the first request; a repeat keeps that date and changes nothing"),
           @ApiResponse(responseCode = "403", description = "FORBIDDEN: only an owner or admin can erase"),
           @ApiResponse(responseCode = "404", description = "NOT_FOUND: no such member in this org")})
   ```
   ```java
   @PostMapping("/erase-address")
   @ApiResponses({
           @ApiResponse(responseCode = "204", description = "No member in this org has the address; this org's records for it are erased now"),
           @ApiResponse(responseCode = "202", description = "The address is a member's; it took the member's erase (30 days after the first request)"),
           @ApiResponse(responseCode = "400", description = "FIELD_INVALID: email missing, blank or over 254 characters (INVALID_REQUEST: body not JSON)"),
           @ApiResponse(responseCode = "403", description = "FORBIDDEN: only an owner or admin can erase")})
   ```
   - **Annotation API at the pinned version.** From `swagger-annotations-jakarta-2.2.43.jar` (javap): `ApiResponse { String description(); String responseCode(); Header[] headers(); Link[] links(); Content[] content(); Extension[] extensions(); String ref(); boolean useReturnTypeSchema(); }` and `ApiResponses { ApiResponse[] value(); Extension[] extensions(); }`. `DateCheckController.java:45-47` already compiles against these.
   - **Why the made-up 200 disappears** (springdoc-openapi-starter-common 3.0.2, `pom.xml:94-96`; sources jar `GenericResponseService.java:420-440`):
     - `buildGenericApiResponses` walks the declared `@ApiResponse` codes when `apiResponsesOp` is non-empty.
     - It falls back to `evaluateResponseStatus` (the default "200") only in the `else` branch.
   - **Why no body schema is added:** `buildContent` returns null when `isVoid(returnType)` (:548-549), and `isVoid` unwraps `ResponseEntity<Void>` (:686-697).
   - **Prod precedent:** `create_2` at webapp `docs/openapi.yaml:905-917` shows only the declared 200/202.
3. **requestErase guard** (`DsarService.java`). Target shape; the comments stay 1-2 lines:
   ```java
   @Transactional
   public void requestErase(UUID orgId, UUID membershipId, AuthPrincipal principal) {
       requirePrivilegedRole(principal, "erase a data subject's record");
       // Membership row first, fan_features second: the same lock order as the projector's write.
       // Locked before anything else loads the row here, so the status read below is the committed one.
       Membership locked = membershipRepo.lockByIdAndOrgId(membershipId, orgId)
               .orElseThrow(() -> ApiException.notFound("Membership"));
       // A repeat writes nothing: the erase date stays 30 days after the first request.
       if (ERASE_PENDING.equals(locked.getStatus())) return;
       consentService.unsubscribe(orgId, membershipId, "dsar_erase", "email",
               ConsentOrigin.DATA_SUBJECT, principal);
       Membership m = require(orgId, membershipId);
       m.setStatus(ERASE_PENDING);
       m.setEraseAt(Instant.now().plus(30, ChronoUnit.DAYS));
       // ...rest unchanged (save, fan_features delete, DSAR_ERASE_REQUESTED audit)
   }
   ```
   - **What this replaces.** It removes the pre-lock `require(orgId, membershipId)` (:297). That call loaded the entity, so the native `FOR UPDATE` (`MembershipRepository.java:41-43`) handed back the cached instance without refreshing it (`CLAUDE.md` "Row locks").
   - **eraseAddress.** In `eraseAddress`, replace :420-421 with `requestErase(orgId, member.getMembershipId(), principal);`, keeping `return false;`.
   - **Javadocs:**
     - class javadoc :50 becomes "First request sets membership.status='erase_pending' + erase_at=now()+30d; a repeat changes nothing."
     - `requestErase` javadoc :286 becomes "The first request sets status='erase_pending', erase_at=now()+30d; a repeat on an erase_pending member writes nothing."
   - **Entry paths that reach this state.** Every path that writes `erase_pending`/`erase_at` now passes through this guard:
     - (a) `POST /api/v1/audience/members/{id}/erase` → `requestErase` (`AudienceController.java:308`);
     - (b) `POST /api/v1/audience/erase-address` → `eraseAddress` → `requestErase` (`DsarService.java:421`).
     - No scheduler, reconciler or admin path writes `erase_at`: grep `setEraseAt|erase_at` in `src/main/java` finds only `DsarService.java:304` as a writer. `AudienceErasureJob` reads it via `findErasureDue` (:354-355) and deletes.
   - **Existing writers of the same `memberships` row** (none has `@Version`/`@DynamicUpdate`, and `status`/`erase_at` are updatable, so every entity save writes them back):

     | Writer | file:line | How it loads | Can it write back an older status/erase_at? |
     |---|---|---|---|
     | `DsarService.requestErase` | DsarService.java:294-310 | lock first (after this change) | — (the writer) |
     | `DsarService.rectify` | DsarService.java:260-270 | `findByIdAndOrgId`, **no lock**, full save | **Yes, in a race**: loaded before an Erase commits and saved after, it writes `active`/NULL back. Pre-existing; follow-up (Risks, OPEN_QUESTIONS 3) |
     | `ConsentService.applyGrant` / `unsubscribe` | ConsentService.java:206, :261 via `requireMembership` :364-369 | flush, `FOR UPDATE`, `refresh` | No |
     | `AudienceOrderProjector.projectMembership` | AudienceOrderProjector.java:359, :373 via `lockOrCreateMembership` :134-141 | `FOR UPDATE` as first load | No |
     | `SmsConsentService.upsertMembership` | SmsConsentService.java:105-109 via `lockOrCreateMembership` | `FOR UPDATE` as first load | No |
     | `ResendWebhookProjector` complaint | ResendWebhookProjector.java:163-165; in `projectTaggedComplaint` (:203-216) the entity is first loaded unlocked at :208-210 in the same transaction | lock returns the cached snapshot | Yes, only in a race of a few milliseconds. Pre-existing; follow-up |
     | `ConsentConfirmationReconciler` RESTORE | ConsentConfirmationReconciler.java:38-50 | targeted UPDATE of consent columns only | No |
     | `MembershipRepository.updateNps` | MembershipRepository.java:45-49 | targeted UPDATE of `nps` | No |

   - **This change adds no new writer and does not widen either race.** The early return is a skip.
4. **Opt-out javadocs.**
   - `MarketingOptOutRepository.java` class javadoc (:16-25) becomes, with the worker free to rewrap inside 2 lines:
     ```java
     /**
      * Sticky marketing opt-outs per (address, org, channel); cross-tenant, so callers scope it. Only MarketingOptOutRecorder
      * writes it: own unsubscribes and checkout refusals, complaints, reminder one-click. Read by consent, reminder, opt-in checks.
      */
     ```
   - `MarketingOptOutRecorder.java:12-13` first sentence becomes: "Writes the sticky {@code marketing_optouts} row for a {@code DATA_SUBJECT} unsubscribe and, via {@code StickyOptOutWriter}, for complaints and reminder one-click."
   - **Writers.** Every write goes through `MarketingOptOutRecorder.record` → `saveAndFlush` (MarketingOptOutRecorder.java:71):
     - `ConsentService.recordStickyOptOut` (ConsentService.java:268-269, :308-321) on every DATA_SUBJECT unsubscribe:
       - campaign one-click/footer (`PublicUnsubscribeController.java:117`);
       - `dsar_object` / `dsar_erase` (DsarService.java:279, :300);
       - `checkout_refusal` (AudienceOrderProjector.java:205-211);
       - SMS STOP with channel `sms` (SmsStopService.java:51-52);
       - the buyer preference centre (BuyerUnsubscribeRunner.java:53).
     - `StickyOptOutWriter.recordOrFail` (StickyOptOutWriter.java:29-31):
       - complaint on an untracked campaign mail (ResendWebhookProjector.java:216);
       - complaint on a reminder (CheckoutReminderDeliveryProjector.java:67);
       - reminder one-click (CheckoutReminderService.java:289).
   - **Readers** (email channel):
     - `ConsentGateSql.java:61-63`;
     - `MarketingOptedOutCheck.java:60`;
     - `CheckoutReminderGate.java:102`;
     - `PublicOptInGrant.java:92`;
     - `SurveyService.java:213`;
     - `BuyerPreferencesService.java:309-313` (account toggle);
     - the recorder's own `existsById` (:65, :86).
   - **Deleters:** `AudienceRetentionPurgeJob.java:129, :139` and `BuyerAccountErasureService.java:301`.
   - **Not a reader:** `SendGateService.evaluate` (SendGateService.java:70-110), which is why the text names the consent gate, not "every gate".
5. **Guard proof.**
   - Comment out the `if (ERASE_PENDING.equals(locked.getStatus())) return;` line.
   - Run the new test and `CheckoutReminderErasureTest` (Verification command 4). Both must go red; in the latter, the `addressEraseOfAMemberAlreadyPending` case.
   - Restore the line, confirm with `git diff` that it is present, and re-run green.
6. **Gates.**
   - Targeted run (Verification command 5), then the full gate (Verification command 6).
   - Read the `Tests run:` line. Skipped Testcontainers tests count as red.
   - Optionally run the local OpenAPI probe (Verification command 8).
7. **Hand to review.** `git status` must show only the files in the imin-api table. No scratch probe file may remain.

## Verification commands
All commands run from `/Users/ivan/imin/imin-api/.claude/worktrees/erase-address-followups`. Logs go to `<scratchpad>/gate-api-erase-address-followups*.log` (one log per worker).

1. `docker info >/dev/null 2>&1 && echo docker-up` — must print `docker-up`.
2. Baseline before any edit: `./mvnw test > <scratchpad>/gate-api-erase-address-followups-base.log 2>&1; grep -E 'Tests run:.*Fail|BUILD|Skipped' <scratchpad>/gate-api-erase-address-followups-base.log | tail -n 5`
3. Red first (after Step 1, before Step 3): `./mvnw test -Dtest='AudienceDsarTest#request_erase_again_keeps_the_first_erase_date_and_writes_nothing'` — expect 1 failure on `erase_at`.
4. Guard proof (Step 5): `./mvnw test -Dtest='AudienceDsarTest#request_erase_again_keeps_the_first_erase_date_and_writes_nothing,CheckoutReminderErasureTest'` — red with the guard commented out, green with it restored.
5. Targeted (comma-separated, not `+`): `./mvnw test -Dtest=AudienceDsarTest,CheckoutReminderErasureTest,DsarServiceLockOrderTest,AudienceDsarScopeTest,ProfilingObjectionTest,EmailChannelSenderErasePendingTest` — judge only by this run's own `Tests run:` line.
6. Full api gate: `./mvnw test > <scratchpad>/gate-api-erase-address-followups.log 2>&1; grep -E 'Tests run:|BUILD|Skipped' <scratchpad>/gate-api-erase-address-followups.log | tail -n 5` — BUILD SUCCESS, 0 failures, 0 errors, no skipped Testcontainers classes.
7. Prod marker, read-only (main session, after the Railway deploy, ≈3 min):
   `curl -s https://imin-api-production.up.railway.app/v3/api-docs | python3 -I -c 'import json,sys; p=json.load(sys.stdin)["paths"]; print(sorted(p["/api/v1/audience/erase-address"]["post"]["responses"]), sorted(p["/api/v1/audience/members/{id}/erase"]["post"]["responses"]))'`
   - expected after deploy: `['202', '204', '400', '403'] ['202', '403', '404']`;
   - before deploy: `['200'] ['200']`.
8. Optional local probe. **Never commit it.** Add a temporary `@IminIntegrationTest` method that does `mvc.perform(get("/v3/api-docs"))` and prints `$.paths['/api/v1/audience/erase-address'].post.responses` and `$.paths['/api/v1/audience/members/{id}/erase'].post.responses`. Run it, then delete it; `git status` must not list it.
9. webapp (main session, `/ship-imin`, after command 7 shows the marker): `npm run api:sync && npm run typecheck && npm run lint && npm test && npm run check:i18n`. `api:check` runs in /ship after the api deploys (`api:fetch` reads prod).

## Test impact
**Branches of the changed logic** (8 rows):

| # | Method | Branch | Covered by |
|---|---|---|---|
| R1 | `requestErase` | caller not OWNER/ADMIN (or a gate device) → 403 | existing `AudienceDsarScopeTest.a_member_cannot_request_erasure` (:268-275) |
| R2 | `requestErase` | member missing or in another org → 404 (now from the lock lookup) | existing `AudienceDsarTest.another_orgs_member_returns_404[requestErase]` (:785-791) |
| R3 | `requestErase` | member active → unsubscribe, `erase_pending`, `erase_at` = now+30d, fan_features gone, one audit row | existing `AudienceDsarTest` :317-330, :332-340, :346-356; lock order in `DsarServiceLockOrderTest` :47-66 |
| R4 | `requestErase` | member already `erase_pending` → nothing written | **new** test (below) |
| A1 | `eraseAddress` | address blank after normalizing → 400 | unchanged code, not re-tested |
| A2 | `eraseAddress` | no member → erased now, 204 | existing `CheckoutReminderErasureTest[addressEraseWithNoMember]` (:107-115) |
| A3 | `eraseAddress` | active member → member erase, 202 | existing `CheckoutReminderErasureTest[addressEraseOfAMember]` (:117-122) |
| A4 | `eraseAddress` | pending member → 202, date kept (guard now in `requestErase`) | existing `CheckoutReminderErasureTest[addressEraseOfAMemberAlreadyPending]` (:124-131) |

**Planned new tests** (1 row):

| Test file | Test | Kind | "What gets a test" item | Bug it catches |
|---|---|---|---|---|
| `src/test/java/com/imin/iminapi/audience/AudienceDsarTest.java` | `request_erase_again_keeps_the_first_erase_date_and_writes_nothing` | integration (`@IminIntegrationTest`, real Postgres) | consent, DSAR, erasure, suppression, legal identity | A second Erase on a member already pending (another admin, a drawer reopened by link, a retried request) pushes the deletion back 30 days. The person's data then outlives the "removed 30 days after the first erase request" promise in the dashboard, and an auditor sees two erase requests and two "erasure request" consent rows for one request. |

- The test adds nothing to the context: no mock beans, property sources or imports. It uses the autowired `DsarService`, `JdbcTemplate`, `ConsentRecordRepository` and `AuditRows`. It needs no value captured at startup.
- `requestErase` reads `Instant.now()` directly, not `MutableClock`, so the test backdates through JDBC instead of moving the clock.
- No test for the `@ApiResponses` annotations: "annotation reflection" and "framework behaviour" are on the "what does not get a test" list. The prod marker check (Verification command 7) is the evidence.
- No test for the javadocs: "source file contains X".
- No concurrency test for lock-first ordering. The drawer disables Erase while its request runs (`MemberDrawer.tsx:417`, `busy={eraseMut.isPending}`). What remains is two admins at the same instant: the second waits on the row lock, then sees `erase_pending`. Before this change it would only have moved the date by the lock wait.
- Existing tests that stay green unedited, each re-checked against the changed call sequence: `CheckoutReminderErasureTest`, `DsarServiceLockOrderTest`, `AudienceDsarScopeTest`, `ProfilingObjectionTest`, `EmailChannelSenderErasePendingTest`, and the rest of `AudienceDsarTest`. Reasons are in Affected files. The plan changes no fixture, constant or data file, so none of them reads changed data.

**Reproduction (2026-10-28, untouched master 950d7fa5).** Baseline `./mvnw test`: Tests run: 8552, Failures: 0, Errors: 0, Skipped: 0. Repro:
```
[ERROR] AudienceDsarTest.request_erase_again_keeps_the_first_erase_date_and_writes_nothing:356
expected: 2026-11-09T09:49:43.857 (java.sql.Timestamp)
 but was: 2026-11-27T09:49:43.873 (java.sql.Timestamp)
```

## Live-test
API only, with no UI change. The worker live-tests nothing in prod.
1. After the Railway deploy, the main session runs Verification command 7. Expected `['202', '204', '400', '403'] ['202', '403', '404']`. Record the output in "Live-test evidence".
2. Optional read-only prod check (main session, `railway psql`): `SELECT count(*) FROM memberships WHERE status = 'erase_pending' AND erase_at IS NULL;` should return 0. This confirms that a pending row without a date (which the guard would leave stuck) does not exist. The code writes both together at `DsarService.java:303-304`, so it is unreachable.
3. Item 2 is **not** exercised in prod: that needs a DSAR erase on a real member, which is a prod write. The integration test covers it.
4. After `api:sync`, the webapp diff touches only `docs/openapi.yaml` and `generated-types.ts`. The main session reviews it for unrelated prod drift that the sync also pulls in.

## Contract impact
- **`/api/v1` change, docs only.** Two existing operations get true response sets. There is no new path, no schema change and no status-code change on the wire.
- **Marker in prod OpenAPI** (`/v3/api-docs`):
  - `paths['/api/v1/audience/erase-address'].post.responses` keys are exactly `202, 204, 400, 403`;
  - `paths['/api/v1/audience/members/{id}/erase'].post.responses` keys are exactly `202, 403, 404`;
  - neither contains `200`.
- **webapp:** the main session runs `npm run api:sync` in `/ship-imin` after the marker is live. It regenerates `docs/openapi.yaml` and `src/shared/api/generated-types.ts`.
  - `src/shared/api/types.ts` needs no edit: there is no hand-written type for these responses.
  - UI code does not import the generated types.
  - Between the api deploy and the sync commit, the webapp `api:check` reports drift. That is expected and closed by the sync.
- **`imin-public/docs/PUBLIC_PAGE_API.md`:** no change, because `/api/v1/public` is untouched.

## i18n impact
None. No UI string is added or changed in any repo. The OpenAPI descriptions are developer-facing English. EN/ES/FR/UK do not apply.

**Copy ledger** (OpenAPI descriptions, plus the existing webapp strings whose truth this task changes):

| String | Field / branch behind it (file:line) | Meaning, scope, range | Shown next to |
|---|---|---|---|
| dsarErase 202 "Erase requested: data goes 30 days after the first request; a repeat keeps that date and changes nothing" | `AudienceController.java:309` always `accepted()`. `requestErase` sets `erase_at` = now+30d on the first request only (DsarService.java:304 behind the new guard). The erasure job deletes when `erase_at <= now` (MembershipRepository.java:354-355). | Per membership; exact instant, not approximate | OpenAPI only |
| dsarErase 403 "FORBIDDEN: only an owner or admin can erase" | `requirePrivilegedRole` DsarService.java:210-217 → `ApiException.forbidden` ApiException.java:37-39 → GlobalExceptionHandler.java:32-36 | MEMBER role or gate device | OpenAPI only |
| dsarErase 404 "NOT_FOUND: no such member in this org" | lock lookup `orElseThrow(ApiException.notFound("Membership"))` (new; ApiException.java:34-36) | missing id or another org's id (no-leak) | OpenAPI only |
| dsarEraseAddress 204 "No member in this org has the address; this org's records for it are erased now" | DsarService.java:424-429 returns true → AudienceController.java:320 `noContent()` | no membership for (org, normalized address) | OpenAPI only |
| dsarEraseAddress 202 "The address is a member's; it took the member's erase (30 days after the first request)" | DsarService.java:419-422 (after the change: unconditional `requestErase`, false) → AudienceController.java:320 `accepted()` | member active (now pending) or already pending (date kept) | OpenAPI only |
| dsarEraseAddress 400 "FIELD_INVALID: email missing, blank or over 254 characters (INVALID_REQUEST: body not JSON)" | `@NotBlank @Size(max = 254)` AudienceController.java:390 → GlobalExceptionHandler.java:39-50; blank after normalizing DsarService.java:412-415; unreadable body GlobalExceptionHandler.java:52-56 | client input | OpenAPI only |
| dsarEraseAddress 403 "FORBIDDEN: only an owner or admin can erase" | DsarService.java:410 → :210-217 | as above | OpenAPI only |
| webapp `copy.ts:2009` scheduledBody "…their data is removed 30 days after the first erase request…" (existing, unchanged) | `memberships.erase_at`, written by the first request only (DsarService.java:304 behind the guard) | per member; exact | EraseAddressCard after a 202 |
| webapp `copy.ts:2004` confirmBody "…removed 30 days after the first request…" (existing, unchanged) | same | same | EraseAddressCard confirm dialog |
| webapp `copy.ts:1962-1964` eraseBody / eraseCta / eraseToast and `:1906` erasePendingBody "…removed after 30 days" (existing, unchanged) | same field. On a repeat in a fresh drawer session the removal is 30 days after the **first** request, i.e. sooner; the drawer cannot know because `MemberDto` has no status (MemberDrawer.tsx:64-65) | overstates the remaining time on a repeat only | Drawer confirm, toast, banner (MemberDrawer.tsx:246-247, :410-418). Flagged: OPEN_QUESTIONS 4 / Risks |

## Blast radius
- **DSAR / Art.17 (legal):** `DsarService.requestErase` now writes nothing for a member already `erase_pending`. It is reached from `AudienceController.dsarErase` (:308) and `DsarService.eraseAddress` (:421), and nothing else in `src/main` calls it. For an active member the behaviour is unchanged: same statements, same order, same audit row. The erasure job reads `erase_at` (`AudienceErasureJob`, `MembershipRepository.java:354-355`) and now sees the first request's date.
- **Membership row writers:** see the table in Step 3. None is edited. Two pre-existing revert races, in `rectify` and the tagged-complaint path, are recorded as follow-ups.
- **`/api/v1` contract:** OpenAPI response sets for `dsarErase` and `dsarEraseAddress` change. Wire behaviour does not. Consumer: webapp `api:sync` only.
- **Comments only:** `MarketingOptOutRepository`, `MarketingOptOutRecorder`, `Membership.eraseAt` and the `DsarService` javadocs. No runtime effect.
- **Tests re-checked unedited:** `CheckoutReminderErasureTest`, `DsarServiceLockOrderTest`, `AudienceDsarScopeTest`, `ProfilingObjectionTest`, `EmailChannelSenderErasePendingTest`, `AudienceDsarTest`.
- No money, Stripe, auth, Flyway migration or shared module is touched.

## Risks
- **Pre-existing, not widened:**
  - `DsarService.rectify` (:260-270) loads the member without a lock and saves every column. A Rectify that loads before an Erase commits and saves after it writes `status='active', erase_at=NULL` back and silently cancels the erasure.
  - The same shape, with a window of a few milliseconds, exists in `ResendWebhookProjector.projectTaggedComplaint` (:203-216 → :163-165).
  - It also exists in `eraseAddress`, whose lookup (:416-418) loads the member before `requestErase` locks it, so the guard reads that snapshot, exactly as the pre-check does today.
  - Proposed follow-up card, either route:
    - (a) `rectify` locks first (one line) plus a test of both orderings with `PgFaults.pauseWrites`;
    - (b) write `status`/`erase_at` through a targeted UPDATE with `updatable=false`, like `nps` (Membership.java:136-138). This route touches 19 test seeding sites in 5 files (`grep 'setStatus("erase_pending")\|setEraseAt('` in `src/test`). OPEN_QUESTIONS 3.
- **Drawer copy on a repeat:** see the copy ledger, last row. Data subjects are not harmed (deletion comes sooner, not later), but the organizer reads "after 30 days". Proposed webapp follow-up: expose `status`/`eraseAt` on `MemberDto` so the drawer shows the real date or "already requested". OPEN_QUESTIONS 4.
- **The same made-up statuses elsewhere:** `deleteSegment` (204, published as 200 at webapp `docs/openapi.yaml:5393-5395`), `createSegment` (201, published as 200 at :2721-2727), and likely every other `ResponseEntity` that returns a non-200 status without `@ApiResponses`. Proposed follow-up: one sweep card.
- **`api:sync` picks up all prod drift**, not only these two operations. The main session reviews the sync diff before committing.
- **Size:** 6 code/test files plus the plan, one concern each. No split needed.

## Definition of done
- The new test fails before Step 3 on `erase_at`; the failure line is in the worker report. After Step 3 it passes, and Step 5 showed it and `CheckoutReminderErasureTest[addressEraseOfAMemberAlreadyPending]` both red without the guard.
- The full api gate is green on Postgres Testcontainers with no skipped classes, and the baseline run is recorded.
- `requestErase` locks first and returns without writing for an `erase_pending` member. `eraseAddress` calls it unconditionally.
- `@ApiResponses` are on both endpoints with the texts in Step 2, and the javadocs from Steps 3-4 are in place.
- The prod `/v3/api-docs` marker shows `['202', '204', '400', '403'] ['202', '403', '404']`.
- The webapp `api:sync` commit (main session) contains only the regenerated `docs/openapi.yaml` and `generated-types.ts`, and its gate is green.
- `docs/superpowers/BACKLOG.md` items at :25 and :26 are deleted in the workspace repo by the main session at ship. Follow-up cards for the Risks are added there if Ivan agrees.

## Live-test evidence
(filled after deploy: Verification command 7 output before and after, optional SQL count, webapp sync diff stat)

## Review rounds
- round 2 (after fix round 1, 2026-11-03, Sonnet) → APPROVE (CLEAN). requestErase flushes, locks and refreshes before the erase_pending check (race closed on both paths); new test a_concurrent_first_erase_is_seen_under_the_lock, case [2] red without the refresh. LOW: the lock+refresh repeats ConsentService.requireMembership's (harmless, re-entrant). Gate 8555, 0 failures, 0 skipped.
- round 1 (2026-10-28, Sonnet) → APPROVE. MEDIUM: on the eraseAddress path the member is already managed before requestErase locks it, so the native FOR UPDATE returns the cached status; two concurrent erase-address calls could still move erase_at and double-write audit/consent rows. Fix round 1: refresh the locked entity (as ConsentService.requireMembership does) + a both-orderings test.
(filled by /do-task)
