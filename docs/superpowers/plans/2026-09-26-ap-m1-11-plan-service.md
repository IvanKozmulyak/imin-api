# M1-11 PlanService, persistence and REST

Slug: `ap-m1-11-plan-service` · Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (M1-11) · Mode: Subagent (autonomous runner, no gates)

## Goal and scope

Expose the M1-10 `PlanCalculator` as `GET` / `POST /api/v1/events/{eventId}/audience-plan`, persist every computed plan (`audience_plans` + `audience_plan_segments`), reuse a stored plan while it is fresh and its inputs are unchanged, and supersede it otherwise. Org-scoped, synchronous, SQL-first (the M1-9 `CandidateLoader` queries).

Out of scope: summaries (M2-2 fills `summaries`), `newPeople` / tribe size (M2-8), invitations (M3-1), list endpoint (M3-R5), plan refresh job (M4-6), webapp (M3-W1/M3-W2a), `api:sync` (orchestrator, after deploy).

Rules carried forward verbatim from the programme plan:
- M1-11: "`GET /api/v1/events/{eventId}/audience-plan?locale=` → latest non-superseded plan; recompute when older than 24 h or `inputs_hash` (mailable count, tiers, genre, city, start, calibration version, logic/priors versions) changed. **Locale is not part of plan identity:** it is not in `inputs_hash` and not a plan column; it only selects which entry of `summaries` is returned (M2-2 fills per locale). `POST` same path with `{targetPct 1..100, excludeSegments[] ⊂ classes, assumptions.ticketsPerOrder 1.0..10.0}` → new plan, old one `superseded_by`. Response = spec §11 shape + `exclusions` (M1-9 breakdown, counts) + `timing.daysToEvent`; `summary` null until M2-2; `newPeople` = [] until M2-8; `versions.model` null. `AudiencePlanAccess.requireEnabled` first, then org-scoped event lookup (leak-safe 404 for another org's event). Synchronous, < 2 s on 10k members (SQL-first)."
- §4.3: "`V135__audience_plans.sql` | M1-11 | `audience_plans` (spec §5 + `inputs_hash VARCHAR(64)`, `summaries TEXT` = JSON keyed by locale; **no `locale` column**) + `audience_plan_segments` (spec §5, `reason TEXT`)"
- §1/§4.3: "Migrations must stay H2/PG-compatible (test profile is H2 `MODE=PostgreSQL`): no `jsonb` (JSON goes in `TEXT`, as `segments.rules_json` and `memberships.genres` do), no native enums, no unnamed `CHECK`".
- §5: "**Coverage is computed from the rounded totals**, not the raw sums"; "Gap = 255 − 93 .. 255 − 26 = **162-229** (also from rounded totals)"; warm fixture 26 / 52 / 93 of 255, coverage `0.10 / 0.20 / 0.36`, verdict `medium`, mode `warm`.
- C23: "shown coverage ratios are truncated to 2 decimals (`RoundingMode.DOWN`), not half-up as §5 says … Cold mode: `expected` and coverage are null (not computed), not 0/0/0 as §5 "Cold pass" says. M1-11 owns the top-3 segment and 2–3 steps caps; wire `Event.onSaleAt` (not tier `saleStartsAt`) and note `Event.timezone` defaults to UTC."
- C22: "a member with empty/no taste (incl. every `imported`) gets genre fit `unknown`, not `other` … M1-10/M1-11 must carry `unknown` through the segment DTO and UI copy."
- §4.4: "`unknown` is a legal value and flows to the API as `null` / `"unknown"`, never as 0."
- §4.5: "Ranges only (`~26-93`); a `null` number renders `?`"; "Numbers shown must trace to a real API field (no sample numbers from the design canvas)."
- D5: "**Decided:** 85% of capacity." (config default 85, overridable per plan).
- Spec §2 / tech-spec §2: "up to 3 segments (size, mailable, expected tickets as a range, why) … 2-3 actions".
- M1-10: "capacity 0 → plan refused with 422 (no divide by zero)"; this task maps `PlanCalculator.NoCapacityException` to `AUDIENCE_PLAN_NO_CAPACITY`.
- Ivan 2026-09-26: "no beta flag — ship straight to prod, no customers yet … `AudiencePlanAccess` becomes a default-open kill switch".
- DoD: "**No seeded or fabricated rows in prod.**"

Decisions made here (documented in code):
1. **Top 3 segments, totals over them.** The engine takes a `maxSegments` cap (3) and computes expected / coverage / gap / invites over the kept segments only, so the shown segments add up to the shown totals; people in dropped segments are counted under a new exclusion reason `segment_cap`.
2. **`excludeSegments` = class keys.** Excluded people leave the segment pool but stay in the lawful `mailable` count and the mode; they are counted under `excluded_segment`. Unknown class key → 400.
3. **Steps cap 3.** Non-invite actions (`import_with_proof`, `rethink_target`) are always kept; remaining slots go to invites in segment order. Every shown segment is still in `segments`.
4. **Hash inputs** = the M1-11 list plus what changes the dates or numbers: enabled tiers (id, quantity), genre key, city key, start instant, event timezone, `onSaleAt`, today's date in the event timezone (so `daysToEvent` and arm dates never go stale across midnight), mailable count, calibration version (0 while `CalibrationSource.NONE`; `ponytail:`), logic/priors versions, and the plan's assumptions (targetPct, ticketsPerOrder, excludeSegments). Locale is never hashed.
5. **GET keeps the latest plan's assumptions** when it recomputes, so a POSTed override survives a stale-refresh. Without any plan: target 85 (logic `target_default_pct`), tickets per order = priors mid (1.6), no exclusions.
6. **POST always writes a new plan** and supersedes the previous latest; omitted fields keep the latest plan's value.
7. **Event without a start date** → 409 `INVALID_STATE` (the engine needs a date to plan arms).
8. **Missing / another org's / deleted event** → the same `404 NOT_FOUND "Audience plan not found"` as the kill switch.
9. **Invalid event timezone** falls back to UTC (the `Event.timezone` default).
10. **Reach needed** is one object shape per channel `{status: estimated|unverified|unknown, low, high}` (spec shows `"unknown"` as a bare string; one typed shape for both channels).
11. **Segments carry no `members`** (spec §11 example): the engine only sees mailable people, and a number must trace to a real field. `reason` carries the class rule bounds from `logic-v1.yaml` + event genre + fit.
12. **No membership ids are stored** in either table (counts only), so DSAR erase/export is untouched.
13. **Concurrency.** `current` and `recompute` both take a row lock on the event's non-superseded plan (`AudiencePlanRepository.lockCurrent`, native plain `FOR UPDATE` because the dialect's `PESSIMISTIC_WRITE` renders `FOR NO KEY UPDATE`, which H2 rejects) before reading assumptions, so a stale-refresh GET that waits on a POST reads the POST's committed row. On Postgres a waiter whose locked row was superseded meanwhile gets no row back (the `WHERE` is re-checked after the wait), so `lockLatest` re-reads unlocked and retries the lock on the new row (3 attempts, then the unlocked read). The first plan of an event has no row to lock (`ponytail:`): a first-ever GET and POST in flight together can both insert and the newer `created_at` wins. Harmless in practice: the webapp only POSTs after a GET has shown a plan, so a row exists; no unique partial index, by choice.
14. **Stored plans are unbounded** until the M4-6 refresh job prunes superseded rows.
15. **No FK from `audience_plans.org_id`** (`event_id` has one, `ON DELETE CASCADE`); adding it is a schema-hygiene follow-up.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-11-plan-service` | `./mvnw test` |

## Affected files

api:
- new `src/main/resources/db/migration/V135__audience_plans.sql`
- new `src/main/java/com/imin/iminapi/audienceplan/model/AudiencePlan.java`, `AudiencePlanSegment.java`
- new `src/main/java/com/imin/iminapi/audienceplan/repository/AudiencePlanRepository.java`, `AudiencePlanSegmentRepository.java`
- new `src/main/java/com/imin/iminapi/audienceplan/service/PlanService.java`
- new `src/main/java/com/imin/iminapi/audienceplan/controller/AudiencePlanController.java`
- new `src/main/java/com/imin/iminapi/audienceplan/dto/AudiencePlanResponse.java`, `AudiencePlanRecomputeRequest.java`
- modify `src/main/java/com/imin/iminapi/security/ErrorCode.java` (+ `AUDIENCE_PLAN_NO_CAPACITY`)
- modify `src/main/java/com/imin/iminapi/audienceplan/engine/PlanCalculator.java` (Input `excludedClasses`, `maxSegments`; old constructor kept)
- modify `src/main/java/com/imin/iminapi/audienceplan/engine/Exclusions.java` (`excluded_segment`, `segment_cap`)
- modify `src/main/java/com/imin/iminapi/audienceplan/engine/ActionPlanner.java` (`topSteps`)
- modify `src/main/java/com/imin/iminapi/audienceplan/service/CandidateLoader.java` (`input` public)
- tests: `src/test/java/com/imin/iminapi/audienceplan/service/PlanServiceTest.java` (zone fallback)
- tests: `src/test/java/com/imin/iminapi/audienceplan/engine/PlanCalculatorOverridesTest.java`, `ActionPlannerTest.java` (+ topSteps), `src/test/java/com/imin/iminapi/audienceplan/controller/AudiencePlanControllerScenarios.java` + `AudiencePlanControllerWebTest.java` (H2) + `AudiencePlanControllerPostgresTest.java` (Postgres 17, skipped without Docker)

## Ordered steps

1. Baseline `./mvnw test` on the untouched worktree.
2. Engine: `excludedClasses` + `maxSegments` in `PlanCalculator.Input`, `Exclusions` reasons, `ActionPlanner.topSteps`; tests per branch.
3. Migration V135 + entities + repositories (`@RepositoryRestResource(exported = false)`).
4. `ErrorCode.AUDIENCE_PLAN_NO_CAPACITY`; DTOs; `PlanService` (access → event → assumptions → inputs → hash → reuse or compute/persist/supersede → response); controller.
5. Web tests (MockMvc, H2): fixture through the API, reuse/recompute/supersede/locale, 400/404/409/422, one un-stubbed real-data cold plan.
6. `./mvnw test`; read the diff for comment rules.

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-11-plan-service && ./mvnw test`

## Test impact

Reproduction test: n-a (new code). Branch map:
- PlanCalculator overrides: excluded class leaves segments, stays in mailable and mode, counted `excluded_segment`; excluded in cold mode counted; cap 3 of 4 segments keeps the three highest-rate, totals/coverage/gap over them, dropped people under `segment_cap`, invites only for kept; cap 0 = no cap; no overrides → both reasons 0; negative cap refused.
- `ActionPlanner.topSteps`: ≤ max unchanged; over max keeps non-invites and the first invites in order; max < non-invites keeps the non-invites in order up to max.
- Web: 200 shape with §5 fixture numbers (expected 26/52/93, coverage 0.10/0.20/0.36, verdict medium, gap 162-229, mode warm, segments with rate/confidence/reason/genreFit, exclusions present, timing.daysToEvent 28, summary null, newPeople [], versions.model null); `unknown` fit carried as `"unknown"`; top-3 cap through the API; cold plan from real data (no stub) with null expected/coverage and ConsentGate exclusions; 404 other org; 404 missing event; 404 kill switch off; 400 targetPct 0 / 101, ticketsPerOrder 0.9 / 10.1, unknown excludeSegments, unknown locale; 422 `AUDIENCE_PLAN_NO_CAPACITY`; 409 no start date; unchanged inputs reuse one row; `locale=fr` then `locale=en` reuse one row; changed inputs (tier quantity) recompute and supersede; 24 h stale recompute (created_at backdated); POST supersede chain and override persisted; GET after POST keeps the override; omitted POST fields keep the latest values. Round 1: `PlanService.zone` null/blank → UTC, invalid → UTC, valid kept; `excludeSegments:[null]` → 400; a stale-refresh GET after a POST keeps its targetPct/excludeSegments; a held plan-row lock makes a POST and a stale GET wait, and after release exactly one current row carries the override (H2 and Postgres; red with the lock removed).

## Live-test

Needed per programme DoD ("`/live-test` shows `GET /events/{id}/audience-plan` returning the §5 fixture numbers against a local … api whose test database holds the fixture"). The runner covers the §5 numbers through the real HTTP stack in the MockMvc test; a local-server run is attempted if the local Postgres helper is available, else left to the orchestrator's `/live-test`.

## Contract impact

`/api/v1` — new `GET` / `POST /api/v1/events/{eventId}/audience-plan`; marker `AudiencePlanResponse` (incl. `exclusions`, `timing`). New error code `AUDIENCE_PLAN_NO_CAPACITY`. Do not run `api:sync` (orchestrator runs it after the api is live).

## i18n impact

none (api; verdict, mode, fit, action and exclusion values are machine keys; copy lands in M3-W2a).

## Blast radius

New tables and endpoints only. Engine changes are additive (old `Input` constructor kept; two new exclusion keys always present in plan exclusions). `CandidateLoader.input` becomes public (no behaviour change). V135 does not add the FKs from `audience_experiments.plan_id` / `plan_segment_id` that V136's header promises: on a fresh database V135 runs before V136 creates that table, so they belong to a later migration (M3-1).

## Risks

- The other-genre gate inside `CandidateBuilder` still sees every shown segment before the top-3 cap, so it may hold back `other` people on coverage that the cap later drops. Accepted for v1; noted in code.
- `inputs_hash` hashes the mailable count, not per-person state, so a class change inside the same count waits for the 24 h refresh (as specified).
- Each GET on a changed input writes a new row; rows are small (no membership ids).

## Definition of done

All branch tests above green; `./mvnw test` green on the worktree; no comment references a ticket; no new strings shown to users; no seeded rows anywhere but tests.

## Live-test evidence

Runner (2026-09-27): no local server run. Docker is unavailable and the `/tmp` live-test database helper was wiped by a reboot, so the orchestrator's `/live-test` owns it. The checked warm fixture numbers go through the real HTTP stack, the H2 database and JSON serialization in `AudiencePlanControllerWebTest`: 26/52/93, coverage 0.10/0.20/0.36 `medium`, gap 162-229, `warm`, `daysToEvent` 28. `AudiencePlanControllerPostgresTest` (the same 20 scenarios on Postgres 17) was skipped here because Docker is missing.

## Review rounds

(appended by the orchestrator)

### Round 1 → SHIP, with fixes

1. MEDIUM — `PlanService.zone` untested: added `PlanServiceTest` (null/blank, invalid, valid).
2. MEDIUM — a GET recompute racing a POST could drop the override: plan-row lock in both transactional paths (Decision 13), sequential and lock-wait tests, verified on H2 and Postgres 17.
3. LOW — `excludeSegments:[null]` → 400 added to `invalidOverrides_are400_andWriteNothing`.
4. LOW — `reason()` `rule == null` branch removed: the segment class comes from the validated logic file, so a miss now throws `IllegalStateException`.
Decisions recorded: stored plans unbounded until M4-6 (14); no `org_id` FK, hygiene follow-up (15).
