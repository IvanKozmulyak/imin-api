# M2-1b: open-events evaluator for 2.6, 5.3 and 2.3
m2-1b-open-events-evaluator · Subagent

## Goal and scope

Answer three date-check questions from the open-data tables M2-1a fills (`open_event_occurrence` V165, `genre_week_count` V162). Data reaches users only through the date check, and only while the `openagenda` / `quefaireaparis` source gates are on (both default off).

- **2.6, genre events in the week vs the city norm** (risk). Count for the candidate date's ISO week (Mon–Sun), check's genre bucket, sub-genre `''`, vs the median of the 12 ISO weeks before the current week. Fewer than 12 counted past weeks → `not_checked no_data`.
- **5.3, big community events** (risk). Stored rows with `community = true` on the night or D±1.
- **2.3, recurring genre parties** (risk), open-data part only. Same-genre listings grouped by `title_key` with a weekly, last-weekday-of-month or nth-weekday-of-month pattern of ≥ 3 nights landing on the candidate date. Out of scope: imin-history part (M2-1c), web part (M2-4).

Also in scope: `sources.yaml` entries for both sources, bank v4, template-keys file, CLAUDE.md bullet.

Not in scope (follow-ups): imin-public `local_events` usedFor label + PUBLIC_PAGE_API §28; imin-webapp copy for `predictor.q.2_6|5_3|2_3`, `predictor.qShort.*`, reason `too_far_ahead`, facts keys, `template-keys.txt` fixture sync; DATAtourisme; `lastUpdated` for open-event sources (`SourceSyncDates.java:27` reads `reference_calendar` only → no `syncPrefix`, null like Wikimedia).

Decisions: 2.3 `star: false` in v4 (coverage counts star ids, `Scorer.java:69-80`; FR today 8/10 checked, `DateCheckControllerTest.java:381-382`). All listed countries (FR, NL, DE, ES, UA) in `applies_when`; cities without a source answer `no_source` (like 3.2, 9.1).

## Repos in ship order
1. `api` (imin-api, base `master`). No migration, no OpenAPI schema/path change.

## Affected files

### main (9)
| # | File | Change |
|---|---|---|
| 1 | `predictor/rules/OpenEventsEvaluator.java` | **New** `@Component implements QuestionEvaluator`: STRUCTURED, ids {2.6, 5.3, 2.3}, `evaluateAll` shares one read per date. Constructor validates params + catalog (step 3). |
| 2 | `resources/predictor/question-bank-v2.yaml` | `version: 4`, header comment, three structured questions (step 1). |
| 3 | `resources/predictor/sources.yaml` | `openagenda` (gate `openagenda`), `quefaireaparis` (gate `quefaireaparis`), `usedFor: [local_events]`, no `syncPrefix`. |
| 4 | `predictor/sources/DataSourceCatalog.java` | `"local_events"` in `USED_FOR` (`:35-37`); `public Optional<PublicDataSource> byId(String id)` ignoring gate. |
| 5 | `predictor/sources/openevents/OpenEventCities.java` | `public Optional<City> resolve(String cityKey)`: key or alias equal to normalised key; empty for null/blank. |
| 6 | `predictor/sources/openevents/OpenEventsJob.java` | `LOOKAHEAD_DAYS` (`:47`) → `public static final`. No behaviour change. |
| 7 | `predictor/sources/openevents/OpenEventsWriter.java` | `genreKeys(String)` (`:182`) → `public static`; `RETENTION_DAYS` (`:36`) → `public`. No behaviour change. |
| 8 | `predictor/rules/Finding.java` | Javadoc facts keys (`:16-19`) gain `weekStart, norm, normWeeks, sources, source, licence, credit, pattern, weekday, ordinal, lastDate, announced, seriesCount`. |
| 9 | `CLAUDE.md` | `PREDICTOR_OPENAGENDA_ENABLED` bullet (`:53`): replace "Nothing reads these tables yet" with an `OpenEventsEvaluator` paragraph; narrow "Before flipping" to imin-public label, §28, webapp copy. Sources bullet: add the two gates. |

### tests (8)
| # | File | Change |
|---|---|---|
| 10 | `predictor/rules/OpenEventsEvaluatorTest.java` | **New**, one per branch (Test impact). |
| 11 | `predictor/rules/OpenEventsEvaluatorRepositoryTest.java` | **New** `@SpringBootTest @Transactional` H2: seed via `OpenEventsWriter`, mocked `SourceGates`, derived queries end to end. |
| 12 | `QuestionBankTest.java` | `loadsShippedBank` (`:77-79`): 20→23, distinct 18→21, `qb3-gp1`→`qb4-gp1`; new `openDataQuestionsAreNotStar`. |
| 13 | `RuleEngineTest.java` | `stubsForShippedBank()` (`:50-55`): **append** `new Stub(STRUCTURED,"2.6","5.3","2.3")` last (keeps `evaluators.remove(3)` semantics); `everyShippedQuestionHasAnEvaluator` (`:122-132`) adds real `OpenEventsEvaluator` with mocks. |
| 14 | `DataSourceCatalogTest.java` | `sourcesListIncludesEveryConfiguredSource` (`:77-98`) turns on openagenda (+`oa_pk_k`) and quefaireaparis; expected ids add `openagenda`, `quefaireaparis`. New tests per Test impact. Tests at `:189-210`, `:238-276` keep flags off. |
| 15 | `OpenEventCitiesTest.java` | `resolveMatchesKeyOrAlias`. |
| 16 | `DateCheckControllerTest.java` | `:397` adds `"2.6:source_off","5.3:source_off","2.3:source_off"`; counts at `:381-382` unchanged. |
| 17 | `test/resources/predictor/template-keys.txt` | Regenerate 53 → 59 lines (`predictor.q.2_3|2_6|5_3`, `predictor.qShort.2_3|2_6|5_3`). |

No change needed: `SourceGates.java` (gates exist `:41-44`), `OpenEventSource.java`, both repositories (queries exist, non-null params), `RuleEngine.java`, `Scorer.java`, `DateCheckService.java` (`:177,230,353`, facts as Map `:368,448`), `ActionPicker.java` (`:33`).

## Ordered steps

1. **Bank v4.** `version: 4`; header "Adds 2.6, 5.3, 2.3 from open event listings (v4); the web-sourced questions bump again." Append under `# --- open event listings (structured) ---`; every weight/param commented "estimates pending product review".
   ```yaml
   - id: "2.6"
     family: competition
     source: structured
     kinds: [risk]
     weight: 1
     window: week
     applies_when: { countries: [FR, NL, DE, ES, UA] }
     params: { busy_ratio: 1.5, strong_ratio: 2.0, min_excess: 2, max_ahead_days: 28 }
     template: predictor.q.2_6
   - id: "5.3"
     family: communities
     source: structured
     kinds: [risk]
     weight: 2
     window: night
     applies_when: { countries: [FR, NL, DE, ES, UA] }
     params: { max_ahead_days: 60 }
     template: predictor.q.5_3
   # Star once imin history answers it in every city; open data alone covers two cities.
   - id: "2.3"
     family: competition
     source: structured
     kinds: [risk]
     weight: 2
     window: night
     applies_when: { countries: [FR, NL, DE, ES, UA] }
     params: { min_occurrences: 3, weekly_max_gap_days: 28, monthly_max_gap_days: 70, max_ahead_days: 90 }
     template: predictor.q.2_3
     actions:
       - { key: predictor.a.competitor_differentiate, when: risk, due: "D-21" }
   ```
   Run `./mvnw test -Dtest=QuestionBankTest -Dpredictor.writeTemplateKeys=true`; `git diff` of `template-keys.txt` shows exactly 6 added lines.

2. **Catalog and cities.** `DataSourceCatalog`: `local_events` in `USED_FOR`, `byId`. `sources.yaml` after `wikimedia-pageviews`, each with a comment giving the date licence/credit were checked:
   - `id: openagenda`, `name: OpenAgenda (Ville de Lille, Métropole Européenne de Lille)`, `usedFor: [local_events]`, `licence: Licence Ouverte 2.0`, `licenceUrl: https://www.etalab.gouv.fr/licence-ouverte-open-licence/`, `creditLine: "Agendas : Ville de Lille, Métropole Européenne de Lille (openagenda.com), Licence Ouverte 2.0"`, `url: https://openagenda.com/`, `gate: openagenda`.
   - `id: quefaireaparis`, `name: Que Faire à Paris (opendata.paris.fr)`, `usedFor: [local_events]`, `licence: ODbL 1.0`, `licenceUrl: https://opendatacommons.org/licenses/odbl/1-0/`, `creditLine: "Que Faire à Paris : Ville de Paris (opendata.paris.fr), ODbL"`, `url: https://opendata.paris.fr/explore/dataset/que-faire-a-paris-/`, `gate: quefaireaparis`.
   - Credit lines are **unverified** until the worker reads each dataset's licence page; correct them if the page says otherwise.
   Then `OpenEventCities.resolve`, and visibility changes (files 6, 7).

3. **Constructor** (`QuestionBank`, `OpenEventCities`, `List<OpenEventSource>`, `GenreWeekCountRepository`, `OpenEventOccurrenceRepository`, `SourceGates`, `DataSourceCatalog`). Like `TrendEvaluator.java:43-74`: find each question or throw "not in the bank"; reject unknown `params.*`; bounds (throw `IllegalStateException` naming `params.<key>`): `busy_ratio` 1 < v ≤ 5; `strong_ratio` busy ≤ v ≤ 10; `min_excess` whole 1..20; `min_occurrences` whole 2..10; every `max_ahead_days` whole 1..`OpenEventsJob.LOOKAHEAD_DAYS`; `weekly_max_gap_days` whole 7..120; `monthly_max_gap_days` whole 28..120. Every `OpenEventSource` bean needs `catalog.byId(id)` with equal licence, else boot fails "open event source <id> has no sources.yaml entry" / "licence mismatch".

4. **Shared checks** per question, in order: blank `cityKey` → `not_provided`; `resolve` empty → `no_source`; any covering source gate off → `source_off`; `DAYS.between(today, d) > max_ahead_days` → `too_far_ahead`; no count row (`findTopByCityKeyOrderByUpdatedAtDesc`) → `not_synced`; its `updatedAt` local date in `in.zone()` before `today − 14` → `stale` (`STALE_DAYS = 14`). Then one `genre_week_count` read (city, `genreFamily`, `''`) over `[thisMonday − 12w, mondayOf(d)]` for 2.6/2.3, and one `open_event_occurrence` read over `[today − RETENTION_DAYS, today + LOOKAHEAD_DAYS]` for 5.3/2.3. **History** (2.6, 2.3): all 12 weeks `thisMonday−12w … thisMonday−1w` present, else `no_data`.

5. **2.6.** Candidate week row missing → `no_data`. `norm` = median of 12 (mean of 6th/7th). Strength 2 when `count ≥ strong_ratio×norm && count−norm ≥ 2×min_excess`; else 1 when `count ≥ busy_ratio×norm && count−norm ≥ min_excess`; else clear. Facts `weekStart, count, norm` (whole stays whole, .5 kept), `normWeeks: 12`, `sources` = candidate week's `sources_json` entries `{source, licence, events}` + `url`, `credit` from `catalog.byId`. Finding `url` null.

6. **5.3.** `community = true` rows with night in `[d−1, d+1]`, deduped by (titleKey, night). None → clear. Strength 2 if any on `d`, else 1. Chosen: smallest |night−d|, then title asc, then source. Facts `date, name, count, source, licence, credit` (null dropped, `Finding.java:84`). `url` = row url.

7. **2.3** (after history check). Series = rows whose `genreKeys` contains `genreFamily`, grouped by titleKey, nights distinct; `support` = nights ≠ d. First match wins:
   - **weekly**: chain of `min_occurrences` nights on d's weekday each 7 days apart; latest such night within `weekly_max_gap_days` before d, or after d.
   - **monthly_last**: d is last such weekday of its month; ≥ `min_occurrences` support nights that are last-of-month on that weekday in distinct months; latest within `monthly_max_gap_days` before d or after.
   - **monthly_nth**: same with `ordinal = (dayOfMonth−1)/7+1` equal to d's.
   Strength 2 when series has night on d (`announced: true`), else 1. Chosen: announced first, then larger count, then title asc. Facts `name` (latest row title), `pattern`, `weekday`, `ordinal` (nth only), `count`, `lastDate`, `announced`, `seriesCount`, `source`, `licence`, `credit`. `url` = latest row url. No match → clear.

8. **Docs.** Finding javadoc, CLAUDE.md (file 9). Comments ≤ 2 lines, no ticket/milestone ids.

9. **Tests** per Test impact; delete each guard once and confirm its test goes red (gate, too_far_ahead, stale, history, genre filter, D±1 edge, dedup, gap).

## Verification commands
```
docker info >/dev/null && echo docker-up
./mvnw test -Dtest=QuestionBankTest -Dpredictor.writeTemplateKeys=true && git diff --stat src/test/resources/predictor/template-keys.txt
/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest='OpenEventsEvaluator*Test,QuestionBankTest,RuleEngineTest,DataSourceCatalogTest,OpenEventCitiesTest,DateCheckControllerTest'
/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test
```
Full gate once on untouched base first. Skipped Testcontainers = red. Re-run after rebase.

## Test impact

Fixture `TODAY = 2026-09-30` (Wed, `RuleFixtures.java:16`); this Monday 2026-09-28; 12 past weeks 2026-07-06 … 2026-09-21. Each fixture asserts `getDayOfWeek()` in setup.

**`OpenEventsEvaluatorTest`** (31)
| # | Test | Forces |
|---|---|---|
| 1 | `blankCityIsNotProvided` | `" "` → not_provided |
| 2 | `cityWithoutSourceIsNoSource` | Metz → no_source; repos never called |
| 3 | `aliasResolvesToCity` | "Lomme" reads lille rows |
| 4 | `sourceGateOffIsSourceOff` | openagenda off → source_off ×3 in bank order |
| 5 | `beyondMaxAheadIsTooFarAhead` | 2.6 today+29 → too_far_ahead; +28 answered |
| 6 | `noCountsIsNotSynced` | no count row |
| 7 | `oldCountsAreStale` | updatedAt 09-15 stale; 09-16 answered |
| 8 | `fewerThan12WeeksIsNoData` | 07-06 missing → 2.6, 2.3 no_data |
| 9 | `missingCandidateWeekIsNoData` | no row for 10-05 |
| 10 | `busyWeekIsRiskStrength1` | {2,2,3,3,3,3,4,4,4,5,5,6} norm 3.5, cand 6 → risk 1, facts |
| 11 | `veryBusyWeekIsStrength2` | cand 8 |
| 12 | `belowRatioIsClear` | cand 5 |
| 13 | `zeroNormNeedsMinExcess` | zeros, cand 1 clear; 2 risk 1 |
| 14 | `sourcesFactCarriesLicenceUrlCredit` | sources[0] fields |
| 15 | `communityOnNightIsStrength2` | 10-10 → risk 2, facts, url |
| 16 | `communityNextNightIsStrength1` | 10-11 |
| 17 | `communityTwoNightsAwayIsClear` | 10-12 |
| 18 | `nonCommunityRowIgnored` | community=false on d |
| 19 | `communityDedupedAcrossSources` | count 1 |
| 20 | `weeklySeriesPredicted` | Thu 09-10/17/24, d 10-08 |
| 21 | `lastWeekdayOfMonthPredicted` | Fri 07-31, 08-28, 09-25; d 10-30 |
| 22 | `nthWeekdayOfMonthPredicted` | Sat 07-11, 08-08, 09-12; d 10-10, ordinal 2 |
| 23 | `announcedSeriesIsStrength2` | series has d |
| 24 | `twoOccurrencesIsClear` | |
| 25 | `everyOtherWeekIsNotWeekly` | 14-day steps |
| 26 | `staleSeriesBeyondGapIsClear` | chain ends 08-20, d 10-08 |
| 27 | `otherGenreSeriesIgnored` | pop vs house & techno |
| 28 | `seriesDedupedByTitleKeyAcrossSources` | count 3 |
| 29 | `unknownParamFailsBoot` | params.foo |
| 30 | `maxAheadOverLookaheadFailsBoot` | 121 |
| 31 | `sourceWithoutCatalogEntryFailsBoot` | missing entry; licence mismatch |

**`OpenEventsEvaluatorRepositoryTest`**: `answersFromStoredRows` (2.6, 5.3, 2.3 found for Lille on H2 via `replaceFuture` + `recount`).

**Edited**: QuestionBankTest, RuleEngineTest, DataSourceCatalogTest (+ `openEventSourcesListedOnlyWhileGateOnWithCredit`, `byIdIgnoresGate`, `openAgendaCreditNamesEveryAgenda`), OpenEventCitiesTest, DateCheckControllerTest, template-keys.txt. Other Spring predictor tests checked unaffected.

## Live-test
Flags off in prod: `/v3/api-docs.yaml` unchanged; `/api/v1/public/predictor/sources` lists neither new id; a Paris check returns `qb4-gp1` and `2.6/5.3/2.3: source_off`.

## Contract impact
None at schema/path level. Value-level: question ids, templateKeys, reason `too_far_ahead`, facts keys, `local_events` sources entries (gates on only). PUBLIC_PAGE_API §28 moves before a gate flips (follow-up).

## i18n impact / Copy ledger
API renders no strings. Until the webapp follow-up ships, webapp shows:
| String | Field | Meaning | Next to |
|---|---|---|---|
| `notChecked.reason.source_off` (`copy.ts:3404`) | step 4.3 | city has source, gate off | `findings.unnamed` "Another check" (`:3395`) |
| `notChecked.reason.no_source` (`:3403`) | 4.2 | city not in yaml | same |
| `notChecked.reason.not_provided` (`:3407`) | 4.1 | blank city | same |
| `notChecked.reason.not_synced` (`:3410`) | 4.5 | no count row | same |
| `notChecked.reason.stale` (`:3405`) | 4.6 | counts > 14 days old | same |
| `notChecked.reason.no_data` (`:3406`) | history / step 5 | < 12 weeks or no candidate week | same |
| `notChecked.reasonUnknown` (`:3401`) | `too_far_ahead` fallback (`resultText.ts:88-90`) | claims nothing specific | same |
| action `competitor_differentiate` (`:3583`) | 2.3 `facts.name` = occurrence title | one listing's recurring series | due label D-21 |

## Blast radius
RuleEngine gains a 4th STRUCTURED evaluator (boot fails on bank/evaluator mismatch). Scoring: found only with a gate on (off in prod), coverage unchanged (non-star). Max gain per date 2+4+4 = 10 risk points once on. Stored qb3 checks still read. Public sources list: entries only while gates on. Webapp: three "Another check" rows per FR/NL/DE/ES/UA check while flags off — the webapp copy follow-up ships alongside. Writer/job: visibility only. No money/auth/Flyway; evaluator only reads.

## Risks
Future weeks under-count (`max_ahead_days` 28). Paris has no history → 2.6/2.3 `no_data` for 12 weeks after first run; Lille backfills ~26 weeks. Known limit: an organizer's own listing on OpenAgenda can count in 2.6 and 2.3, since no link exists to exclude it. ODbL titles in finding facts (precedent: OpenHolidays 4.5; M0-5 legal item). Read cost: one occurrence query per date. 17 files, one concern.

## Definition of done
- [ ] Bank v4, template-keys 59 lines, 3 non-star questions.
- [ ] Every branch tested, every guard proven red.
- [ ] sources.yaml entries with licence-check dates; `local_events` in USED_FOR; boot fails on missing entry.
- [ ] CLAUDE.md updated.
- [ ] Full gate green, Docker up, no skipped Testcontainers, re-run after rebase.
- [ ] Follow-ups: imin-public `local_events` label + §28; webapp copy for 2.6/5.3/2.3 + `too_far_ahead` + facts + fixture sync; M2-1c imin history for 2.3 then star.

## Decisions (main session, 2026-10-01)
- 2.3 non-star in v4; star returns with M2-1c.
- Thresholds and weights accepted as estimates pending product review.
- Prod has `PREDICTOR_DATE_CHECK_ENABLED=true` and `PREDICTOR_DATE_CHECK_ALL_ORGS=true`, so the webapp copy follow-up (labels for 2.6/5.3/2.3, `too_far_ahead`) is built in parallel and ships right after this api change.
- Worker verifies both credit lines against each dataset's licence page and records the check date.
- ODbL-derived titles in facts follow the 4.5 precedent; covered by the open legal review item.
- Review round 1: step 7's "or after d" is replaced. A series predicts d only from a chain (or monthly nights) ending on or before d; a series not announced on d with a same-pattern night after d is clear (the stored rows show it skipping d); the earlier chain-steps-over-d rule is dropped with it. A weekly-shaped series (two same-weekday nights 7 days apart, or two in one month) is judged by the weekly rule only. `resolve` matches the check's country. A malformed `sources_json` gives 2.6 `not_checked no_data` and an ERROR log.

## Live-test evidence
_(filled by /do-task)_

## Review rounds
_(filled by /do-task)_

## Round 2 decisions (main session, 2026-10-01)
- Accepted recall trade-off (2.3 answers clear, an under-claim, never a false fact): a twice-monthly series (1st + 3rd Saturday), a monthly series with one extra same-weekday edition under the same title in the read window, and a series that went from weekly to monthly are judged weekly-only by `weeklyShaped`. Revisit with real Lille data once the gate is on.
- A series predicts the date from at least `min_occurrences` nights strictly before it; an announced series with fewer earlier nights is clear.
- Follow-ups: when announced, name and link the row on the date rather than the title's newest row; `lastDate` is the end of the latest qualifying chain, so any copy must not call it "last seen".
