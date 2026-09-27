# M3-1 Invitations and experiments (ap-m3-1-invitations)

Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` §M3-1 (+ M3-2/M3-3 context, §4.3, "Programme audit 2026-09-27").
Mode: autonomous runner. Reproduction test: n-a (new code).

## Goal and scope

`POST /api/v1/events/{eventId}/audience-plan/invitations` turns chosen segments of the event's current audience plan
into an experiment: per segment the ConsentGate is re-read now, members are shuffled deterministically, a holdout is
taken (only when the segment has ≥ 60 members), the rest is split evenly across the chosen timing arms, assignments
are written, and per arm a hidden static segment (`origin='audience_plan'`) plus a **draft** campaign
(`origin='audience_plan'`, `event_id`, status `draft`) is created. Nothing is scheduled or sent; the M3-3 kill switch
(`sends-enabled=false`) stays the enforcement point. Idempotent: a second call for the same `{classKey, genreFit}`
of the event returns the same ids.

Also (programme audit): FKs `audience_experiments.plan_id → audience_plans`, `plan_segment_id → audience_plan_segments`
(deferred from V135/V136) and `audience_plans.org_id → organizations`.

Out of scope: arm scheduling times (M4-1), outcomes (M3-5), webapp dialog (M3-W3), any beta gating beyond
`AudiencePlanAccess`.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-1-invitations` | `./mvnw test` |

## Affected files

api:
- new `src/main/resources/db/migration/V154__segments_origin_and_experiment_fks.sql` (was V137, reserved for M3-1 in §4.3; renumbered above origin's V153 in round 2)
- new `audienceplan/service/ExperimentService.java` (pure split + experiment/assignment writes)
- new `audienceplan/service/InvitationService.java` (gate, plan lookup, validation, segments + draft campaigns, idempotency)
- new `audienceplan/controller/AudiencePlanInvitationController.java` (separate controller: M3-R5 is editing PlanService concurrently)
- new `audienceplan/dto/AudiencePlanInvitationsRequest.java`, `audienceplan/dto/AudiencePlanInvitationsResponse.java`
- modify `audienceplan/repository/AudienceExperimentRepository.java` (existing invitations of an event by class × fit)
- modify `audienceplan/repository/AudiencePlanSegmentRepository.java` (lock a plan's segment rows)
- modify `audience/model/Segment.java` (`origin`), `audience/service/SegmentService.java` (list hides plan segments; delete refuses them)
- modify `CLAUDE.md` (endpoint line)
- tests: `audienceplan/service/ExperimentServiceTest.java`, `audienceplan/controller/AudiencePlanInvitationScenarios.java`
  + `AudiencePlanInvitationWebTest.java` (H2) + `AudiencePlanInvitationPostgresTest.java` (Postgres 17),
  (18 scenarios each, incl. a concurrency test proven red without the segment-row lock).

Not touched: `PlanService`, `AudiencePlanRepository`, `AudiencePlanController` (concurrent M3-R5 work).

## Ordered steps

1. Tests first for the pure split (`ExperimentServiceTest`): same seed → same assignment; different seed → different
   order; 235 @15% → 35/100/100; 40 → 0/20/20; 59 → no holdout; 60 → 9; 1,000 → 150; odd remainder goes to the first
   arm; one arm takes everything; members sorted before shuffling (input order irrelevant); disjoint and complete.
2. V154 (was V137): `segments.origin VARCHAR(32) NOT NULL DEFAULT 'organizer'`; experiments FKs plan_id, plan_segment_id
   (NO ACTION, so a plan with experiments cannot be pruned); `audience_plans.org_id → organizations ON DELETE
   CASCADE`; unique `(plan_segment_id, arm)` backstop. No event/org FK on experiments: four M3-2 tests write
   experiments for random event/org ids, and nothing hard-deletes events or orgs.
3. `Segment.origin` + `SegmentService.listSegments` filter + `deleteSegment` 404 for plan segments.
4. `ExperimentService` (split + persist experiments/assignments before any campaign), `InvitationService`,
   controller, DTOs.
5. Web scenarios (H2 + Postgres): 200 shape; holdout/arm counts 235 → 35/100/100 and 40 → 0/20/20; assignments rows;
   draft campaigns (origin, event, status draft, segment = arm members); arm segments hidden from
   `GET /audience/segments`; holdout members get no recipient row when the arm campaign materialises; idempotent
   repeat (same ids, no new rows) incl. after a plan recompute; other org → 404; beta off → 404 and nothing written;
   no plan → 404; holdoutPct 9/21 → 400; unknown segment / arm / duplicate / d3 without a D-3 date → 400; empty
   segment now → 409; scheduling an arm campaign → 409 `AUDIENCE_SENDS_DISABLED`; migration FKs on Postgres
   (plan with experiments cannot be deleted; event delete cascades).
6. CLAUDE.md line; full `./mvnw test`.

## Verification commands

- `cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-1-invitations && ./mvnw test`

## Test impact

New tests only; existing segment/campaign tests must stay green (`listSegments` filter only removes plan rows).

## Live-test

Not run: sends stay disabled, the endpoint only writes drafts; H2 + Postgres 17 scenario tests cover the endpoint
end to end (orchestrator decides on a /live-test).

## Contract impact

`/api/v1` — new path `POST /api/v1/events/{eventId}/audience-plan/invitations`, marker `AudiencePlanInvitationsResponse`.
Webapp (M3-W3) after prod OpenAPI shows the marker; api:sync is the orchestrator's.

Request: `{segments: [{classKey (alias "class"), genreFit, arms: ["launch"|"d3"], holdoutPct?: 10..20}]}`;
`holdoutPct` omitted → logic default (15); segments under 60 members never get a holdout.

## i18n impact

None (api only). Draft names are data (event name · class · fit · arm keys), not UI copy.

## Blast radius

- `SegmentService.listSegments` hides `origin='audience_plan'` rows (all existing rows default `organizer`).
- `deleteSegment` 404s for plan segments (their ids surface only through arm campaigns).
- New FKs on tables that are empty in prod (`audience_experiments`) or whose rows always have a live org (plans).
- No change to sending: drafts only; M3-3 switch blocks schedule/send; M3-2 holdout skip already live.

## Risks

- Idempotency is keyed on event × class × fit (not only plan segment id) so a plan refresh cannot double-invite; two
  concurrent calls on different plan generations of one event are not serialised (ponytail, noted in code).
- Adding FKs fails the migration if an orphan exists: impossible for plans (events cascade from orgs; plans cascade
  from events) and experiments (no writer before this task).

## Definition of done

Endpoint + migration + tests green on H2 and Postgres 17; full `./mvnw test` green; CLAUDE.md documents the endpoint.

## Live-test evidence

n-a (see Live-test).

## Review rounds

(appended by the orchestrator)

### Round 1 → FIX_REQUIRED

Findings and fixes (review-fix; rebased onto origin/master, last at e5d35fa5; full `./mvnw test` there: 5058 run, 0 failed, 3 skipped):

1. HIGH — org deletion 400ed: `experiments.plan_id` NO ACTION blocked the org → event → plan cascade. Fix: V137 adds
   `fk_audience_experiments_event` (`event_id → events ON DELETE CASCADE`), which also covers an event hard delete.
   The four M3-2 tests that wrote experiments for random event ids now use real events (`OrderFixtures.event`).
   Tests: `anEventWithInvitations_canBeHardDeleted_andTakesItsExperimentsAlong`, `anOrgWithInvitations_canStillBeDeleted`
   (`DELETE /api/v1/org` → 204); both red on Postgres and H2 without the FK. Postgres only: H2 checks NO ACTION row by
   row inside a cascade, and any org with an event already 400s on H2 via `events.created_by` (pre-existing).
2. MEDIUM — idempotency: assignment stays permanent per event × class × fit. Other arms on a repeat →
   `409 AUDIENCE_PLAN_ALREADY_INVITED`, `fields['segments[i].arms']` = stored arms. A stored arm whose draft was deleted
   → `draftMissing:true`, null ids; top-level `recreateMissingDrafts:true` rebuilds the static segment + draft from the
   stored assignments (`AudienceAssignmentRepository.findMembershipIds`), no reshuffle.
3. MEDIUM — invitations serialise per event on `PlanService.lockFirstPlan` (advisory lock / H2 event row) at the start
   of the transaction; the plan-segment row lock is gone. Test `twoCallsOnDifferentPlanGenerations_…` holds call A in
   the loader, commits a recompute, and proves B waits and returns A's ids (red without the lock).
4. MEDIUM — assignments are a JDBC batch (1,000 per batch) after flushing the experiment row. Local Postgres 17
   container, 10,000 assignments in one `record()`: 192 / 154 / 142 ms over three runs.
5. Tests: event already started (moved before the lock) → 409 even for an invalid body; stored() with a deleted draft;
   name truncation (campaign 120, segment 128).

Also touched: `security/ErrorCode.java` (new code), `audienceplan/repository/AudienceAssignmentRepository.java`,
`AudiencePlanInvitationsRequest/Response` (new fields), tests `SendPathGuardTest`, `SendPathGuardPerBatchTest`,
`SendPathGuardMaterializeTest`, `AudienceDsarTest`. `AudiencePlanSegmentRepository` is back to origin.

### Round 2 → SHIP

Final fixes applied before shipping (review-fix):

1. Deploy safety: the migration is renumbered V137 → V154 (origin/master already applied V153; below it only works
   because Railway runs `SPRING_FLYWAY_OUT_OF_ORDER=true`). Before adding the keys it deletes plans whose org is gone
   (their plan segments cascade) and experiments whose event, plan or plan segment is gone (assignments cascade); plain
   `DELETE … WHERE NOT EXISTS`, valid on H2 and Postgres. New `migration/ExperimentForeignKeysMigrationScenarios` +
   `…H2Test` + `…PostgresTest`: Flyway to V153 on a fresh database, seed orphans next to valid rows, migrate to latest;
   exactly the orphans go and the keys hold after; plus an empty-database run. Red on both without the deletes
   (`FlywayMigrateException`).
2. The stored-invitation lookup now runs before the D-3 check; only a segment with no stored experiments needs a D-3
   date for d3. Test `aStoredD3Invitation_staysReadableAndRecreatable_afterThePlanLosesItsDMinus3Date` (red: 400).
3. `audience_experiments.holdout_pct` (V154, nullable) records the pct each invitation used. A repeat with another
   explicit `holdoutPct` → `409 AUDIENCE_PLAN_ALREADY_INVITED`, `fields['segments[i].holdoutPct']` = stored pct (arms and
   pct conflicts reported together); an omitted pct accepts the stored one; pre-column rows never conflict. Test
   `aRepeatWithAnotherHoldoutPct_is409NamingTheStoredPct_andWritesNothing` (red: 200).
4. CLAUDE.md: invitations line updated (V154, d3/pct rules, orphan cleanup); new convention line on
   `SPRING_FLYWAY_OUT_OF_ORDER=true` and taking the next number above the max.

Also touched: `audienceplan/model/AudienceExperiment.java` (`holdoutPct`), `ExperimentService.record` (pct parameter),
V137 → V154 in comments of `SendPathGuardTest`, `SendPathGuardPerBatchTest`, `SendPathGuardMaterializeTest`,
`AudiencePlanInvitationPostgresTest`.
