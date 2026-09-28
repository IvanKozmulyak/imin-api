# M3-5 OutcomeCollector and outcome endpoint

Slug: `ap-m3-5-outcomes` · Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (M3-5, M1-8 `CalibrationSource`, M3-1, M3-W4 "too few to tell", audits) · Reproduction test: n-a (new code).

## Goal and scope

After an event, measure what each invitation arm did against its holdout, store it as aggregates, and feed those aggregates back into the response model.

- `OutcomeCollector`: `@Scheduled(cron = "0 0 10 * * *", zone = "Europe/Paris")` + `@SchedulerLock(name = "audience_outcomes")`. Processes events with experiments whose door closed at least 1 day ago (`d1`) or at least 7 days ago (`d7`). Door close = `ends_at`, else `starts_at` + 12 h. The job is idempotent: an event whose stored phase already equals the due phase is skipped, and D+7 overwrites D+1. Only orgs that pass `AudiencePlanAccess.isEnabled` are processed (the kill switch; there is no beta gating beyond it).
- Per assignment:
  - bought = a non-test order for this event, from the member's email, created between `assigned_at` and door close, with at least one ticket that is neither refunded nor revoked. Partial refunds count; full refunds do not.
  - tickets = the live tickets of those orders.
  - attended = one of those tickets is redeemed.
  - unsubscribed = an email `unsubscribed` consent record after `assigned_at`.
  - complained = a `complained` recipient row on any campaign for this event (the Resend complaint projection).
  - sent = the arm campaign's recipient row left the provider: sent, delivered, opened, clicked, complained or unsubscribed.
  - Stored phases bound unsubscribe and complaint at door close + 1 d (`d1`) or + 7 d (`d7`), so a rerun gives the same numbers.
- Aggregated per arm into `audience_outcomes`. There is one row per experiment and no membership id. `audience_event_outcomes` holds the per-event `newGuests`: distinct buyers of the event with no membership in the org created before the first `assigned_at`.
- `response_calibration`: rebuilt from `audience_outcomes` after each run that wrote something. Aggregates only, scope `org` and `imin`, unique `(scope, org_id, class, genre_fit, arm)`. The `imin` rows use the nil UUID as `org_id`, so the unique key holds on H2 and Postgres alike.
  - Invitation arms: `n` = members who were sent the email, `bought` = those of them who bought. Arms with nothing sent are left out, so drafts that never went out do not pull the band down.
  - Holdout rows: `n` = held-out members.
- `CalibrationService` implements M1-8's `CalibrationSource`:
  - `own` = the org's invitation arms for that class × fit.
  - `imin` = the IMIN total minus own.
  - It keeps an in-memory snapshot, reloaded after a rebuild and at most 10 minutes old on other replicas, because `CandidateBuilder` calls `rate` once per person.
  - `version()` is 0 while empty, else a content hash. `PlanService` hashes and stores it in place of the constant 0, so a calibration change recomputes plans.
- `GET /api/v1/events/{eventId}/audience-plan/outcome`:
  - Order: `requireEnabled` first, then an org-scoped event lookup. Both failures give the same 404.
  - `phase`:
    - `live` before door close: computed on demand and never written, and `nextWave` is the earliest scheduled arm campaign.
    - `pending` after door close with nothing stored: the webapp shows "results tomorrow" with no zeros, and all counts are absent.
    - `d1` / `d7`: the stored rows.
  - Per segment (plan segment: class, genre fit, planned rate band) and arm, the counts are members, sent, bought, tickets, attended, unsubscribed and complained.
  - `responseRate` is a Wilson 80% range.
  - `lift` = arm − holdout as a Newcombe 80% range. It is present only when both the arm and the holdout have at least 60 members (logic `holdout_min_mailable`), and `liftStatus` is `ok | too_few | no_holdout | baseline`. The mid is a tick, never shown alone.
  - Also returned: `newGuests`, `intervalLevel` 0.8 and `minimumForLift` 60.
  - No opens anywhere.

Out of scope:
- Calibrating no-show separately (C21). `response_calibration` has no no-show key. See OUT_OF_PLAN.
- The webapp (M3-W4).
- api:sync.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-5-outcomes` | `./mvnw test` (full run via `/Users/ivan/.imin-pipeline/mvnlock.sh test`) |

## Affected files (per repo)

api:
- new `src/main/resources/db/migration/V156__audience_outcomes.sql` (the planned V138; V155 is taken by ap-consent-hardening, V157/V158 by M4-1/M4-8)
- new `audienceplan/engine/OutcomeMath.java`, `audienceplan/repository/OutcomeStore.java`, `audienceplan/service/CalibrationService.java`, `audienceplan/service/OutcomeCollector.java`, `audienceplan/service/OutcomeService.java`, `audienceplan/dto/AudiencePlanOutcomeResponse.java`, `audienceplan/controller/AudiencePlanOutcomeController.java`
- modified:
  - `audienceplan/engine/CalibrationSource.java` (+ default `version()`)
  - `audienceplan/engine/ResponseModel.java` (+ `calibrationVersion()`)
  - `audienceplan/config/AudiencePlanConfig.java`: the `NONE` bean is dropped, and the model takes the `CalibrationSource` bean when one exists, else `NONE`, so config-only context tests still start.
  - `audienceplan/service/PlanService.java` (hash and store the calibration version)
  - `CLAUDE.md` (one bullet for the job and the endpoint)
- tests:
  - `engine/OutcomeMathTest` and `service/CalibrationServiceTest` (unit)
  - `service/OutcomeScenarios` + `OutcomeH2Test` + `OutcomePostgresTest` (queries, job, endpoint, OpenAPI marker)
  - `engine/ResponseModelTest`: the M1-8 binding test `springBeans_bindEmptyCalibrationAndModel` pinned the `NONE` bean, which this task replaces as M1-8 planned. It becomes two tests (with a source bean, and without one gives the prior) plus a `calibrationVersion` test.
  - No `migration` test is needed: the scenarios boot V156 on both databases.

## Ordered steps

1. `OutcomeMathTest` then `OutcomeMath`: Wilson, Newcombe, lift status, door close, due phase.
2. V156 migration.
3. `OutcomeStore` (JDBC, H2/PG compatible, no nullable parameter).
4. `CalibrationService` + unit test; wire as the `CalibrationSource` bean; `CalibrationSource.version()`, `ResponseModel.calibrationVersion()`, `PlanService` uses it.
5. `OutcomeCollector` job.
6. `OutcomeService` + DTO + controller.
7. Scenario tests on H2 and Postgres 17.
8. Targeted runs, then one full `./mvnw test`.

## Verification commands

- `cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-5-outcomes && ./mvnw test` (run through `/Users/ivan/.imin-pipeline/mvnlock.sh test`)

## Test impact

New tests only. The branches:
- order before the assignment is not counted;
- order after door close is not counted;
- fully refunded is not counted;
- partial refund is counted;
- test-mode order is not counted;
- lift is null without a holdout (`no_holdout`);
- a small arm gives `too_few`;
- a large arm and holdout give an `ok` range;
- a rerun is idempotent (same rows, skipped);
- D+7 overwrites D+1;
- the live phase writes nothing;
- `pending` after door close;
- `newGuests` excludes pre-existing members;
- calibration rows carry no membership id (row count per class/arm, and the column list is pinned);
- calibration skips unsent arms;
- `imin` excludes own;
- a disabled org is skipped by the job;
- kill switch off gives 404, as does another org's event;
- the OpenAPI marker is present.

The only existing test that changes is the M1-8 binding test above. With `CalibrationSource.NONE` the version is 0, as before, so every existing plan test keeps its numbers.

## Live-test

Not run. The endpoint needs experiments on a past event, and building them locally takes the whole M3-1 chain plus orders after assignment. The scenario tests drive the real controller on H2 and Postgres 17 instead.

## Contract impact

`/api/v1`: new `GET /api/v1/events/{eventId}/audience-plan/outcome`, marker `AudiencePlanOutcomeResponse` (nested schemas `AudiencePlanOutcomeSegment`, `AudiencePlanOutcomeArm`, `AudiencePlanOutcomeRange`, named so they cannot collide with `AudiencePlanInvitationsResponse.Arm`).

## i18n impact

None (api only; `phase` and `liftStatus` are keys).

## Blast radius

- A new daily job, gated per org by the kill switch.
- The response model now reads stored calibration: after the first collected event, bands for that class × fit move from the prior (smoothly, `w = n/(n+20)`), and plans recompute once because the calibration version is in the inputs hash.
- No existing table changes.

## Risks

- Calibration from arms whose sends were partly capped counts only the members who were sent.
- The job looks back 40 days from the event start. An event that was never processed within that window stays `pending`.
- The nil-UUID sentinel for `imin` scope is documented in the migration.

## Definition of done

The scenario tests are green on H2 and Postgres 17, and the full `./mvnw test` is green.

## Live-test evidence

n/a (see Live-test).

## Review rounds

(appended by /do-task)

### Round 1 → SHIP, with approved fixes

1. MEDIUM: cancelled (`status = 'CANCELLED'`) and soft-deleted (`deleted_at` set) events are left out of `OutcomeStore.eventsWithExperiments` and of `orgCalibrationFromOutcomes` (joined to `events`), so an event cancelled or deleted after collection also drops out of the calibration. Scenario tests on H2 and Postgres 17 for each.
2. MEDIUM: `OutcomeCollectorTest` covers one event failing while the next is still written, and a failed calibration rebuild giving `calibrated=false` with the snapshot still invalidated.
3. LOW: `OutcomeCollector` runs at 08:00 Europe/Paris, before the 09:00 plan refresh; cron pinned in `OutcomeCollectorTest`.
4. LOW: the `imin` band of a cell counts only when at least `CalibrationService.MIN_OTHER_ORGS` (3) other orgs sent invitations in it; unit tests in `CalibrationServiceTest`.
5. Added for the webapp results view (M3-W4): top-level `invited` (the event has any audience experiments), so a `pending` response tells "invited, results tomorrow" from "no invitations"; and per segment `plannedConfidence` (the plan segment's `own | imin | prior`, null without one) next to `plannedRate`. Scenario and OpenAPI-marker assertions for both.

Rebased onto origin/master (c83debac, V158 invite-on-publish); V156 kept, applied out of order under `SPRING_FLYWAY_OUT_OF_ORDER`.
