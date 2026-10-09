# Campaign patch/delete read stale status after waiting on the row lock

Repo: `imin-api` · worktree `.claude/worktrees/fix-patch-refresh` · base `origin/master` d6993a4a

## Bugs

1. `CampaignService.patch` (`CampaignService.java:210-248`) loads the campaign unlocked via `require` (`:212`), then
   `campaigns.findByIdAndOrgIdForUpdate` (`:217`). The locked query waits for a concurrent writer's row lock, but Hibernate
   hands back the entity already in the persistence context without overwriting its state, so the re-check at `:219`
   sees the stale `draft`. `campaigns.save(c)` (`:248`) then flushes the full entity, writing `status='draft'` over the
   `scheduled` a send (`:466`) or `TimingArmScheduler.fireSlump` (`TimingArmScheduler.java:169`) committed meanwhile.
2. `CampaignService.delete` (`:684-694`) checks `draft` on an unlocked read and deletes at flush. A send/slump that
   schedules the draft between the check and the commit is lost: the DELETE waits for the writer, then removes the
   now-`scheduled` row.

## Writers of the `campaigns` row (workspace rule: new writer vs existing writers)

| path | file:line | kind | interaction with this fix |
|---|---|---|---|
| create | `CampaignService.java:139` | INSERT | new row, no race |
| patch | `CampaignService.java:248` | full-entity save | **fixed**: refresh after lock |
| duplicate | `CampaignService.java:288` | INSERT of a copy | new row |
| send | `CampaignService.java:466` | `markScheduledIfDraft` (conditional UPDATE) | waits on patch/delete lock; Postgres re-evaluates `status='draft'` after it |
| cancel | `CampaignService.java:641` | `cancelIfScheduled` | conditional |
| retry | `CampaignService.java:663` | `retryIfFailed` | conditional |
| delete | `CampaignService.java:691` | entity delete | **fixed**: locked + refreshed re-check |
| forceStatusForTest | `CampaignService.java:779` | full save, test-only | n/a |
| fireSlump | `TimingArmScheduler.java:169` | `markScheduledIfDraft` | same as send |
| Momentum approve | `MomentumService.java:312` | INSERT | new row |
| invitation draft | `InvitationService.java:233` | INSERT | new row |
| dispatcher claim / release | `CampaignDispatcher.java:127`, `:141` | `markClaimed`, guarded `releaseClaim` | claims scheduled/failed/sending only, never a draft; SKIP LOCKED passes a locked row |
| send unit | `CampaignSendUnit.java:83`, `:143`, `:161` | `markSendingIf`, `markSentIfSending`, `markFailedIfActive` | conditional, never from draft |
| email sender | `EmailChannelSender.java:238`, `:251`, `:263`, `:373` | `touch`, `markFailedIfActive` | heartbeat / conditional |
| materializer | `RecipientMaterializer.java:69`, `:141` | `lockForMaterialize`, `recordMaterialized` | counts only, `sending` campaigns |

The only full-entity writers of an existing row are patch and `forceStatusForTest`; every other writer is a conditional
UPDATE, so after this fix no path can write `draft` back.

## Fix

- `CampaignService`: inject `EntityManager` by field (`@PersistenceContext`, constructor untouched to keep the hunk
  clear of concurrent edits); private helper `lockFresh(orgId, id)` = `findByIdAndOrgIdForUpdate` + 404 +
  `entityManager.refresh(c)`.
- `patch`: use the helper at `:217`. The unlocked pre-check stays (it answers 409 mid-materialize without waiting).
- `delete`: after the existing unlocked check, take the helper and re-check `draft` → 409 `INVALID_STATE`.

## Tests (`CampaignServiceRaceTest`, `@IminIntegrationTest`, concurrency on real Postgres)

Writers parameterized: `send` (`CampaignService.send`) and `slump` (`campaigns.markScheduledIfDraft`, the statement
`fireSlump` runs). Ordering forced with `PgFaults.pauseWrites(campaigns.id)` (writer holds the tuple lock inside its
transaction) and the existing tagged `pg_stat_activity` lock-wait poll — no sleeps.

1. `aSchedulingWriterThatCommitsWhileAPatchWaits_makesThePatch409` — writer paused holding the row; patch waits on
   the lock; release → patch 409 INVALID_STATE, status `scheduled`, subject unchanged.
   Red-proof: drop the `refresh` line → status `draft`.
2. `aPatchHoldingTheRow_isKeptAndTheWriterSchedulesAfterIt` — patch paused in its UPDATE holding the lock; writer waits;
   release → status `scheduled`, subject patched. Other ordering; pins that the lock serializes rather than loses either
   write (no guard of its own beyond the pre-existing lock).
3. `aSchedulingWriterThatCommitsWhileADeleteWaits_makesTheDelete409` — writer paused; delete waits; release → delete 409
   INVALID_STATE, row present and `scheduled`. Red-proof: drop the locked re-check in `delete` → row gone.
4. `aDeleteHoldingTheRow_leavesTheWriterNoDraftToSchedule` — delete held open in the test's own transaction wrapper after it returned (a latch outside the service; the pause trigger fires on INSERT/UPDATE, not DELETE); writer
   waits on its lock; commit → row gone, writer schedules nothing (send 409 / slump 0 rows). Red-proof: same removal →
   the writer schedules before the delete flushes.

## Verification commands

- `docker info`
- `./mvnw test` (full gate) → log `/private/tmp/claude-501/-Users-ivan-imin/1ac0569d-bf55-46d5-87a0-30bb790e2e34/scratchpad/gate-api-patch-refresh.log`

## Affected files

- `src/main/java/com/imin/iminapi/marketing/service/CampaignService.java`
- `src/test/java/com/imin/iminapi/marketing/CampaignServiceRaceTest.java`
- this plan
