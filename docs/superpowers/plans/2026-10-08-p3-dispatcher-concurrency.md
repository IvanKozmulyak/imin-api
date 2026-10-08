# P3: concurrent campaign dispatcher

## Goal

Prove on real Postgres that two dispatcher instances (two replicas) never claim one campaign
together and never email one recipient twice. Test-only; `src/main` is untouched.

What guards what today:
- Campaign claim: `CampaignRepository.claimDue` is `... LIMIT 10 FOR UPDATE SKIP LOCKED`
  (`CampaignRepository.java:107-124`). The dispatcher calls it with no enclosing transaction
  (`CampaignDispatcher.java:72-81`, `99-103`), so the row locks last only for the claim itself;
  the status flip to `sending` follows in its own transaction (`CampaignSendUnit.java:71-74`).
- Replica exclusion: `@SchedulerLock(name = "campaign_dispatcher", lockAtMostFor = "PT10M")` on
  `run()` (`CampaignDispatcher.java:65-69`), JDBC provider with DB time (`SchedulingConfig.java:35-43`).
- Per-recipient: `claimPendingBatch` is `status = 'pending' ... FOR UPDATE SKIP LOCKED`
  (`CampaignRecipientRepository.java:167-177`) inside `sendNextBatch`'s REQUIRES_NEW transaction
  (`EmailChannelSender.java:113-132`), so a claimed batch stays locked until its `sent` flips commit
  (`EmailChannelSender.java:206-214`).

## Affected files

- `src/test/java/com/imin/iminapi/marketing/send/CampaignDispatcherConcurrencyTest.java` (new)
- this plan

## Test impact

One new `@IminIntegrationTest` class, three tests:
1. `concurrentClaims_splitTheDueSetWithoutOverlap` — two threads each claim in an open transaction;
   a barrier makes both claims return while both transactions are open (overlap proven). 12 own
   campaigns, ANCIENT `scheduled_at`: each own id is in exactly one result; intersection empty.
2. `twoDrainsOfOneCampaign_sendEachRecipientOnce` — 150 pending rows. Replica A (`runOnce`) is held
   inside the provider call for its first batch (its transaction open on those 100 rows, proven by
   a `SKIP LOCKED` probe seeing only 50); the heartbeat goes stale; replica B (`runOnce`) reclaims
   the campaign, sends the other 50 and finishes while A is still held.
   Every own address sent exactly once; all rows `sent` with distinct provider ids; campaign `sent`.
3. `whileOneReplicaHoldsTheDispatcherLock_anotherRunDoesNothing` — A runs `run()` through the bean
   and is held mid-batch; B's `run()` returns without sending although the campaign is reclaimable.
   Shedlock row expired before and in `finally`.

Red proofs in a scratch copy: drop `FOR UPDATE SKIP LOCKED` / `SKIP LOCKED` from `claimDue` (1),
from `claimPendingBatch` (2), drop `@SchedulerLock` (3).

## Risks

- The claim is global: own rows sort first via ANCIENT `scheduled_at`, behind any null-`scheduled_at` leftovers (NULLS FIRST, CampaignRepository.java:120) — the CampaignRows.delete rule keeps those out; asserts name own ids;
  `CampaignRows.delete` in `@AfterEach`. A precondition (the claimDue predicate without LIMIT)
  fails naming other tests' claimable rows when they leave too few slots.
- Two simultaneous claims interleave row by row (a full-suite run split the 12 own campaigns 6/6,
  one side holding #0 and #11), so the skip proof is "the holder of the latest own campaign lacks
  an earlier one", never "the later claimer lacks the first".
- `PgFaults.pauseWrites` on `campaign_recipients` cannot hold A: Hibernate runs the heartbeat
  `touch` (`EmailChannelSender.java:255`) before it flushes the recipient rows, so the paused
  transaction also holds the campaigns row and B could never reclaim it. A is held in the provider
  fake instead, before the `touch`.
- A held call that is never released hangs the run: release in `finally`, then join the threads and
  shut the executors down (again in `@AfterEach`).
- A leftover shedlock row would make `run()` a no-op: expire before and after.

## Definition of done

Docker up; the new class and `SpringContextGuardTest` green; each new test 5/5 green; each red
proof red; full `./mvnw test` green with no skipped Testcontainers tests, log in the scratchpad.

## Review rounds
round 1 → PASS (MEDIUM fixed: comment that the outer tx stands in for a transactional claim; LOW fixed: NULLS FIRST wording).
Card (one, combined — check first whether materializer writes null membership_id rows: if so, duplicate rows + duplicate emails → HIGH): (1) claim → sending flip not atomic (CampaignDispatcher.java:99-103); (2) RecipientMaterializer.java:66-70 check-then-insert race marks a sending campaign failed (CampaignDispatcher.java:77-79), winner later overwrites (no @Version); (3) @SchedulerLock lockAtMostFor=PT10M shorter than a long drive, so replicas can overlap.
Checked: RecipientMaterializer always sets a real membership_id (RecipientMaterializer.java:98,126); NULL only after DSAR erase (V53 ON DELETE SET NULL), so the race cannot duplicate emails — card stays MEDIUM.
