# Restore the payout transaction on payOneEvent
payout-tx-restore · Subagent (money) · Notion: none

## Goal and scope
Prod defect: commit 367ecaef (2026-10-09) put static `payoutDescription` between `@Transactional(propagation = REQUIRES_NEW)` and `payOneEvent` (`payout/PostEventPayoutService.java:206-216` @137edd2a). Spring can't proxy a static method → `payOneEvent` runs with no transaction; each repository call autocommits.
In scope: annotation back on `payOneEvent` (keep REQUIRES_NEW); PLANNED insert → `saveAndFlush` (UNIQUE checked before Stripe, as the javadoc/V46 always claimed); fix javadoc (class `:55-62`, step 5 `:103-106`, method `:201-205`, comment `:453-455`); three regression tests; read-only prod impact check.
Out: two-phase payout write (follow-up, see Risks); cancelled-event hold (other card).

### Semantics
| # | Write | Before 367ecaef | Now (no tx) | After fix |
|---|---|---|---|---|
| 1 | `OrderRepository.stampSettlement` (own REQUIRES_NEW, `:121`) via `stampOrg` `:239` | commits immediately | same | same |
| 2 | `RefundRecoveryMarker.markRecovered` (`:47`) at `:831` | commits after Stripe call | same | same |
| 3 | `DisputeRecoveryMarker.markRecovered/markReturned` (`:30,47`) at `:1080,1096,1108` | same | same | same |
| 4 | No-bank BLOCKED park `:723` + `PayoutBlockedEvent` `:728` | commit at end; AFTER_COMMIT listener sends in-app + email | row autocommits; event published with no tx → listener SKIPPED (spring-tx 7.0.6 `TransactionalApplicationListenerMethodAdapter.java:85-102`, `fallbackExecution=false`); guard `:700` blocks re-park → notice never sent | as before |
| 5 | Attempt-cap park `:680-689` | as #4 | as #4; `existsBlockedNeedingAHuman` (`:246`) keeps event out | as before |
| 6 | PLANNED insert `:456` | persist only; INSERT at commit AFTER Stripe | committed BEFORE Stripe | INSERT before Stripe (UNIQUE checked), commits with outcome |
| 8 | SUBMITTED/RETRYING/FAILED `:490-533` | flushed at commit | separate autocommit merge | flushed at commit |

| Failure | Before | Now | After |
|---|---|---|---|
| F1 unchecked exception after `:456` before/inside `:477` | rollback, next tick attempt 1 | PLANNED committed → event + org frozen permanently (sweep reconciles SUBMITTED only, `PostEventPayoutSweeper.java:166-169`) | rollback |
| F2 transport error, RETRYING save fails | no row, replay same key | frozen as F1 | as before |
| F3 4xx → FAILED | FAILED | same end state | same |
| F4 create OK, then SUBMITTED save/commit fails | run lost, next tick replays at Stripe ≥24 h key edge → possible double payout (pre-existing) | PLANNED stays, frozen, no double pay | as before (pre-existing) |
| F5 UNIQUE violation (needs 2nd writer; ShedLock rules out) | at commit after Stripe | at insert before call | at insert before call |
No code since 10-09 relies on autocommit (27a41607 reads only; 36960605 queries only; 5bc792b8 no change; cancelled-event-payouts re-read uses scalar JPQL). No `@Lock`/`FOR UPDATE`/bare `@Modifying`/lazy association in the path → the sweep has NOT been throwing TransactionRequiredException/LazyInitializationException.

## Repos in ship order
| key | base | worktree | verification |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/payout-tx-restore` (@137edd2a) | `docker info && ./mvnw test` |
Rebase onto `cancelled-event-payouts` (same two files) once it lands: edit only `:201-216` and `:453-456`; on conflict keep both sides (FakeStripe fields + `reset()` lines; appended tests each closed once); `./mvnw -q test-compile`, full gate.

## Affected files (per repo)
| File | Change |
|---|---|
| `src/main/java/com/imin/iminapi/payout/PostEventPayoutService.java` | move `payoutDescription` + its javadoc above the `payOneEvent` javadoc so the annotation sits on `public void payOneEvent`; `:456` `saveAndFlush`; comment `:453-455`; class step-5 javadoc; method javadoc (+ BLOCKED notices rely on commit) |
| `src/test/java/com/imin/iminapi/payout/PostEventPayoutServiceTest.java` | FakeStripe `volatile String crashPayoutForEvent` (reset null; throw before `payoutCount++` when metadata.event_id matches); T1–T3; teardown deletes second org |
Checked, no change: `PostEventPayoutSweeper.java`, `RefundRecoveryMarker.java`, `OrganizerPayoutNotifier.java`, `CLAUDE.md`.

## Ordered steps
1. `docker info`; base `./mvnw test`, record.
2. Move `payoutDescription` above the `payOneEvent` javadoc; annotation directly above `public void payOneEvent(UUID eventId)`.
3. `:456` → `return payoutRuns.saveAndFlush(r);` + comment.
4. Javadoc fixes (1–2-line comments, no ticket ids).
5. `grep -n -A2 "@Transactional" src/main/java/com/imin/iminapi/payout/*.java` — no annotation above a static method or javadoc.
6. T1–T3.
7. Prove red: annotation back on the static helper → T1, T2 red; `save` instead of `saveAndFlush` → T3 red; restore → green.
8. Targeted: `./mvnw test -Dtest=PostEventPayoutServiceTest,PostEventPayoutSweeperTest,PostEventPayoutDisputeRecoveryTest,PayoutTestModeExclusionTest`.
9. Full gate.
10. Rebase if cancelled-event-payouts landed; re-run 9.

## Verification commands
```
docker info
cd /Users/ivan/imin/imin-api/.claude/worktrees/payout-tx-restore
./mvnw test -Dtest=PostEventPayoutServiceTest,PostEventPayoutSweeperTest,PostEventPayoutDisputeRecoveryTest,PayoutTestModeExclusionTest
./mvnw test
grep -n -A2 "@Transactional" src/main/java/com/imin/iminapi/payout/*.java
```

## Test impact
All integration (`@IminIntegrationTest`), money/payouts/idempotency, regression for this prod incident.
| Test | Setup | Asserts | Bug caught | Red when |
|---|---|---|---|---|
| T1 `a_parked_payout_reaches_the_organizer_after_the_payout_commits` | ended event + order 10_000/1_000, `fake.hasBank=false`, `payOneEvent` via bean, drain `ticketEmailExecutor` | BLOCKED row; `notifications` row `kind='payout.blocked'` for creator + link; one mail to org contact with event name | organizer with no bank never told | annotation on static helper |
| T2 `an_unexpected_crash_mid_payout_rolls_back_its_run_and_freezes_no_org` | two eligible orgs, event A ends before B, `crashPayoutForEvent=A`, expire shedlock, `sweeper.sweep()`; clear flag, expire, sweep again | tick 1: A no run, B SUBMITTED; tick 2: A SUBMITTED attempt 1 key `evt:A:attempt:1`, `payoutCount==2` | one crash freezes an org forever; one org's crash costs another | annotation on static helper |
| T3 `a_payout_run_that_cannot_be_written_sends_no_money` | `PgFaults.failWrites(jdbc, "payout_runs", "event_id", e.getId())` around `payOneEvent` | throws; `payoutCount==0`; no run | bank payout with no ledger row, next tick can pay twice | `save` instead of `saveAndFlush` |
Not planned: reflection/ArchUnit annotation test (on the "does not get a test" list; T1–T3 catch it by behaviour); two-replica concurrency test (ShedLock).

## Live-test
No live payout in prod. Read-only pre-ship: Railway logs `"[payout-sweep] tick done"` (processed/errored), `"payout failed for event"`, `"recovery failed for org"`, `TransactionRequiredException`, `LazyInitializationException`; SQL: (1) runs since 2026-10-09 / in flight; (2) BLOCKED since 10-09 with/without `payout.blocked` notification; (3) `shedlock` row `PostEventPayoutSweeper.sweep`; (4) current candidate approximation. After ship: next 03:00 tick `locked_at` after deploy, `tick done errored=0`, no stale PLANNED.

## Contract impact
None.

## i18n impact
None (re-enables delivery of existing `payout.blocked` copy, EN/ES/FR/UK in `OrganizerPayoutNotifier.java:100-123,195-230`).

## Blast radius
- `payOneEvent` is the only `payouts().create` (`:477`), only caller `PostEventPayoutSweeper.java:119`; `recoverForOrg`/`reconcileSubmittedRun` untouched.
- Other `payout_runs` writers (reconcile, `payout.*` webhook, cutover scripts) never touch PLANNED.
- Behaviour after fix: BLOCKED notices delivered again; crash rolls back instead of freezing; PLANNED INSERT before Stripe, uncommitted until outcome; one DB connection held across one event's Stripe calls (as before 10-09; nested REQUIRES_NEW markers take a second; Hikari max 20).
- No migration, no contract, no shared module.

## Risks
- F4 pre-existing gap (commit fails after Stripe success → replay at ≥24 h key edge → possible double payout) — follow-up card: two-phase write (PLANNED committed by its own REQUIRES_NEW store, replayable like RETRYING).
- Rebase onto cancelled-event-payouts (adjacent hunks).
- Long-held transaction across Stripe calls if Postgres has `idle_in_transaction_session_timeout` (not in repo yaml) — same as before 10-09.

## Definition of done
- Annotation directly on `public void payOneEvent`; step-5 write `saveAndFlush`; javadoc matches "after fix".
- T1–T3 green, each proven red.
- Full gate green after any rebase, zero skipped; base recorded.
- Step-5 grep clean.
- Pre-ship log + SQL results recorded below.

## Live-test evidence
Pre-ship read-only prod 2026-10-12: payout_runs = 1 live PAID (2026-09-29) + 6 test PAID; 0 PLANNED, 0 RETRYING, 0 runs since 2026-10-09; 0 BLOCKED since 10-09 (no lost notices); shedlock PostEventPayoutSweeper.sweep locked_at 2026-10-12 01:00 UTC (tick ran); recent Railway log tail had no [payout-sweep]/TransactionRequired/LazyInitialization lines (older ticks rolled out of the CLI tail).

## Review rounds
- Gate 1 approved by the user 2026-10-12 as recommended: saveAndFlush included; F4 two-phase write → separate follow-up card. Repro: T1–T3 red with the annotation on the static helper / save instead of saveAndFlush.
- round 1 → FIX_REQUIRED (HIGH test-only: T1 re-asserts notifier content owned by OrganizerPayoutNotifierTest — assert only one notification row + one mail; MEDIUM: step-6 javadoc :107-109 and :471 'crash-window safe' no longer true — point to F4; LOW: T2 global payoutCount — count per event id). Reviewer: prod change correct; ship only with the F4 two-phase follow-up card opened. Rebase onto 98d8b2ee: service merges clean; test file 3 append conflicts.

---

# Amendment 2 (2026-10-12) — two-phase payout write (user chose option 1); supersedes conflicting lines above

## Goal and scope (amended)
Goal unchanged: one bank payout per event attempt, never two; no org frozen by a crash. On top of round 1 (annotation, saveAndFlush, T1–T3, javadoc):
1. **PLANNED commits on its own** via new `PayoutRunStore.plan` (REQUIRES_NEW) before the Stripe call; every outcome (SUBMITTED, RETRYING, FAILED, human-needed BLOCKED) commits right away through the store with conditional updates. `payOneEvent` keeps its own tx for reads + the two organizer-facing parks (no bank, attempt cap) so AFTER_COMMIT notices fire.
2. **Unknown outcomes (PLANNED/RETRYING left by an earlier tick) are resolved by listing, never by recomputing**, in `resolveUnknownRun`, called first by the sweep: list payouts on the connected account; exactly one match (event_id metadata + amount + currency + po_ not already recorded) → adopt SUBMITTED; none → FAILED `not_created` (normal path recomputes attempt+1, new key, every guard); ≥2 → BLOCKED `PAYOUT_AMBIGUOUS_MATCH`; listing fails → no write, BLOCKED `PAYOUT_OUTCOME_UNKNOWN` once the run is > 7 days old. `payOneEvent` never replays a key: new step 0u returns while its event has a PLANNED/RETRYING run.
3. **`IdempotencyException` → BLOCKED `PAYOUT_IDEMPOTENCY_MISMATCH`** (human, ERROR log, no organizer notice), never FAILED → no attempt 2.
4. **409 = unknown outcome** (RETRYING), not a refusal.
5. Stored amount never re-sent with a recomputed one; adopted payout keeps Stripe's amount (must equal the row's to match).
Out of scope: adding RETRYING to org-level `IN_FLIGHT` (`:147`).

| Failure | Round-1 | After amendment |
|---|---|---|
| F1 crash before/inside create | rollback, no row | PLANNED committed; siblings wait one tick; next tick list empty → FAILED `not_created` → attempt 2 paid once with all guards |
| F4 Stripe accepted, then crash | row lost → (a) early return on drained balance / later fresh payout, (b) IdempotencyException → FAILED → attempt 2 | PLANNED stays → list finds po_ → adopted SUBMITTED (submitted = Stripe `created`); no balance read, no second create |
| transport/429/5xx/409/no status | RETRYING, replay past balance clamp (bug a) | RETRYING committed → resolver |
| 4xx refusal | FAILED | FAILED committed |
| idempotency_error | FAILED → attempt 2 | BLOCKED human |
| listing fails | — | no write; BLOCKED after 7 days |
| ≥2 matching unrecorded payouts | — | BLOCKED human |
| cancelled-event hold (0c/4b), open-refund hold (1c) | RETRYING bypasses them | bypass removed (`replaying` gone); resolver adopts existing money regardless of status, else FAILED and the fresh attempt meets 0c/1c/4b |

## Repos in ship order (amended)
imin-api only. Rebase BEFORE implementing (origin has 98d8b2ee, d4410807, 48019b2a, 8935a2f5): `git fetch && git stash push -u -m payout-tx-restore && git merge --ff-only origin/master && git stash apply <sha>` (drop the entry after); keep both sides of the 3 append conflicts in `PostEventPayoutServiceTest`; `./mvnw -q test-compile`.

## Affected files (per repo) (amended)
| File | Change |
|---|---|
| `payout/PayoutRunStore.java` (new `@Service`) | all REQUIRES_NEW: `plan(draft)` → saveAndFlush; `recordSubmitted(runId, poId, submittedAt)`, `recordRetrying(runId, reason)`, `recordFailed(runId, reason)`, `holdForHuman(runId, reason)` — each one conditional JPQL update `where id = :id and status in (PLANNED, RETRYING)` setting `updatedAt`; false on 0 rows (WARN) |
| `payout/PayoutRunRepository.java` | the `@Modifying` updates; `existsByEventIdAndStatusIn`; `existsByStripePayoutId`; `findByStatusInAndTestMode` |
| `payout/PayoutBlockReason.java` | `IDEMPOTENCY_MISMATCH`, `OUTCOME_UNKNOWN`, `AMBIGUOUS_MATCH` (human-needed, no organizer notice); `NOT_CREATED = "not_created"` |
| `payout/PostEventPayoutService.java` | inject store; step 0u after 0b; remove `replaying` + RETRYING branch of `nextAttempt` (max+1 with cap); step 5 `runStore.plan`; steps 6/7 → `submit(run)` with `createParams(run)` built only from the row (amount, currency, description w/ V180 fallback, metadata event_id/org_id, key); success → recordSubmitted; IdempotencyException → holdForHuman + ERROR `[payout] HUMAN NEEDED`; unknown → recordRetrying; definitive → recordFailed; RuntimeException leaves PLANNED; `isDefinitiveRejection`: 409 not definitive; new `@Transactional(REQUIRES_NEW) public void resolveUnknownRun(UUID)`; javadoc (delete round-1 "Known gap", drop RETRYING/replay sentences incl. `:151-155`) |
| `payout/PostEventPayoutSweeper.java` | step A0 `resolveUnknownOutcomes()` before `reconcileStaleSubmitted()`: `findByStatusInAndTestMode([PLANNED, RETRYING], !props.isLiveKey())`, each `resolveUnknownRun` in try/catch + ERROR |
| `CLAUDE.md` | resolver paragraph in Stripe Connect section; fix step-1c sentence (`:244`) and 98d8b2ee RETRYING-exemption text |
| tests | see Test impact |
No change: `EventRepository` (RETRYING candidate clause harmless after 0u), `PayoutRetentionMonitor`, `OrganizerPayoutNotifier`; `PostEventPayoutSweeperTest`, `PostEventPayoutDisputeRecoveryTest`, `PayoutTestModeExclusionTest` expected unchanged (grep RETRYING/PLANNED; rewrite hits like below).

## Ordered steps (amended)
1. Rebase; re-run the four payout classes on the new base, record.
2. Reason constants + repository methods. 3. `PayoutRunStore`. 4. Service: 0u; remove `replaying` + RETRYING nextAttempt; `createParams`/`submit`; step 5 `runStore.plan`. 5. `isDefinitiveRejection` (409) + IdempotencyException first.
6. `resolveUnknownRun`: kill switch; load run, return unless PLANNED/RETRYING and `testMode == !isLiveKey`; list `payouts().list(PayoutListParams.builder().setCreated(Created.builder().setGte(createdAt-300s)).setLimit(100L).build(), RequestOptions.setStripeAccount(acct)).autoPagingIterable()` (stripe-java 32.1.0 `PayoutService.java:56`, `PayoutListParams:144,:446`); filter event_id + amount + currency (case-insensitive) + `!existsByStripePayoutId`; StripeException → hold after 7 days else WARN; 1 → recordSubmitted(po.created); ≥2 → AMBIGUOUS; 0 → recordFailed(NOT_CREATED).
7. Sweeper A0. 8. Javadoc + CLAUDE.md. 9. Tests + red proofs. 10. Gate (four classes, full `./mvnw test`); grep `payoutRuns.save(run)\|setStatus(PayoutRunStatus` shows no full-entity save of a store-settled run (parks `:684`, `:723` stay).

## Test impact (amended)
FakeStripe: in-memory ledger of created payouts (id, amount, currency, metadata event_id, created); `GET /v1/payouts` list from the ledger (page helper from `PostEventPayoutDisputeRecoveryTest:137-141`); `createCalls`; flags reset per test: `ackThenCrashForEvent`, `failIdempotency` (`IdempotencyException(..., 400)`), `fail409` (`ApiException(..., 409, ...)`), `failPayoutList` (`ApiConnectionException`). Fake doesn't dedup keys (models > 24 h).
| Test | Asserts | Bug | Red proof |
|---|---|---|---|
| T1 unchanged | — | — | — |
| T2 re-pinned `a_crash_before_the_payout_request_keeps_its_run_and_pays_once_next_tick` | tick 1: A one PLANNED attempt 1, B SUBMITTED; tick 2: A attempt 1 FAILED `not_created`, A SUBMITTED attempt 2 key `evt:A:attempt:2`, payoutsByEvent A==1, B==1 | crash loses ledger row / freezes org / pays twice | round-1 code: A has no row after tick 1 |
| T3 unchanged | — | — | `runStore.plan` → `payoutRuns.save` in outer tx: payoutCount==1 |
| T4 `a_payout_stripe_accepted_before_a_crash_is_adopted_and_never_sent_twice` (@Param balance after tick 1 ∈ {0, 4_000}) | after tick 1 PLANNED; after tick 2 one run SUBMITTED, po_ = ledger's, amount 9_000, attempt 1, createCalls==1 | double payout after lost response | round-1 code: 0 → no SUBMITTED; 4_000 → createCalls==2 |
| T5 `an_idempotency_mismatch_parks_the_payout_for_a_human_and_never_tries_again` | BLOCKED IDEMPOTENCY_MISMATCH; no PayoutBlockedEvent; after tick createCalls==1, max attempt 1 | attempt 2 double-pays; wrong "fix your bank" | old isDefinitiveRejection → FAILED → attempt 2 |
| T6 `an_unknown_create_outcome_parks_retrying_not_failed` (@Param transport, 409) | RETRYING attempt 1, no FAILED | 409 bumps attempt → second payout | 409 → FAILED on old code |
| T7 `an_undecidable_run_is_held_for_a_human_without_paying` (@Param list fails + 8 days → OUTCOME_UNKNOWN; two matches → AMBIGUOUS_MATCH) | BLOCKED reason; createCalls==0 | guessed payout | delete hold branches |
Rewritten deliberately: `transport_failure_parks_run_retrying_and_replays_the_same_idempotency_key` → `a_transport_failure_is_resolved_from_stripe_next_tick`; `an_open_refund_does_not_delay_replaying_a_retrying_run` (`:1421`) → `a_retrying_run_with_no_payout_at_stripe_waits_for_the_open_refund`; 98d8b2ee cancelled RETRYING replay → `a_cancelled_event_s_unknown_run_is_adopted_if_paid_and_held_otherwise` (@Param ledger has payout → adopted; empty → FAILED, no create).

## Live-test (addendum)
Ship precondition: query 1 right before /ship-imin = 0 planned/retrying rows (else back to Ivan). After ship: log filters `"[payout-resolve]"` and `"HUMAN NEEDED"`.

## Blast radius (addendum)
Writers of `payout_runs` (no @Version): store (conditional), `reconcileSubmittedRun` (SUBMITTED only), `SettlementIngestService.ingestPayout` (by po_), `parkAtAttemptCap` (FAILED/BLOCKED run), `parkNoBankAccount` (insert), cutover scripts — none can revert a store write. Creating paths `payOneEvent` and `resolveUnknownRun` both go through `submit`. One `GET /v1/payouts` per unknown run per tick. No migration (amount/currency/description/key/created already on `payout_runs`; status VARCHAR(16), no CHECK), no contract, no new organizer string.

## Risks (amended)
- Decision rests on Stripe's payout list; a payout missing from the list → treated as not created → fresh attempt could double-pay (≥ one tick after creation; match on event+amount+currency+unrecorded po_; two candidates → human).
- RETRYING stays out of IN_FLIGHT (siblings may pay; never double-sent).
- Each `not_created` spends an attempt (cap 3).
- ~8 files, one concern.

## Definition of done (amended)
Round-1 items hold; PLANNED committed before every create; every outcome via `PayoutRunStore`; no key re-sent with a recomputed amount, no auto-create on unknown outcome; T1–T7 green, T2/T4–T7 proven red; three rewritten tests green; full gate after rebase; CLAUDE.md updated; ship precondition query = 0 open runs.

## Review rounds (append)
- Gate 2 (2026-10-12): user folded the two-phase write in (option 1). Planner added the 409 path and found the existing RETRYING replay has bug (a). Planner deviation for confirmation: no-match → FAILED + recompute (not same-key replay at stored amount), because a stale stored amount would skip 0c/1c/4b guards.
- Gate 1 (Amendment 2) approved by the user 2026-10-12 as recommended: no-match → FAILED not_created + recompute (planner's deviation accepted); human-needed parks → ERROR/Sentry only, no organizer notice for now; each not_created spends an attempt (OK); ship precondition: 0 PLANNED/RETRYING in prod right before ship.
- round 3 → FIX_REQUIRED (HIGH test gap: resolver match filter (event_id/amount/currency in madeBy, !existsByStripePayoutId) untested — any check deletable with tests green; MEDIUM: vacuous a_replayed_payout_keeps_its_description test; list-fails-under-7-days branch untested; attempt-cap notice says 'bank did not accept' for not_created. LOW: lost po_ only WARN; unreachable description fallback; sandbox human parks not counted; 0u ignores testMode). No double-payout code path found. Max 3 rounds reached → blockers to the user.
- round 4 (user-authorized) → APPROVE (LOW: in-app body English-only + vague for not_created).
