# Dispatcher follow-ups: claim starvation, materialize vs run budget, release wrapper test

Follow-ups left open by `2026-10-08-fix-dispatcher-claim.md` ("Open follow-ups").

## Goal

1. **Held campaigns no longer starve other orgs.** `CampaignRepository.claimDue` took `LIMIT 10 FOR UPDATE SKIP LOCKED`
   and the dispatcher filtered afterwards in Java: org gone, complaint pause, quiet hours, daily cap
   (`CampaignDispatcher.java:166-189` on base). Ten held campaigns at the head of the queue (`ORDER BY scheduled_at
   NULLS FIRST`) filled every slot on every run, so an eligible campaign of another org behind them was never claimed.
   Fix:
   - Org gone and complaint pause move into the claim SQL: `EXISTS (organizations o WHERE o.id = campaigns.org_id AND
     o.marketing_paused_at IS NULL)`. `campaigns.org_id` has no FK (`V52__campaigns.sql:6-34`), so a missing org is a
     real case.
   - Quiet hours and the daily cap stay in Java. Quiet hours resolve `organizations.timezone` through `ZoneId` with a
     UTC fallback for blank or invalid names (`QuietHours.java:30-34`). Postgres `AT TIME ZONE` would throw on an
     invalid name and fail the whole claim, and its zone names are not Java's. The cap is a rolling-24h count with no
     calendar day, so no zone is involved.
   - The query now scans `SCAN_LIMIT = 100` rows. The dispatcher drops held ones and keeps at most `CLAIM_LIMIT = 10`
     eligible ones in queue order. The quiet/cap decision is cached per org, so 100 rows cost one org lookup and one
     count per org, not per campaign.
   - Held rows are still never written. Only the kept ids go into `markClaimed`, and the scanned-but-held rows' locks
     end with the claim transaction.
   - ponytail: more than 100 quiet-hours or capped campaigns ahead in the queue still delay the ones behind them.
2. **Materialize vs `RUN_BUDGET`: documented, no behaviour change.** Reasoning:
   - The obvious fix is a deadline check before `materialize`. It would be dead code. The dispatcher already checks
     the deadline right before each `processOne` (`CampaignDispatcher.java:94-98`). On the dispatcher path the
     campaign arrives already `sending` (flipped in the claim, `CampaignDispatcher.java:130-133`), so `processOne`
     skips its own flip (`CampaignSendUnit.java:81`) and goes straight to `materialize`. Nothing runs between the two.
     `processOne(c)` without a deadline passes `Instant.MAX` (`CampaignSendUnit.java:75-77`).
   - The real exposure is how long `materialize` takes. It is not one INSERT…SELECT. It does one JPA `save` per
     recipient, and `CampaignRecipient` assigns its own UUID with no `Persistable`, so each save is a SELECT plus an
     INSERT. It also runs one frequency-cap count per sendable member (`RecipientMaterializer.java:95-121`,
     `CampaignVolumeGuard.java:31-35`), all in one REQUIRES_NEW transaction under the campaign row lock.
   - Measured on the test Postgres container on a laptop (throwaway test, not committed): 2,000 members took
     38.2 s, about 19 ms per member. At that rate an audience of about 6k fills the 2-minute margin, and about 30k
     outlasts `lockAtMostFor=PT10M` by itself. Prod is likely faster but has the same linear shape.
   - Why an overrun is accepted for now:
     - With one replica nothing overlaps: `@Scheduled(fixedDelay)` never starts a run while the last one is still
       running.
     - With two replicas (a rolling deploy), the second run's `FOR UPDATE SKIP LOCKED` claim passes over the
       campaign being materialized, which holds `FOR NO KEY UPDATE` (`CampaignRepository.lockForMaterialize`).
     - Claimed campaigns the first run has not started go stale after 5 minutes, so the second run can reclaim and
       drive them, and both runs may drive one campaign. Batches are claimed `FOR UPDATE SKIP LOCKED`
       (`CampaignRecipientRepository.claimPendingBatch`) and materialize is serialized, so no recipient gets two
       emails. The first run's release is guarded on its claim stamp, so it does not overwrite the second run's
       heartbeat.
   - A `ponytail:` comment at the `materialize` call names the ceiling. The real fix is a bulk materialize (batched
     inserts, one frequency query per set), listed as a follow-up.
3. **Test for the `release` catch wrapper** (`CampaignDispatcher.java:138-148`): a release that fails for one
   unstarted claim does not stop the next one from being released.

## Affected files

- `src/main/java/com/imin/iminapi/marketing/repository/CampaignRepository.java`: `claimDue` adds the org/pause
  `EXISTS` and takes `scanLimit`.
- `src/main/java/com/imin/iminapi/marketing/send/CampaignDispatcher.java`: `CLAIM_LIMIT`, `SCAN_LIMIT`, per-org
  `isHeld`. The Java pause check is gone because SQL owns it.
- `src/main/java/com/imin/iminapi/marketing/send/CampaignSendUnit.java`: ponytail comment only.
- `src/test/java/com/imin/iminapi/marketing/send/CampaignDispatcherConcurrencyTest.java`: two new tests,
  a `requireClaimRoom` overload that excludes a second own org, and `claimDue(..., 10)` in the split-claim test.
- `src/test/java/com/imin/iminapi/marketing/CampaignClaimPostgresTest.java`: `claimDue(..., 100)`, and its comment.
- this plan

## Test impact

Both new tests are in `CampaignDispatcherConcurrencyTest` (`@IminIntegrationTest`, Postgres):

1. `heldCampaignsFirstInTheQueue_doNotStarveAnotherOrgsCampaign(Hold)`, parameterized over `PAUSED`, `ORG_GONE`
   and `QUIET_HOURS`. Setup:
   - held campaigns sort first, one of them `failed` with attempts 1: `SCAN_LIMIT + 1` (101) for `PAUSED` and
     `ORG_GONE`, so a Java-side filter only ever sees held rows; 12 for `QUIET_HOURS`;
   - an eligible campaign of an awake org sorts after all of them.

   One `runOnce` must send the eligible campaign's recipients exactly once and mark it `sent`. Every held row's
   status, attempts, `updated_at` and `last_error` must be unchanged.
   - Red on base: all three, eligible "Expected size: 2 but was: 0".
   - Red with the SQL filter but `SCAN_LIMIT = 10` (scratch copy): `QUIET_HOURS` red, the other two green. This
     proves the scan widening separately.
   - Red with the SQL `EXISTS` removed and the pause check moved to Java `isHeld` (scratch copy): `PAUSED` and
     `ORG_GONE` red ("Expected size: 2 but was: 0"), `QUIET_HOURS` green.
   - Quiet hours are computed in Java, not SQL, so the zone-day fixture rule does not apply. The quiet org gets an
     offset that puts its local time near 02:00 at the current instant, the same recipe as the existing filter test.
2. `aClaimThatCannotBeReleased_doesNotKeepTheNextOneFromBeingReleased`. The first provider call advances
   `MutableClock` past `RUN_BUDGET` and installs `PgFaults.failWrites` on the first unstarted claim's campaign row.
   That campaign stays `sending`. The second unstarted claim, previously `failed`, is back to its exact prior state.
   - Red with the `try/catch` around `releaseClaim` removed (scratch copy): `JpaSystem ... injected test fault`
     propagates out of `runOnce`.

3. `moreEligibleCampaignsThanOneClaim_claimsTheFirstTenAndLeavesTheRest`. 11 eligible campaigns of one org, one
   pending row each. One `runOnce` sends the first 10 in queue order. The 11th keeps its state and its pending row.
   - Red with the `CLAIM_LIMIT` break deleted (scratch copy): the 11th is `sent`.

Not tested: the per-org cache (an optimisation with no visible effect). The materialize item has no behaviour change, so it has no test.

## Risks

- The claim transaction now locks up to 100 rows instead of 10. Held rows are locked only for the claim's duration
  (one org lookup and one count per distinct org), and an organizer write to one of them waits that long.
- A paused or missing org is now filtered by a correlated `EXISTS` on `organizations` (PK lookup per candidate row).
- `requireClaimRoom` in the concurrency test still counts paused-org leftovers as claimable. That is conservative:
  a test can only fail early, never pass wrongly.
- Materialize can still outlast the lock for very large audiences (item 2). Safe against double sends, not against
  overlap.

## Definition of done

- `docker info` up.
- Targeted run green: `CampaignDispatcher*Test, Campaign*DrainTest, CampaignSendCrashResumeTest,
  AudiencePlanSendsKillSwitchTest, AudiencePlanLegalIdentityDispatchTest, EmailChannelSender*Test,
  SpringContextGuardTest`.
- The starvation test is red on base and green after. The scan-limit and release-wrapper red proofs ran in a
  scratch copy.
- Full `./mvnw test` green with no skipped tests, log in the scratchpad.
