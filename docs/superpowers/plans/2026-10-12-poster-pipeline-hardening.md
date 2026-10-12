# imin-api poster pipeline hardening
poster-pipeline-hardening · Subagent (api only) · Notion: n/a

## Goal and scope
Stop the Ideogram poster pipeline from wasting renders and discarding good output. Prod evidence (read-only DB, last 90 days): 15 variants, 14 BEST_EFFORT; every text-gate failure = missing required date (often plus garbled extras); 13/15 used all 3 attempts with identical failures; `ideogram_cost_eur` NULL everywhere. Poster-lab (2026-10-12, `~/Claude Cowork/OUTPUTS/IMIN/poster-lab/results/2026-10-12_date-text/`): remix at weight 70 fixed 0/2 failing renders, a fresh generate fixed 2/2.

In scope: (2) missing → fresh `generate` + new seed, extra-only → remix; (3) best-of when all fail; (4) Recraft persona + dead `style_type` removed; (6) timeouts on Ideogram + OpenRouterRestClients; (7) overview LLM failure non-fatal once posters exist; (8) one retry with backoff on Ideogram 429/5xx; (9) `enable_copyright_detection` on remix, probe-gated; (11) per-variant cost from configured prices, no default numbers.

Out of scope: render-prompt/date changes (fresh generate re-sends `variant.ideogramPrompt()` unchanged); primary ChatClient timeout/retries (`OpenRouterConfig.java:38`); AI quota ordering (`ConceptController.java:42`); webapp.

### Amendment 1 (2026-10-12, user-approved scope change)
- Item 11 REPLACED: Ideogram V3 returns no per-image cost (V4 async `usage_cost_usd_micros` came back empty in the lab). Drop `poster_variants.ideogram_cost_eur` instead of pricing it. No `IdeogramPricing`, no `price-eur` keys, no C1/C2/P1/P2. Prod: 0 non-null of 237 rows, all time (verified 2026-10-12).
- Lane C added: C1 text-first render prompt (code builds the first sentence; LLM writes only the visual + a `type_style` clause), C2 no-nudity clause + yaml fixes, C3 gate counts fine print / vertical pseudo-text as extra.
- Item 9 probe approved by the user (one paid remix call).
- Out of scope still: date format (`d MMM yyyy` kept), Ideogram V4, printing lineup text, primary ChatClient timeout, quota ordering, webapp.

## Repos in ship order
| key | base | worktree | verification |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/poster-pipeline-hardening` (origin/master 137edd2a) | `./mvnw test` (Docker up, zero skipped) |

## Affected files (per repo)
Paths relative to the api worktree.

| # | File | Change |
|---|---|---|
| 1 | `src/main/java/com/imin/iminapi/service/poster/PosterOrchestrator.java` | Retry routing (missing or unevaluated → `generate` with original prompt + `nextSeed`; extra-only → remix). Best-of tracking. Retry render throw → ship best-so-far. Stored seed = shipped attempt's. Journal fields. Running cost via `IdeogramPricing`. Javadocs (:39-48, :236) rewritten. |
| 2 | `.../service/poster/PosterTextValidationService.java` | `ValidationDecision` gains `boolean evaluated` + 4-arg ctor defaulting `true`; unevaluable branch (:43-45) passes `false`. |
| 3 | `.../service/poster/IdeogramV3Client.java` | `post()` (:230-249): one retry after `retryBackoffMillis` on 429/5xx `RestClientResponseException`, POST and GET download separately; other 4xx and `ResourceAccessException` not retried. `buildRemixParts` (:129-141) adds `enable_copyright_detection` (B4, probe-gated). Ctor gains `@Value("${ideogram.retry-backoff-millis:2000}") long`. |
| 4 | `.../service/poster/IdeogramPricing.java` (new) | `@Component`; `ideogram.price-eur.{turbo,default,quality,character-turbo,character-default,character-quality}` default blank; `BigDecimal priceOf(Call, boolean characterRef)` null when unknown; startup rejects non-numeric / negative / >1000 naming the key; one WARN when a used price is blank. |
| 5 | `src/main/java/com/imin/iminapi/config/IdeogramImageConfig.java` | `HttpClientSettings.defaults().withTimeouts(connect, read)` idiom from `MetaGraphConfig`; `ideogram.connect-timeout-millis:10000`, `ideogram.read-timeout-millis:90000`. |
| 6 | `.../config/OpenRouterRestClients.java` | `v1(baseUrl, apiKey, purpose, Duration readTimeout)` replaces 3-arg form; connect 10 s constant. |
| 7 | `.../service/poster/OpenRouterPosterTextValidationClient.java` | `poster.validation.read-timeout-seconds:60` → `v1` (:62). |
| 8 | `.../service/poster/OpenRouterPosterStyleValidationClient.java` | Same property → `v1` (:44). |
| 9 | `.../service/poster/tools/StyleCardGenerator.java` | `poster.style-cards.generator-read-timeout-seconds:180` → `v1` (:113); `@Profile("tools")`. |
| 10 | `.../service/AiEventDescriptionService.java` | :404, :452 Recraft → Ideogram V3; delete :461 `style_type` line, :282-284 validation; :380 3-arg. |
| 11 | `.../dto/PosterVariant.java` | Drop `style_type` component (:14). |
| 12 | `.../service/ai/ConceptStudioService.java` | Move `overviewLlm.generate` (:267) out of the 502 try; `overviewOrFallback(req, poster)`: on throw or null → WARN + Sentry WARNING `subsystem=concept-overview`, request-derived fallback. Fixes NPE at :282 on null overview. |
| 13 | `src/main/resources/application.yaml` | New keys (timeouts, backoff, `price-eur` block blank); fix stale comments :65-67, :102-103. |
| 14 | `.env.example` | Commented new vars with defaults / "blank = unknown, cost stays NULL". |
| 15 | `CLAUDE.md` | :15 TURBO defaults; :40 new env vars; request flow :157-172 (regenerate vs remix, best-of, retry; drop "Semaphore", "Sonnet 4.6"); :179 wording. |
| 16 | `src/test/.../service/poster/PosterOrchestratorTest.java` | Helpers pass blank `IdeogramPricing`; drop 4th `PosterVariant` arg; edits + O1-O6, C1-C2. |
| 17 | `src/test/.../service/poster/PosterTextValidationServiceTest.java` | Unevaluable test asserts `evaluated()==false`. |
| 18 | `src/test/.../service/poster/IdeogramV3ClientTest.java` | Ctor backoff `0L`; R1-R3; remix test (:145) asserts copyright part (B4). |
| 19 | `src/test/.../service/poster/IdeogramPricingTest.java` (new) | P1-P2. |
| 20 | `src/test/.../config/IdeogramImageConfigTest.java` (new) | T1 black-hole socket, modelled on `MetaGraphConfigTest`. |
| 21 | `src/test/.../config/OpenRouterRestClientsTest.java` | T2. |
| 22 | `src/test/.../service/AiEventDescriptionServiceTest.java` | Drop 4th arg (55 sites); delete prompt-`contains` assertion :170; keep `"style_type"` JSON fixtures (stale reply still parses). |
| 23 | `src/test/.../service/ai/ConceptStudioServiceTest.java` | Drop 4th arg (12 sites); F1-F2. |

### Amendment 1
Removed: #4 `IdeogramPricing.java`, #19 `IdeogramPricingTest.java`. #1 no cost logic. #11 `PosterVariant`: rename `@JsonProperty("style_type") styleType` → `@JsonProperty("type_style") typeStyle` (arity stays 4; 71 test call sites and repro tests unchanged). #13/#14 no price keys. #15 CLAUDE.md also documents the four-part render prompt.

| # | File | Change |
|---|---|---|
| 24 | `src/main/resources/db/migration/V185__drop_poster_variants_ideogram_cost_eur.sql` (new; number re-picked before review) | `ALTER TABLE poster_variants DROP COLUMN IF EXISTS ideogram_cost_eur;` with a why-comment |
| 25 | `src/main/java/com/imin/iminapi/model/PosterVariantEntity.java` | Delete `:54-55` and the `BigDecimal` import `:7` |
| 26 | `src/main/java/com/imin/iminapi/dto/PosterTextSpec.java` | `promptBlock` → `textSentence`; `forPrompt()` → `textSentence()` (same arity) |
| 27 | `src/main/java/com/imin/iminapi/service/poster/PosterTextSpecFactory.java` | `:32-42` EXACT TEXT CONTRACT → text-first sentence; required/allowed lists unchanged |
| 28 | `src/main/java/com/imin/iminapi/service/poster/RenderPrompt.java` (new) | `compose(textSentence, visual, typeStyle)` → sentence, visual, `Type: …` (omitted when blank), clothing clause |
| 29 | `src/test/java/com/imin/iminapi/service/poster/RenderPromptTest.java` (new) | LC2 |
| 30 | `src/test/java/com/imin/iminapi/service/poster/PosterTextSpecFactoryTest.java` | `:28-29` replaced by LC1 |
| 31 | `src/main/resources/vibes.yaml` | `:22` negative adds "or nudity — every person is fully clothed" |
| 32 | `src/main/resources/vibes/style-cards/open_air_festival.yaml` | `:13` drop "one shirtless guy" |
| (7) | `OpenRouterPosterTextValidationClient.java` | C3 wording in `VALIDATION_PROMPT` `:27-42` |

Total ≈ 30 files.

## Ordered steps
Pre: `docker info`, then `./mvnw test` once on the untouched base (repro A1/B1 expected red, A2 green).

**Lane A — render policy (items 4, 2, 3, 11).**
- A1. Item 4 (rows 10, 11 + call sites 16, 22, 23). `./mvnw -q test-compile`; grep `styleType|style_type` in `src/main` = 0; `grep -rni recraft AiEventDescriptionService.java` = 0.
- A2. `ValidationDecision.evaluated` (row 2).
- A3. `IdeogramPricing` (row 4) + yaml. Speed `toUpperCase(Locale.ROOT)`; unknown speed → null. GENERATE uses generate speed; REMIX (incl. palette regrade) uses remix speed. Column `NUMERIC(10,4)` (`V3__poster_generations.sql:26`); sum rounded 4 dp HALF_UP.
- A4. Orchestrator (row 1):
  - `best` = (bytes, seed, journalIndex, missing, extra, evaluated, attempt); package-private static `isBetter`: evaluated beats unevaluated → lower missing+extra → lower missing → earlier attempt.
  - Failure, not last: `mode = (!evaluated || !missing.isEmpty()) ? "regenerate" : "remix"`; `seed = nextSeed(seed)`. Regenerate = `generate(variant.ideogramPrompt(), seed, style.parts(), style.preset(), brandPalette, characterRef)` (same args as attempt 0; DJ keeps char ref). Remix keeps `buildCorrectionPrompt`.
  - Last fails: `setSeed(best.seed)`, `accept(best.bytes, BEST_EFFORT)`, mark `shipped:true`. No palette regrade on best-effort (as today :270-274).
  - Attempt ≥1 render throw → journal `{render:"failed", text:{accepted:false, evaluated:false, reason}}`; ship best if any, else rethrow. Attempt-0 throw keeps FAILED path (:228-232).
  - Text pass: `shipped:true` on the entry whose bytes ship (regrade entry if kept).
  - `attemptJson` adds `text.evaluated`, `text.missing`, `text.extra` (ints); existing keys unchanged.
  - Cost: append `priceOf` after each successful render; any null → whole sum null; zero renders → NULL.
- A5. Edit existing orchestrator tests; add O1-O6, C1-C2.
- A6. Docs rows 13-15 for Lane A; full gate.

**Lane B — transport and resilience (items 6, 8, 7, 9), after Lane A green.**
- B1. Timeouts (rows 5-9) + yaml/.env/CLAUDE.md; T1, T2; prove red by removing `.requestFactory(...)`.
- B2. Retry (row 3), R1-R3; prove red by removing the wrapper.
- B3. Overview fallback (row 12), F1-F2; throw vs null logged separately; nothing stored.
- B4. Item 9 probe-gated: with user OK, one paid `POST /v1/ideogram-v3/remix` with `enable_copyright_detection=true` (key from chmod-600 file). 200 → add part + assert; 4xx or no OK → drop item 9, record under Review rounds. Rides `IDEOGRAM_COPYRIGHT_DETECTION`.
- B5. Full gate, zero skipped.

### Amendment 1
- **A1 amended:** rename `style_type` → `type_style`; drop the "Design" check `:282-284`; `:380` passes `v.typeStyle()`; `:461` becomes `- type_style: one short clause describing the lettering style`; grep `styleType` in `src/main` = 0.
- **A3 replaced:** no pricing; delete `PosterVariantEntity.java:54-55` and `:7` in the Lane A commit (the first shipped build no longer maps the column).
- **A4 amended:** delete the cost bullet.

**Lane C — render prompt (commit 3, after Lane B green).**
- LC-1 Text sentence (`PosterTextSpecFactory`): elements in order title, date, venue (each only if present). Full form: `A finished event poster whose only text is "<TITLE>" as the huge headline, the date "<d MMM yyyy>" in large bold type directly under the title, and the venue "<Venue>" in smaller type below the date.` No elements → `A finished event poster with no text at all.` DJ/city never in the sentence, kept in `allowed` for the gate (DJ mode: character ref carries the DJ, no name text).
- LC-2 `RenderPrompt.compose` (row 28). Clothing clause phrased positively ("every person shown is fully clothed").
- LC-3 Art-director contract (`AiEventDescriptionService`): `ideogram_prompt` = visual only, 60-180 words (`MIN_WORDS`/`MAX_WORDS` `:61-62`), no quotes, no text slots; `type_style` ≤20 words, no placement/words; delete anatomy items 6-7 (`:458-459`); drop "required text is integrated…" (`:465`); `:472` → "The renderer adds the event text itself — do not write it."; brief `:483-485` labelled "context only — never write these words". Both return paths `:166`, `:181`: `forcePortrait` → `finish(concept, request)` = forcePortrait + compose per variant.
- LC-4 Inverted check replacing `:353-367`: per variant `ideogramPrompt` and `typeStyle` must contain no `"`, no required/allowed string (case-insensitive whole phrase), no `LAYOUT_LABELS` word (headline, title, subtitle, tagline, caption, venue, date, lineup, line-up, credit, credits, city, badge, logo, "dj name"); error `variant[i].<field> writes poster text: "<hit>"`; blank `type_style` = validation error (best-effort omits `Type:`).
- LC-5 C2: rows 31, 32.
- LC-6 C3: STEP 1 adds "Fine print, tiny marginal text, credit lines, vertical text columns and letter-like pseudo-text count as text even if illegible."; `:40-42` → "A pattern with no letter-like shapes is not text; a block of letter-like marks is — report it once as one extraText entry describing it (e.g. "fine print, bottom-left")." `accepted` rule unchanged.
- LC-7 Full gate + C3 lab re-run (see Live-test).

**LC-8 (C4, user-approved 2026-10-12): no organizer date → no date on the poster.** `ConceptStudioService.java:358` substitutes `LocalDate.now().plusMonths(2)` when the request has no `eventDate`. Keep whatever non-poster consumers need (trace them, cite file:line; do not change their behaviour unless it also fabricates a user-visible date), but the poster text spec must receive "no date": `PosterTextSpecFactory` omits the date from `required` and from the text sentence (LC-1 already handles a missing element). Test LC5 (unit): request without eventDate → spec has no date in `required` and the sentence quotes no date — bug: a fabricated date printed large on the organizer's poster. Prove red by restoring the default date into the spec path.

**Migration (commit 4, shipped in a separate later push).**
- M-1 Write row 24. M-2 Before review: `git fetch && git ls-tree origin/master src/main/resources/db/migration/`, renumber to max+1, `./mvnw clean`. M-3 Before push 2, read-only prod: `SELECT count(*) FILTER (WHERE ideogram_cost_eur IS NOT NULL), count(*) FROM poster_variants;` must be 0 non-null (else stop, ask user). M-4 Ship only after push 1 is live and stable.

## Verification commands
```
docker info >/dev/null && echo docker-up
cd /Users/ivan/imin/imin-api/.claude/worktrees/poster-pipeline-hardening
./mvnw test 2>&1 | tee <scratchpad>/gate-api-poster-pipeline-hardening.log
grep -E "Tests run:.*Skipped: [1-9]" <scratchpad>/gate-api-poster-pipeline-hardening.log && echo RED-skipped
grep -rn "styleType\|style_type" src/main                      # no hits
grep -rni "recraft" src/main/java/com/imin/iminapi/service/AiEventDescriptionService.java   # no hits
grep -n "IDEOGRAM_PRICE_EUR\|IDEOGRAM_.*TIMEOUT\|IDEOGRAM_RETRY\|POSTER_.*TIMEOUT" CLAUDE.md .env.example
```

## Test impact
**Reproduction tests** (`PosterOrchestratorTest`, unit; not rewritten, only the 4th `PosterVariant` arg dropped):
- A1 `missingRequiredText_isRetriedWithFreshGenerateAndNewSeed_notRemix` — red today:
  ```
  NeverWantedButInvoked: ideogramV3Client.remix(...)
  But invoked here: -> at PosterOrchestrator.renderWithValidation(PosterOrchestrator.java:260)
  ```
- A2 `extraTextOnly_isStillRetriedWithRemix` — green before and after.
- B1 `allAttemptsFail_shipsFewestErrorsRender_notTheLast` — red today:
  ```
  Expecting actual: [12] to contain exactly (and in same order): [10]
  at PosterOrchestratorTest.allAttemptsFail_shipsFewestErrorsRender_notTheLast(PosterOrchestratorTest.java:628)
  ```

**New orchestrator tests (unit):**
- O1 `unevaluatedGate_isRetriedWithFreshGenerate` — gate outage triggers an empty-correction remix that reproduces the same image.
- O2 `bestOf_tieOnTotal_prefersFewerMissing_andShipsItsSeed` (parameterized; equal → earliest) — a dateless poster ships over one with a stray word; stored seed doesn't match shipped image. Losing render ordered last.
- O3 `unevaluatedAttempt_neverBeatsAnEvaluatedFailure` — unchecked render (0/0) ships over a checked one; unevaluated ordered last.
- O4 `retryRenderThrows_shipsBestSoFar_asBestEffort` — a 503 on attempt 2 throws away two paid renders.
- O5 `djMode_missingText_regenerateCarriesCharacterRef` — DJ poster loses the DJ's face on retry.
- O6 `journal_marksShippedEntry_andRecordsRegenerateMode` — auditor can't tell which attempt shipped or why.
- C1 `cost_isSumOfEveryRenderIncludingRegrade` (test-only prices 0.0300/0.0900) — ledger under-counts retries/regrade.
- C2 `cost_staysNull_whenAnyUsedPriceIsUnknown` — partial sum booked as full cost.

**Existing orchestrator tests that change** (`textFail()` has a missing item → now regenerate): `textFails_thenRemixCorrects_…` (:144) → extra-only fixture, renamed `…remixPromptCarriesExtraText`; `textNeverPasses_acceptsBestEffortWithJournal` (:168) → `times(9)` generate, `never()` remix; `supersededRetryRenders_…` (:194) → `times(9)` generate, `writePng times(3)`; `brandColors_…` (:248), `djModeCorrectiveRemixCarriesCharacterRef` (:406) → extra-only fixture. Palette-regrade tests (:471-552) unedited.

**Pricing** (`IdeogramPricingTest`, unit): P1 `rejectsInvalidPrice` (-0.01, 1000.01, abc) — typo books absurd cost; P2 `blankPriceIsUnknown_unknownSpeedIsUnknown` — blank booked as 0 €.

**Text gate:** unevaluable test asserts `evaluated()==false`.

**Ideogram client** (unit, MockRestServiceServer, backoff 0): R1 `retriesOnceOn429Or5xx_thenSucceeds` (429/500/503) — transient blip fails the variant; R2 `secondRetryableFailure_throws_afterExactlyTwoPosts` — unbounded retries; R3 `clientError400_isNotRetried` — deterministic 4xx sent and maybe billed twice.

**Timeouts** (config checks): T1 `IdeogramImageConfigTest.the_ideogram_client_gives_up_on_a_socket_that_never_answers`; T2 `OpenRouterRestClientsTest.v1_gives_up_on_a_socket_that_never_answers` — one hung socket hangs a variant thread and the organizer's request forever. Defaults not echo-asserted.

**Concept service** (unit): F1 `overviewFailsAfterRender_returnsPostersWithRequestDerivedFallback` (throws / null × with/without title+capacity) — paid render ends in 502/500, organizer loses posters and quota; F2 `renderFailure_stillReturns502` — fallback swallows real render failures (prove red by widening the try).

**No tests for:** `style_type` removal, persona wording (prompt `contains` banned).

**Stay green unedited:** `ConceptStudioFailurePersistenceTest`, `ConceptControllerTest`, `PosterProvenanceTest`, `ConceptStudioServiceDjPhotoTest`, `IdeogramApiKeyScopeTest`.

**Prove guards red:** remove `!evaluated` → O1/O3; remove missing tie-break → O2; remove throw catch → O4; drop `characterRef` → O5; null-skipping sum → C2; remove retry → R1; loop → R2; drop status filter → R3.

### Amendment 1
Removed: C1, C2, P1, P2. Column drop: no own test (shared `@IminIntegrationTest` context migrates through V185; Hibernate `ddl-auto: none` maps the entity without the field).
- LC1 `PosterTextSpecFactoryTest.textSentence_quotesPresentElementsFirstInOrder` (title+date+venue; date+venue; title+date; nothing; fixture 2026-06-07 → `7 JUN 2026`) — dates missing from posters (prod: all 14 best-effort variants) / invented DJ names. Unit. Red: reorder elements or quote `allowed`.
- LC2 `RenderPromptTest.composes_textSentenceFirst_visual_typeClause_clothingLast` (blank vs present typeStyle) — late text drops the date (lab A: 0/8); topless hero ships. Unit.
- LC3 `AiEventDescriptionServiceTest.generateConcept_everyRenderPromptStartsWithTheTextSentence` (paths `:166`, `:181`) — one return path ships dateless prompts. Unit. Red: remove `finish` from either path.
- LC4 `AiEventDescriptionServiceTest.validate_rejectsLlmTextThatWritesPosterText` (required string; DJ name; city; `"`; "venue line"; label in `type_style`) — labels/names printed literally (lab: DITY/SITY). Unit. Red: remove each term once.
Existing edits: `AiEventDescriptionServiceTest` delete `:132` assertion, delete `validate_withRequest_rejectsVariantMissingRequiredText` (`:363-390`), delete/convert `:333-352`, rewrite `djPeoplePrompt1-3` (`:546-575`) without labels; keep `"style_type"` JSON fixtures (stale reply still parses). `PosterTextSpecFactoryTest:28-29` replaced by LC1. C2/C3 wording: no unit test, checked in Live-test.

## Live-test
Needed: yes, after deploy. One concept generation in dashboard Poster Studio on a dated test event (paid, 3 variants); read-only `SELECT validation_verdict, validation_attempts_json, seed, ideogram_cost_eur FROM poster_variants ORDER BY … DESC LIMIT 3` — every missing-text failure followed by a `regenerate` entry, exactly one `shipped:true`, seed = shipped entry's seed, cost NULL until prices set. Railway logs `[ideogram v3] … in N ms` for real latency vs 90 s. Overview fallback covered by F1 only.

### Amendment 1
- Pre-ship C3 lab check: re-run the lab gate with the new `VALIDATION_PROMPT` verbatim on lab images — 4/4 known fine-print misses now flagged, 6/6 B-arm passes kept. Record counts.
- After push 1: 3 posters from one prod generation (title+date+venue): all three strings present and correct, no invented words/fine print, no nudity; `ideogram_prompt` starts with `A finished event poster whose only text is`; verdict mix recorded, BEST_EFFORT journals quoted.
- After push 2: `\d poster_variants` has no `ideogram_cost_eur`; one generation saves fine.

## Contract impact
None. `PosterVariant`/`PosterConcept` internal (absent from prod `/v3/api-docs.yaml`). `ConceptResponse` shape unchanged; fallback can null `name`/`confidencePct`, unread by webapp (`postersApi.ts:73-86`). No `/api/v1/public` change.

## i18n impact
None. Fallback values are the organizer's own title/capacity or null, never generated text; not rendered by the current webapp.

## Blast radius
- Ideogram: same render count per gate attempt; ≤1 extra call per attempt only on 429/5xx. Regenerate = generate at `IDEOGRAM_GENERATE_RENDERING_SPEED` (TURBO). One entry path: `ConceptStudioService.run` ← `ConceptController.create`/`.regenerate`.
- Money/auth/Stripe: none. Flyway: none (columns exist, V3:26, V37).
- Shared: `OpenRouterRestClients.v1` signature (3 callers); `IdeogramV3Client` ctor (faked in `IminIntegrationTest.java:44`); `ValidationDecision` keeps 4-arg ctor.
- Config: new optional env vars; prices blank.

### Amendment 1
- Flyway: yes — V185 drops one column on a small table (brief lock). No other reader (only `V3:26`, `PosterVariantEntity.java:54-55`). Code rollback onto a dropped column breaks poster inserts → migration ships in a separate later push.
- Lane C: every render on create and regenerate gets the new prompt shape; concept LLM JSON contract changes (internal only).
- C3: stricter gate → more fresh-generate retries, bounded by `max-regenerations` (2).

## Risks
- 23 files, mostly mechanical; Lanes A then B as sequential commits in one worktree.
- Worst-case request time ≈ 11 min if every call times out (previously unbounded); overall per-variant deadline = follow-up.
- Remix copyright flag may 400 → ships only after B4 probe.
- Retry may double-bill on a 5xx after render; bounded to one.
- DJ-mode seed not sent (`IDEOGRAM_CHARACTER_SEED=false`), stored seed can't reproduce — pre-existing.
- No EUR price source; cost stays NULL until set.
- Hand-written webapp `types.ts` says `name: string`; null possible only in fallback, unread today.
- Primary ChatClient has no timeout + default retries — out of plan.

### Amendment 1
- Dropping data: 0 non-null in all 237 prod rows; git shows no writer ever (only commit 13ca2014 declared it). M-3 rechecks. Not undoable by Flyway.
- Small lab sample (8+8 renders, 4 events); long titles/dense vibes may still miss — fresh-generate + best-of is the net; visual live-test is the proof.
- Less layout variety (headline → date → venue fixed); type-style clause still varies lettering.
- LLM may still write text; inverted check retries once (`MAX_ATTEMPTS=2`), the gate catches printed labels.
- Straight `"` inside a title is not escaped; gate decides.
- Fabricated default date: resolved by LC-8 (user said yes).

## Definition of done
- A1, B1 green; A2 green.
- All Test impact tests exist and pass; each guard proven red once.
- `./mvnw test` green, zero skipped, Docker up, after any rebase.
- Verification greps clean.
- `CLAUDE.md`, `.env.example`, `application.yaml` list every new key; stale comments fixed.
- Item 9 shipped after a 200 probe, or recorded dropped.
- Live-test shows a `regenerate` entry and one `shipped` marker per variant.

### Amendment 1
- V185 (re-picked) present; `grep -rn "ideogram_cost_eur\|ideogramCostEur" src/main/java` = 0; M-3 count = 0 recorded before push 2.
- LC1-LC4 pass, each proven red once; `grep -rn "EXACT TEXT CONTRACT\|forPrompt()" src/main` = 0.
- C3 lab counts recorded (4/4 flagged, 6/6 kept); 3-poster visual check recorded.
- CLAUDE.md describes the four-part render prompt.
- Ship: push 1 = commits 1-3 (Lanes A, B, C) + live-test; push 2 = commit 4 (V185) after M-3.

## Live-test evidence
(post-deploy checks filled in after deploy)

### Pre-ship C3 lab check (2026-10-12)
New `VALIDATION_PROMPT` read verbatim from `OpenRouterPosterTextValidationClient.java`, `openai/gpt-4o` via OpenRouter, temperature 0, max_tokens 1500, same required/allowed lists as the 2026-10-12 lab run; accept = no missing and no extra (prod rule). 8 images (the two sets overlap: `e1_B_s1` and `e2_B_s1` are both B-arm passes and fine-print misses).
- Restated target: the 4 known fine-print misses flagged, and the 4 clean B-arm passes that are not among them kept.
- Fine-print misses flagged: **1/4** (`e1_D_s1` flagged with `REVERB, ICE DOWN, SEPTEMBER FESTIVAL 2026, IN PARTIUM, NADIA R, KOLA`; `e1_B_s1`, `e1_C_s1`, `e2_B_s1` still pass). Below target; shipping decision is the user's.
- Clean, non-overlapping B-arm passes kept: **4/4** (`e2_B_s2`, `e3_B_s1`, `e3_B_s2`, `e4_B_s2`).
- Measured before the review fix that asks the gate to put descriptive entries in square brackets (marker only, no detection wording changed).
- On `e1_D_s1` the gate also listed the allowed DJ names `NADIA R`, `KOLA` as extra.
- Prompt wording kept as specified in LC-6; a stronger gate (or wording change) is a follow-up decision.

## Review rounds

- Gate 1 approved by the user 2026-10-12 with answers: C4 yes; DJ/city names not printed OK; C3 lab re-run (few cents) OK; migration as a separate second push OK; B4 probe OK.
- B4 probe 2026-10-12: one `POST /v1/ideogram-v3/remix` with `enable_copyright_detection=true` (TURBO, image_weight 70, lab image `e4_B_s2.png`) answered **200** with an image URL (`is_image_safe: true`). Item 9 implemented.
- round 1 → FIX_REQUIRED (1 HIGH: art-director prompt still feeds example_prompts/Layout hints with quoted text and slot words, best-effort path ships them; MEDIUM: remix quotes C3 descriptive entries, LC-4 label false positives (hyphen, "city", generic venue names), regradeShipped untested, stored seed wrong when regrade ships, C3 target 1/4 needs user sign-off; LOW: pricing horizon plusMonths(2), fallback negative prompt lacks nudity clause, stale .env.example Recraft lines)
- round 2 → FIX_REQUIRED (HIGH: single-quoted invented text and unlisted slot words in style-card pools — disco_italo 'YOUR CLUB PRESENTS', perks line, (SUMMER / FRIDAY); berlin_minimal footer of tiny text, dark_experimental annotation labels, industrial_hard_groove small labels/credits/QR/kicker; plural badges — pass visualHint, validator and finish(); LOW: label→"type" substitution yields odd phrases)
- round 3 → APPROVE (MEDIUM: best-effort test doesn't cover single-quoted spans through finish(); LOW: '90s elision false positive; berlin_minimal tick-mark numerals)
