# M1-6 Rule engine (calendar + internal + organizer)
m1-6-rule-engine · feature · Notion: (main session fills)

## Goal and scope
Turn the M1-5 question bank into findings for one candidate date. `RuleEngine.evaluate(DateCheckInput, LocalDate) -> List<Finding>` returns one `Finding` per applicable (question id, source): `found`, `clear` or `not_checked`. The engine is deterministic, uses no LLM, and runs synchronously.
Out of scope: scoring, coverage, verdict and actions (M1-7), persistence and endpoint (M1-8), web questions (M2), and the M1-4b data (OpenHolidays, diaspora and ad-period YAML, football).
**Decision:** questions that need M1-4b data (3.2, 4.5, 5.1, 10.3) are *emitted* as `not_checked` with `facts.reason="no_source"`, not omitted. Coverage needs these rows (Review Focus 2), and M1-4b replaces the stub with a rule.
Base: `origin/master` **after M1-4a and M1-5 merge**. Today neither is on master; the M1-4a worktree holds only its plan. Signatures below come from their plans and are re-verified in step 0.

## Repos in ship order
1. api (`imin-api`, base `master`). Api-only; ships alone.

## Affected files (per repo)
api, under `src/main/java/com/imin/iminapi/`:
- Create `predictor/rules/DateCheckInput.java`: record `(String city, String country, String postalCode, Double venueLat, Double venueLng, String genreFamily, String subGenre, Integer capacity, Long priceMinor, String format, Integer startHour, Integer endHour, List<String> lineup, List<KnownEvent> knownEvents, UUID orgId, LocalDate today)`.
  - Nested `KnownEvent(String name, LocalDate date, String venue, int strength)`.
  - Compact constructor: country upper-cased; `lineup` null → `List.of()`; `knownEvents` null stays null (meaning "not provided"); `strength` must be 1..2.
  - Helpers `ZoneId zone()` (`CountryTimeZones.zoneFor(country)`, else UTC) and `String cityKey()` (`EventNormalization.cityKey`).
- Create `predictor/rules/Finding.java`: record `(String questionId, Kind kind, Status status, int strength, int weight, SourceKind sourceKind, Window window, boolean stopFactor, Map<String,Object> facts, String url, String quote, Instant fetchedAt)`.
  - `enum Status {FOUND, CLEAR, NOT_CHECKED}`.
  - Factories `found(Question, Kind, int strength, Map, String url)`, `clear(Question)`, `notChecked(Question, String reason)`.
  - Invariants enforced in the factories: `strength` is clamped to `q.maxStrength()`; `clear`/`not_checked` have strength 0; `stopFactor = q.stopFactor() && status==FOUND && source ∈ {STRUCTURED, INTERNAL}`; the default kind of a two-kind question's clear row is RISK.
  - These mirror the V162 CHECKs `ck_date_check_finding_soft_strength` and `ck_date_check_finding_stop_source`.
- Create `predictor/rules/QuestionEvaluator.java` (interface: `SourceKind source(); Set<String> questionIds(); Finding evaluate(Question, DateCheckInput, LocalDate)`).
- Create `predictor/rules/RuleEngine.java` (`@Service`).
- Create `predictor/rules/CalendarEvaluator.java`, `InternalEvaluator.java`, `OrganizerEvaluator.java` and `InputEvaluator.java` (`@Component`).
- Create `predictor/rules/NightDates.java`: `NIGHT_ROLLOVER_HOUR = 6`; `LocalDate nightOf(Instant, ZoneId)` (local date, minus one day when the local hour is < 6); `Instant nightStart(LocalDate, ZoneId)` = local 06:00.
- Modify `predictor/service/CompetingNightsService.java`: add `record CityEvent(UUID id, UUID orgId, String name, String description, String genreKey, String subGenre, Instant startsAt, String venueName)` and `List<CityEvent> between(String cityKey, Instant from, Instant to)`. `compute(Event)` is untouched.
- Modify `repository/EventRepository.java`, adding three JPQL queries with no nullable String params (H2/PG trap):
  - `findCityEventsBetween(cityKey, from, to)`: published, status IN (LIVE, PAST), not deleted;
  - `findOrgEventsInCityBetween(orgId, cityKey, from, to)`: any status except CANCELLED, including DRAFT, not deleted, `startsAt` set;
  - `existsPublishedInCitySince(cityKey, since)`.
- Modify `src/main/resources/predictor/question-bank-v2.yaml`: `params` on 2.9 `{sell_out_opp_min: 0.4, sell_out_risk_max: 0.05, min_events: 5}` and on 10.2 `{missed_share_min: 0.25}`. Template keys are unchanged.
- Conditional: `predictor/calendar/CalendarHit.java` + `ReferenceCalendarService`: add `String region` if M1-4a didn't ship it.
- Tests: see Test impact. Total about 19 files.

## Ordered steps
0. **Rebase and verify dependencies.**
   - Confirm on `origin/master`: `QuestionBank.Question` has `(id, …, source, kinds, weight, maxStrength, window, stopFactor, countries, cities, params, …)`; `ReferenceCalendarService.between(LocalDate, LocalDate, CalendarPlace)`, `on(...)` and `covers(String country, String kind, LocalDate)`; `CalendarRegions.of(country, postalCode, city)`; `CalendarHit`.
   - Quote any signature drift in the plan. Add `region` to `CalendarHit` if it is absent.
   - Run `./mvnw test` on the untouched base.
1. **Records** `DateCheckInput`, `Finding`, `NightDates`.
2. **RuleEngine.**
   - The constructor takes `QuestionBank` and `List<QuestionEvaluator>`.
   - It builds a `(SourceKind, id) → evaluator` map and **throws at boot** if any non-WEB bank question has no evaluator, or two evaluators claim the same question.
   - `evaluate(in, d)`: requires `!d.isBefore(in.today())`. For each `q` in `bank.questionsFor(in.country())` (bank order): skip WEB; skip when `q.cities()` is non-empty and `in.cityKey()` is not in it (bank cities pass through `cityKey`); otherwise call the evaluator.
   - Returns an immutable list.
3. **InputEvaluator (10.1).**
   - `lead = DAYS.between(today, d)`.
   - `min = capacity != null && capacity >= big_capacity_min ? big_min_days : small_min_days`.
   - If `lead < min`: FOUND with strength 3 when `lead < min/2`, else 2. Facts: `{leadDays, minDays, capacityKnown}`.
   - Otherwise CLEAR. A null capacity uses the small threshold and sets `capacityKnown=false`.
4. **CalendarEvaluator** (source STRUCTURED). `place = CalendarRegions.of(...)`. Hits come from `service.between(d-7, d+7, place)` (one call, filtered per question).
   - **Coverage rule.** Not covered means NOT_CHECKED with `reason=no_data`. Holiday coverage is `service.covers(c,"holiday",d) || PublicHolidayCalendar.covers(c,d)`.
   - **Hit dedupe.** Collapse repeated rows (same fact stored for three départements) by `(kind, name, date)`. One row per question per date, with facts `{date, name, count}` of the nearest hit.
   - **Strength.** 3 if a hit covers `d` itself, else 2. An approximate hit (Hijri) is capped at 2 and gets `facts.approximate=true`.
   - **Per-question rules:**
     - 4.1: kind `holiday`, region `""`, overlapping d±7 → risk.
     - 4.2: `holiday|pont` on d+1 → opportunity; `holiday` on d with d+1 a working day → risk.
     - 4.3: `pont` on d or d+1 → risk. Covered = `covers(c,"pont",d)`.
     - 4.4: `holiday` with region = place holiday region, d±1 → risk. No regional region for the place → CLEAR if national coverage exists.
     - 4.7: `dst` on d. `dst_back` → opportunity; `dst_forward` → risk.
     - 5.2: `hijri` named `ramadan`, overlapping d±7 → risk. Covered = `covers(c,"hijri",d)`.
     - 7.1: `school` for the place's school zone, overlapping d±7 → risk. Covered = a zone exists for the place and `covers(c,"school",d)`, so UA and Corsica are NOT_CHECKED.
     - 3.2, 4.5, 5.1, 10.3: NOT_CHECKED with `reason=no_source` until M1-4b.
5. **InternalEvaluator** (source INTERNAL). Windows are on night dates in `in.zone()`: night = d±1, week = d±7, 2.7 = d±14. Query bounds are `nightStart(d-k)` to `nightStart(d+k+1)`.
   - **2.1 and 2.2:**
     - No published event in the city in the last 365 days → NOT_CHECKED with `reason=no_imin_events_in_city`.
     - Candidates are `CompetingNightsService.between`, excluding `in.orgId()`, with genre key = `EventNormalization.genreKey(in.genreFamily())`.
     - 2.1 fires when `|nightOf−d| ≤ 1`: strength 3 when Δ=0, else 2.
     - 2.2 fires when `1 < |Δ| ≤ 7`, so one event never scores in both: strength 2, or 3 when the sub-genre also matches.
     - Facts: `{date, name, venue, count}` of the nearest event. These are public listings.
   - **2.7:** `findOrgEventsInCityBetween` for d±14.
     - Strength 2; 3 when a lineup name (trimmed, ≥3 chars, case-insensitive, whole word) appears in the event's name or description.
     - Facts: `{date, name, sharedArtists}`. None → CLEAR.
   - **2.9:** `ComparableCorpusService.retrieve(orgId, city, country, genreFamily, CapacityBand.of(capacity), Season.of(nightStart(d), zone))`.
     - Sample `n` = own count + foreign aggregate count (the foreign part counts only when not suppressed). `n < min_events` or a null band → NOT_CHECKED.
     - Rate = pooled sell-out rate. `≥ opp_min` → opportunity, strength 2; `≤ risk_max` → risk, strength 2; otherwise CLEAR.
     - Facts: `{sellOutRate (rounded 0.05), n, relaxation}`. Foreign names are never exposed (the service already enforces this).
   - **10.2:** `PacingCurveService.lookup(city, country, genreFamily, band, season)`; empty → NOT_CHECKED.
     - `lead = days(today, d)`. `share` = `medianPct` of the point with the smallest `daysOut ≥ lead`, else 0.
     - `share ≥ missed_share_min` → risk FOUND, strength 3 if `share ≥ 0.5`, else 2. Facts: `{leadDays, soldShareBefore}`.
6. **OrganizerEvaluator** (2.1 and 2.2, source ORGANIZER).
   - `knownEvents == null` → NOT_CHECKED with `reason=not_provided`; empty list → CLEAR.
   - Otherwise events with `|date−d| ≤ 1` (2.1) or `1 < |Δ| ≤ 7` (2.2) → FOUND at `max(strength)`, clamped to 2. `stopFactor` is always false.
   - Facts: `{date, name, venue}` of the strongest match. The names stay in this org's rows only.
7. Add the YAML params and rerun `QuestionBankTest` (`templateKeysFileIsCurrent` must stay green).
8. Run `./mvnw test`.

## Verification commands
- `cd /Users/ivan/imin/imin-api/.claude/worktrees/m1-6-rule-engine && ./mvnw test` (run once on the untouched base first).
- Targeted: `./mvnw test -Dtest='RuleEngineTest,CalendarEvaluatorTest,InternalEvaluatorTest,OrganizerEvaluatorTest,InputEvaluatorTest,NightDatesTest,CompetingNightsServiceTest,QuestionBankTest'`.

## Test impact
All new files are under `src/test/java/com/imin/iminapi/predictor/rules/` unless noted. Branch map first; one test per branch.
- `RuleEngineTest` (plain JUnit, stub evaluators, bank built via `QuestionBankLoader.parse`):
  - `webQuestionsSkipped`, `cityScopedQuestionOnlyForListedCity` (4.5 Metz yes, Paris no), `countryFilterApplied`;
  - `missingEvaluatorFailsConstruction`, `duplicateEvaluatorFailsConstruction`, `everyShippedQuestionHasAnEvaluator` (real bank + real evaluator beans' ids);
  - `pastCandidateRejected`, `oneRowPerIdAndSource` (2.1 appears twice: internal + organizer).
- `FindingTest`: `clearAndNotCheckedHaveZeroStrength`, `strengthClampedToMaxStrength`, `stopFactorNeverOnOrganizerOrWeb`.
- `NightDatesTest`:
  - `nightWindowCrossesMidnightLocal`: 2026-10-17T23:30Z is Paris 18 Oct 01:30, which maps to night 17 Oct. A naive UTC date is also 17, so a second case is needed: 2026-10-18T22:30Z (Paris 00:30 on 19 Oct) maps to night 18 Oct;
  - `autumnDstNight2026EventMapsToItsNight` (2026-10-25T01:30Z maps to night 24 Oct);
  - `springDstNight2027EventMapsToItsNight`;
  - `kyivUsesItsOwnZone`.
- `CalendarEvaluatorTest` (Mockito mock of `ReferenceCalendarService`):
  - 4.1: `holidayInWeekFound`, `holidayOnDayStrength3`, `noCoverageNotChecked`, `fallbackCoverageCountsAsChecked` (UA);
  - 4.2: `eveOfHolidayOpportunity`, `holidayBeforeWorkdayRisk`, `neitherClear`;
  - 4.3: `pontFound`, `noPontCoverageNotChecked`;
  - 4.4: `metzRegionalHolidayFound`, `parisGetsClear`;
  - 4.7: `dstBackOpportunity`, `dstForwardRisk`;
  - 5.2: `ramadanFoundApproximateCappedAt2`;
  - 7.1: `schoolHolidayFound`, `uaCityHasNoSchoolDataSoNotChecked`, `corsicaNoZoneNotChecked`;
  - `m14bQuestionsNotCheckedWithReason` (3.2, 4.5, 5.1, 10.3);
  - `sameFactHitsAllFiveCandidateDatesOnce`: one school range spanning 5 candidates, stored for 3 régions. Each date has exactly one 7.1 FOUND row, facts name the same holiday, and `count=1`.
- `InternalEvaluatorTest` (`@SpringBootTest` + H2, the `CompetingNightsServiceTest` style):
  - 2.1/2.2: `sameNightSameGenreStrength3`, `adjacentNightStrength2`, `weekEventOnlyIn22`, `otherGenreClear`, `ownOrgExcludedFrom21`, `cancelledAndDraftIgnored`, `cityWithNoIminEventsNotChecked`;
  - 2.7: `ownEventWithin14Found`, `ownEventWithSharedHeadlinerRaisesStrength`, `ownEventDay15Clear`, `ownCancelledIgnored`;
  - 2.9: `comparablesSellOutOpportunity`, `lowSellOutRisk`, `middleClear`, `nullCapacityNotChecked`, `underMinEventsNotChecked`;
  - 10.2: `noCurveNotChecked`, `shortLeadMissesBuyersRisk`, `longLeadClear`.
- `OrganizerEvaluatorTest`: `nullListNotChecked`, `emptyListClear`, `sameNightFound21`, `weekFound22Not21`, `knownEventNeverStopFactor` (with a bank row forcing `stop_factor` absent and strength 3 input rejected), `strengthIsMaxOfMatches`.
- `InputEvaluatorTest`: `shortLeadSmallEventFound`, `veryShortLeadStrength3`, `bigCapacityUses60`, `nullCapacityUsesSmallThreshold`, `enoughLeadClear`.
- Modify `src/test/java/com/imin/iminapi/predictor/CompetingNightsServiceTest.java`: `betweenReturnsPublishedCityEventsOnly`. The existing `compute` tests stay unchanged.

## Live-test
None to click: no endpoint until M1-8. After deploy, the Railway boot log should show no `RuleEngine` wiring error, and `/actuator/health` should be UP. The engine is inert until M1-8.

## Contract impact
none. There is no DTO or endpoint. Facts keys (`date, name, venue, count, leadDays, minDays, capacityKnown, sharedArtists, sellOutRate, n, relaxation, soldShareBefore, approximate, reason`) become template params in M3-1; list them in the `Finding` javadoc.

## i18n impact
none in this repo; the API returns keys and facts. The `reason` values (`no_data`, `no_source`, `no_imin_events_in_city`, `not_provided`) need EN/ES/FR/UK copy in M3.

## Blast radius
- `EventRepository` is shared: three additive queries only; the existing ones are unchanged. `CompetingNightsService.compute` (Stage-0, live) is untouched.
- `QuestionBank` bean plus new `RuleEngine` boot validation: a missing evaluator fails startup of the whole API. This is intended, and `everyShippedQuestionHasAnEvaluator` catches it in CI.
- The YAML params edit is read at boot; a typo fails boot, which `loadsShippedBank` catches.
- No Flyway migration, no money, auth or Stripe, no `/api/v1` change. Nothing runs at request time until M1-8.

## Risks
- **Size:** about 19 files. It is one concern (the rules), but it could split into M1-6a (records, engine, input, organizer, calendar) and M1-6b (internal evaluator + repo queries) if review load matters.
- **Dependencies unmerged:** the M1-4a signatures are from its plan only. Step 0 re-verifies; `CalendarHit.region` may be missing.
- **Invented thresholds** (2.9, 10.2, the Δ-based strengths) are estimates, kept in YAML params where possible for M0-6.
- **Lineup overlap** is a text match on name/description (no lineup column), so false negatives are likely.
- **Night rollover at 06:00** is a code constant. An afternoon event and a night event on the same local date map to the same night, which is intended.
- **H2 vs PG:** the new queries take `cityKey` non-null (callers pass a normalised key), plus UUID and Instant params only.

## Definition of done
- Every test above is green, including the 5 programme tests (`sameFactHitsAllFiveCandidateDatesOnce`, `nightWindowCrossesMidnightLocal`, `uaCityHasNoSchoolDataSoNotChecked`, `knownEventNeverStopFactor`, `ownEventWithSharedHeadlinerRaisesStrength`).
- `./mvnw test` is green in the worktree, and the base was green first.
- Every shipped non-web question has an evaluator, enforced at boot.
- No `Finding` can violate a V162 CHECK.
- Nothing committed by the worker.

## Live-test evidence
(filled by /live-test)

## Review rounds
- round 1 → FIX_REQUIRED (1 HIGH private-event leak, 2 MEDIUM, 4 LOW — all fixed)
- round 2 → PASS (2 LOW fixed: 4.3 holiday coverage, year-crossing tests)

## Decisions (main session, 2026-09-30)
- 2.9 and 10.2 thresholds are estimates in YAML params, flagged for product review.
- knownEvents: null = not_checked (not provided); [] = clear (organizer said none).
- Lineup overlap for 2.7 is a text match on name/description for now.
- CalendarHit.region already shipped in M1-4a — skip the conditional step.
- DateCheckInput ALSO gets nullable `List<Integer> audienceAge, List<String> communities, Integer buyingLeadDays` now (so M1-7 doesn't churn it). communities null = not provided, [] = organizer's explicit empty.
- Drop a national pont when the place's region has its own holiday that day (e.g. 26 Dec in FR-57/67/68): the 4.3 pont finding must not fire on a regional holiday date for that region.
- One task, no split.
