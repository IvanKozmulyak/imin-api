# ap-invitation-robustness: stored invitations independent of the plan, at-least-once invite-on-publish

Slug: `ap-invitation-robustness` · Mode: Subagent · Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md`
("Programme audit #3", pre-flip checklist items 12 and 13; follow-ups M3-1 stored lookup, M4-1 × M4-8 early_bird_end, M4-8 at-most-once).

## Goal and scope

(a) M3-1: `POST /events/{id}/audience-plan/invitations` for a class × genre fit that is already invited returns the
stored invitation (and can rebuild a deleted draft) even when the current plan no longer shows that segment. Stored
experiments are looked up first; only a segment with no stored invitation must be a shown segment of the current plan.

(b) M4-8: invite-on-publish
- drops arms a new invitation could not get at publish time (`d3` without a D-3 date; after M4-1, `early_bird_end`
  when `TimingArmScheduler.earlyBirdOffered` is false) instead of skipping the segment; `slump` has no availability rule
  in M4-1 and is kept. The rule is asked of `InvitationService.offeredArms`, so both paths share it;
- becomes at-least-once: the intent is claimed with `claimed_at` (+ `attempts`) instead of deleted; it is deleted only
  after every segment was processed (invited, or refused with a 4xx `ApiException`); an unexpected failure leaves the
  claim in place;
- `InviteOnPublishSweeper` (ShedLock `audience_plan_invite_on_publish_sweep`, every 15 min) re-runs intents of LIVE
  events claimed over 30 min ago, or never claimed although published 30 min to 48 h ago (the publish listener's
  executor drops overflow); a never-claimed one gets the plan refresh the publish would have done first. After 3 attempts
  the intent is dropped with a WARN. Re-runs are idempotent through (a). Each pass first deletes never-claimed intents of
  LIVE events published over 48 h ago (INFO log; leftovers from before the deploy must not become drafts days later) and
  claimed intents of DRAFT/PAST/CANCELLED events whose claim is over 7 days old.

Out of scope: webapp, contract changes, `two_emails`, any send.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-invitation-robustness` | `./mvnw test` (full run via `/Users/ivan/.imin-pipeline/mvnlock.sh clean test`) |

## Affected files (per repo)

api:
- `src/main/resources/db/migration/V159__publish_invites_claim.sql` (new)
- `src/main/java/com/imin/iminapi/audienceplan/model/PublishInvite.java`
- `src/main/java/com/imin/iminapi/audienceplan/repository/PublishInviteRepository.java`
- `src/main/java/com/imin/iminapi/audienceplan/service/InviteOnPublishService.java`
- `src/main/java/com/imin/iminapi/audienceplan/service/InviteOnPublishSweeper.java` (new)
- `src/main/java/com/imin/iminapi/audienceplan/service/InvitationService.java`
- `src/test/java/com/imin/iminapi/audienceplan/controller/AudiencePlanInvitationScenarios.java`
- `src/test/java/com/imin/iminapi/audienceplan/controller/AudiencePlanInviteOnPublishScenarios.java` (the former
  `AudiencePlanInviteOnPublishWebTest` body, now abstract) + `AudiencePlanInviteOnPublishWebTest.java` (H2) +
  `AudiencePlanInviteOnPublishPostgresTest.java` (new, PG 17: the claim compares stored timestamps)
- `src/test/java/com/imin/iminapi/audienceplan/service/InviteOnPublishSweeperTest.java` (new)
- `src/test/java/com/imin/iminapi/audienceplan/service/InviteOnPublishServiceTest.java` (new, review round 1: sweep
  branches that need a concurrent sweeper)
- `CLAUDE.md` (invitations and invite-on-publish lines)

## Ordered steps

1. Reproduction tests (red on the unfixed code):
   - `aStoredInvitation_isReturned_afterARefreshDropsItsSegment` (invitations): invite loyal/same, recompute the plan
     without loyal guests, repeat → 200 `created:false` with the same ids; delete a draft and repeat with
     `recreateMissingDrafts:true` → rebuilt.
   - `anUnexpectedFailure_keepsTheIntentClaimed_forTheSweeper` (invite-on-publish): `InvitationService.invite` throws a
     non-API exception once → the intent row stays with `claimed_at` set and `attempts = 1`.
2. `V159`: `claimed_at TIMESTAMP WITH TIME ZONE NULL`, `attempts INTEGER NOT NULL DEFAULT 0`.
3. `InvitationService`: validate the request shape without the plan; per segment `findInvited` first (guarding null
   keys before the query); a segment with nothing stored must be shown on the current plan (400 otherwise) and pass the
   arm-availability check; `stored(...)` works from the stored plan segment's keys; `offeredArms(event, plan, arms, now)`.
4. `InviteOnPublishService`: `claim` / `reclaim` / `complete` / `findStale` repository methods; PUT resets the claim;
   `runOnPublish` claims, `sweepStale` re-claims; `run` drops arms via `offeredArms`, logs an already-invited 409 as such,
   keeps the row on an unexpected failure, deletes it with a matching `claimed_at` otherwise.
5. `InviteOnPublishSweeper` scheduled + ShedLock.
6. Rebase onto origin/master once M4-1 has shipped; add the `early_bird_end` rule to `offeredArms` with a test.
7. CLAUDE.md lines.

## Verification commands

- `./mvnw test -Dtest='AudiencePlanInvitation*,AudiencePlanInviteOnPublishWebTest,InviteOnPublishSweeperTest,PlanRefreshJobTest'`
- `/Users/ivan/.imin-pipeline/mvnlock.sh clean test`

## Test impact

New, one per branch:
- invitations: stored returned after the segment left the plan; recreate after it left; a never-invited segment not on
  the plan is still 400; a null class key is 400 without a query.
- invite-on-publish: unexpected failure keeps the claim (attempts 1); a refusal (4xx) still completes and deletes;
  already-invited 409 logged as such; a republish while claimed does nothing; PUT after a stale claim resets it;
  `early_bird_end` dropped when not offered (after M4-1).
- sweeper: stale claim re-run and completed; fresh claim untouched; never-claimed intent of an event published > 30 min
  ago refreshed + run; never-claimed of a recent publish untouched; DRAFT event untouched; attempts at the cap dropped
  with WARN; a re-run after a crash between segments invites nothing twice.
- review round 1: blank `classKey` 400; never-claimed intent published 47 h ago run, 49 h ago deleted with INFO and
  nothing invited; claimed intents of PAST/CANCELLED/DRAFT events deleted after 7 days, a 6-day one kept; a sweeper that
  loses the claim race (stale and never-claimed) skips the row; the attempt cap with nothing left to drop logs nothing.

## Live-test

Not needed: no contract change and no UI; the flows are covered end to end through MockMvc and the service on H2
(Postgres scenarios run the invitation suite on PG 17).

## Contract impact

none (request/response shapes unchanged; a previously-400 repeat now answers 200 with the stored invitation).

## i18n impact

none.

## Blast radius

Audience-plan invitations and invite-on-publish only, both beta-gated and drafts-only; sends stay behind
`IMIN_AUDIENCE_PLAN_SENDS_ENABLED`. One new scheduled job (tiny table, 50 rows per pass).

## Risks

- A run longer than 30 min would be re-run in parallel: the per-event invitation lock and the stored lookup make the
  second call return the stored ids.
- An intent that always fails is retried 3 times, then dropped with a WARN.
- Accepted race, PUT during an in-progress run: a PUT needs the event back in DRAFT, so it only overlaps a run when the
  organizer unpublishes while the run is inviting. The PUT replaces the segments and clears the claim; the running run
  still finishes the segments it read before the PUT (drafts only), and its `complete` no longer matches `claimed_at`,
  so the new intent survives for the next publish, whose run returns the stored invitations for any overlap. Not
  guarded further: the window is one run long and the outcome is at most draft campaigns the organizer already asked
  for.

## Definition of done

Reproduction tests red then green; verification commands green; CLAUDE.md updated; no contract change.

## Live-test evidence

n/a (no live-test needed). Reproduction red on the unfixed code (origin/master 0ba27e72), 2026-09-28:

```
[ERROR]   AudiencePlanInvitationWebTest>AudiencePlanInvitationScenarios.aStoredInvitation_isReturned_afterARefreshDropsItsSegment:399 Status expected:<200> but was:<400>
[ERROR]   AudiencePlanInviteOnPublishWebTest.anUnexpectedFailure_keepsTheIntentClaimed_forTheSweeper:316
expected: 1
 but was: 0
```

Baseline full suite on the untouched worktree: `Tests run: 5252, Failures: 0, Errors: 0, Skipped: 3` BUILD SUCCESS.
Rebased onto origin/master 9c684d7c (M4-1 + M3-5) before the final run.

## Review rounds

- Round 1 → SHIP, with fixes applied: never-claimed intents swept only within 48 h of the publish, older ones of LIVE
  events deleted with an INFO log (MEDIUM); tests for a blank `classKey`, a lost claim race and the attempt cap with
  nothing dropped (LOW); claimed intents of events no longer LIVE purged after 7 days (LOW); the PUT-during-run race
  documented under Risks.
