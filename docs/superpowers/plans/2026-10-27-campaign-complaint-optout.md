# Campaign complaint: retry the webhook until the opt-out is recorded
campaign-complaint-optout · Subagent (webhooks) · Backlog item: none given (follow-up to api 8d6116c3, plan `docs/superpowers/plans/2026-10-23-checkout-reminder-complaint-optout.md`)

**Plan gate:** approved by Ivan 2026-10-28. D1 = option B (extract StickyOptOutWriter). D2 done: Sentry 0 hits.

## Goal and scope

Someone reports a campaign email as spam. Resend sends the event tagged `imin_campaign`, but no recipient row matches it (the row was purged or never recorded) and the address has no membership in that organizer's audience. In that case the only record of the objection is the per-address opt-out row in `marketing_optouts` (source `complaint`). Today `ResendWebhookProjector.projectTaggedComplaint` (`src/main/java/com/imin/iminapi/marketing/webhook/ResendWebhookProjector.java:215-222`) catches any non-duplicate failure of `MarketingOptOutRecorder.record`, logs it and carries on. The webhook answers 200 and the svix claim commits, so Resend never retries. The person can then be mailed again by that organizer:
- campaign send gate: `audienceplan/repository/ConsentGateSql.java:60-61` maps an opt-out row to `unsubscribed`;
- checkout reminders: `checkoutreminder/service/CheckoutReminderGate.java:102`;
- public opt-in grants: `audienceplan/service/PublicOptInGrant.java:92`;
- `audience/service/MarketingOptedOutCheck.java:62-65`.

Fix: the branch calls a fail-loud opt-out write, the same one the checkout-reminder complaint got in 8d6116c3. If the write fails it throws 500. The webhook's transaction rolls back, including the svix claim, and Svix redelivers until the row exists.

**Audit of the other ResendWebhookProjector branches (the task's ask).** Of the 11 rows, only row 7 is this defect:

| # | Branch | file:line | Write | On failure | Verdict |
|---|---|---|---|---|---|
| 1 | matched complaint, member | `ResendWebhookProjector.java:152-170` | `SuppressionService.addMarketingIfAbsent` (`SuppressionService.java:97-107`), objection on the locked membership | joins the webhook transaction, no catch: 500, claim rolled back | fail-loud already |
| 2 | matched complaint, recipient row + breaker | `ResendWebhookProjector.java:173-174` | `markComplained`, `ComplaintRateBreaker.evaluate` | no catch | fail-loud already |
| 3 | Permanent bounce | `ResendWebhookProjector.java:133-138` | `addDeliverabilityIfAbsent` (`SuppressionService.java:169-172`) | no catch | fail-loud already |
| 4 | soft-bounce escalation | `ResendWebhookProjector.java:140`, `:255-268` | `addMarketingIfAbsent` | no catch | fail-loud already |
| 5 | unmatched Permanent bounce | `ResendWebhookProjector.java:190-195` | `addDeliverabilityIfAbsent` | no catch | fail-loud already |
| 6 | tagged complaint, member at the address | `ResendWebhookProjector.java:211-213` (goes to row 1) | as row 1 | as row 1 | fail-loud already |
| 7 | **tagged complaint, no member** | `ResendWebhookProjector.java:215-222` | `MarketingOptOutRecorder.record` (REQUIRES_NEW, `MarketingOptOutRecorder.java:58-68`) | `catch (RuntimeException)`: logged, 200, claim committed | **swallowed: fixed here** |
| 8 | checkout-reminder complaint | `checkoutreminder/service/CheckoutReminderDeliveryProjector.java:64-72` | `recordStickyOptOutOrFail` | throws | fixed in 8d6116c3 |
| 9 | unsubscribe | not projected (`ResendWebhookProjector.java:39-42`); an unsubscribe-link click writes nothing (`:231`) | — | — | n/a |
| 10 | owned one-click / STOP unsubscribe (not this webhook) | `audience/service/ConsentService.java:308-341` | sticky row as a documented bonus; the membership's `unsubscribed` consent record in the caller's transaction is the opt-out | swallows by documented design | different class, not touched |
| 11 | `SUPPRESSION_ADDED` audit row | `service/audit/AuditLogger.java:104-108` | audit only | swallows by design ("audit failures must not break the surrounding business txn") | different class, not touched |

No other bounce or unsubscribe branch shares the defect, so none is fixed here.

**Every path that records a complaint.** `grep TYPE_COMPLAINED|markComplained src/main` finds only rows 1, 6, 7 and 8, plus readers (`ComplaintRateBreaker.java:89,98`, `MarketingChannelsService.java:114`). No scheduler, reconciler or admin endpoint records a complaint. After this change all four writer paths fail loud.

**Idempotency and claim ordering (verified).**
- `ResendWebhookController.receive` is `@Transactional` (`ResendWebhookController.java:78-80`).
- The claim is a JDBC INSERT through `ProviderEventDedupService.tryClaim` (`ProviderEventDedupService.java:45-63`; its javadoc at `:14-17` states it commits or rolls back with the projector) at `ResendWebhookController.java:150-152`. That is before projection and in the same transaction.
- `projectTaggedComplaint` is `@Transactional` (REQUIRED) and joins that transaction. An `ApiException` (a `RuntimeException`, `security/ApiException.java:7`) thrown from it marks the transaction rollback-only and propagates. `GlobalExceptionHandler.java:33-37` answers with its status, 500. The claim never commits.
- The opt-out row itself commits in the recorder's own REQUIRES_NEW transaction before the outer commit. If the outer transaction later fails, the retry's `existsById` short-circuits (`MarketingOptOutRecorder.java:63`), so the write is idempotent.
- The precedent `CheckoutReminderDeliveryTest#aComplaintWhoseOptOutWriteFailsIsRetriedUntilRecorded` (`:112`) proves the rollback-then-redeliver behaviour empirically through the same controller.

**Decisions for the gate**
- **D1 (recommended B).**
  - B: move `CheckoutReminderService.recordStickyOptOutOrFail` and `logStickyFailure` (`CheckoutReminderService.java:302-330`) unchanged into a new `audience/service/StickyOptOutWriter.recordOrFail(email, orgId, source)`. All three callers use it: campaign complaint, reminder complaint, reminder one-click unsubscribe. The audience package already owns `marketing_optouts` and the recorder. The log line becomes `[sticky-optout] <source> …` instead of `[checkout-reminder] …`.
  - A (minimal): inject `CheckoutReminderService` into `ResendWebhookProjector` and call `recordStickyOptOutOrFail(address, orgId, "complaint")`. One main file, but every log line from a campaign complaint would be prefixed `[checkout-reminder]`, which misleads anyone triaging in Sentry.
- **D2:** an optional read-only search of past losses (see Live-test).

Out of scope: rows 9-11, the Bird/SMS webhook, and any backfill.

## Repos in ship order

| # | repo | base | worktree |
|---|---|---|---|
| 1 | api (`imin-api`) | `master` (02010c46) | `/Users/ivan/imin/imin-api/.claude/worktrees/campaign-complaint-optout` |

## Affected files (per repo)

api, 8 files (option B):

| # | File | Change |
|---|---|---|
| 1 | `src/main/java/com/imin/iminapi/audience/service/StickyOptOutWriter.java` (new, `@Component`) | `public void recordOrFail(String email, UUID orgId, String source)`, moved unchanged from `CheckoutReminderService.java:302-324`: calls `MarketingOptOutRecorder.record(email, orgId, "email", source)`. A `DataIntegrityViolationException` with `exists(...)` true means a lost race and returns quietly. Any other failure, or a failed `exists` read, logs and throws `new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "The opt-out was not recorded. Try again.")`. Logging is moved from `:326-330`, with the "no throwable" comment kept, as `log.error("[sticky-optout] {} opt-out not recorded for org {}: {}: {}", source, orgId, e.getClass().getSimpleName(), LogSafe.redact(e.getMessage()))`. Javadoc in 1-2 lines: the row is written in its own transaction; a lost race is the row we wanted; anything else throws so the sender retries. |
| 2 | `src/main/java/com/imin/iminapi/checkoutreminder/service/CheckoutReminderService.java` | Constructor parameter and field `MarketingOptOutRecorder optOutRecorder` (`:96`, `:108`, `:123`) become `StickyOptOutWriter stickyOptOuts`. `unsubscribe` (`:294`) calls `stickyOptOuts.recordOrFail(r.getEmailNormalized(), r.getOrgId(), "one_click")`. Delete `recordStickyOptOutOrFail` and `logStickyFailure` (`:302-330`) and the imports left unused: `MarketingOptOutRecorder` (`:7`), `LogSafe` (`:40`, only use `:329`), `DataIntegrityViolationException` (`:43`, only use `:310`). `ApiException`, `HttpStatus` and `ErrorCode` stay, since `:221`, `:228`, `:260`, `:264` and `:267` still use them. |
| 3 | `src/main/java/com/imin/iminapi/checkoutreminder/service/CheckoutReminderDeliveryProjector.java` | Its only use of `CheckoutReminderService` is `:66`, so replace that dependency (`:34`, `:39`, `:43`) with `StickyOptOutWriter`. The call at `:66` becomes `stickyOptOuts.recordOrFail(email, r.getOrgId(), "complaint")`. The class javadoc (`:21-26`) stays true. |
| 4 | `src/main/java/com/imin/iminapi/marketing/webhook/ResendWebhookProjector.java` | Import, field, constructor parameter and assignment (`:6`, `:63`, `:75`, `:86`) change from `MarketingOptOutRecorder optOuts` to `StickyOptOutWriter stickyOptOuts`. Replace the try/catch at `:215-222` with `stickyOptOuts.recordOrFail(address, orgId, "complaint");`. The breaker call at `:223` stays after it. Extend the `projectTaggedComplaint` javadoc (`:197-201`): "a failed opt-out write throws, so the webhook answers 500 unclaimed and Resend retries." |
| 5 | `src/main/java/com/imin/iminapi/marketing/webhook/ResendWebhookController.java` | Javadoc only (`:33-43`, the response contract): add "500, with nothing claimed, when projection fails (e.g. a complaint's opt-out could not be recorded), so Svix retries". This keeps the documented response set in step with the change, per `imin-api/CLAUDE.md` § Engineering rules. |
| 6 | `src/test/java/com/imin/iminapi/marketing/ResendWebhookControllerTest.java` | New test `aTaggedComplaintWhoseOptOutWriteFailsIsRetriedUntilRecorded`, a helper `optOuts(email, orgId)` and `import com.imin.iminapi.support.PgFaults;`. Optional: pull the campaign setup shared with `aTaggedComplaintWithNoRowSuppressesByAddress` (`:450-460`) into a `sentCampaign(orgId)` helper; that test's assertions stay as they are. |
| 7 | `CLAUDE.md` (line 81, the "Resend webhook grace and two secrets" bullet) | After "…records the per-address opt-out (`marketing_optouts`, source `complaint`)" add: "; a failed opt-out write answers 500 before anything is claimed, so Svix retries until it lands". |
| 8 | `docs/superpowers/plans/2026-10-27-campaign-complaint-optout.md` | This plan. |

Option A instead changes only row 4 (inject `CheckoutReminderService`, call `recordStickyOptOutOrFail(address, orgId, "complaint")`) plus rows 5-8. Rows 1-3 drop out.

Read, no change needed:
- `marketing/webhook/ProviderEventDedupService.java`: the claim already shares the controller's transaction.
- `audience/service/MarketingOptOutRecorder.java`: REQUIRES_NEW and "catch lives in the caller" (`:31-35`) stay true, because the writer is now the caller.
- `audience/service/SuppressionService.java`: rows 1 and 3-6 already fail loud.
- `marketing/service/ComplaintRateBreaker.java`: unchanged; it only runs after the opt-out lands.
- `security/GlobalExceptionHandler.java`: already maps `ApiException` to its status.
- `audience/service/ConsentService.java`: row 10, best-effort by design.
- `service/audit/AuditLogger.java`: row 11, by design.
- `audienceplan/repository/ConsentGateSql.java`, `audience/service/MarketingOptedOutCheck.java`, `checkoutreminder/service/CheckoutReminderGate.java`, `audienceplan/service/PublicOptInGrant.java`: readers only; they now see the row that used to be lost.
- `src/test/.../checkoutreminder/CheckoutReminderDeliveryTest.java`, `CheckoutReminderUnsubscribeTest.java`: work through HTTP and assert status and table counts, not class internals. Neither asserts the 500 message (`grep 'not recorded' src/test` finds nothing relevant), and the plan changes no fixture, constant or data file they read.
- `src/test/.../audience/StickyMarketingOptOutIsolationTest.java`: the `ConsentService` path is unchanged.

## Ordered steps

0. **Baseline.** `docker info` must succeed. Run `./mvnw test` once on the untouched worktree and read its own `Tests run:` line, with no skipped Testcontainers tests. If the base is red, stop: that is its own card.
1. **Repro, test only.** Add to `ResendWebhookControllerTest` only `import com.imin.iminapi.support.PgFaults;`, the helper and the test below. Do not touch `src/main`.
   ```java
   /** A complaint whose opt-out could not be written is not acknowledged: nothing is claimed and Resend's retry records it. */
   @Test
   void aTaggedComplaintWhoseOptOutWriteFailsIsRetriedUntilRecorded() throws Exception {
       UUID orgId = fx.org().getId();
       orgIds.add(orgId);
       Campaign c = new Campaign();
       c.setId(UUID.randomUUID());
       c.setOrgId(orgId);
       c.setChannel("email");
       c.setName("purged rows");
       c.setStatus("sent");
       c.setCreatedAt(Instant.now());
       c.setUpdatedAt(Instant.now());
       campaignRepo.save(c);
       String email = fx.email("complainer");            // no consumer, no membership: the opt-out row is the whole record
       String svixId = "svix_" + UUID.randomUUID();
       String body = taggedBody("email.complained", "msg_" + UUID.randomUUID(), email, c.getId(),
               Instant.now().minus(2, ChronoUnit.HOURS));

       try (PgFaults.Fault ignored = PgFaults.failWrites(jdbc, "marketing_optouts", "org_id", orgId)) {
           mvc.perform(signed(svixId, body)).andExpect(status().isInternalServerError());
       }
       assertThat(optOuts(email, orgId)).isZero();
       assertThat(claimed(svixId)).isZero();

       mvc.perform(signed(svixId, body)).andExpect(status().isOk());
       assertThat(optOuts(email, orgId)).isEqualTo(1);
       assertThat(claimed(svixId)).isEqualTo(1);
   }

   private int optOuts(String email, UUID orgId) {
       return jdbc.queryForObject("select count(*) from marketing_optouts where email_normalized = ? and org_id = ?",
               Integer.class, email, orgId);
   }
   ```
   Run `./mvnw test -Dtest='ResendWebhookControllerTest#aTaggedComplaintWhoseOptOutWriteFailsIsRetriedUntilRecorded'`. The expected result is `Tests run: 1, Failures: 1` with `Status expected:<500> but was:<200>` at the first `andExpect`. On base the swallow commits the claim, so even past that line the retry would be deduped and the count would read 0 where 1 is expected. Any other failure (503, context error, NPE) means the repro is invalid: stop and report.
2. Create `StickyOptOutWriter` (Affected file 1), moving the method unchanged.
3. Rewire `CheckoutReminderService` (file 2) and `CheckoutReminderDeliveryProjector` (file 3). Delete the old methods and the imports left unused.
4. In `ResendWebhookProjector` (file 4), replace `:215-222` with the writer call and keep the breaker after it. This leaves all four complaint paths fail-loud: `ResendWebhookProjector.java:150-175` (member, same transaction), `:211-213` (member, same as above), `:215-222` (this step) and `CheckoutReminderDeliveryProjector.java:64-72`. No scheduler, reconciler or admin endpoint records a complaint.
5. Update the javadoc (files 4 and 5) and `CLAUDE.md:81` (file 7).
6. Targeted run: `./mvnw test -Dtest=ResendWebhookControllerTest,CheckoutReminderDeliveryTest,CheckoutReminderUnsubscribeTest,StickyMarketingOptOutIsolationTest` (comma-separated). All green.
7. Mutation proofs. Run each one alone, then revert it and confirm `git diff` shows no mutation left.
   - **M1:** in `projectTaggedComplaint`, wrap the writer call in `try { … } catch (RuntimeException e) { }`. The new test goes red with `Status expected:<500> but was:<200>`.
   - **M2:** in `StickyOptOutWriter.recordOrFail`, replace the final `throw` with `return`. The new test goes red, and so do `CheckoutReminderDeliveryTest#aComplaintWhoseOptOutWriteFailsIsRetriedUntilRecorded` and `CheckoutReminderUnsubscribeTest#anOptOutThatCouldNotBeRecordedAnswers500SoTheClientRetries`.
8. Full gate: `./mvnw test`. Read the `Tests run:` line and confirm nothing was skipped.

## Verification commands

```bash
cd /Users/ivan/imin/imin-api/.claude/worktrees/campaign-complaint-optout
docker info >/dev/null && echo docker-up
./mvnw test 2>&1 | tee <scratchpad>/gate-api-campaign-complaint-optout.log    # baseline on base, then the final gate
./mvnw test -Dtest='ResendWebhookControllerTest#aTaggedComplaintWhoseOptOutWriteFailsIsRetriedUntilRecorded'   # repro: red on base
./mvnw test -Dtest=ResendWebhookControllerTest,CheckoutReminderDeliveryTest,CheckoutReminderUnsubscribeTest,StickyMarketingOptOutIsolationTest
grep -n 'catch' src/main/java/com/imin/iminapi/marketing/webhook/ResendWebhookProjector.java              # expect no hits
grep -rn 'recordStickyOptOutOrFail\|optOutRecorder.record\|optOuts.record' src/main/java                  # expect only StickyOptOutWriter + ConsentService
```

The check command is `./mvnw test` (workspace table). A run that skips Testcontainers tests counts as red. Judge a targeted run only by its own `Tests run:` line.

## Test impact

Branches of `projectTaggedComplaint` after the fix (5 rows):

| Branch | Forced by | Test |
|---|---|---|
| B1 org null or email blank: return | — | none; unchanged code |
| B2 a member at the address goes to the complaint branch (suppression, same transaction) | consumer + membership | existing `ResendWebhookControllerTest#aTaggedComplaintWithNoRowSuppressesByAddress[memberInsideTheGrace]` |
| B3 no member, write succeeds: 200, row, claim under the campaign | plain address | existing `…#aTaggedComplaintWithNoRowSuppressesByAddress[noMemberAfterTheGrace]` |
| B4 no member, write fails (not a duplicate): 500, no row, no claim; same svix id retried: 200, row, claim | `PgFaults.failWrites(jdbc, "marketing_optouts", "org_id", orgId)` | **new** `…#aTaggedComplaintWhoseOptOutWriteFailsIsRetriedUntilRecorded` |
| B5 no member, lost race (duplicate key, row exists): treated as success | concurrent insert | none. If this were misread as a failure, the webhook would answer 500 and the retry would find the row and answer 200, so nobody would notice anything. The logic is moved unchanged from `CheckoutReminderService`, and the recorder's duplicate behaviour is already pinned by `StickyMarketingOptOutIsolationTest#aRealDuplicateKeyViolation_doesNotRollBackTheUnsubscribe`. |

Planned and kept tests:
- **New:** `src/test/java/com/imin/iminapi/marketing/ResendWebhookControllerTest.java#aTaggedComplaintWhoseOptOutWriteFailsIsRetriedUntilRecorded`.
  - Kind: integration (`@IminIntegrationTest`, existing class; nothing added that changes the context).
  - Guards the "consent, DSAR, erasure, suppression" item.
  - Bug it catches: someone reports an organizer's campaign as spam, the opt-out write fails, and the webhook is still acknowledged. Resend never retries, so no opt-out exists and that organizer can mail them again through campaigns, reminders and opt-in grants. An auditor would find a complaint with no opt-out.
  - The `claimed == 0` assertion pins that the event is not marked processed before the opt-out lands.
- **Existing, unedited, now covering the moved writer:**
  - `src/test/java/com/imin/iminapi/checkoutreminder/CheckoutReminderDeliveryTest.java#aComplaintWhoseOptOutWriteFailsIsRetriedUntilRecorded` (a reminder complaint must keep retrying);
  - `src/test/java/com/imin/iminapi/checkoutreminder/CheckoutReminderUnsubscribeTest.java#anOptOutThatCouldNotBeRecordedAnswers500SoTheClientRetries` (a reminder one-click unsubscribe must answer 500, so the client retries);
  - the parameterized happy paths in both classes.
- **Existing, possibly touched only by the optional helper extraction:** `ResendWebhookControllerTest#aTaggedComplaintWithNoRowSuppressesByAddress`, with assertions unchanged.
- No test is planned for logging, the javadoc, the constructor wiring or the moved message text (all on the "does not get a test" list).

**Reproduction (2026-10-27, untouched master 02010c46).** Baseline `./mvnw test`: Tests run: 8463, Failures: 0, Errors: 0, Skipped: 0. Repro:
```
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 ... <<< FAILURE!
java.lang.AssertionError: Status expected:<500> but was:<200>
ResendWebhookControllerTest.aTaggedComplaintWhoseOptOutWriteFailsIsRetriedUntilRecorded:512
```

## Live-test
- D2 checked 2026-10-27 (read-only): Sentry org imin-nm, 90 days — 0 logs and 0 issues for "opt-out for a complaint on campaign". No known lost opt-outs.

There is no live test of the fix, because the failure needs a Postgres write fault and prod cannot get one. After deploy (main session):
- Railway shows the deployment of the shipped master commit as SUCCESS. There is no OpenAPI marker because the contract does not change.
- Smoke check: `curl -s -o /dev/null -w '%{http_code}' -X POST https://imin-api-production.up.railway.app/api/v1/public/webhooks/resend -H 'content-type: application/json' -d '{}'` should print `401` (endpoint up, signature gate intact).
- Optional read-only check (D2): search Sentry (org imin-nm) and Railway logs for `[resend-projector] opt-out for a complaint on campaign`. Each hit is a past complainer whose opt-out was lost. The address was never logged; only the campaign id was. Report the count. Recovery, if wanted, means replaying from Resend's event log and is a separate card.

## Contract impact

None. No path, schema or DTO changes, and no Flyway migration. The webhook's consumer is Resend, not a frontend. It can now answer 500 on one more branch, but 500 already comes from `GlobalExceptionHandler`, so no prod OpenAPI marker, no `src/shared/api/types.ts` edit and no `PUBLIC_PAGE_API.md` change are needed. `PUBLIC_PAGE_API.md` on imin-public origin/main does not document `/webhooks/resend`.

## i18n impact

None. No string is added in webapp, public or fan-app.

Copy ledger (1 row; the only reused string, moved unchanged):

| String | Field / origin | Meaning, scope, range | Rendered next to it |
|---|---|---|---|
| "The opt-out was not recorded. Try again." | `ApiError.message` from `StickyOptOutWriter.recordOrFail` (moved from `CheckoutReminderService.java:322-323`), mapped by `GlobalExceptionHandler.java:33-37` | Per request. Means the `marketing_optouts` row was not written and the failure was not a lost race. Only ever comes with 500 `INTERNAL`. | Webhook: the response body to Resend, which no human reads. Reminder one-click: imin-public's `app/unsubscribe/[token]/UnsubscribeClient.tsx:99-101` (origin/main) shows its own localized `c.error`, never this message. |

## Blast radius

- **Resend webhook** `POST /api/v1/public/webhooks/resend` (`ResendWebhookController.java:78-173`). New behaviour applies only to a tagged complaint with no recipient row and no member, and only when the opt-out write fails: 500, nothing claimed, Svix redelivers. Every other branch is byte-identical.
- **Checkout-reminder one-click unsubscribe** (`CheckoutReminderService.unsubscribe`, reached through `/api/v1/public/unsubscribe/{token}` with the `reminder:` prefix) **and the reminder complaint** (`CheckoutReminderDeliveryProjector.java:64-72`). Behaviour is unchanged because the code is moved as is; only the ERROR log text changes from `[checkout-reminder] sticky opt-out not recorded…` to `[sticky-optout] <source> opt-out not recorded…`. Option B only.
- **`ComplaintRateBreaker`**: no code change. On a failed attempt it does not run until the retry succeeds, so an org pause can lag by one Svix retry interval.
- **`marketing_optouts` readers** (`ConsentGateSql.java:60-61`, `MarketingOptedOutCheck.java:62-65`, `CheckoutReminderGate.java:102`, `PublicOptInGrant.java:92`): no change; they now see rows that used to be lost.
- **Consent/suppression (compliance-sensitive):** the change only makes an opt-out more durable. No money, auth, Stripe, Flyway, `/api/v1` contract or new env var. The only shared module touched is one new bean in `audience/service`.

## Risks

- **Svix retry budget.** A failure that lasts longer than Svix's retry window still loses the opt-out. Svix retries non-2xx for about a day per its docs; the schedule is not verified here. The ERROR log (org id and source, no address) is then the only trace. This is the same accepted limit as 8d6116c3.
- **Persistent failure.** A schema bug would make every such complaint answer 500 repeatedly and add Sentry noise. Svix disables an endpoint only when it fails consistently over days (per Svix docs; not verified here), and other event types keep succeeding.
- **A second pool connection** is taken for the REQUIRES_NEW write while the webhook holds one. This already happens today and nothing here changes it.
- **Sentry grouping** for the reminder's log line changes with the new message template (option B). No alert rule keyed on the old text is known; it shipped 4 days ago.
- **Size:** 8 files (option B) or 5 (option A), one concern. No split needed.

## Definition of done

- The repro test is red on untouched master with `Status expected:<500> but was:<200>`, and green after the fix.
- Mutations M1 and M2 each turn the named tests red; both are reverted and absent from `git diff`.
- The `./mvnw test` gate is green, with its `Tests run:` line read, Docker up and no skipped Testcontainers tests. The baseline was run on base first.
- `grep -n 'catch' …/ResendWebhookProjector.java` has no hits. `grep -rn 'recordStickyOptOutOrFail\|optOutRecorder.record\|optOuts.record' src/main/java` shows only `StickyOptOutWriter` and `ConsentService` (option B).
- The `ResendWebhookController` javadoc lists the 500; the `projectTaggedComplaint` javadoc and `CLAUDE.md:81` are updated in the same edit.
- No migration, no env var, no contract change. Comments are 1-2 lines and name no ticket or milestone.

## Live-test evidence

(filled by /do-task after ship: Railway deploy commit + status, 401 smoke output, optional Sentry count)

## Review rounds
- round 1 (2026-10-28, Sonnet) → APPROVE. No CRITICAL/HIGH/MEDIUM. LOW: MarketingOptOutRepository.java:20-22 javadoc stale (pre-existing); StickyOptOutWriter lost-race branch not asserted at writer level (unchanged by the move). Main session reworded CLAUDE.md:81 to "answers 500 with nothing claimed".

(filled by /do-task)
