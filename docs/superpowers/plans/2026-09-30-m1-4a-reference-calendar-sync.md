# M1-4 Reference calendar sync (split: M1-4a now, M1-4b next)
m1-4-reference-calendar-sync · feature · Notion: (card id not supplied)

## Goal and scope
Fill `reference_calendar` (M1-2, V162) from open data plus computed rules, and serve it through `ReferenceCalendarService`. The old static `PublicHolidayCalendar` stays only as a fallback.
**M1-4a (this task):** calendrier.api.gouv.fr zones `metropole` and `alsace-moselle` (Licence Ouverte 2.0); data.education.gouv.fr `fr-en-calendrier-scolaire` (Licence Ouverte 2.0, UTC → Europe/Paris); `ComputedCalendar` (pont, DST from `ZoneRules`, Hijri from `HijrahDate`, ±1 day); `ReferenceCalendarWriter`, `ReferenceCalendarService`, `ReferenceCalendarJob` (weekly, ShedLock, and at startup when the table is empty); `PredictionInputSnapshotService` switched to the service.
**M1-4b (follow-up task, same package):** `OpenHolidaysSync` (LU, DE, BE, CH, ES, PT, NL; ODbL; test `luToussaint2026FromOpenHolidays`); `predictor/diaspora-days.yaml` and `predictor/ad-periods.yaml`, read at query time; `FootballFixturesSync` (Ligue 1 `FL1` and Champions League `CL`, off unless `FOOTBALL_DATA_API_KEY` is set, with the credit line "Football data provided by the Football-Data.org API").
Out: `LuxembourgSync` (OpenHolidays covers LU public holidays; LU school holidays come later), UA sync (no source; UA stays on the fallback), Aladhan calls, any endpoint (M2-7 owns `/predictions/sources`).

## Repos in ship order
1. api (`imin-api`, base `master`). The base must contain M0-1 (`regionOf`, 4-arg `near`) and M1-2 (V162, `ReferenceCalendarEntry`, `ReferenceCalendarEntryRepository`). If either has not merged, stack this branch on it.

## Affected files (per repo)
api, M1-4a (all under `src/main/java/com/imin/iminapi/predictor/`):
- Create `calendar/CalendarConfig.java`: a private `RestClient` with connect 5 s / read 30 s timeouts and a User-Agent (the `audienceplan/opendata/OpenDataConfig` pattern), a 1-thread `referenceCalendarSyncExecutor`, and `@EnableConfigurationProperties(CalendarSyncProperties)`.
- Create `calendar/CalendarSyncProperties.java` (`syncEnabled`, `yearsAhead=2`).
- Create `calendar/CalendarRow.java`, a record: country, region, date, endDate, kind, name, sourceUrl.
- Create `calendar/CalendarSource.java`, an interface: `String key(); List<Batch> fetch(LocalDate today)`. `Batch` = scope source_url, kinds and window, plus its rows.
- Create `calendar/FrenchHolidaySync.java`, `calendar/FrenchSchoolCalendarSync.java` and `calendar/ComputedCalendar.java`.
- Create `calendar/CalendarRegions.java`: `CalendarPlace of(country, postalCode, city)` returns `regions = ["", holidayRegion?, schoolZone?]`.
- Create `calendar/ReferenceCalendarWriter.java`, `calendar/ReferenceCalendarService.java`, `calendar/ReferenceCalendarJob.java`, and `calendar/CalendarHit.java` (record: date, endDate, kind, name, sourceUrl, approximate, origin `synced|fallback`).
- Modify `repository/ReferenceCalendarEntryRepository.java`: add an `@Query` JPQL `findOverlapping(country, regions, from, to)` (`calendarDate <= :to and coalesce(endDate, calendarDate) >= :from`, no nullable String params), `findBySourceUrlAndKindInAndCalendarDateBetween(...)` and `existsByCountryAndKind(...)`.
- Modify `service/PredictionInputSnapshotService.java`: inject `ReferenceCalendarService` and replace the `covers`/`near` pair at line ~97.
- Modify `src/main/resources/application.yaml` (`imin.predictor.calendar.sync-enabled: ${PREDICTOR_CALENDAR_SYNC_ENABLED:true}`), `src/test/resources/application.yaml` (`sync-enabled: false`) and `CLAUDE.md` (env var docs).
- Tests: see Test impact. Fixtures go in `src/test/resources/predictor/calendar/` (`metropole-2027.json`, `alsace-moselle-2027.json`, `calendrier-scolaire.json`).
Count: 13 main + 4 config/docs + 8 test + 3 fixtures, about 28 files. This is why the task is split (see Risks).

## Ordered steps
1. Baseline: run `./mvnw test` on the untouched worktree and record the result.
2. Capture real fixtures. Run curl once on `https://calendrier.api.gouv.fr/jours-feries/{metropole|alsace-moselle}/2027.json` and on `https://data.education.gouv.fr/api/explore/v2.1/catalog/datasets/fr-en-calendrier-scolaire/exports/json?where=annee_scolaire in ("2026-2027","2027-2028")`, then trim the results to the rows the tests need. Confirm the `end_date` meaning, i.e. that the local date is the day school starts again (see OPEN_QUESTIONS).
3. **`FrenchHolidaySync`.** For each year from the current year through `yearsAhead`:
   - `metropole` rows become kind `holiday`, region `''`.
   - `alsace-moselle` rows missing from metropole are stored three times, with regions FR-57, FR-67 and FR-68.
   - `source_url` = the exact zone/year URL. Each URL is its own batch (scope = that URL, kinds `holiday`+`pont`, the year).
   - Pont rows come from `ComputedCalendar.ponts(holidays)` and go into the same batch, so a fetch failure also keeps the old ponts.
4. **`FrenchSchoolCalendarSync`:**
   - Skip `population == "Enseignants"` and any `zones` not in Zone A/B/C (Corse, overseas).
   - Region `FR-Z{A|B|C}`; `calendar_date = start_date.atZoneSameInstant(Europe/Paris).toLocalDate()`; `end_date` = the local end date minus 1 day when that local time is midnight (the day school starts again). Pin this against the fixture.
   - Deduplicate the per-académie rows per zone on (region, date, description). Name = `description`. `source_url` = the dataset page `https://data.education.gouv.fr/explore/dataset/fr-en-calendrier-scolaire/`.
   - One batch, covering the window of the fetched school years.
5. **`ComputedCalendar`:**
   - `ponts`: a holiday on Tuesday makes Monday a pont, and a holiday on Thursday makes Friday a pont, unless that day is itself a holiday. Name `pont:<holiday name>`, same region as the holiday.
   - `dstNights(zone, from, to)` walks `ZoneRules.nextTransition`. The night date is the local transition date minus 1. Hours = `Duration.between(sun.atStartOfDay(z), sun.plusDays(1).atStartOfDay(z)).toHours()`. Names `dst_forward`/`dst_back`.
   - `hijri(from, to)`: `HijrahDate.of(y, 9, 1)` (Ramadan, a range to the day before 1 Shawwal), `(y, 10, 1)` for `eid_al_fitr`, `(y, 12, 10)` for `eid_al_adha`.
   - Computed rows are written for every country in the zone map {FR Europe/Paris; later LU/DE/BE/CH/ES/PT/NL}, region `''`.
   - `source_url`: `https://www.iana.org/time-zones` (DST) and `https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/chrono/HijrahChronology.html` (Hijri). The Hijri batch is scoped to its kinds and window.
   - Names are stable keys. Text is rendered at view time (Review Focus 6).
6. **`ReferenceCalendarWriter.replace(Batch)`**, one transaction per batch:
   - An empty batch is refused (WARN, rows kept).
   - Upsert on the unique key (country, region, calendar_date, kind, name): update `end_date`, `source_url` and `synced_at` on an existing row, insert otherwise.
   - Then delete the rows in that scope (source_url + kinds + window) that are no longer in the batch.
   - A fetch exception never reaches the writer. The source catches it, logs it and returns nothing for that URL, so the previous rows stay.
7. **`ReferenceCalendarJob`:**
   - `@Scheduled(cron = "0 30 4 * * SUN", zone = "Europe/Paris")` with `@SchedulerLock(name = "reference_calendar_sync", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")`.
   - Returns at once when `syncEnabled` is false.
   - Each source runs in its own try/catch. The final log line is `ReferenceCalendarJob: done, N rows, failed sources [..]`.
   - `@EventListener(ApplicationReadyEvent)`: when the flag is on and `repository.count() == 0`, submit `self.getObject().run()` to the executor, so the ShedLock proxy applies and boot never waits on the network (pattern: `AudienceBackfillJob.onStartup`). The body is wrapped in try/catch.
8. **`CalendarRegions`:**
   - Holiday region = `PublicHolidayCalendar.regionOf(country, postal, city)` (from M0-1).
   - Département = a 5-digit postcode prefix, else `OpenDataCities.find(cityKey).inseeCode` prefix.
   - School zone = a static metropolitan département → zone map, built from the académie lists: A = Besançon, Bordeaux, Clermont, Dijon, Grenoble, Limoges, Lyon, Poitiers; B = Aix-Marseille, Amiens, Lille, Nancy-Metz, Nantes, Nice, Normandie, Orléans-Tours, Reims, Rennes, Strasbourg; C = Créteil, Montpellier, Paris, Toulouse, Versailles. 2A, 2B and overseas get no zone.
   - Non-FR countries get `[""]`, plus the subdivision code in M1-4b.
9. **`ReferenceCalendarService`:**
   - `List<CalendarHit> on(LocalDate day, CalendarPlace p)` = `between(day, day, p)`. `between(from, to, p)` uses `findOverlapping`, and `approximate = kind == hijri`.
   - `boolean covers(String country, String kind, LocalDate day)` = synced rows exist for that country, kind and year. M1-6 reads this for `not_checked`.
   - Fallback: a `holiday` query for a country with no synced holiday rows that year returns `PublicHolidayCalendar.near(country, region, …)` hits with `origin=fallback` and `sourceUrl=null`, and logs a WARN. This covers UA, and every country while the table is empty.
10. **`PredictionInputSnapshotService`:** covered = `service.covers(country, "holiday", day)` or the fallback's `covers`. Holiday lines = `between(day-3, day+3)` filtered to kind `holiday`, mapped to the existing `HolidayLine(dateIso, name)`. `SNAPSHOT_VERSION` stays as it is, since the content change already changes the input hash (see Blast radius).
11. Update CLAUDE.md (env var, job, ShedLock name, sources), then run the verification.

**M1-4b (next task):**
- `OpenHolidaysSync`: `GET https://openholidaysapi.org/PublicHolidays?countryIsoCode=XX&languageIsoCode=EN&validFrom=…&validTo=…`. `nationwide` → region `''`, else one row per `subdivisions[].code`. One batch per country.
- `StaticCalendarTables`: the YAML loader, validated at startup. Diaspora days take the spec §6 rows (BW 09-30, NG 10-01, GN 10-02, SN 04-04, CI 08-07, CM 05-20, CD 06-30, ML 09-22, UA 08-24). The service merges them as kinds `diaspora`/`ad_period` into `CalendarHit`; they are never stored.
- `FootballFixturesSync`: `GET https://api.football-data.org/v4/competitions/{FL1|CL}/matches?dateFrom&dateTo` with header `X-Auth-Token`. Kind `fixture`. The region/city mapping for venues is decided in that plan.
- Env var `FOOTBALL_DATA_API_KEY`, blank = off.

## Verification commands
`cd /Users/ivan/imin/imin-api/.claude/worktrees/m1-4a-reference-calendar-sync && ./mvnw test`. Run it on the untouched base first. Targeted runs: `./mvnw test -Dtest='FrenchHolidaySyncTest,FrenchSchoolCalendarSyncTest,ComputedCalendarTest,ReferenceCalendar*Test,CalendarRegionsTest,PredictionInputSnapshotServiceTest,PublicHolidayCalendarTest'`.

## Test impact
Branch map: fetch ok / 5xx / empty; row new / existing / gone upstream; alsace-only row / shared row; school population and zone filters; UTC midnight conversion; pont on Tue / Thu / Wed / already a holiday; DST forward / back; Hijri; region by postcode / by city / none; synced vs fallback; job disabled / enabled; startup with an empty / non-empty table.
Test files, all under `src/test/java/com/imin/iminapi/predictor/calendar/`:
- `FrenchHolidaySyncTest` (MockRestServiceServer): `metzGoodFriday2027` (through the service: 2027-03-26 at Metz 57000 is a hit with a source_url; the same date at Paris 75011 is not), `alsaceOnlyRowsStoredForThreeDepartments`, `sharedRowsNotDuplicatedIntoRegions`.
- `FrenchSchoolCalendarSyncTest`: `parisSchoolHolidayUtcRowMapsTo17Oct` (`2026-10-16T22:00Z` gives calendar_date 2026-10-17 in FR-ZC, with the end_date pinned), `teacherRowsSkipped`, `nonMetropolitanZoneSkipped`, `academiesOfOneZoneDeduplicated`.
- `ComputedCalendarTest`: `ascension2027MakesFriday7MayAPont`, `tuesdayHolidayMakesMondayPont`, `wednesdayHolidayMakesNoPont`, `pontSkippedWhenDayIsHoliday`, `springDstNight2027Is23Hours` (night 2027-03-27), `autumnDstNight2026Is25Hours` (night 2026-10-24), `eidAlFitr2027Within1DayOfAladhanReference` (plus Ramadan 2027-02-08 and Adha 2027-05-16, each within ±1).
- `ReferenceCalendarWriterTest` (H2 slice, same style as M1-2's `DateCheckSchemaTest`): `syncIsIdempotent` (two runs: same ids and count, synced_at advanced), `upstream500KeepsPreviousRows` (rows and synced_at unchanged), `emptyResponseKeepsPreviousRows`, `rowDroppedUpstreamIsDeleted`, `otherScopesUntouched`.
- `ReferenceCalendarServiceTest`: `rangeStartingBeforeFromIsReturned`, `regionRowsOnlyForMatchingRegion`, `fallbackWhenCountryHasNoSyncedRows` (UA, origin fallback), `hijriHitIsApproximate`.
- `CalendarRegionsTest`: `metzPostcode`, `metzCityWithoutPostcode`, `parisZoneC`, `corsicaHasNoZone`, `nonFrOnlyNational`.
- `ReferenceCalendarJobTest`: `disabledMakesNoCall`, `startupSyncsOnlyWhenEmpty`, `oneSourceFailingDoesNotStopOthers`.
- Modify `src/test/java/com/imin/iminapi/predictor/PredictionInputSnapshotServiceTest.java`: new constructor argument, plus `syncedRowsFeedHolidayLines`. The existing M0-1 tests pass through the fallback.
- `PublicHolidayCalendarTest` (M0-1) stays unchanged.

## Live-test
There is no HTTP surface. Local run: `./mvnw spring-boot:run` against the dev DB with an empty `reference_calendar` and `PREDICTOR_CALENDAR_SYNC_ENABLED=true`. Then check:
- the log shows `ReferenceCalendarJob: done` with no failed sources;
- `select kind, country, region, count(*) from reference_calendar group by 1,2,3` shows holiday/pont/school/dst/hijri rows;
- a Metz Good Friday 2027 row exists for FR-57, and the Toussaint 2026 FR-ZC row starts on 2026-10-17.
After deploy: the Railway logs show the startup run, and the same query through `railway psql` returns rows.

## Contract impact
none. No endpoint or DTO change, and no OpenAPI marker.

## i18n impact
none. The api stores source names and stable keys; webapp rendering belongs to M3.

## Blast radius
- **Live Stage-0 predictor:** holiday lines now come from synced data (French names from calendrier.api.gouv.fr, plus Alsace-Moselle). This is not dark. Input hashes change for events near holidays, so their cached scores are recomputed on the next view. That is bounded by the existing `score-per-day` quota and uses OpenRouter credit.
- **Outbound calls from Railway:** about 8 GETs at the first boot on an empty table, and weekly (Sunday 04:30 Paris) after that. All go to public no-key endpoints. A failure is logged and never blocks boot, because the sync runs on its own executor.
- No Flyway migration (uses V162). No money, auth or Stripe. No shared module changed, apart from reading `OpenDataCities` from audienceplan.
- New ShedLock name `reference_calendar_sync`.

## Risks
- **Size.** The full M1-4 is about 40 files and has separable concerns, so it is split into M1-4a (about 28 files, still over 15, but one concern: French data plus the service) and M1-4b (OpenHolidays, YAML tables, football).
- **School `end_date` meaning** (the day school restarts, or the last day of the holiday) is assumed. Step 2 pins it against a live fixture.
- **The département → zone map is static.** A zone reform needs a code change. The sync could later WARN when the dataset disagrees.
- **Hijri** from Umm al-Qura can differ by a day from the local announcement. It is flagged `approximate`, and no live check against Aladhan is made.
- **Dependency on M1-2 names.** Exact entity setters and the table come from M1-2, which is not yet implemented (only planned). Rebase on it before step 3.
- **Row explosion:** computed rows are only written for countries in the zone map.

## Definition of done
- All the M1-4a tests listed above pass, including the 8 programme tests.
- `./mvnw test` is green in the worktree, and the base was green before.
- `reference_calendar` fills on a local boot with an empty table.
- The snapshot service reads through `ReferenceCalendarService`, with a WARN'd fallback.
- CLAUDE.md documents `PREDICTOR_CALENDAR_SYNC_ENABLED`, the job and the sources.
- Every stored row has a `source_url`.
- Nothing committed by the worker. The M1-4b card is opened.

## Live-test evidence
(filled by /live-test)

## Review rounds
(filled by review)

---

OPEN_QUESTIONS:
1. `PREDICTOR_CALENDAR_SYNC_ENABLED` defaults to true, and switching Stage-0 reads makes a live (not dark) change to predictor inputs and invalidates cached scores near holidays. Should the Stage-0 switch sit behind the date-check flag instead?
2. I could not open the data.education.gouv.fr `end_date` semantics without a network call. Is the local end date the day school restarts? The worker verifies this in step 2.
3. The Notion card id was not supplied for the header line.
4. Diaspora and ad-period YAML content (M1-4b): who owns it? Bohdan (M0-6), or the spec §6 example rows as a starter set?
5. Should NL move from the static table to OpenHolidays in M1-4b? It is covered by the fallback today; UA stays on the fallback because no source covers it.
6. The programme names `LuxembourgSync` (data.public.lu). I propose dropping it in favour of OpenHolidays for LU public holidays and deferring LU school holidays. Please confirm.

## Decisions (main session, 2026-09-30)
- This task is M1-4a only (French sources, computed rows, writer, service, job, CalendarRegions). M1-4b (OpenHolidays, YAML tables, football) is a separate later task.
- DROP step 10: Stage-0 `PredictionInputSnapshotService` stays on `PublicHolidayCalendar` (no live predictor change, no LLM re-scores). Only the date check will read `ReferenceCalendarService`. Do not modify PredictionInputSnapshotService or its test.
- Add the overlap finder `findOverlapping` (calendarDate <= :to and coalesce(endDate, calendarDate) >= :from) as planned — the M1-2 review asked for it.
- Verify the school `end_date` meaning against a live fixture in step 2 and pin it in a test.
- `PREDICTOR_CALENDAR_SYNC_ENABLED` default true is fine (it only fills the table; nothing live reads it yet). Startup sync runs off-thread, never blocks boot.
- LuxembourgSync dropped (OpenHolidays in M1-4b covers LU).
- CalendarHit also carries `String region` (the row's region, '' = whole country; fallback hits use the region passed in or ''), so M1-6 can tell national (4.1) from regional (4.4) holidays. Pinned in `regionRowsOnlyForMatchingRegion`.

round 1 → PASS (2 MEDIUM + 5 LOW fixed)
round 2 → PASS (1 LOW fixed in main session: server.verify(); regional pont/holiday overlap handled in M1-6)
