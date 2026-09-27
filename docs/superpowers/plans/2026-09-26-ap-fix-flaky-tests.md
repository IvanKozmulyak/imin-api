# ap-fix-flaky-tests

## Goal and scope
Make five intermittently failing imin-api tests deterministic so they stop breaking the ship gate. Test-only changes; no production code touched.

## Repos in ship order
| key | base | worktree | verification command |
|---|---|---|---|
| api | master | imin-api/.claude/worktrees/ap-fix-flaky-tests | `./mvnw test` |

## Affected files (per repo)
api (all under `src/test/java/com/imin/iminapi/`):
- `marketing/CapiTokenCipherTest.java`: tamper by flipping a bit of the decoded ciphertext body.
- `buyer/BuyerMailListenerTest.java`: a latch counted down at the end of the mock answer replaces `verify(timeout)`.
- `support/AsyncDrain.java` (new): waits until a `ThreadPoolTaskExecutor` has finished every submitted task.
- `audienceplan/service/ConsentGateScenarios.java`: drain the fan-feature LIVE executor before `contact()` writes its row and before teardown.
- `marketing/MarketingOptInWriteTest.java`: cleanup drains the default pool and then the fan-feature pool (reuses AsyncDrain).
- `marketing/send/CampaignDailyCapDuringDrainTest.java`: holds the `campaign_dispatcher` ShedLock for the test body.

## Ordered steps
1. Reproduce each race on untouched origin/master (repeated runs, or an injected delay/tick where the natural rate is too low).
2. Apply the five fixes above.
3. Run each fixed test repeatedly (@RepeatedTest, temporary, reverted), plus the injected-delay variants.
4. Rebase onto the latest origin/master, then run the full `./mvnw test`.

## Verification commands
`./mvnw -q test -Dtest=CapiTokenCipherTest,BuyerMailListenerTest,ConsentGateTest,MarketingOptInWriteTest,CampaignDailyCapDuringDrainTest -Dsurefire.rerunFailingTestsCount=0`, then `./mvnw test`.

## Test impact
Root causes (one line each):
- CapiTokenCipherTest.rejectsTamperedCiphertext: the test checks the last character but replaces the second-to-last, so the old tamper left the string unchanged whenever the chosen character was already `A`/`B`. That happened about 1 time in 64, and decrypt then succeeded.
- BuyerMailListenerTest.the_send_happens_with_no_transaction_in_scope: Mockito records the invocation before the `doAnswer` body runs, so `verify(timeout)` returned while `txActive` was still null.
- ConsentGateScenarios (ConsentGateTest + ConsentGatePostgresTest): `consentService.capture` queues an async FanFeatureProjector insert of the same `fan_features` PK that `contact()` then merges. The projector also runs during teardown, which deletes the rows under it.
- MarketingOptInWriteTest: cleanup drained only `taskExecutor`, but a membership commit there queues a fan-feature recompute on `fanFeatureExecutor`. That recompute locks and writes membership rows while cleanup deletes them.
- CampaignDailyCapDuringDrainTest: every cached Spring context shares the H2 `mem:imin` DB and runs its own 30 s `@Scheduled` dispatcher. A tick that lands mid-seed claims the campaign with only part of its recipients.
Reproduction test: repeated/injected runs on origin/master (see Live-test evidence); no permanent new tests.

## Live-test
Not needed (test-only change).

## Contract impact
none

## i18n impact
none

## Blast radius
Test sources only. `AsyncDrain` is a new shared test helper and has no production references.

## Risks
- The drain helper waits at most 10 s and then fails loudly, never silently.
- Holding the ShedLock lock blocks the dispatchers of every live test context for the test body (under 1 s); `lockAtMostFor` is 2 min in case the test JVM dies.

## Definition of done
Each fixed test passes at least 30 consecutive runs, each reproduction is red on base where one could be forced, and the full `./mvnw test` is green after the rebase.

## Live-test evidence
Base = origin/master 94745c41, fix = this worktree.
- CapiTokenCipherTest @RepeatedTest(2000) x 4 methods: base 24 failures / 8000; fix 0 / 8000.
- BuyerMailListenerTest @RepeatedTest(40): base 80/80, fix 80/80 (no natural repro). With a 50 ms sleep injected at the start of the answer, @RepeatedTest(10): base 10 failures / 20 ("Expecting actual not to be null"), fix 0 / 20.
- ConsentGateTest @RepeatedTest(40) on all 46 @Test methods: base 1846/1846 green but logged 2 `FanFeatureProjector: skipped membership ... ObjectOptimisticLockingFailureException` (projector racing teardown); fix 1846/1846 green with 0 such warnings.
- MarketingOptInWriteTest @RepeatedTest(40): fix 600/600 (base 600/600, no natural repro).
- CampaignDailyCapDuringDrainTest @RepeatedTest(40): fix 40/40. With a scheduler tick forced mid-seed (`dispatcher.run()` after 51 rows): base 3 failures / 3 ("expected 100L but was 51L"), fix 0 / 3.
- Full `./mvnw test` on untouched base 94745c41: green (3869 run, 0 fail).
- Rebased onto origin/master 15368af5 (tagged stash `ap-fix-flaky-tests-prerebase`, 26ed59b9, popped clean). Full `./mvnw test` after the rebase: EXIT 0, 3963 run, 0 failures, 0 errors, 0 skipped.

## Review rounds
