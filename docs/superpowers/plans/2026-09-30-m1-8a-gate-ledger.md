# M1-8 Date-check API
m1-8-date-check-api · plan-gated · Notion: (main session fills)

## Goal and scope
Expose "Check a date" as organizer endpoints (spec §4): `POST /api/v1/predictions/date-checks`, `GET …/{id}`, `GET …?limit=`, `PATCH …/{id}/assumptions`. Each run is deterministic (calendar + internal + organizer + input rules), persisted in `date_check` / `date_check_date` / `date_check_finding`, and ledgered (`surface=DATE_CHECK`, `event_id=null`, `date_check_id`, `question_bank_version`) before the response. Responses carry structured data, template keys and params only.
Also in scope: the `all-orgs` gate mode (Ivan a) and excluding DATE_CHECK from `findJoinable` (Ivan c).
Out of scope:
- web research, the 202 + job path and quota charging (M2-4);
- the event link and stale flag (M1-9);
- webapp UI and copy (M3-*);
- `/internal/predictor/costs` (M2).

## Repos in ship order
1. api (`imin-api`, base `master`). Branch from `origin/master` after M1-6 and M1-7 have merged. If split, M1-8a can branch now.

## Affected files (per repo)
api, paths under `src/main/java/com/imin/iminapi/`:
- **M1-8a (gate + ledger):**
  - `predictor/config/DateCheckProperties.java`: add `Boolean allOrgs = FALSE` with a null-safe setter.
  - `predictor/config/DateCheckAccess.java`: pass when `enabled && (allOrgs || betaOrgIds.contains(orgId))`, and still 404 on a null org.
  - `src/main/resources/application.yaml`: add `all-orgs: ${PREDICTOR_DATE_CHECK_ALL_ORGS:false}` under `imin.predictor.date-check`, and a `ratelimit.predictor-date-check` block (capacity 20, window-minutes 10).
  - `predictor/repository/PredictionLedgerRepository.java`: add `and l.surface <> com.imin.iminapi.predictor.model.PredictionSurface.DATE_CHECK` to `findJoinable`, plus a 1-line comment.
  - `predictor/service/PredictionLedgerService.java`: add `UUID recordDateCheck(UUID orgId, UUID dateCheckId, String qbVersion, String inputHash, String outputJson)`. It sets surface DATE_CHECK, stage 0, `modelId="rules/date-check"`, `promptVersion=qbVersion` and `questionBankVersion`. `RecordCommand` stays as it is, so the existing callers don't change.
  - `config/RateLimitConfig.java`: two `@Value` fields and `configs.put("predictor-date-check", …)`.
- **M1-8b (API):**
  - `predictor/controller/DateCheckController.java` (`@RequestMapping("/api/v1/predictions/date-checks")`). Returns typed `ResponseEntity<DateCheckResponse>`, not `<?>`, so the schema reaches OpenAPI.
  - `predictor/service/DateCheckService.java`: create, get, list and patchAssumptions. It also builds `DateCheckInput`, runs and persists, and maps entities to DTOs.
  - `predictor/service/DateCheckValidator.java`: collects every field error, then throws `ApiException(UNPROCESSABLE_ENTITY, FIELD_INVALID, "Validation failed", fields)`.
  - `predictor/dto/`:
    - `DateCheckRequest`: `city, country?, genreFamily, subGenre?, dates[], capacity?, priceMinor?, format?, startHour?, endHour?, lineup?, knownEvents?[{name,date,venue?,strength}], audienceAge?, communities?, buyingLeadDays?, research?`.
    - `DateCheckResponse`: `id, status, city, country, genreFamily, subGenre, capacity, priceMinor, research, researchStatus, questionBankVersion, createdAt, updatedAt, assumptions[], dates[]`.
    - `DateCheckDateDto`: `date, verdict, riskScore, oppScore, coverage, coverageBucket, rank, breakdown[{questionId,kind,sourceKind,points}], findings[], notChecked[], actions[]`.
    - `FindingDto`: `questionId, kind, status, strength, weight, sourceKind, window, stopFactor, templateKey, facts, url, quote, fetchedAt`.
    - `ActionDto`: `key, dueDate, questionId, params`.
    - `AssumptionDto`: `field, value, source, estimate, sourcedUrl`.
    - `AssumptionsPatch`: `audienceAge?, communities?, priceMinor?, startHour?, buyingLeadDays?`.
    - `DateCheckSummaryDto`: `id, status, city, genreFamily, createdAt, dates[{date,verdict,riskScore,rank}]`.
  - `predictor/repository/DateCheckRepository.java`: add `@Lock(PESSIMISTIC_WRITE) @Query findLockedById(UUID)` for PATCH.
  - `predictor/repository/DateCheckDateRepository.java`: add `deleteByDateCheckId`.
  - `predictor/repository/DateCheckFindingRepository.java`: add `deleteByDateCheckDateIdIn`.
- **Tests:** see Test impact. Total about 22 files (8a: 7, 8b: 15).

## Ordered steps
0. Confirm on `origin/master` that M1-6 and M1-7 have merged, and re-quote their signatures:
   - `RuleEngine.evaluate(DateCheckInput, LocalDate) → List<Finding>`;
   - `Scorer.score(List<Finding>, QuestionBank) → DateResult`;
   - `Ranker.rank(List<Candidate>) → List<Ranked>`, plus a public bucket-of-coverage helper (if it's missing, add a static `Ranker.bucketOf(BigDecimal)`);
   - `ActionPicker.pick(findings, bank, eventDate, today)`;
   - `AssumptionResolver.resolve(DateCheckInput, GenreProfile)` (EUR-only price per M1-7);
   - the `DateCheckInput` component order.

   Then create the worktree and run `./mvnw test` on the untouched base. If the base is red, stop and file it as its own card.
1. **Gate (8a).**
   - Add `allOrgs` to the properties and yaml, and the `DateCheckAccess` logic.
   - `requireEnabled` is still the first call in every endpoint, so a closed gate and a foreign id both return 404.
2. **Ledger (8a).** Add `recordDateCheck` and the `findJoinable` surface exclusion.
3. **Rate limit (8a).** Add the `predictor-date-check` bucket in `RateLimitConfig` and the yaml. `RateLimitBucketCoverageTest` then guards the name.
4. **Validator (8b).**
   - Resolve `country` = `req.country` (upper-cased) ?: org country. Blank → `country: required`.
   - `zone = CountryTimeZones.zoneFor(country).orElse(UTC)` and `today = LocalDate.now(clock.withZone(zone))`.
   - Field codes the webapp humanizes in M3-2:
     - `city`: `required`, or `too_long` over 100;
     - `genreFamily`: `unknown` when not in `QuestionBank.GENRE_BUCKETS`;
     - `subGenre`: `unknown` when not in `bank.profiles().get(genre).subGenres()`;
     - `dates`: `required` for null or empty, `too_many` over `props.maxDates`, `duplicate` for a repeated date;
     - `dates[i]`: `past` when before `today`, `beyond_horizon` when after `today.plusMonths(maxHorizonMonths)`;
     - `capacity` and `priceMinor`: `must_be_positive` when ≤ 0;
     - `startHour` and `endHour`: `out_of_range` outside 0..23;
     - `knownEvents[i].strength`: `out_of_range` outside 1..2; `knownEvents[i].name` and `.date`: `required`;
     - `lineup`: `too_many` over 20 entries.

     Malformed JSON stays 400 (the existing handler).
5. **Run and persist (8b)**, in one `@Transactional`:
   - `rateLimiter.consume("predictor-date-check", userId)`, placed after the gate and before validation.
   - Build the `DateCheckInput`. Save `DateCheck` (`status="done"`, `research=false` whatever the request says, `questionBankVersion=bank.version()`, `assumptionsJson` = the resolved assumptions).
   - For each date: `evaluate` → `score` → `pick`. Then `rank` across dates, and save `DateCheckDate` and `DateCheckFinding` rows (lower-case wire values; `facts_json` through `PredictorJson.MAPPER`).
   - Call `ledger.recordDateCheck(...)`:
     - `inputHash` = sha256 hex of the canonical input JSON;
     - `outputJson` = `{dates:[{date,verdict,riskScore,oppScore,coverage,rank}]}`.
   - Return the DTO. If the ledger write throws, the whole transaction rolls back and no check is stored.
6. **Read (8b).**
   - GET `{id}`: gate, then `findById`. A missing id or another org's check → `ApiException.notFound("Date check")`.
   - Map entities to DTOs:
     - `templateKey` = the question's `template`, with `.risk` / `.opportunity` appended for two-kind questions (the same rule as `QuestionBank.templateKeys()`);
     - `breakdown` = FOUND findings with `min(maxPointsPerFinding, strength×weight)`, points descending;
     - `notChecked` = the `not_checked` findings (questionId, sourceKind, `facts.reason`).
   - List: `limit` defaults to 20 and is clamped to 1..50, org-scoped, via `findByOrgIdOrderByCreatedAtDesc`.
7. **PATCH assumptions (8b).**
   - Order: gate → rate limit → `findLockedById` + org check (404) → validate the patch (the same codes).
   - Merge: `priceMinor` and `startHour` go to the `DateCheck` columns. `audienceAge`, `communities` and `buyingLeadDays` go into `assumptionsJson` as ORGANIZER entries. A null field means unchanged; an explicit `[]` is an organizer value.
   - Delete the findings, then the dates, and flush. The `uq_date_check_date` constraint needs the flush before the re-insert.
   - Re-run step 5's scoring and write a new ledger row. Return 200.
   - When research is enabled later, M2-4 adds the 202 re-research branch here.
8. **Responses.**
   - `researchStatus` is always `"off"` in M1-8.
   - No field carries rendered prose: only keys, enums, numbers, dates, facts params and source url/quote.
9. Run the tests below, then `./mvnw test`.

## Verification commands
- `cd /Users/ivan/imin/imin-api/.claude/worktrees/m1-8-date-check-api && ./mvnw test`. Run it on the untouched base first.
- Targeted: `./mvnw test -Dtest='DateCheckControllerTest,DateCheckValidatorTest,DateCheckAccessTest,PredictorPagedScanOrderTest,PredictionLedgerServiceTest,RateLimitBucketCoverageTest'`.

## Test impact
Branches first, one test each.
- `src/test/java/com/imin/iminapi/predictor/config/DateCheckAccessTest.java` (modify, ApplicationContextRunner):
  - `allOrgsLetsNonBetaOrgThrough`;
  - `allOrgsStill404WhenDisabled`;
  - `allOrgsFalseKeepsBetaListSemantics`;
  - `allOrgsDefaultsFalse` (property absent).
- `src/test/java/com/imin/iminapi/predictor/PredictorPagedScanOrderTest.java` (modify, H2): `findJoinableSkipsDateCheckRowEvenWithFinalizedEvent`. A DATE_CHECK row with an `event_id` whose outcome is finalized is the only setup that reaches the new clause, because the `exists` subquery already hides null event ids.
- `src/test/java/com/imin/iminapi/predictor/PredictionLedgerServiceTest.java` (modify): `recordDateCheckStampsSurfaceVersionAndNullEvent`.
- `src/test/java/com/imin/iminapi/predictor/DateCheckValidatorTest.java` (new, plain JUnit, fixed `Clock`):
  - `emptyDatesRequired`, `sixDatesAre422` (`too_many`), `duplicateDatesAre422`;
  - `pastDateIs422InVenueZone`: the clock is at 23:30Z on the 16th, Paris is already on the 17th, and a 16th date is past;
  - `horizonBoundaryInclusive`: today + 18 months is ok, +1 day is `beyond_horizon`;
  - `capacityZeroIs422`, `blankCityRequired`, `unknownGenre`, `subGenreNotInBucketList`, `subGenreFromYamlAccepted`;
  - `knownEventStrength3Rejected`, `hourOutOfRange`;
  - `countryFallsBackToOrg`, `noCountryAnywhereIs422`;
  - `allErrorsCollectedInOneResponse`.
- `src/test/java/com/imin/iminapi/predictor/DateCheckControllerTest.java` (new; `@SpringBootTest @AutoConfigureMockMvc @Import(TestRateLimitConfig.class)`; `authentication(...)` like `PredictionControllerTest`; `@TestPropertySource` enabled=true plus the beta org; a fixed `Clock` bean):
  - `betaOffIs404` (a second class, or a property override, with enabled=false);
  - `otherOrgCheckIs404` (GET and PATCH);
  - `unauthenticatedIs401`;
  - `duplicateDatesAre422`, `pastDateIs422`, `sixDatesAre422`: status 422, `code=FIELD_INVALID`, and the `fields` key asserted;
  - `syncCheckReturnsRankedDates`: 2 dates, a school holiday seeded on one via `ReferenceCalendarEntryRepository`. Status 200; the holiday date ranks below the other; findings and the `notChecked` list are present;
  - `researchFlagIgnoredWhileDisabled`: `research=true` → 200, `research=false`, `researchStatus="off"`, and no `predictor_job` row;
  - `ledgerRowWrittenBeforeResponse`: exactly one ledger row, with `surface=DATE_CHECK`, `event_id` null, `date_check_id` = the response id and `question_bank_version` = `bank.version()`;
  - `ledgerFailureRollsBackCheck`: `@MockitoSpyBean` ledger throws → 500 and zero `date_check` rows;
  - `patchAssumptionsRescoresWithoutResearch`: 200, a new ledger row (count 2), the assumption source is `organizer`, dates are replaced not duplicated, and no job row;
  - `listIsOrgScopedNewestFirstAndClamped`: `limit=500` returns at most 50, and another org's check is absent;
  - `responseHasNoRenderedStrings`: walk the JSON. No key is in {text,label,message,sentence,title,description}, every `templateKey` and `actions[].key` is in `bank.templateKeys()`, and the response contains no `predictor.` rendered copy.
- `RateLimitBucketCoverageTest` is unchanged; it now covers `predictor-date-check`.

## Live-test
- Prod (dark by default): all four endpoints return 404 with a valid organizer token while `PREDICTOR_DATE_CHECK_ENABLED` is false, and `/v3/api-docs.yaml` contains `DateCheckResponse`.
- Local (`/live-test api`) with `PREDICTOR_DATE_CHECK_ENABLED=true PREDICTOR_DATE_CHECK_ALL_ORGS=true`:
  - POST Paris, 3 dates (one in the Toussaint 2026 break) → 200, ranked;
  - GET `{id}`;
  - PATCH with `communities:[]` → a second ledger row;
  - POST with a past date → 422 `fields.dates[0]=past`;
  - `psql`: a `prediction_ledger` row with surface DATE_CHECK.
- Turning the flag on in Railway is Ivan's call; this task doesn't do it.

## Contract impact
- New `/api/v1` paths (organizer auth, additive). OpenAPI marker for `/ship-imin`: the `DateCheckResponse` schema appears in `/v3/api-docs.yaml`.
- No existing schema changes.
- The webapp `src/shared/api/types.ts` edit (`DateCheckRequest`/`DateCheckResponse`/`AssumptionsPatch` hand types) belongs to the consuming webapp task M3-1/M3-2, after `api:sync`. See open question 4 for the `api:check` drift window.
- Not `/api/v1/public`, so `PUBLIC_PAGE_API.md` doesn't change.

## i18n impact
None in api: keys and params only. The M3 webapp tasks must add EN/ES/FR/UK copy for:
- the 422 codes (`required, too_long, unknown, too_many, duplicate, past, beyond_horizon, must_be_positive, out_of_range`);
- `researchStatus` values;
- `not_checked` reasons.

## Blast radius
- **Ledger (never-cuttable, feeds calibration):**
  - `findJoinable` gets an extra predicate. PRE_PUBLISH and REFORECAST rows are unaffected; `PredictionScoringJobTest` stays green.
  - New `recordDateCheck`; `RecordCommand` callers are untouched.
- **Shared `RateLimitConfig`:** an additive bucket. A missing yaml key would fail boot, and `RateLimitBucketCoverageTest` catches that.
- **Auth and org scoping:** every endpoint takes `@CurrentUser AuthPrincipal`, gates first and 404s cross-org. No new `SecurityConfig` permit.
- **No Flyway migration, no money or Stripe, no AI spend** (the sync path makes no LLM call and uses no quota).
- **Default-off in prod:** with the flag false the endpoints are inert, and `all-orgs` also defaults to false.

## Risks
- **Size (about 22 files) → split proposed:**
  - M1-8a (gate `all-orgs`, ledger method + `findJoinable`, rate-limit bucket; about 7 files, no M1-7 dependency, can ship now);
  - M1-8b (controller, service, validator, DTOs, tests; after M1-7).
- **Upstream drift:** M1-6 and M1-7 are only plans today. Step 0 re-verifies; adapt M1-8 to their merged shape and don't edit them.
- **PATCH concurrency:** without the row lock, two PATCHes would race on `uq_date_check_date` and return 500. `findLockedById` (PESSIMISTIC_WRITE, which H2 also supports) serialises them.
- **Breakdown recomputed at read time** from stored strength×weight and the current `maxPointsPerFinding`. If a later bank changes the cap, old checks' breakdown can disagree with the stored `riskScore`. Stored scores stay authoritative; M3 shows the stored score.
- **The 202 path is deferred:** spec §4's "202 {id}" is not reachable until M2-4. The webapp must treat 200 as the only M1-8 success.
- **Validation is duplicated** (server here, client in M3-2); the field codes are the shared contract.

## Definition of done
- Every test listed is green, including the 9 programme tests (`betaOffIs404`, `otherOrgCheckIs404`, `duplicateDatesAre422`, `pastDateIs422`, `sixDatesAre422`, `syncCheckReturnsRankedDates`, `ledgerRowWrittenBeforeResponse`, `patchAssumptionsRescoresWithoutResearch`, `responseHasNoRenderedStrings`).
- `./mvnw test` is green in the worktree, and the base was green first.
- `DateCheckResponse` appears in the locally generated `/v3/api-docs.yaml`.
- The `PREDICTOR_DATE_CHECK_ALL_ORGS` default is false.
- Nothing is committed by the worker.

## Live-test evidence
(filled by /live-test)

## Review rounds
(filled by review)

Key files read: /Users/ivan/imin/imin-api (origin/master) `predictor/config/DateCheckAccess.java`, `DateCheckProperties.java`, `predictor/repository/PredictionLedgerRepository.java`, `predictor/service/PredictionLedgerService.java`, `security/GlobalExceptionHandler.java`, `config/RateLimitConfig.java`, `predictor/rules/QuestionBank.java`, `V162__predictor_date_check.sql`; the M1-6 and M1-7 plans were taken from the handback messages in the two agent transcripts.

## Decisions (main session, 2026-09-30)
- Split accepted: THIS task is M1-8a only (plan's "M1-8a (gate + ledger)" files and ordered steps 1–3 + their tests: DateCheckAccessTest allOrgs*, PredictorPagedScanOrderTest.findJoinableSkipsDateCheckRowEvenWithFinalizedEvent, PredictionLedgerServiceTest.recordDateCheckStampsSurfaceVersionAndNullEvent, RateLimitBucketCoverageTest). M1-8b (controller/service/validator/DTOs) is a later task after M1-7.
- `country` optional with org fallback (M1-8b). Postal code: M1-8b adds an optional `postalCode` request field and a `date_check.postal_code` column (small migration) so school zones resolve for small towns.
- Ledger stage: 0 with modelId "rules/date-check" unless the code maps stage values to meanings somewhere that would misreport — check and state.
- After M1-8b ships, /ship-imin does a sync-only webapp commit (api:sync) so api:check stays green.
- all-orgs default false; env PREDICTOR_DATE_CHECK_ALL_ORGS; document in imin-api CLAUDE.md next to the other PREDICTOR_DATE_CHECK_* bullet.

## Review rounds
round 1 → PASS (2 LOW fixed in main session: CLAUDE.md tense, null-output test)
