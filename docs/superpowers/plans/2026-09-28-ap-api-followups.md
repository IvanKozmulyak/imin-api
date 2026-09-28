# ap-api-followups — audience plan api follow-up batch (2026-09-28)

Source: programme plan `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md`, follow-up cards
(audits #1–#3) and "Ivan's decisions 2026-09-28".

## Goal and scope

Seven small api follow-ups, each with tests (one per branch):

1. **Summary model default.** `imin.audience-plan.summary-model` defaults to `anthropic/claude-haiku-4.5`, with its
   OpenRouter prices as defaults for `summary-price-input-usd-per-mtok` (1) and `summary-price-output-usd-per-mtok` (5),
   so `cost_usd` is recorded. Verified 2026-09-28 against `https://openrouter.ai/api/v1/models`:
   `anthropic/claude-haiku-4.5` prompt `0.000001` / completion `0.000005` USD per token = $1 / $5 per Mtok.
   Env vars `IMIN_AUDIENCE_PLAN_SUMMARY_MODEL` / `_PRICE_IN` / `_PRICE_OUT` still override (blank env = the default).
2. **Summarizer bounds as the card shows them.** The model's DATA carries every low/high pair rounded exactly like the
   webapp's `displayRange` (`src/shared/lib/rangeDisplay.ts`): confidence `prior` → floor/ceil to a step of 5 with equal
   bounds widened by one step (percent clamped to 100); `own`/`imin` → `Math.round`. Percent ranges become whole
   percents (`round(v×1000)/10`, then the same rule). Plan-level ranges (expected, coverage, gap) use the least sure
   segment confidence (`planConfidence`: prior < imin < own; no segments → prior). New-people `audience` groups use
   `prior`; `context` groups (students) are a single scalar `people` (the card shows `size.low` only). Raw coverage
   ratios (0.10) are dropped from DATA; only the percents go. A cold plan's equal-bound gap stays the exact target
   (the cold panel shows "~255", not a widened range). The code templates (`SummaryTemplates`) round their ranges the
   same way, so their numbers still all appear in DATA (`SummaryTemplatesTest` invariant) and match the card.
3. **CampaignDetailDto** gains `segmentName` (org-scoped name of `segmentId`, null when none or gone — hidden
   `audience_plan`/`momentum` segments included) and `eventTimezone` (IANA zone of the linked active event, null when
   none). Additive; contract marker `segmentName`.
4. **One sent-status set.** New `marketing.model.RecipientStatuses.SENT_SQL` (compile-time constant) used by
   `OutcomeStore` native SQL and both `CampaignRecipientRepository` JPQL queries.
5. **Flaky tests.** `PaidCheckoutDuplicateKeyTest`: `OrderRepository` built and stubbed in a `@TestBean` factory (as
   99dd2800), so the async fan-feature recompute can never race the stubbing. `CampaignSendCrashResumeTest`: clears
   campaigns before and after (the claim is global, LIMIT 10), as `CampaignDispatcherGatingTest` /
   `AudiencePlanLegalIdentityDispatchTest` already do.
6. **`scripts/seed-demo-events.sql`**: no references anywhere (repo, workspace docs, webapp); it is a manual seed with a
   hard-coded org, so it is fixed rather than deleted: `'public', 'live'` → `'PUBLIC', 'LIVE'` (`@Enumerated(STRING)`).
7. **Prune superseded plans** in the daily `PlanRefreshJob` (after the refresh loop, own try): delete, in batches of
   500 (at most 20 batches per run), superseded plans created more than 30 days ago that are older than every plan of
   their event referenced by `audience_experiments` (`plan_id`, or `plan_segment_id`'s plan). Oldest first per event,
   so the deleted set is always a whole prefix of a chain: a kept row never points at a deleted one (no
   `ON DELETE SET NULL` resurrection) and no referenced plan or segment is touched. Non-superseded rows are never
   deleted. Not gated per org: it removes only stale history rows and never exposes anything.

Out of scope: webapp changes (separate follow-up batch), `claimDue` SQL filters card, disabling `@Scheduled` in tests.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-api-followups` | `/Users/ivan/.imin-pipeline/mvnlock.sh clean test` (= `./mvnw clean test`) |

## Affected files

api:
- `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanProperties.java` (defaults)
- `src/main/resources/application.yaml` (summary model/price defaults + comments)
- `src/main/java/com/imin/iminapi/audienceplan/service/DisplayBounds.java` (new: displayRange port)
- `src/main/java/com/imin/iminapi/audienceplan/service/Summarizer.java` (`data()` uses display bounds), `SummaryTemplates.java` (same rounding)
- `src/main/java/com/imin/iminapi/marketing/dto/CampaignDetailDto.java`, `marketing/service/CampaignService.java`,
  `audience/service/SegmentService.java` (`segmentName`, `eventTimezone`)
- `src/main/java/com/imin/iminapi/marketing/model/RecipientStatuses.java` (new),
  `audienceplan/repository/OutcomeStore.java`, `marketing/repository/CampaignRecipientRepository.java`
- `src/main/java/com/imin/iminapi/audienceplan/service/PlanPruner.java` (new), `PlanRefreshJob.java`
- `scripts/seed-demo-events.sql`
- `CLAUDE.md` (summary defaults, prune sentence)
- tests: `AudiencePlanPropertiesTest` (or existing props test), `DisplayBoundsTest` (new), `SummarizerTest`,
  `SummarizerFlowTest`, recorded answers under `src/test/resources/audienceplan/summarizer/`,
  `CampaignControllerTest`, `CampaignDetailDtoTest`/service test, `RecipientStatusesTest` (new),
  `PlanPrunerScenarios` + H2/Postgres subclasses (new), `PlanRefreshJobTest`, `PaidCheckoutDuplicateKeyTest`,
  `CampaignSendCrashResumeTest`.

## Ordered steps

1. Properties + yaml defaults; tests: unset → Haiku + 1/5; env override → override; blank → default.
2. `DisplayBounds` (count/percent × prior/own/imin, null, widen, clamp) + `planConfidence`; unit tests per branch.
3. `Summarizer.data()` uses it; update the recorded answers and assertions whose numbers were the raw bounds
   (fixture is `prior`: 26–93 → 25–95, 8–26 → 5–30, 7–22 → 5–25, 11–45 → 10–45, 162–229 → 160–230, rate 12–40 → 10–40,
   coverage 10–36 → 10–40); tests that DATA carries the rounded pairs and that an answer quoting raw bounds is refused.
4. `CampaignDetailDto` fields + service lookup; tests: segment present / hidden / gone / none; event present / none.
5. `RecipientStatuses`; wire both callers; test pins the set and the repo counts.
6. Flaky test fixes.
7. Seed script fix; verified by running it inside a rolled-back transaction on local Postgres.
8. `PlanPruner` + job wiring; H2 + Postgres scenarios per branch; `PlanRefreshJobTest` update.
9. CLAUDE.md lines.

## Verification commands

- `/Users/ivan/.imin-pipeline/mvnlock.sh clean test` from the worktree root (full suite, H2 + Testcontainers Postgres).

## Test impact

New: DisplayBoundsTest, RecipientStatusesTest, PlanPruner H2/Postgres scenarios, properties default tests, detail DTO
tests. Changed: SummarizerTest / SummarizerFlowTest expectations and recorded answers (DATA numbers are now the shown
bounds), SummaryTemplatesTest (rounded ranges + own/imin case), PlanRefreshJobTest (constructor + `pruned` count), CampaignControllerTest (DTO arity), the two flaky tests.

## Live-test

Not needed for the app: no endpoint behaviour a live run would show beyond the unit/integration tests. The seed script
is exercised against the local Postgres in a rolled-back transaction (evidence below).

## Contract impact

`/api/v1` additive: `CampaignDetailDto.segmentName`, `CampaignDetailDto.eventTimezone` (both nullable strings).
Marker: `segmentName`.

## i18n impact

None (api only; no new user-visible api strings).

## Blast radius

Summary text of new plans (numbers now match the card); cost recording turns on by default with Haiku prices;
campaign detail payload grows two fields; daily job deletes old superseded plan rows (bounded per run); tests only
for items 5.

## Risks

- Prices drift from OpenRouter's → cost_usd slightly off; env vars override. Ponytail noted in yaml comment.
- Pruning deletes history; rows referenced by experiments and everything after them are kept; Postgres + H2 tested.

## Definition of done

All seven items implemented with tests; full suite green (or reds proven pre-existing on base); plan evidence filled.

## Live-test evidence

- Baseline (untouched origin/master 3a8421bf, full suite): `Tests run: 5456, Failures: 0, Errors: 0, Skipped: 3`, BUILD SUCCESS.
- This change (full suite, `mvnlock.sh clean test`): `Tests run: 5498, Failures: 0, Errors: 0, Skipped: 3`, BUILD SUCCESS.
- Seed script, `SeedDemoEventsSqlPostgresTest` (Testcontainers Postgres 17) on the unfixed script: red,
  `InvalidDataAccessApiUsageException: No enum constant com.imin.iminapi.model.EventStatus.live`; on the fixed
  script: green (12 events load as LIVE / PUBLIC).
- `PlanPrunerPostgresTest` 7/7 and `PlanPrunerTest` (H2) 7/7 green.
- OpenRouter price check 2026-09-28 (`GET https://openrouter.ai/api/v1/models`):
  `anthropic/claude-haiku-4.5 0.000001 0.000005` (USD per token).

## Review rounds

(orchestrator)
