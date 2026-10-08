# Fix: campaign dispatcher claim, materialize race, run bound

## Goal

Close the three concurrency gaps the P3 review found in the campaign dispatcher
(`2026-10-08-p3-dispatcher-concurrency.md`, card at the end):

1. **Claim and flip are one step.** `CampaignRepository.claimDue` (`CampaignRepository.java:107-124`)
   ran with no transaction (`CampaignDispatcher.java:72-81, 99-103`), so its `FOR UPDATE SKIP LOCKED`
   ended at statement end, and the flip to `sending` committed later without a heartbeat
   (`CampaignSendUnit.java:71-74`, no `setUpdatedAt`). A campaign whose `updated_at` was older than
   five minutes when it fell due (any campaign scheduled ahead: `markScheduledIfDraft` does not touch
   `updated_at`, `CampaignRepository.java:67-68`) stayed claimable as stale `sending` until its first
   batch heartbeat, so a second run claimed and drove it too.
   Fix: `runOnce` claims inside one REQUIRES_NEW transaction: the locked `claimDue`, then the Java
   filters (org gone, complaint pause, quiet hours, daily cap), then
   `status='sending', updated_at=<claim stamp>` for the eligible ids only, then commit. A campaign the
   Java filters drop is never written, so it keeps its prior state with nothing to restore.
   `claimDueCampaignIds` stays the read-only eligibility view the gating tests use.
2. **Materialize is serialized per campaign.** `RecipientMaterializer.materialize` checked
   `countByCampaignId` then inserted (`RecipientMaterializer.java:66-70`); two concurrent drives both
   saw 0 and the loser hit `uq_campaign_recipient` (V53), which the dispatcher turned into `failed`
   (`CampaignDispatcher.java:77-79`). Fix: lock the campaign row (`FOR NO KEY UPDATE`) before the
   count, inside materialize's REQUIRES_NEW transaction. The loser waits, sees rows, skips — no
   unique violation, and it does not overwrite the winner's counts. The lock also makes the claim's
   `SKIP LOCKED` pass over a campaign that is mid-materialize.
3. **A run ends under its lock.** `@SchedulerLock lockAtMostFor=PT10M` (`CampaignDispatcher.java:65-69`)
   while one run could drive ten campaigns for as long as the batches last. Fix: a run budget of
   8 minutes (`RUN_BUDGET`, 2 minutes under `lockAtMostFor`). The drive loop stops starting batches
   once the deadline passes (the campaign stays `sending` and resumes through the stale reclaim,
   the same path the daily-cap and backoff stops use). Claimed campaigns the run never started are
   released to the status and `updated_at` they held before the claim, guarded on
   `status='sending' AND updated_at=<claim stamp>` so a writer that changed the row meanwhile wins.

Also: the dispatcher's `markFailed` inside the `catch` is wrapped, so a failure there cannot abort the
loop and strand the rest of the claimed (now flipped) campaigns.

Round 2 (after review, user-approved):
4. **Cancel and retry are compare-and-set.** `CampaignService.cancel` / `retry` loaded the row and
   full-saved it with no version, so a cancel that raced the claim waited on the claim's row lock and
   then overwrote `sending` with `canceled`. It returned 200 and the mail still went out. Now
   `cancelIfScheduled` (`status='scheduled'`) and `retryIfFailed` (`status='failed' AND attempts < 3`)
   are single UPDATEs. 0 rows gives the same 409 INVALID_STATE as before. `require` still runs first, so
   the 404 stays no-leak. Audit, `updated_at`, `scheduled_at` and `requeueFailed` are kept.
5. **The drive re-reads the status before each batch** (`EmailChannelSender.sendNextBatch`, which
   already does several DB reads per batch). If the status is not `sending`, the drive stops: no
   further batch, and `processOne` returns before `finish`.
   ponytail: this is a plain read, so a stop that lands while a batch is at the provider still lets
   that one batch go.
6. **No drive-path write puts `sending`/`sent`/`failed` over another writer's status.**
   - materialize writes only its counts (`recordMaterialized`);
   - finish writes `sent` only `WHERE status='sending'` (`markSentIfSending`), and publishes the
     reforecast event only when that row was written;
   - `markFailed` is `markFailedIfActive` (`WHERE status IN ('scheduled','sending')`).
7. **The run deadline reads the injected `Clock`** (the dispatcher and `CampaignSendUnit`), so the
   budget test advances `MutableClock` instead of sleeping. Claims and heartbeats stay on wall time.

Round 3 (review: test gaps):
8. `failForMissingLegalIdentity` uses `markFailedIfActive` and marks the drive's copy failed only on
   1 row. On 0 rows the copy takes the stored status, so the drive stops on what is in the database.
9. `processOne`'s own flip for direct callers is `markSendingIf(id, expected=copy's status)`. On 0
   rows the campaign is not driven.
10. The failure log carries the attempt number again (`findAttemptsById` after the update).
11. The race tests wait on exactly the blocked backend. The organizer UPDATE is tagged with
    `SET LOCAL application_name`. The second drive is matched on its `FOR NO KEY UPDATE`, or, on the
    unfixed code, on a second paused insert.

Round 4 (review: guard tests, and the patch writer):
12. **`CampaignService.patch` loads the draft row-locked** (`findByIdAndOrgIdForUpdate`,
    PESSIMISTIC_WRITE) and keeps its `status='draft'` check. A send's or timing arm's
    `markScheduledIfDraft` waits for the edit to commit and then schedules the edited draft. The full
    save can no longer write `draft` back over `scheduled`; drafts are not claimable anyway.
13. **Both `releaseClaim` predicates stay; each has its own red proof.**
    - `updated_at = stamp` catches a heartbeat from another drive, where the status is still `sending`.
    - `status = 'sending'` catches a writer that changes status without touching `updated_at`. Defensive:
      every writer that moves a `sending` row today also sets `updated_at`.
14. **`retryIfFailed` keeps `attempts < 3`.** The Java pre-check reads the copy loaded before the
    update, so a claim plus failed drive landing in between would let a retry beyond the budget
    through. A test reproduces this with a row lock held across the retry's update.

## Writer audit — `campaigns.status` (and `updated_at` where it drives the claim)

| writer | file:line | shape | interaction with this change |
|---|---|---|---|
| create / duplicate | `CampaignService.java:126,139` / `264,281` | insert `draft` | none (not claimable) |
| Momentum approve | `MomentumService.java:298,312` | insert `draft` | none |
| invitation arm | `InvitationService.java:226,233` | insert `draft` | none |
| patch | `CampaignService.patch` → `findByIdAndOrgIdForUpdate` | row-locked load, then full save (round 4) | a send's draft→scheduled CAS waits for it, so `draft` is never written over `scheduled` |
| send / timing arm | `CampaignService.java:459`, `TimingArmScheduler.java:169` → `markScheduledIfDraft` (`CampaignRepository.java:65-71`) | CAS draft→scheduled, leaves `updated_at` | the reason a due campaign has an old `updated_at` (gap 1) |
| cancel | `CampaignService.cancel` → `cancelIfScheduled` | CAS `scheduled→canceled` (round 2) | loses to the claim with 409; the release CAS does not revert a cancel |
| retry | `CampaignService.retry` → `retryIfFailed` | CAS `failed→scheduled` (round 2) | loses to the claim with 409 |
| force status (tests) | `CampaignService.java:764-769` | full save | test only |
| **claim flip (new)** | `CampaignDispatcher` | bulk UPDATE status+updated_at under the claim's row locks | replaces the processOne flip on the dispatcher path |
| **release (new)** | `CampaignRepository.releaseClaim` | CAS on `status='sending' AND updated_at=stamp` | only touches rows the run claimed and never started |
| processOne flip | `CampaignSendUnit` → `markSendingIf` | CAS from the copy's status (round 3) | a no-op on the dispatcher path; direct callers only |
| finish `sent` / `failed` | `CampaignSendUnit` → `markSentIfSending` / `markFailedIfActive` | conditional UPDATEs (round 2) | never over `canceled` or `sent` |
| legal identity fail | `EmailChannelSender` → `markFailedIfActive` | conditional UPDATE (round 3) | never over `canceled` or `sent` |
| heartbeat | `EmailChannelSender.sendNextBatch` → `touch` | `updated_at` only | unchanged; the release leaves a heartbeated claim alone |
| materialize | `RecipientMaterializer` → `recordMaterialized` | counts only (round 2), under the row lock | never writes status |

## Affected files

- `src/main/java/com/imin/iminapi/marketing/send/CampaignDispatcher.java`
- `src/main/java/com/imin/iminapi/marketing/send/CampaignSendUnit.java`
- `src/main/java/com/imin/iminapi/marketing/send/RecipientMaterializer.java`
- `src/main/java/com/imin/iminapi/marketing/repository/CampaignRepository.java`
- `src/main/java/com/imin/iminapi/marketing/send/EmailChannelSender.java` (round 2)
- `src/main/java/com/imin/iminapi/marketing/service/CampaignService.java` (round 2)
- `src/test/java/com/imin/iminapi/marketing/send/CampaignDispatcherConcurrencyTest.java`
- `src/test/java/com/imin/iminapi/marketing/send/CampaignSendUnitSendsSwitchTest.java` (constructor gains `Clock`; stubs `markSentIfSending`)
- `src/test/java/com/imin/iminapi/marketing/send/EmailChannelSenderOrgLookupTest.java` (stubs the status re-read)
- `src/test/java/com/imin/iminapi/marketing/CampaignServiceRaceTest.java` (new, round 4)
- `src/test/java/com/imin/iminapi/marketing/send/AudiencePlanLegalIdentityDispatchTest.java` (legal-identity fail starts at attempts 1 and must reach 2; red without `attempts = attempts + 1`)
- this plan

## Test impact

All in `CampaignDispatcherConcurrencyTest` (`@IminIntegrationTest`, Postgres, concurrency/locks):
1. `aSecondRunDoesNotClaimACampaignTheFirstIsStillDriving` (repro, red on base) — a due campaign
   whose `updated_at` is an hour old; run A held in its first provider call; run B must send nothing.
2. `twoDrivesMaterializingAtOnce_neitherThrows` (repro, red on base) — two drives of one campaign over
   a real segment; A held inside its first recipient insert; B started and blocked; on release both
   return normally, one row and one email per member, campaign `sent`, attempts 0.
3. `aCampaignTheFiltersHold_keepsItsStateThroughARun` — quiet-hours org, `failed` campaign:
   status, attempts and `updated_at` unchanged by `runOnce` (guard: flip only the filtered ids).
4. `aRunPastItsBudget_stopsTheDriveAndReleasesWhatItNeverStarted` — the first provider call of
   campaign X advances `MutableClock` past the budget and, on its own connection, cancels claimed
   campaign Z. Expected:
   - X stops after one batch, still `sending`, with 50 rows pending;
   - claimed Y (`failed`, attempts 1) is back to `failed` with its old `updated_at`;
   - Z stays `canceled` (the release guard).
5. `aCampaignThatCannotBeMarkedFailed_doesNotStopTheRestOfTheClaim` — the provider throws for X and
   installs `PgFaults.failWrites` on X's campaign row (from its own connection). `markFailed` is
   rejected, X stays `sending`, and Y still drives and sends.
6. `anOrganizerActionRacingTheClaim_answers409AndTheCampaignSends` (parameterized CANCEL / RETRY;
   repro, red on the load-then-save code) — the claim is held after `FOR UPDATE` by
   `PgFaults.pauseWrites` on its flip. Cancel or retry is fired and blocked, then the claim is
   released. The action gets a 409 and every recipient is sent once.
7. `aCampaignCanceledMidDrive_sendsNoFurtherBatchAndStaysCanceled` (repro, red without the re-read)
   — the first provider call cancels the campaign by direct SQL. Only that batch is sent, the status
   stays `canceled`, and 50 rows stay pending.
   It also seeds one pending row with `attempt_count=3`, which must stay `pending` (the stopped drive
   never reaches `finish`; red with the old `"failed".equals` check).
8. `aCampaignCanceledDuringItsLastBatch_isNeitherMarkedSentNorAnnounced` — exactly `BATCH_SIZE`
   rows, canceled from the provider answer, so the drain ends and `finish` runs. Asserted:
   - status `canceled`;
   - `sent_at` NULL;
   - no `CampaignSent` for the campaign's event, via `@RecordApplicationEvents`.
   Red with the unconditional `sent` save.
9. `materializingAStaleCopy_keepsTheStoredStatus` — the drive's copy says `sending`, but the row is
   `canceled` before materialize runs. The status survives and the counts are recorded (red with
   `campaigns.save(c)`). The race the review proposed, a status change while `pauseWrites` holds the
   insert, cannot show the difference: materialize holds `FOR NO KEY UPDATE` on the row, so that
   writer only lands after materialize commits, with or without the full save.
10. Round 4, in `CampaignDispatcherConcurrencyTest`:
    - The budget test splits the release guard. One claimed campaign changes only its status (it must
      stay `canceled`), another only gets a heartbeat via `touch` (it must stay `sending`).
    - `aCampaignCanceledBeforeItsDriveThrows_keepsItsStatusAndAttempts`: the provider answer cancels,
      then throws. The status stays `canceled`, attempts 0, `last_error` NULL.
    - `aDirectDriveOfACopyWhoseStatusMoved_materializesAndSendsNothing`: `processOne` on a
      `scheduled` copy of a canceled row writes no recipient rows.
11. Round 4, `EmailChannelSenderOrgLookupTest.audiencePlanCampaign_canceledBeforeItCouldBeFailed_stopsOnTheStoredStatus`:
    `markFailedIfActive` returns 0, so the drive copies the stored `canceled` status.
12. Round 4, `CampaignServiceRaceTest`:
    - `aPatchRacingASend_neverWritesDraftOverScheduled` (repro, red on base): the patch is held after
      its load by an ACCESS EXCLUSIVE lock on `campaign_ai_suggestions`, and the send is fired. End
      state: `scheduled`, with the edit kept.
    - `aRetryRacingAnAttemptThatUsedTheLastOne_answers409`: the retry loads attempts 2 and its update
      waits on a held row lock; the holder sets attempts 3 and commits. The retry gets 409.
Existing three tests stay green. Red proofs:
- 1, 2, 6 and 7 against the code before their fix;
- 6 (cancel) again with the tagged wait; 7's exhausted row, 8 and 9 by restoring the old line;
- 3, 4 (release, release guard, deadline) and 5 (wrapper) by one-line removals in a scratch copy.

## Risks

- The claim transaction holds the claimed rows' locks while the Java filters run (one org lookup
  and one count per campaign); an organizer write to one of those ten rows waits that long.
- `FOR NO KEY UPDATE` in materialize blocks every UPDATE of the campaign row for the length of the
  materialization (bounded by the audience size). It does not block FK key-share locks.
- The budget assumes one batch finishes within the 2-minute margin; a batch that hangs longer can
  still outlive the lock (batches stay safe through `claimPendingBatch` SKIP LOCKED).
- `markFailed` no longer fails a campaign that is not `scheduled`/`sending`. It is a no-op on
  `failed`, `sent` and `canceled` rows, and is logged.
- `processOne`'s own flip for direct callers is a CAS on the copy's status (`markSendingIf`). It is
  not reached from the dispatcher, whose campaigns arrive already flipped.

## Definition of done

Docker up; targeted `marketing/send` tests, `CampaignDispatcherGatingTest`,
`AudiencePlanSendsKillSwitchTest` and `SpringContextGuardTest` green; repro tests red on base, green
after; full `./mvnw test` green with no skipped Testcontainers tests, log in the scratchpad.

## Review rounds
round 1 → PASS; round 2 → FIX_REQUIRED (guard tests, legal-identity save); round 3 → FIX_REQUIRED (guard tests, patch race); round 4 → PASS.
Round-4 follow-ups taken by the orchestrator: unlocked draft pre-check before patch's locked load (no wait on materialize), lock_timeout on the race test's table hold, status-predicate wording.
Open follow-ups: test for the `release` catch wrapper (CampaignDispatcher); claim LIMIT 10 before the Java filters can starve other orgs; materialize outside RUN_BUDGET; AudienceExperiment full saves in TimingArmScheduler (disarm vs slump approval); markScheduledIfDraft leaves updated_at (cosmetic).
