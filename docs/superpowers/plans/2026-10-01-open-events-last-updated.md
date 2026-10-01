# Open-data sources get a lastUpdated date
Slug: open-events-last-updated · Mode: Subagent · Notion: not given

## Goal and scope
`GET /api/v1/public/predictor/sources` fills `lastUpdated` only for entries with a `syncPrefix`, read from `reference_calendar.synced_at` (`SourceSyncDates.java:22-29`, `DataSourceCatalog.java:133-142`). OpenAgenda and Que Faire à Paris store their rows in `open_event_occurrence` (V165, column `synced_at TIMESTAMP WITH TIME ZONE NOT NULL`), so their `lastUpdated` is always null. Licence Ouverte 2.0 requires "la date de dernière mise à jour de l'Information réutilisée", so `PREDICTOR_OPENAGENDA_ENABLED` must stay off until this is fixed.

In scope:
- New optional `syncSource` key per sources.yaml entry. It cannot be used with `syncPrefix`, and its value must be in `SourceSyncDates.SOURCES` = `OpenEventSource.SOURCE_IDS` (`openagenda`, `quefaireaparis`, `datatourisme`; `OpenEventSource.java:18`, which mirrors the V165 CHECK `ck_open_event_occurrence_source CHECK (source IN ('openagenda', 'quefaireaparis', 'datatourisme'))`) ∪ {`wikimedia`}. sources.yaml stays the only place that says which store dates which entry.
- `lastUpdated` for a `syncSource` entry = UTC date of `max(synced_at)`, from `open_event_occurrence where source = :id` or from `wikimedia_pageviews_month`. That table has no source column and holds only Wikimedia rows; `WikimediaPageviewsWriter.java:28-50` sets `synced_at` on every month it upserts.
- A boot check that every `OpenEventSource` bean's entry carries `syncSource` equal to its id.
- imin-api CLAUDE.md is updated in the same change.

What the date means (decided here): **the UTC date imin last fetched data from that source that it still stores.** Traced:
- A successful (city, source) run calls `replaceFuture`, which deletes the source's city rows from the window start and inserts the fetched rows stamped with that run's `now` (`OpenEventsWriter.java:59-63`, `OpenEventsJob.java:167-168`). `insertBackfill` stamps the same `now` (`OpenEventsWriter.java:66-70`).
- A successful run that matched no listing still calls `replaceFuture`, which deletes that source's city rows from the window start (`OpenEventsWriter.java:61`) and inserts nothing. The date then falls back to the newest row still held (older, e.g. backfilled or past nights, or null). The rows we hold and credit were fetched on that date, so the date stays accurate for what is reused; it may be older than the newest zero-row fetch, but never newer. Wikimedia only upserts (`WikimediaPageviewsWriter`), so its date never goes back.
- A partial (page cap), failed or rate-limited pair writes nothing (`OpenEventsJob.java:160-185`), so the date stays.
- Pruning deletes nights older than 200 days (`OpenEventsWriter.java:138-141`). Fresh rows are never pruned, so the date goes null only after about 200 days without a stored row. Then imin holds no copy and null is correct.
- The upstream modification time is not stored by either client. "Our last successful run" would need a run log, which means a migration, and would date rows we did not re-fetch. Neither is in scope.

Out of scope: imin-public and PUBLIC_PAGE_API §28 (follow-up below), flipping either flag (the CLAUDE.md "Before flipping either flag" prerequisites still apply), and per-city dates.

## Repos in ship order
1. `api` (imin-api, base `master`). This is the only repo. imin-public renders `lastUpdated` generically (`components/buyer/data-source-list.tsx:73-77` on origin/main), so it needs no code change.

## Affected files (per repo)
imin-api (paths relative to `/Users/ivan/imin/imin-api/.claude/worktrees/open-events-last-updated`):

| # | File | Change |
|---|---|---|
| 1 | `src/main/resources/predictor/sources.yaml` | Header comment: document `syncSource` (store id; not allowed with `syncPrefix`). Add `syncSource: openagenda` to `openagenda`, `syncSource: quefaireaparis` to `quefaireaparis` and `syncSource: wikimedia` to `wikimedia-pageviews`. The Wikimedia comment stays as it is (a parallel worktree edits the adjacent `open-meteo` block). `reviewedOn` stays `2026-10-01`. |
| 2 | `src/main/java/com/imin/iminapi/predictor/sources/DataSourceCatalog.java` | New nested `public interface SyncDates { Optional<LocalDate> lastUpdated(String prefix); Optional<LocalDate> lastUpdatedOfSource(String source); }`. `Entry` gains `syncSource`. `parse` reads `syncSource` via `text(...)` (a blank value fails), rejects a value outside `SourceSyncDates.SOURCES` ("predictor sources <id>.syncSource: unknown source '<v>'"), and rejects both keys together ("predictor sources <id>: syncPrefix and syncSource are mutually exclusive"). `active(SyncDates)` replaces `active(Function<…>)`. `withDate` uses the prefix lookup when there is a prefix, else the source lookup when there is a source, else returns the entry unchanged. New `public Optional<String> syncSource(String id)`. |
| 3 | `src/main/java/com/imin/iminapi/predictor/sources/SourceSyncDates.java` | `implements DataSourceCatalog.SyncDates`. Adds `public static final String WIKIMEDIA = "wikimedia"` and `public static final Set<String> SOURCES` (= `OpenEventSource.SOURCE_IDS` + `WIKIMEDIA`). Constructor also takes `OpenEventOccurrenceRepository` and `WikimediaPageviewMonthRepository`. New `lastUpdatedOfSource(String source)`: `wikimedia` → `wikimedia.findLatestSyncedAt()`, anything else → `occurrences.findLatestSyncedAt(source)`, mapped to the UTC `LocalDate` like `lastUpdated`. There is no separate unknown-value guard: the catalog validates values at boot, and an unknown id simply matches no rows, so such a guard could never be shown failing in a test. Javadoc: date of the newest stored row's sync; an open-event run that matches nothing deletes rows from the window start, so the date can fall back; Wikimedia only upserts. |
| 4 | `src/main/java/com/imin/iminapi/predictor/repository/OpenEventOccurrenceRepository.java` | `@Query("select max(o.syncedAt) from OpenEventOccurrence o where o.source = :source") Instant findLatestSyncedAt(@Param("source") String source);` with the comment "null when none". |
| 5 | `src/main/java/com/imin/iminapi/predictor/repository/WikimediaPageviewMonthRepository.java` | `@Query("select max(w.syncedAt) from WikimediaPageviewMonth w") Instant findLatestSyncedAt();` with the comment "null when none". |
| 6 | `src/main/java/com/imin/iminapi/predictor/controller/PublicPredictorSourcesController.java` | `catalog.active(syncDates)` replaces `catalog.active(syncDates::lastUpdated)` (line 34). |
| 7 | `src/main/java/com/imin/iminapi/predictor/rules/OpenEventsEvaluator.java` | In the loop at lines 111-118, after the licence check: `if (!catalog.syncSource(s.id()).equals(Optional.of(s.id()))) throw new IllegalStateException("open event source " + s.id() + ": sources.yaml entry needs syncSource: " + s.id());` |
| 8 | `CLAUDE.md` | Line 51 (Wikimedia bullet): add "`sources.yaml` gives it `syncSource: wikimedia`: `lastUpdated` = UTC date of the newest `wikimedia_pageviews_month.synced_at`." Line 53 (open events): "every `OpenEventSource` bean needs a `sources.yaml` entry with the same licence and `syncSource` equal to its id, else startup fails." Line 54 (sources): replace "both `usedFor` `local_events` with no `syncPrefix`" with "both `usedFor` `local_events`", and after the `syncPrefix` sentence add: "An optional `syncSource` (not allowed with `syncPrefix`; an `open_event_occurrence.source` id or `wikimedia`, checked at boot) gives the UTC date of the newest `synced_at` of that source's stored rows. Either date is the last fetch whose rows imin still holds. For the open-event sources, a run that fails, is rate-limited or stops at a page cap leaves it; a successful run that matches nothing deletes that source's city rows from the window start, so the date falls back to the newest row still held (older, or null). Wikimedia only upserts, so its date never goes back. The date goes null once every row is pruned." |
| 9 | `src/test/java/com/imin/iminapi/predictor/SourceSyncDatesTest.java` | 4 new tests (Test impact). Autowire `OpenEventOccurrenceRepository` and `WikimediaPageviewMonthRepository`. |
| 10 | `src/test/java/com/imin/iminapi/predictor/DataSourceCatalogTest.java` | `NO_DATES` becomes a `DataSourceCatalog.SyncDates` returning empty for both methods. The lambda at line 175 becomes an anonymous `SyncDates` recording prefixes. Edit `lastUpdatedComesFromTheSyncPrefixLookup` (line 322) and add 4 tests (Test impact). |
| 11 | `src/test/java/com/imin/iminapi/predictor/rules/OpenEventsEvaluatorTest.java` | 1 new test (Test impact). |

The plan itself, `docs/superpowers/plans/2026-10-01-open-events-last-updated.md`, ships with the code.

Files read that need no change:
- `PublicDataSourcesResponse.java`: its javadoc ("ISO date of the latest sync of its stored rows, or null") already holds.
- `OpenEventsWriter.java` / `OpenEventsJob.java` / `WikimediaPageviewsWriter.java`: their writes are unchanged and only read.
- `PublicPredictorSourcesControllerTest.java`: reads sources.yaml through the app, but the test profile lists only `open-meteo`, whose entry is unchanged, and `reviewedOn` stays `2026-10-01`, so it stays green unedited.
- `RuleEngineTest.java`: passes `List.of()` sources (line 144), so the new boot check never runs.
- `OpenEventsEvaluatorRepositoryTest.java`: uses the real client beans (`openagenda`, `quefaireaparis` from `OpenAgendaClient.java:64` / `QueFaireAParisClient`) with the real catalog, which now has a matching `syncSource`. It stays green and is what proves the real bean ids match the YAML.

## Ordered steps
1. Baseline: `docker info`, then run the full api gate once on the untouched worktree (Verification commands). If it is red there, stop and report; that is a separate card.
2. Repositories: add `findLatestSyncedAt` to `OpenEventOccurrenceRepository` and `WikimediaPageviewMonthRepository` (files 4 and 5). JPQL `max` over an `Instant` attribute is the same pattern as `ReferenceCalendarEntryRepository.findLatestSyncedAtLike`, which runs on both H2 and PG. The only String parameter is used in a plain `=`, with no `lower`/`concat`/`like`, so the H2-vs-PG null-string `bytea` trap does not apply.
3. `SourceSyncDates` (file 3): `SOURCES`, `WIKIMEDIA`, `lastUpdatedOfSource`, `implements DataSourceCatalog.SyncDates`.
4. `DataSourceCatalog` (file 2): interface, `Entry.syncSource`, parse validation (blank, unknown value, both keys), `active(SyncDates)`, `withDate`, `syncSource(id)`.
5. Controller (file 6).
6. `OpenEventsEvaluator` boot check (file 7), placed after the licence check so the two existing failure messages keep coming first.
7. `sources.yaml` (file 1).
8. Tests (files 9-11). Then prove each guard: remove `where o.source = :source` and test 1 goes red; remove the both-keys check and test 8 goes red; remove the `SOURCES` check and test 7 goes red; remove the evaluator check and test 10 goes red. Restore each one.
9. CLAUDE.md (file 8).
10. Full gate (Verification commands). Read the Surefire summary and treat any skipped Testcontainers test as red.

## Verification commands
```
docker info >/dev/null && cd /Users/ivan/imin/imin-api/.claude/worktrees/open-events-last-updated && /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest='SourceSyncDatesTest,DataSourceCatalogTest,OpenEventsEvaluatorTest,OpenEventsEvaluatorRepositoryTest,RuleEngineTest,PublicPredictorSourcesControllerTest'
docker info >/dev/null && cd /Users/ivan/imin/imin-api/.claude/worktrees/open-events-last-updated && /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test
```
The second command (full `./mvnw test`) is the gate. Re-run it after any rebase.

## Test impact
Branches of the changed logic:
- `SourceSyncDates.lastUpdatedOfSource`: (a) open-event source with rows, other sources' rows excluded; (b) open-event source with none; (c) wikimedia with rows; (d) wikimedia with none.
- `DataSourceCatalog.parse` for `syncSource`: (e) absent (all existing tests); (f) blank; (g) unknown; (h) both keys; (i) valid (real YAML).
- `withDate`: (j) prefix entry; (k) source entry; (l) neither.
- Evaluator: (m) matching `syncSource` (existing `evaluator()` setUp and the repository test); (n) missing or mismatched.

| # | Test (file) | Branch | Minimal setup → expectation |
|---|---|---|---|
| 1 | `openEventSourceDateIsItsNewestSyncInUtc` (SourceSyncDatesTest) | a | `openagenda` rows: lille at 2026-09-20T03:45Z and lille at 2026-09-27T23:30Z (Paris date 09-28). A `quefaireaparis` paris row at 2026-09-29T03:45Z, newer, so a missing filter would return it. Expect `lastUpdatedOfSource("openagenda")` = 2026-09-27, the UTC date not the Paris one. |
| 2 | `openEventSourceWithNoRowsIsEmpty` (SourceSyncDatesTest) | b | Only a `quefaireaparis` row. Expect `lastUpdatedOfSource("openagenda")` empty. |
| 3 | `wikimediaDateIsItsNewestSync` (SourceSyncDatesTest) | c | Two `wikimedia_pageviews_month` rows at 2026-09-21T03:15Z and 2026-09-28T03:15Z, plus an `openagenda` row at 2026-09-30T03:45Z that must not count. Expect `lastUpdatedOfSource("wikimedia")` = 2026-09-28. |
| 4 | `wikimediaWithNoRowsIsEmpty` (SourceSyncDatesTest) | d | Only an `openagenda` row. Expect `lastUpdatedOfSource("wikimedia")` empty. |
| 5 | `lastUpdatedComesFromTheSyncSourceLookup` (DataSourceCatalogTest, new) | i, k, l | All gates on (`gates(true,true,true,true)`, `football(true,"k")`, `openEvents(true,"oa_pk_k",true)`). The lookup records the sources asked and returns dates for `openagenda` (2026-09-29), `quefaireaparis` (2026-09-30) and `wikimedia` (2026-09-28). Expect asked sources exactly `wikimedia, openagenda, quefaireaparis` in file order, those three dates on `openagenda` / `quefaireaparis` / `wikimedia-pageviews`, and `iana-tz` / `openjdk-hijrah` / `open-meteo` null with no lookup call. |
| 6 | `lastUpdatedComesFromTheSyncPrefixLookup` (DataSourceCatalogTest, edited) | j | Same assertions as today, through the `SyncDates` interface. Prefixes asked are still exactly the three calendar ones (`wikimedia-pageviews` is never asked a prefix). `wikimedia-pageviews` stays null because this test's source lookup returns empty. |
| 7 | `unknownSyncSourceFailsLoad` (DataSourceCatalogTest) | g | `VALID` with `syncSource: tides` → `IllegalStateException` containing `one` and `syncSource`. |
| 8 | `syncPrefixWithSyncSourceFailsLoad` (DataSourceCatalogTest) | h | `VALID` with `syncPrefix: https://one.example/` and `syncSource: openagenda` → message contains `one` and `mutually exclusive`. |
| 9 | `blankSyncSourceFailsLoad` (DataSourceCatalogTest) | f | `VALID` with `syncSource: "  "` → message contains `one` and `syncSource`. |
| 10 | `sourceWithoutMatchingSyncSourceFailsBoot` (OpenEventsEvaluatorTest) | n | Catalog from `DataSourceCatalog.parse` of an inline file: entry `openagenda`, licence `Licence Ouverte 2.0`, gate `openagenda`, without `syncSource`. Constructing with `List.of(OPENAGENDA)` throws a message containing `needs syncSource: openagenda`. Repeat with `syncSource: quefaireaparis` (mismatch): same message. |

That is 10 rows: 4 in SourceSyncDatesTest, 5 in DataSourceCatalogTest, 1 in OpenEventsEvaluatorTest.

Mechanical edits with no new assertion:
- In `DataSourceCatalogTest`, the `NO_DATES` constant and the line-175 lambda change type. Every existing `active(NO_DATES)` assertion of `lastUpdated` null (lines 98, 146, 210, 219) still holds, because `NO_DATES` returns empty for both lookups.
- No dedicated controller test for the source lookup: the test profile has the date check off (`PublicPredictorSourcesControllerTest.java:19`), so no `syncSource` entry is listed there. The wiring is a type-checked `active(syncDates)`.

## Live-test
- Local, before ship: in the worktree with the local Postgres (docker compose), run `PREDICTOR_DATE_CHECK_ENABLED=true PREDICTOR_QUEFAIREAPARIS_ENABLED=true ./mvnw spring-boot:run` (Que Faire à Paris needs no key). Wait for the `OpenEventsJob: done …` (or `… stored but no matched event`) log from the boot run. Then:
  - `curl -s localhost:8080/api/v1/public/predictor/sources | jq '.sources[] | select(.id=="quefaireaparis") | .lastUpdated'` should equal the UTC date of `psql … -c "select max(synced_at) at time zone 'UTC' from open_event_occurrence where source='quefaireaparis'"`.
  - The calendar entries still carry dates.
  - Boot succeeds, which proves the YAML validation and the evaluator check against the real beans.
- Prod, after deploy: `curl -s https://imin-api-production.up.railway.app/api/v1/public/predictor/sources | jq` should return 200 with the same entries as before the deploy (the flags are off, so no `syncSource` entry is listed) and unchanged calendar dates. A boot failure would show as the deploy never going live.

## Contract impact
No schema change: `PublicDataSource.lastUpdated` (string or null) exists already, so there is no new OpenAPI marker and no webapp `types.ts` edit. This is a behaviour change on `/api/v1/public/predictor/sources`: `openagenda`, `quefaireaparis` and `wikimedia-pageviews` now carry a date once they have rows.

Follow-up (not in this api-only task): imin-public `docs/PUBLIC_PAGE_API.md` §28, row `sources[].lastUpdated` (line 2558 on origin/main), should read: "UTC date imin last fetched rows of this source that it still stores: the newest `reference_calendar` sync under the entry's URL for the calendar sources and football-data.org, the newest `open_event_occurrence` row of the source for OpenAgenda and Que Faire à Paris, the newest `wikimedia_pageviews_month` row for Wikimedia Pageviews. A failed or partial sync keeps the previous date; an open-event sync that matches nothing can move it back to an older stored row. `null` when imin holds no dated copy; the page then shows no date, never a guessed one." Add it to the §28 update already required before either open-events flag flips (CLAUDE.md line 53).

## i18n impact
None in this repo. It reuses the existing imin-public string `dataSources.sourceUpdated` in all four locales.

Copy ledger:

| String (reused) | Field behind it | Meaning, scope, range | Rendered next to |
|---|---|---|---|
| EN "Updated {date}" (`lib/i18n/en.ts:1184`), ES "Actualizado el {date}" (`es.ts:1058`), FR "Mis à jour le {date}" (`fr.ts:1067`), UK "Оновлено {date}" (`uk.ts:1104`), in imin-public origin/main | `sources[].lastUpdated`, from `DataSourceCatalog.withDate` (`DataSourceCatalog.java:133-142` today; edited in step 4) ← `SourceSyncDates.lastUpdatedOfSource` (new, file 3) ← `max(synced_at)` written by `OpenEventsWriter.java:171` / `WikimediaPageviewsWriter.java:46` | UTC calendar date of the newest `synced_at` among the rows imin currently stores for that source. Global per catalog entry, across all cities (today OpenAgenda covers only `lille` and Que Faire à Paris only `paris`). It is the date of the last fetch whose rows are still held, not upstream modification time and not the last run attempt; it can lag a run that stored 0 rows. Null when no row; never defaulted. | A `<dd>` under the source name, usage, credit line and licence pill (`components/buyer/data-source-list.tsx:73-77`), shown only when non-null and formatted by `formatDay`. "Updated" / "Mis à jour le" claims only that imin's copy is from that date, which holds. |

## Blast radius
- Public, unauthenticated endpoint `/api/v1/public/predictor/sources` (cached 300 s): it gains one cheap `max` query per listed `syncSource` entry (at most 3).
- Boot: a bad `syncSource` in sources.yaml, or an `OpenEventSource` bean without a matching `syncSource`, now fails startup. This is intended and covered by tests 7-10. The real YAML is valid, so prod boot is unaffected (verified by `OpenEventsEvaluatorRepositoryTest` with real beans).
- Shared module `DataSourceCatalog`: its consumers are `PublicPredictorSourcesController` (file 6) and `OpenEventsEvaluator` (file 7, which uses `byId`, unchanged, plus the new `syncSource`). No other callers (`grep DataSourceCatalog src/main`).
- No Flyway migration. No index: PG builds a btree for `uq_open_event_occurrence (source, source_event_id, night_date)`, which starts with `source`, so `where source = ?` uses it. The scan is bounded by matched nights of covered cities kept for at most 200 days. `wikimedia_pageviews_month` is a few hundred rows (articles × 13 months).
- No writer changes, so `synced_at` keeps its other reader, `findFirstBySourceAndCityKeyOrderBySyncedAtAsc` (`OpenEventsJob.java:119`).
- Not money, auth or Stripe.

## Risks
- **Max across cities.** With one city per source today the date is exact. Once a source covers two cities and one keeps failing, the date reflects the healthy city. If that matters, a follow-up can switch to the minimum, over configured cities, of each city's max.
- **Can lag the newest fetch, or go back.** A successful open-event run that matches nothing deletes the source's city rows from the window start, so the date falls back to the newest row still held (older, or null); it never moves forward on that run. This only understates freshness, which is the safe direction for an attribution obligation. Wikimedia only upserts, so its date never goes back.
- **Licence reading.** The date is imin's retrieval date, not upstream's. This matches the existing calendar sources and §28's own wording ("UTC date imin last synced the source's rows"). Listed as an open question.
- **Wikimedia reverses plan m2-3**, which chose no date because CC0 does not ask for one. It costs one YAML line, and dropping that line restores the old behaviour.
- **Flag still blocked by other work.** This removes only the `lastUpdated` blocker. Flipping `PREDICTOR_OPENAGENDA_ENABLED` still needs the imin-public `local_events` label plus §28, and the webapp copy (CLAUDE.md line 53).
- Size: 11 files, one concern, so no split.

## Definition of done
- All 11 affected files changed as in the table; the full gate is green with no skipped Testcontainers tests; each guard has been shown to fail its test once.
- With the gates on, `openagenda`, `quefaireaparis` and `wikimedia-pageviews` return the UTC date of their newest stored `synced_at`, or null with no rows. Calendar entries are unchanged.
- CLAUDE.md describes `syncSource`, what the date means, and the evaluator boot check.
- The PUBLIC_PAGE_API §28 follow-up line is recorded on the open-events flip card.

## Live-test evidence
(to fill: local curl and psql output, prod curl after deploy)

## Review rounds
(to fill)

## Decisions (main session, 2026-10-01)
- "Last update" = the UTC date imin last fetched rows it still stores (retrieval date), as the calendar sources already do; upstream modification time is out of scope.
- Wikimedia is included (one YAML line).
- The imin-public PUBLIC_PAGE_API §28 `lastUpdated` row follow-up rides on the public source-labels task in flight.

