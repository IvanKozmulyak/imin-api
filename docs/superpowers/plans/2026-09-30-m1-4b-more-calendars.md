# M1-4b: OpenHolidays, diaspora and ad-period tables, football fixtures, and the four missing calendar rules
m1-4b-more-calendars · /do-task · Notion: <card id not provided>

## Goal and scope
- **OpenHolidays.** Public holidays for LU, DE, BE, CH, ES, PT and NL (ODbL) go into `reference_calendar`. NL moves from the static fallback to synced rows automatically, because the service prefers synced rows for any country-year that has them. `LuxembourgSync` is dropped.
- **Static tables.** `predictor/diaspora-days.yaml` and `predictor/ad-periods.yaml` are loaded and validated at startup and read at query time as kinds `diaspora` and `ad_period`. They are never stored, and V162 `ck_reference_calendar_kind` stays unchanged.
- **Football.** Ligue 1 (FL1) and Champions League (CL) fixtures from football-data.org v4, stored as kind `fixture` (already allowed by V162). The source is off unless `FOOTBALL_DATA_API_KEY` is set.
- **Rules.** Replace `CalendarEvaluator.NO_SOURCE_YET` with real rules for 3.2 (football), 4.5 (neighbour holidays for border cities), 5.1 (diaspora national days) and 10.3 (ad periods).
- **`endDate` in facts.** Calendar findings emit `endDate` for ranges (handoff §7 decision 3). This covers school (7.1), Ramadan (5.2), multi-day holidays and ad periods.
- **Out of scope:**
  - OpenHolidays school holidays for 4.5;
  - DE/ES ponts;
  - DED, BL1 and PD football, so 3.2 answers `no_source` for NL, DE and ES;
  - Stage-0, which still reads `PublicHolidayCalendar` per CLAUDE.md;
  - the `sources.yaml` entries, because M2-7 is not on master.

## Repos in ship order
1. `api` (imin-api, `master`). Worktree: `/Users/ivan/imin/imin-api/.claude/worktrees/m1-4b-more-calendars`.

## Affected files (per repo)
api. Paths are under `src/main/java/com/imin/iminapi/predictor/` unless shown otherwise.

New files:
- `calendar/OpenHolidaysSync.java`
- `calendar/FootballFixturesSync.java`
- `calendar/StaticCalendarTables.java`
- `src/main/resources/predictor/diaspora-days.yaml`
- `src/main/resources/predictor/ad-periods.yaml`

Modified files:
- `calendar/CalendarConfig.java`: two beans; football gets its own client with the key header.
- `calendar/CalendarSyncProperties.java`: `footballDataApiKey`.
- `calendar/CalendarHit.java`: new constant `STATIC = "static"`.
- `calendar/CalendarSource.java`: `default String scopePrefix() { return null; }`.
- `calendar/CalendarRegions.java`: `BORDER_NEIGHBOURS` and `neighbours(cityKey)`.
- `calendar/ComputedCalendar.java`: `ZONES` gains NL, DE and ES (the comment there already says countries join when their holiday source lands).
- `calendar/ReferenceCalendarJob.java`: a startup seed per source.
- `calendar/ReferenceCalendarService.java`: `latest(country, kind)`.
- `repository/ReferenceCalendarEntryRepository.java`:
  - `existsBySourceUrlStartingWith(String)`;
  - `Optional<ReferenceCalendarEntry> findTopByCountryAndKindOrderByCalendarDateDesc(String, String)`.
- `rules/CalendarEvaluator.java`
- `rules/Finding.java`: Javadoc facts list only.
- `src/main/resources/application.yaml`: `football-data-api-key: ${FOOTBALL_DATA_API_KEY:}`.
- `CLAUDE.md`: the `PREDICTOR_CALENDAR_SYNC_ENABLED` line gains the new sources, the env var and the credits.

Tests (under `src/test/java/com/imin/iminapi/predictor/`):
- New: `calendar/OpenHolidaysSyncTest.java`, `calendar/FootballFixturesSyncTest.java`, `calendar/StaticCalendarTablesTest.java`.
- Modified: `rules/CalendarEvaluatorTest.java`, `calendar/CalendarRegionsTest.java`, `calendar/ReferenceCalendarJobTest.java`, `calendar/ComputedCalendarTest.java`, `calendar/ReferenceCalendarServiceTest.java`, `calendar/CalendarSyncPropertiesTest.java`.
- New fixtures under `src/test/resources/predictor/calendar/`: `openholidays-LU-2026.json`, `openholidays-DE-2026.json`, `openholidays-CH-2026.json`, `football-FL1.json`, `football-CL.json`.

That is about 32 files. See Risks for the proposed split.

## Ordered steps
Run `./mvnw test` on the untouched worktree first. A red base is its own card.

**Phase A: OpenHolidays, 4.5 and `endDate`**
1. Record live fixtures (read-only curl, then trim to the rows the tests need):
   - `https://openholidaysapi.org/PublicHolidays?countryIsoCode={LU|DE|CH}&languageIsoCode=EN&validFrom=2026-01-01&validTo=2026-12-31`;
   - from the recorded DE and CH data, confirm the subdivision codes used by `BORDER_NEIGHBOURS` (expected `DE-SL`, `DE-RP`, `DE-BW`, `CH-BS`, `CH-BL`) and adjust the map to the real codes.
2. `OpenHolidaysSync implements CalendarSource`:
   - key `openholidays`, `scopePrefix` `https://openholidaysapi.org/PublicHolidays?`;
   - `COUNTRIES = List.of("LU","DE","BE","CH","ES","PT","NL")`;
   - one GET, and so one batch, per country and year for `today.year .. +yearsAhead`, kinds `{holiday}`, with the URL as `source_url`.
   Row mapping:
   - `nationwide=true` gives region `''`;
   - otherwise one row per `subdivisions[].code`;
   - a code over 16 characters (V162 `region VARCHAR(16)`) is skipped and counted in a WARN;
   - a non-nationwide row with no subdivisions is skipped and counted;
   - name is `name[]` where `language=EN`, else the first entry;
   - `endDate` is set when the holiday lasts more than one day.
   Failure handling:
   - a failed call, non-array body or empty result gives no batch for that country-year, so stored rows stay;
   - the fetch logs and never throws, matching `FrenchHolidaySync.get`.
3. `ReferenceCalendarJob.onStartup` runs when `count()==0`, or when any source with a non-null `scopePrefix()` has `!existsBySourceUrlStartingWith(prefix)`. Without this, prod (already seeded by M1-4a) would wait until Sunday. Add `ComputedCalendar.ZONES` entries NL `Europe/Amsterdam`, DE `Europe/Berlin` and ES `Europe/Madrid`.
4. `CalendarRegions`:
   - add a static `BORDER_NEIGHBOURS` map from city key to `Map<country, List<region>>`:
     - metz → LU `['']`, DE `[DE-SL, DE-RP]`;
     - thionville → LU `['']`, DE `[DE-SL]`;
     - strasbourg → DE `[DE-BW]`;
     - mulhouse → DE `[DE-BW]`, CH `[CH-BS, CH-BL]`;
     - lille → BE `['']`;
   - the draft list comes from Bohdan B5 option A;
   - add `neighbours(String city)`;
   - a static check fails class init when a region code is over 16 characters.
5. `CalendarEvaluator` rule 4.5 (opportunity, night):
   - the question bank already limits it to FR and the listed cities;
   - no neighbours for the city gives `not_checked no_source`;
   - any neighbour country without `calendar.covers(nc,"holiday",d)` or for `d+1`'s year gives `no_data`;
   - otherwise read `between(d, d+1, new CalendarPlace(nc, region, null))` per region and `''`, filtered to kind holiday;
   - a hit covering `d+1` gives strength 3 (eve of the neighbour's day off), a hit on `d` only gives strength 2, none gives clear;
   - facts add `country`, the neighbour's ISO code.
6. `found()` adds `endDate` (ISO) when `nearest.endDate()!=null`. `withoutNulls` already drops a null. Update the Javadoc facts list in `Finding` with `endDate, country, community, competition, kickoff, estimate`.

**Phase B: static tables, 5.1 and 10.3**
7. `diaspora-days.yaml`:
   - schema: `version: 1`, `rows: [{country, date: "MM-DD", name, source}]`;
   - starter rows from Bohdan's tech spec §6: BW 09-30, NG 10-01, GN 10-02, SN 04-04, CI 08-07, CM 05-20, CD 06-30, ML 09-22, UA 08-24;
   - `name` is the stable key `independence_day` (copy is rendered in M3);
   - `source` is a real https page (the Wikipedia "Public holidays in X" page), which the worker checks with `curl -sI`;
   - add a header comment: "pending product review".
8. `ad-periods.yaml`:
   - schema: `rows: [{name, countries[], from: "MM-DD", to: "MM-DD"} | {name, countries[], rule}]`;
   - `december` is 12-01..12-31 for FR, NL, DE, ES and UA;
   - `black_friday_week` has rule `black_friday_week` (Monday before Black Friday to Cyber Monday; Black Friday is the day after the 4th Thursday of November) for FR, NL, DE and ES;
   - `summer_sales` has rule `fr_summer_sales` (last Wednesday of June plus 27 days) for FR only;
   - there is no source URL, and hits carry `estimate: true`;
   - same "pending product review" header.
9. `StaticCalendarTables` (`@Component`):
   - load with `new Yaml(new SafeConstructor(new LoaderOptions()))`, as `QuestionBankLoader` does;
   - boot fails with an `IllegalStateException` naming the row on: a country not in `Locale.getISOCountries()`, a bad or `02-29` MM-DD, `from > to` (no wrap), an unknown rule, a non-https source, or a duplicate (country, name);
   - API: `diaspora(Set<String> countries, from, to)` and `adPeriods(country, from, to)`, returning `CalendarHit`s per year in range with kinds `diaspora`/`ad_period`, origin `static`, and region = the community code for diaspora hits.
   - Design note: the evaluator reads these directly and does not merge them in `ReferenceCalendarService.between` as M1-4a sketched. `between` stays DB and fallback only, so no other caller sees new kinds.
10. Rule 5.1 (window week):
    - communities are `in.communities()`, or the `QuestionBank` profile for `in.genreFamily()` when that is null. The evaluator gets `QuestionBank` injected;
    - drop the venue's own country, since that day is already a public holiday under 4.1;
    - input `[]` gives clear, because the organizer said none;
    - the profile is empty or unknown gives `not_checked no_communities`;
    - no community appears in the table gives `no_data`;
    - otherwise hits in d±7 give OPPORTUNITY: strength 3 on `d`, 2 in the week;
    - facts add `community`; `url` is the row source.
11. Rule 10.3 (window month):
    - promo window `[d-28, d]` overlapping an ad period of the venue country gives RISK;
    - strength 2 when `d` is inside the period, else 1; the strength is capped at 2 because the rows are estimates;
    - a country with no rows gives `no_data`;
    - facts carry `date`, `endDate`, `name` and `estimate: true`.

**Phase C: football and 3.2**
12. Properties and client:
    - `CalendarSyncProperties.footballDataApiKey` (String; blank means off);
    - the `CalendarConfig` bean builds its own `RestClient` from the same factory with `X-Auth-Token`, and only when the key is non-blank;
    - the key never goes into a URL or a log line.
13. `FootballFixturesSync`:
    - key `football-data`; `scopePrefix` is `https://api.football-data.org/v4/competitions/` when the key is set, else null;
    - a blank key gives `fetch` → `List.of()` with no call;
    - `GET .../competitions/FL1/matches` first (current season, no date filter, which avoids the dateFrom/dateTo range limit), then CL; a failed FL1 fetch skips CL, because CL needs FL1's team ids.
    Row mapping:
    - `country FR`, `region ''`, kind `fixture`;
    - `date` = `utcDate` in Europe/Paris minus the 06:00 night rollover (`NightDates.NIGHT_ROLLOVER_HOUR`);
    - `name` = `"{FL1|CL} {HH:mm|TBC} {home.shortName} – {away.shortName}"`: `HH:mm` only when `status=TIMED`, `TBC` when `SCHEDULED`;
    - rows with status POSTPONED, CANCELLED, SUSPENDED or AWARDED are skipped;
    - CL rows are kept only when a team id is among FL1's team ids.
    Batching:
    - one batch per competition URL, window `[today, today+400]`, rows outside the window dropped;
    - `static FixtureName parse(String)` returns `(competition, LocalTime kickoff|null, teams)`.
14. `ReferenceCalendarService.latest(country, kind)` is backed by `findTopBy…OrderByCalendarDateDesc`.
15. Rule 3.2 (night):
    - country is not FR → `no_source`;
    - `latest("FR","fixture")` is empty or before `d` → `no_data`;
    - `d` still inside the window but no fixture covers it → clear;
    - otherwise, for each fixture: RISK when the kickoff is unknown, `startHour` is null, or kickoff+120 min > the start (a start hour below 6 counts as +24 h); else OPPORTUNITY (the match ends before doors, so screen it as a warm-up);
    - RISK wins when both kinds are present;
    - strength 3 for CL, 2 for FL1;
    - facts: `date`, `name` (teams), `competition`, `kickoff` (when known), `count`.
16. Delete `NO_SOURCE_YET` and add the four ids to `RULES`. Update the CLAUDE.md line:
    - the OpenHolidays credit "Contains data from OpenHolidays API (openholidaysapi.org), ODbL";
    - `FOOTBALL_DATA_API_KEY`, blank = off; credit "Football data provided by the Football-Data.org API";
    - the two YAML tables;
    - the per-source startup seed.
17. Run the verification commands.

## Verification commands
- `cd /Users/ivan/imin/imin-api/.claude/worktrees/m1-4b-more-calendars && ./mvnw test`. Run it on the base first.
- Targeted run: `./mvnw test -Dtest='OpenHolidaysSyncTest,FootballFixturesSyncTest,StaticCalendarTablesTest,CalendarEvaluatorTest,CalendarRegionsTest,ReferenceCalendarJobTest,ComputedCalendarTest,ReferenceCalendarServiceTest,CalendarSyncPropertiesTest,RuleEngineTest,QuestionBankTest'`

## Test impact
Branch map:
- OpenHolidays: ok / 5xx / empty / non-array; nationwide vs subdivision; code over 16 characters; no subdivisions; EN name vs fallback name; multi-day range.
- Football: key blank / set; TIMED / SCHEDULED / POSTPONED; CL with / without a French club; FL1 failing; rollover; outside the window.
- Tables: every validation failure; rule dates.
- Rule 4.5: no neighbours / uncovered / d+1 / d / none.
- Rule 5.1: input null→profile / `[]` / profile empty / uncovered / on d / within the week / own country.
- Rule 10.3: inside / overlap / none / uncovered.
- Rule 3.2: non-FR / beyond latest / none / overlap / before doors / unknown kickoff / null startHour / CL.
- Job: empty table / one prefix missing / all present / null prefix.
- `endDate`: ranged / single-day.

Tests per file:
- `OpenHolidaysSyncTest` (MockRestServiceServer, `@SpringBootTest` like `FrenchHolidaySyncTest`):
  - `luToussaint2026FromOpenHolidays`: through the service, 2026-11-01 at `CalendarPlace("LU",null,null)` is a hit whose `sourceUrl` is the LU 2026 URL;
  - `subdivisionRowsStoredPerCode`;
  - `nationwideRowStoredOnceWithEmptyRegion`;
  - `overlongSubdivisionCodeSkipped`;
  - `rowWithoutSubdivisionsSkipped`;
  - `englishNameChosen`;
  - `upstream500KeepsPreviousRows`;
  - `emptyArrayKeepsPreviousRows`;
  - `nlSyncedRowsReplaceFallback` (origin `synced`, not `fallback`).
- `FootballFixturesSyncTest`:
  - `blankKeyMakesNoCall`;
  - `keySentAsHeaderNeverInUrl`;
  - `timedMatchNameCarriesParisKickoff`;
  - `scheduledMatchNameIsTbc`;
  - `postponedSkipped`;
  - `clMatchWithoutFrenchClubSkipped`;
  - `fl1FailureSkipsCl`;
  - `lateKickoffBelongsToPreviousNight`;
  - `fixtureNameRoundTrips`.
- `StaticCalendarTablesTest`:
  - `shippedTablesLoad`;
  - `ng1OctoberDiasporaHit`;
  - `blackFridayWeek2026IsNov23To30`;
  - `frSummerSales2027StartsLastWednesdayOfJune`;
  - one `rejects…` test each for: bad country, `02-29`, from > to, unknown rule, http source, duplicate.
- `CalendarEvaluatorTest`: delete `m14bQuestionsNotCheckedWithReason` and add:
  - 4.5: `metzLuxHolidayNextDayIsOpportunity3`, `neighbourHolidayOnDayIsStrength2`, `neighbourUncoveredIsNoData`, `cityWithoutNeighboursIsNoSource`;
  - 5.1: `inputCommunityNationalDayOnDate`, `nullInputFallsBackToProfile`, `emptyInputIsClear`, `emptyProfileIsNotCheckedNoCommunities`, `uncoveredCommunityIsNoData`, `venueCountryCommunityIgnored`;
  - 10.3: `adPeriodCoveringDateIsRisk2WithEstimate`, `adPeriodOverlappingPromoWindowIsRisk1`, `noAdPeriodIsClear`;
  - 3.2: `nonFrFootballIsNoSource`, `dateBeyondLatestFixtureIsNoData`, `matchOverlappingStartIsRisk`, `matchEndingBeforeDoorsIsOpportunity`, `unknownKickoffIsRisk`, `clMatchIsStrength3`, `noMatchIsClear`;
  - `endDate`: `schoolFindingCarriesEndDate`, `ramadanFindingCarriesEndDate`, `singleDayHolidayHasNoEndDate`.
- `CalendarRegionsTest`: `everyBank45CityHasNeighbours` (loads `RuleFixtures.BANK`, so the bank stays the authority) and `neighbourRegionCodesFitColumn`.
- `ReferenceCalendarJobTest`: `startupSyncsWhenASourcePrefixHasNoRows`, `startupSkipsWhenEveryPrefixHasRows`. The existing tests stay as they are (mocks return a null prefix).
- `ComputedCalendarTest`: `dstRowsForNlDeEs`.
- `ReferenceCalendarServiceTest`: `latestReturnsNewestFixtureDate`.
- `CalendarSyncPropertiesTest`: `blankFootballKeyBindsBlank`.

## Live-test
There is no HTTP surface: the evaluator is not reachable until M1-7.

Local run, against the dev DB with M1-4a rows present, using `PREDICTOR_CALENDAR_SYNC_ENABLED=true ./mvnw spring-boot:run`, optionally with `FOOTBALL_DATA_API_KEY`:
- the log shows `ReferenceCalendarJob: done` with `failed sources []` from the startup seed;
- this query shows LU, DE, BE, CH, ES, PT and NL holiday rows (and `fixture` when the key is set): `select country, kind, count(*) from reference_calendar group by 1,2`;
- an LU 2026-11-01 row exists, and DE-SL rows exist.

After deploy, check the Railway log for the startup seed and run the same query through `railway psql`.

## Contract impact
None. No endpoint or DTO changes, and there is no OpenAPI marker. The new finding facts keys (`endDate`, `country`, `community`, `competition`, `kickoff`, `estimate`) reach the wire only with M1-7/M3-1, where the template-params test pins them.

## i18n impact
None in the api. Names are stable keys (`independence_day`, `black_friday_week`, `december`, `summer_sales`) or source text, and rendering belongs to M3.

## Blast radius
- **Dark:** `CalendarEvaluator` has no caller outside tests until M1-7. Stage-0 reads `PublicHolidayCalendar` and is unaffected.
- **`ReferenceCalendarService.between` for NL, DE and ES:** answers switch from the static fallback to synced rows. The only readers are date-check rules, which are dark.
- **Outbound traffic:** about 21 OpenHolidays GETs at the next boot (the startup seed) and weekly after that; 2 football-data GETs weekly with a key. A failure keeps stored rows and never blocks boot, because the sync runs on its own executor.
- **Table size:** a few thousand rows, from DE/CH/ES subdivisions × 3 years.
- **No migration, no Stripe or auth changes, no shared module.**
- **ODbL:** OpenHolidays rows share the `reference_calendar` table with Licence Ouverte rows. They can be separated by `source_url` prefix.

## Risks
- **Size: about 32 files, so I propose a split** that follows the phases, in ship order:
  - M1-4b-1: OpenHolidays + 4.5 + `endDate` + ZONES + startup seed;
  - M1-4b-2: YAML tables + 5.1 + 10.3;
  - M1-4b-3: football + 3.2.
  Each phase is independently green.
- **OpenHolidays subdivision codes and `groups` shape are unverified until the fixtures are recorded in step 1.** A code over 16 characters is skipped, never truncated.
- **football-data Free tier:** commercial use is unconfirmed (see the api-keys guide). The key stays unset until Ivan confirms by email.
- **Fixture names:** the `HH:mm` prefix encodes the kickoff. A time change means a new key, and the old row is deleted by scope.
- **Communities:** the 5.1 fallback to genre-profile communities follows Bohdan I4 option A (the current default). If I4 lands on C, drop the fallback.
- **Ad-period and diaspora rows are estimates or spec examples pending product review.** Ad-period findings are capped at strength 2 and flagged `estimate`.
- **M2-7 `sources.yaml` is not on master.** Whichever of M2-7 and M1-4b lands second adds two entries:
  - OpenHolidays: gate `calendar-sync`, usedFor `public_holidays`, ODbL credit;
  - football-data: a new gate `football` (sync on and key set), a new usedFor `football_fixtures`, and its credit line.
  The imin-authored YAML tables are not listed, following M2-7's rule for `PublicHolidayCalendar`.

## Definition of done
- Every test named above passes, including `luToussaint2026FromOpenHolidays`.
- `./mvnw test` is green, and the base was green before.
- `NO_SOURCE_YET` is gone. With a covered source, the 4 ids answer `found` or `clear`; without one they answer `no_data`, `no_source` or `no_communities`.
- Ranged findings carry `endDate`.
- CLAUDE.md documents the sources, the credits, `FOOTBALL_DATA_API_KEY` and the startup seed.
- The `sources.yaml` follow-up is noted on the card.
- Nothing is committed by the worker.

## Live-test evidence
(filled by /live-test)

## Review rounds
(filled by review)


## Decisions (main session, 2026-09-30)
- Split accepted: THIS task is M1-4b-1 = Phase A only (OpenHolidays sync, per-source startup seed, ComputedCalendar ZONES NL/DE/ES, CalendarRegions BORDER_NEIGHBOURS, rule 4.5, endDate in calendar facts) + its tests. Phase B (tables, 5.1, 10.3) and Phase C (football, 3.2) are later tasks.
- 3.2 risk/opportunity split confirmed (for Phase C).
- 5.1 (Phase B): organizer-provided communities ONLY — no genre-profile fallback until product answers (legal sensitivity).
- Ad periods (Phase B): estimates, strength ≤2, `estimate: true`.
- Deferred: OpenHolidays school holidays for 4.5; NL/DE/ES football; DE/ES ponts.
- sources.yaml entries: whichever of M2-7 / M1-4b lands second adds them.
