# M3-R5 list plans endpoint + M4-6 plan refresh job

Slug: `ap-m3-r5-plan-list-refresh` · Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (M3-R5, M4-6, carry-overs, "Programme audit 2026-09-27") · Mode: Subagent (autonomous runner, no gates)

## Goal and scope

1. **M3-R5** `GET /api/v1/audience-plans?from=`: the caller org's upcoming draft and published events with their stored plan status, so the Overview (M3-R1) can list events and open the event picker. Reads stored plans only; never computes or writes a plan.
2. **M4-6** keep plans current without an organizer opening the card: one plan when an event is published, and a daily 09:00 Europe/Paris pass over on-sale upcoming events that writes a new plan only when the inputs changed.

No beta gating beyond the `AudiencePlanAccess` kill switch (Ivan: no customers); "beta off → 404" means the kill switch.

Out of scope: webapp (M3-R1), `api:sync` (orchestrator after deploy), invitations on publish (M4-8), Momentum targeting (M4-2), pruning superseded plans (see Decisions 9).

Rules carried forward verbatim from the programme plan:
- M3-R5: "`GET /api/v1/audience-plans?from=` (beta-gated): the org's upcoming published and draft events with `eventId`, name, `startsAt`, plan status (`none` / `fresh` / `stale`), coverage mid and verdict when a plan exists. Reads stored plans only (no recompute in a list call)." Tests: "org-scoped; past events excluded; `none` when no plan; stale after 24 h or changed inputs; beta off → 404."
- M4-6: "Listen to `PredictorReactivityEvents.EventPublished` (C16) → recompute; daily 09:00 Europe/Paris while on sale if `inputs_hash` changed; on Momentum trigger. Tests: publish triggers one plan; unchanged inputs → no new row."
- C16: "Listen to those records (pure data, no predictor logic reused)."
- C7: "New jobs set `zone = "Europe/Paris"` explicitly."
- C23: "shown coverage ratios are truncated to 2 decimals (`RoundingMode.DOWN`) … Cold mode: `expected` and coverage are null (not computed)".
- §4.5: "a `null` number renders `?`"; "Numbers shown must trace to a real API field".
- M1-11 Decision 13 (concurrency, row lock on the current plan) and Decision 14 ("Stored plans are unbounded until the M4-6 refresh job prunes superseded rows").

Decisions made here (documented in code):
1. **`from`** is an optional ISO-8601 instant; blank = now; a `from` in the past is raised to now, so started and past events never list. Upcoming = `startsAt > from`, status `DRAFT` or `LIVE`, not deleted, `startsAt` set (a draft without a date cannot be planned). Parsed after the kill switch (unparseable → 400 `FIELD_INVALID`), so a disabled feature answers 404 for any query. Sorted by `startsAt`, capped at 100 (`ponytail:`).
2. **Status = what a GET would do.** `fresh` iff the current plan is under 24 h old **and** its `inputs_hash` equals the hash of today's inputs, computed with the same code `PlanService` uses (tiers, genre, city, start, zone, on-sale, today in the event zone, org mailable count, calibration/logic/priors versions, the plan's own assumptions); otherwise `stale`; no plan → `none`. The org mailable count is read once per list call (one ConsentGate query) and only when some listed event has a plan. No write, no calculator run.
3. **Item** `AudiencePlanListItem {eventId, name, startsAt, eventStatus (draft|live), status (none|fresh|stale), coverageMid, verdict, computedAt}`. `coverageMid` is the stored truncated mid (null in cold mode or with no plan); `verdict` and `computedAt` null with no plan. Response is a JSON array.
4. **Refresh** = `PlanService.refresh(eventId)`: same lock, assumptions, hash and reuse rule as `current`; returns `CREATED` / `UNCHANGED` / `SKIPPED`. Skipped without writing: event missing or deleted, kill switch off for its org, no start date, already started, or no enabled capacity (422/409 cases of the GET).
5. **Publish listener**: `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` on a dedicated single-thread pool whose overflow is logged and dropped (a rejection inside `afterCommit` would fail an already committed publish); every exception is caught and logged. A rolled-back publish never plans.
6. **Daily job** `@Scheduled(cron = "0 0 9 * * *", zone = "Europe/Paris")`, ShedLock `audience_plan_refresh`, iterates `EventRepository.findMomentumCandidates(now)` (LIVE, future start, on sale), each event in its own transaction, one failure never stops the pass. Because "today" is one of the hashed inputs (M1-11 Decision 4), an on-sale event gets at most one new plan per day; a second pass the same day writes nothing.
7. **First-plan race.** With a background writer, a publish-time refresh and the organizer's first GET can both find no plan. `lockLatest` now takes a per-event lock when no plan exists and re-reads, so the second writer waits and then reuses the first plan. Postgres: `pg_advisory_xact_lock(hashtextextended(event_id::text, 0))`, which leaves the `events` row unlocked (a `FOR UPDATE` there conflicts with the `FOR KEY SHARE` every FK insert takes, so a checkout on a just-published event would wait); H2 (tests only) falls back to `SELECT … FOR UPDATE` on the event row. Engine read once from the DataSource metadata. Held only until the first plan commits.
8. **Momentum trigger hook: not wired.** `MomentumEvaluator` publishes no application event when a trigger fires; adding one means changing the marketing evaluator (constructor + tests), which M4-2 owns next. On-sale events are already refreshed daily and on every GET. Recorded as a follow-up.
9. **No pruning.** `audience_plans.superseded_by` is `ON DELETE SET NULL`: deleting a superseded row whose predecessor is kept would make that predecessor current again, and M3-1 will point `audience_experiments.plan_id` at plan rows. Growth is ≤ 1 plan + ≤ 3 segment rows per on-sale event per day (`ponytail:` in the job). Follow-up card.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-r5-plan-list-refresh` | `./mvnw test` |

## Affected files

api:
- modify `src/main/java/com/imin/iminapi/audienceplan/service/PlanService.java` (hash extraction, `refresh`, `listStatus` helpers, first-plan event lock)
- modify `src/main/java/com/imin/iminapi/audienceplan/repository/AudiencePlanRepository.java` (`findCurrentForEvents`, `lockEventAdvisory` (PG), `lockEventRow` (H2))
- modify `src/main/java/com/imin/iminapi/repository/TicketTierRepository.java` (`findByEventIdInOrderBySortOrderAsc`, review round 1)
- modify `src/main/java/com/imin/iminapi/repository/EventRepository.java` (`findUpcomingForPlans`)
- modify `src/main/java/com/imin/iminapi/audienceplan/service/CandidateLoader.java` (`mailableCount`)
- new `src/main/java/com/imin/iminapi/audienceplan/service/PlanListService.java`
- new `src/main/java/com/imin/iminapi/audienceplan/service/PlanRefreshJob.java`
- new `src/main/java/com/imin/iminapi/audienceplan/config/PlanRefreshExecutor.java`
- new `src/main/java/com/imin/iminapi/audienceplan/controller/AudiencePlanListController.java`
- new `src/main/java/com/imin/iminapi/audienceplan/dto/AudiencePlanListItem.java`
- modify `CLAUDE.md` (endpoint + job line next to the audience plan env vars)
- tests: new `src/test/java/com/imin/iminapi/audienceplan/controller/AudiencePlanListScenarios.java` + `AudiencePlanListWebTest.java` (H2) + `AudiencePlanListPostgresTest.java` (Postgres 17); new `src/test/java/com/imin/iminapi/audienceplan/service/PlanRefreshJobTest.java`, `PlanListServiceTest.java`, `src/test/java/com/imin/iminapi/audienceplan/config/PlanRefreshExecutorTest.java` (unit)

## Ordered steps

1. Baseline: per orchestrator (machine loaded) targeted tests during work, one full `./mvnw test` at the end; origin `dc7bd799` is the M1-11 ship.
2. `PlanService`: extract `inputsHash(...)` from `prepare` (no behaviour change; the M1-11 suite pins it), add `refresh`, list status helper, event lock in `lockLatest`.
3. Repos: event list query, current-plans-for-events query, event row lock.
4. `PlanListService`, `AudiencePlanListItem`, `AudiencePlanListController`.
5. `PlanRefreshExecutor`, `PlanRefreshJob` (listener + daily job).
6. Tests (below), targeted on H2 and Postgres; M1-11 plan suites re-run.
7. CLAUDE.md line; full `./mvnw test`; read the diff for comment rules.

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-r5-plan-list-refresh && ./mvnw test`

## Test impact

Reproduction test: n-a (new code). Branch map:
- List (H2 + PG, MockMvc): org-scoped (another org's events absent); past event excluded; `from` in the past raised to now; `from` in the future drops earlier events; cancelled / PAST status and undated drafts excluded; draft listed with `eventStatus` draft; deleted event excluded; `none` with null coverage/verdict/computedAt; `fresh` right after a GET with coverage mid 0.20 + verdict medium + computedAt; `stale` after 24 h (backdated); `stale` after an input change (tier quantity) with no new row written; cold plan lists null coverageMid + verdict cold; real-data (no stub) GET then list = `fresh` (list and GET agree on the mailable count); kill switch off → 404; bad `from` → 400; sorted by `startsAt`; the list call writes no plan row.
- Refresh (H2 + PG): published event (real `POST /events/{id}/publish`) gets exactly one plan asynchronously; `refresh` twice → one row (`UNCHANGED`); changed inputs → `CREATED` and the old row superseded; kill switch off → `SKIPPED`, no row; started event → `SKIPPED`; no capacity → `SKIPPED`; missing event → `SKIPPED`; `EventPublished` in a rolled-back transaction → no plan; job `run()` over two on-sale events → two plans, second run same day → none new, draft and off-sale events untouched; first-plan race (holder takes `PlanService.lockFirstPlan`, a refresh and a GET wait, one plan row after release; red without the lock). Postgres only: an FK insert on the event (`event_funnel_events`, `lock_timeout 3s`) succeeds while the first-plan lock is held (red with the old `events … FOR UPDATE`).
- Unit `PlanListServiceTest`: `from` blank/null → now, past → now, future kept (trimmed), unparseable → 400 on `from`.
- Unit `PlanRefreshJobTest`: listener swallows a refresh exception; job counts created/unchanged/skipped, continues after one event throws (failed count), `scheduled()` swallows a failing run; executor rejection handler logs and does not throw.

## Live-test

Not needed as a separate server run: both endpoints and the listener run through the real HTTP stack, H2 and Postgres 17 in the scenario tests; the daily cron itself is a Spring annotation (pinned by reading it). The orchestrator's `/live-test` may call `GET /api/v1/audience-plans` against prod after deploy (shape only, no numbers asserted).

## Contract impact

`/api/v1` — new `GET /api/v1/audience-plans`; marker `AudiencePlanListItem`. No change to existing schemas. Do not run `api:sync`.

## i18n impact

none (api; status/verdict are machine keys, copy lands in M3-R1).

## Blast radius

- `PlanService.lockLatest` takes a per-event advisory lock (Postgres) while an event's **first** plan is computed (< 2 s); it blocks only another first-plan writer of that event, never an edit of the event or an FK insert (orders, funnel beacons). Existing plans take the plan-row lock as before.
- Every publish now schedules one plan computation (one ConsentGate + candidate query set) on a dedicated 1-thread pool; failures are logged, never surfaced.
- The daily job writes ≤ 1 plan per on-sale event per day for orgs the kill switch admits (all orgs while it is open).
- `EventRepository` gains one additive query.

## Risks

- Test suites that publish events now also trigger an async plan computation; it may hit a deleted event during teardown (logged at WARN, no failure).
- Plan rows grow daily for on-sale events until pruning is designed (Decision 9).

## Definition of done

All branch tests above green on H2 and Postgres; `./mvnw test` green; comments ≤ 2 lines, no ticket refs; no user-visible strings; no seeded rows outside tests.

## Live-test evidence

(see Live-test)

## Review rounds

(appended by the orchestrator)

### Round 1 → SHIP (with MEDIUM + LOW fixed)

- MEDIUM fixed: `lockEvent` (`SELECT … FROM events … FOR UPDATE`) conflicted on Postgres with the `FOR KEY SHARE` of every FK insert on the event, so a checkout on a just-published event would wait for its first plan. Replaced by `PlanService.lockFirstPlan`: `pg_advisory_xact_lock` on Postgres, event row `FOR UPDATE` on H2. New `AudiencePlanListPostgresTest.firstPlanLock_doesNotBlockAnFkInsertOnTheEvent` (red on the old lock with `55P03 lock timeout`, green now); the race test now holds the same lock through `lockFirstPlan` and stays green on both engines.
- LOW fixed: `PlanListService` read tiers per event; now one `TicketTierRepository.findByEventIdInOrderBySortOrderAsc` over the events that have a plan, grouped by event.
