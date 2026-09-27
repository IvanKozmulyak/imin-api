# M4-2 Momentum targets the plan's best segment (+ M4-6 Momentum → plan refresh hook)

Slug: `ap-m4-2-momentum-plan` · Programme plan: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (§ M4-2, M4-6, "Programme audit 2026-09-27": "Momentum trigger → plan refresh not wired … M4-2 owns that file — add the hook there").

## Goal and scope

When `MomentumEvaluator` fires a trigger for an event:
1. **Targeting.** If the org passes `AudiencePlanAccess.isEnabled` and the event has a current (non-superseded) audience plan with a shown segment of at least `min_segment_to_show` (logic file, 10) mailable, the draft targets the members of the highest expected-mid shown segment (tie → lower position), re-derived now via `CandidateLoader.build` with the plan's own target and tickets-per-order, **minus every holdout member of that event** (`AudienceAssignmentRepository.findHeldOut`). If fewer than 10 remain, or the plan target is below the Momentum SendGate floor, it falls back to `defaultTargetSegmentId` (Repeat), as today.
2. The chosen members are frozen into a static segment (`kind='static'`, `snapshot_ids`) whose id goes into the draft payload, so approve → draft campaign → `RecipientMaterializer` send exactly that list (SendGate + M3-2 SendPathGuard still re-check at materialise). The segment is written only when a suggestion is about to be persisted; if the copy or suggestion write then fails it is deleted (the cleanup is wrapped so it cannot replace the original error).
3. **Hook.** A fired trigger that passes the live/cooldown guardrails publishes `MomentumTriggered(orgId, eventId, trigger)`; an `@Async` listener calls `PlanService.refresh(eventId)` (M3-R5). Publish and listener failures are logged, never thrown into the evaluator.

Out of scope: no new endpoint, no migration, no UI, no sends (Momentum output stays a suggestion; approve still creates a `draft`; `sends-enabled` untouched). M3-1 invitation/experiment files and `Segment.java`/`SegmentService` are not edited.

Reproduction test: n-a (new behaviour).

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m4-2-momentum-plan` | `./mvnw test` |

## Affected files (per repo)

api:
- `src/main/java/com/imin/iminapi/marketing/service/MomentumEvaluator.java` (modify)
- `src/main/java/com/imin/iminapi/marketing/service/MomentumTriggered.java` (new, event record)
- `src/main/java/com/imin/iminapi/audienceplan/service/MomentumPlanTarget.java` (new)
- `src/main/java/com/imin/iminapi/audienceplan/service/MomentumPlanRefresh.java` (new, listener on `PlanService.refresh` + `PlanRefreshExecutor`, both on origin since 11c10e4f)
- `src/test/java/com/imin/iminapi/audienceplan/service/MomentumPlanTargetTest.java` (new)
- `src/test/java/com/imin/iminapi/audienceplan/service/MomentumPlanRefreshTest.java` (new)
- `src/test/java/com/imin/iminapi/marketing/service/MomentumEvaluatorPlanTargetTest.java` (new, Mockito; `evaluateOne` becomes package-private for it)
- `src/main/java/com/imin/iminapi/audienceplan/service/SendPathGuard.java` (modify, review round 1: ConsentGate for Momentum plan snapshots)
- `src/test/java/com/imin/iminapi/marketing/send/SendPathGuardMaterializeTest.java` (modify, review round 1)
- `src/test/java/com/imin/iminapi/marketing/MomentumEvaluatorTest.java` (modify: end-to-end holdout test, Repeat fallback, async refresh, drain of the refresh executor before the wipe)
- `CLAUDE.md` (one line under the audience-plan env notes)
- this plan

## Ordered steps

1. `MomentumPlanTarget.best(Event)` → `Optional<Target(classKey, genreFit, membershipIds)>`; no writes. Branches: beta off; no current plan; no shown segment ≥ min; best by expected-mid (tie → position); segment not re-derived now; holdouts removed; < min after holdouts.
2. `MomentumPlanTarget.snapshot(orgId, eventName, Target)` → static segment id; `discard(orgId, segmentId)` deletes it.
3. `MomentumEvaluator`: after guardrails publish `MomentumTriggered` (wrapped); try plan target (wrapped: any failure → Repeat); SendGate floor on plan ids, below floor → Repeat; snapshot just before copy generation; on failure discard (wrapped, suppressed) and rethrow.
4. `MomentumPlanRefresh` listener (`@EventListener @Async(PlanRefreshExecutor.NAME)`) → `PlanService.refresh`, log, swallow. Last step; rebased onto M3-R5 when it lands on origin.
5. Tests per branch; CLAUDE.md line.

## Verification commands

- `./mvnw test -Dtest='MomentumPlanTargetTest,MomentumPlanRefreshTest,MomentumEvaluatorPlanTargetTest,MomentumEvaluatorTest,PlanRefreshJobTest'`
- `./mvnw test` (once, at the end)

## Test impact

New: `MomentumPlanTargetTest` (Mockito, one test per branch above + snapshot content/name + discard), `MomentumPlanRefreshTest` (refresh called; exception swallowed). `MomentumEvaluatorTest` gains: plan target → draft `segmentId` is the snapshot and its members are the target ids; no plan → Repeat; plan below floor → Repeat and no snapshot; plan lookup throws → Repeat; suggestion write fails → snapshot discarded; failing discard keeps the original error; trigger publishes `MomentumTriggered`; publish failure does not stop the suggestion.

## Live-test

Not run: Momentum is an hourly job that needs an on-sale event with a stored plan and ConsentGate-mailable members with fan features in a beta org; covered by the Spring tests above. No contract change.

## Contract impact

none

## i18n impact

None in the FE. The static segment name (shown as the Momentum card's segment label) is English, like the prebuilt segment names it sits beside.

## Blast radius

`MomentumEvaluator` for every org: behaviour changes only for beta orgs (`AudiencePlanAccess.isEnabled`) with a stored plan; everyone else keeps Repeat. New static segments appear in the org's segment list (one per persisted plan-targeted suggestion; suggestions are capped by one live per trigger and a 7-day cooldown).

## Risks

- Stored plan can be stale (up to a day, or older if never refreshed); members are re-derived now, so consent/bought/fatigue are current; only the segment choice uses the stored ranking.
- Static snapshot freezes at suggestion time; SendGate + SendPathGuard re-check at materialise, so later unsubscribes and holdouts are still skipped.
- The hook refreshes asynchronously, so the draft that fired it uses the pre-refresh plan.

## Definition of done

All branches tested; targeted tests and full `./mvnw test` green; hook wired against origin `PlanService.refresh`.

## Live-test evidence

n-a (see Live-test).

## Review rounds

(appended by /do-task)

### Round 1 → SHIP, with three approved fixes (applied)

1. MEDIUM — plan-targeted Momentum sends passed ConsentGate only at snapshot. The snapshot now carries `prebuilt_key='MOMENTUM_PLAN'` (`MomentumPlanTarget.SNAPSHOT_KEY`, no migration; `prebuilt` stays false, static resolution never routes on the key), and `SendPathGuard` applies ConsentGate to an `origin='momentum'` campaign whose segment has that key. Test: `SendPathGuardMaterializeTest` — a member who objects to profiling after the snapshot is skipped with `consent_gate` at materialise, the other member stays pending; a Momentum campaign on a non-snapshot segment with the flag off still reaches the member. Proven red with the guard branch disabled.
2. LOW — `MomentumPlanTarget.best` chunks `findHeldOut` by `SendPathGuard.MAX_IDS_PER_QUERY` (1000). Test: 2500 members → three calls, holdouts from first and last chunk removed.
3. LOW — a `snapshot()` failure now logs and falls back to Repeat (Repeat floor still applies; nothing to discard). Test: `MomentumEvaluatorPlanTargetTest.snapshotFailure_fallsBackToRepeat_withNothingToDiscard`.
