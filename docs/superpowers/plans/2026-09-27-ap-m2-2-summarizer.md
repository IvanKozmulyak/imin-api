# M2-2 Summarizer (ap-m2-2-summarizer)

Programme plan: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` §M2-2, audit #2 (2026-09-27).
Reproduction test: n-a (new code).

## Goal and scope

Fill `AudiencePlanResponse.summary` with a short text per locale (en, es, fr, uk), shaped
`{headline, segmentLines[], gapLine, actions[], assumptions[]}`.

- **Lazy, GET only (audit #2).** `GET /events/{id}/audience-plan?locale=` hands the returned plan to `Summarizer`
  after `PlanService.current` has committed. If that locale has no summary yet, one async task generates it and
  stores it under the locale key in `audience_plans.summaries` (the same row; locale never creates a plan). POST
  recompute, `PlanRefreshJob` (publish + daily), `MomentumPlanRefresh` and the list never summarise, so LLM cost
  scales with organizers opening the card, not with on-sale events.
- The GET that triggers it still answers `summary: null`; the next GET reads the stored text.
- LLM: the existing `@Primary` OpenRouter `ChatClient`. The model is `imin.audience-plan.summary-model`, and blank
  means `openrouter.model`. There is no web search.
- Input: aggregates only (plan numbers, segment reasons, gap, exclusion counts, dates, portrait group labels
  and sizes). There are no ids, event titles or names. `LlmPayloadGuard.check` runs on the full prompt, with a
  sample of the org's member/consumer display names.
- Numeric check: every number in the output must appear in the input, which lists each ratio both as a fraction
  and as a percent. A failure is retried once, and a second failure falls back to a per-locale code template.
  Refusal, empty output, unparsable or malformed JSON, a guard rejection or an exception go straight to the
  template.
- Provenance (AI Act marker, ADR-0005 vocabulary). Each stored summary carries `aiGenerated`, `aiDisclosure`
  (`mode=ai-originated` when AI, else null), `model` (null for a template), `locale` and `generatedAt`. The plan
  row accumulates `tokens_in`/`tokens_out` and `cost_usd`, and `model_id` is set when an AI summary is stored.
  `cost_usd` = tokens × `summary-price-{input,output}-usd-per-mtok`. When either price is blank, the cost stays
  null (Spring AI drops OpenRouter's `usage.cost`, so no price is invented).
- `summary-enabled` is a kill switch: true in main, false in the test yaml, so no Spring test reaches OpenRouter.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m2-2-summarizer` | `./mvnw test` (full run via `/Users/ivan/.imin-pipeline/mvnlock.sh test`) |

## Affected files (per repo)

api:
- new `src/main/java/com/imin/iminapi/audienceplan/service/Summarizer.java`
- new `src/main/java/com/imin/iminapi/audienceplan/service/SummaryTemplates.java`
- new `src/main/java/com/imin/iminapi/audienceplan/service/SummaryNumbers.java`
- new `src/main/java/com/imin/iminapi/audienceplan/config/SummaryExecutor.java`
- `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanProperties.java` (+ `summaryModel`,
  `summaryEnabled`, two price keys)
- `src/main/java/com/imin/iminapi/audienceplan/dto/AudiencePlanResponse.java` (Summary + provenance fields)
- `src/main/java/com/imin/iminapi/audienceplan/controller/AudiencePlanController.java` (GET triggers Summarizer)
- `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- `CLAUDE.md` (env var lines)
- review round 1: `src/main/java/com/imin/iminapi/config/OpenRouterConfig.java` (static `chatClient` factory, primary bean
  unchanged), new `src/main/java/com/imin/iminapi/audienceplan/config/SummaryChatClient.java`, new
  `SummarizerTimeoutTest`
- tests: new `SummarizerTest`, `SummaryNumbersTest`, `SummaryTemplatesTest`, `SummaryExecutorTest`,
  `SummarizerStoreTest` (Spring, H2), recorded responses under `src/test/resources/audienceplan/summarizer/`;
  `AudiencePlanControllerScenarios` (GET triggers, POST does not); a properties binding test.
- `PlanService.java` is not edited.

## Ordered steps

1. Properties + both yaml files + a binding test for the defaults.
2. `SummaryNumbers` (tokeniser, interpretations, allowed set), with tests first.
3. `SummaryTemplates` (4 locales, numbers only from the plan), with tests first.
4. `Summarizer.generate` (prompt, guard, LLM call, parse, numeric check, retry, template), tested with recorded
   responses through a real `OpenAiChatModel` on `MockRestServiceServer` and a mock `ChatClient` for exceptions.
5. `Summarizer.store` (row lock with `SELECT … FOR UPDATE`, merge the locale, add tokens/cost, set model),
   tested on H2.
6. `SummaryExecutor` + `Summarizer.requestIfMissing` (in-flight dedupe, rejection clears in-flight), and the
   controller GET wiring. Web tests: GET schedules it once and stores it, POST never does, and the kill switch
   off means no call.
7. CLAUDE.md env lines, then the full suite.

## Verification commands

- `cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m2-2-summarizer && /Users/ivan/.imin-pipeline/mvnlock.sh test`

## Test impact

New tests only, plus a few additions to `AudiencePlanControllerScenarios`. The existing summary-null assertions still
hold because the test yaml switches summaries off.

## Live-test

Not run: a real summary needs a live OpenRouter key, and the rules forbid real keys and live LLM calls. The web
tests cover the GET → async → stored path with a recorded response.

## Contract impact

`/api/v1`: `AudiencePlanResponse.Summary` gains `locale`, `aiGenerated`, `aiDisclosure`, `model`, `generatedAt`.
Marker: `aiDisclosure` inside the `Summary` schema. The five text fields are non-null whenever a summary is present.

## i18n impact

The api template ships EN/ES/FR/UK. There are no webapp string changes in this task.

## Blast radius

`AudiencePlanController` GET only. It adds one async task per (plan, locale) and nothing on the request path
except an in-memory set check. There is no migration: V135 already has `summaries`, `model_id`, `tokens_in`,
`tokens_out` and `cost_usd`.

## Risks

- The webapp shows the "AI analysis" label whenever `summary.headline` is present, so a template summary would
  be labelled AI until the webapp reads `aiGenerated`. This is out of this repo's scope and is reported as a
  follow-up.
- The name guard is fail-closed. A member whose display name equals a city key or genre word forces the template.
- `cost_usd` stays null until prices are configured.

## Definition of done

Every test branch listed in §M2-2 is green, the full `./mvnw test` is green, and no live call is made.

## Live-test evidence

n-a (see Live-test).

## Review rounds

(appended by /do-task)

### Round 1 → SHIP with three MEDIUMs, all fixed

1. Numeric check. `SummaryNumbers.allowed` now reads the input JSON as a tree: ISO date strings become whole dates
   (never split into day/month/year numbers), `low/high` and `lowPct/highPct` pairs become range pairs, and every other
   number is a scalar field. In the output, dates pass only as whole input dates (digits, or month names in
   en/es/fr/uk), a range end passes only inside its own `low–high` (any of `- ‐ ‑ ‒ – — −`) unless it is also a scalar,
   and a midpoint is never added, so it passes only when it is itself a field. The prompt says so. Tests: invented mid
   equal to a day of month is rejected, a bare `30` is rejected, `12–30` with every dash is accepted.
2. Spend cap. `Summarizer.recent`: within 24 h of a model summary for the same event and locale on an earlier plan row,
   no call is made; it is copied (marker, model, `generatedAt` kept; zero tokens) when its shape and numbers fit the
   new plan, else the template. `summary-daily-cap-per-org` (`IMIN_AUDIENCE_PLAN_SUMMARY_DAILY_CAP`, default 50)
   caps model calls per org per UTC day, retries included (in memory, `ponytail:` comment names the ceiling).
3. Timeout. Spring AI 2.0.0-M4 has no per-call timeout option, so summaries use their own
   `audiencePlanSummaryChatClient`, built through the same `OpenRouterConfig.chatClient` factory (privacy interceptor
   kept) over a `JdkClientHttpRequestFactory` with `summary-timeout` (`IMIN_AUDIENCE_PLAN_SUMMARY_TIMEOUT`, default
   30s) and a retry template that retries a 5xx once and never a timeout (Spring AI's default retries a timeout ten
   times with backoff up to 3 min). Test: a stalled local server gives the template and the executor's next task runs
   within 5 s; removing the read timeout turns it red.

LOWs: tests for a line over 400 chars (and exactly 400 kept), malformed JSON, and the Momentum hook never summarising.
