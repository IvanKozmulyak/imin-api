# ap-m2-4-public-data

Task M2-4 of `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (+ the never-done M0-4 Metz open-data snapshot, folded in as the seed).

## Goal and scope

`PublicDataService` + `city_open_data`: a shared cache of public, aggregate open data per city, one row per `(city_key, dataset)` with `period` (year / reference date), `source_url`, `licence`, `attribution`, `fetched_at`, `expires_at`. Consumers (M2-5 TribeSizeCalculator, M2-6 CatchmentService, M2-7 portraits) read it; nothing reads it yet, so there is no contract change.

Datasets (data-sources-v1.md §1.1 and §2):
- `insee_age`: INSEE RP2023 population by single-year age, commune (Melodi `DS_RP_TD_POPULATION_AGESEX_PRINC`, no key, 30 calls/min). Headline value = people aged 18-35 (sum of single years, rounded); payload also `pop_total`.
- `students`: MESR Atlas régional, `regroupement = TOTAL`, commune, latest `annee_universitaire`, sum of `effectif`.
- `frontaliers`: IGSS (data.public.lu, CC0) people in employment in Luxembourg by French commune of residence, latest reference date, all genders and statuses.
- `osm_venues`: OpenStreetMap counts of `amenity=nightclub|bar|pub|music_venue` in the commune, **seed only**. No runtime fetcher: data-sources §4 forbids the public Overpass server as a production backend; a self-hosted extract is a TODO. Counts only, never a merged venue list (ODbL share-alike).

Seed (M0-4): small CSV extracts under `src/main/resources/audienceplan/open-data/` for Metz, Nancy and Thionville, downloaded 2026-09-27 from the official APIs/files (all licences allow storage and commercial reuse), with a README naming every source URL, licence and attribution. The seeder inserts a row only when none exists for that `(city_key, dataset)`.

Refresh: TTL per dataset (census and students 365 days, IGSS 182 days, OSM 365 days) and a weekly `CityOpenDataRefreshJob` (Monday 04:00 Europe/Paris, ShedLock `city_open_data_refresh`) that refetches missing or expired rows of datasets that have a fetcher. `PublicDataService.get` only reads the stored row (fresh, stale or empty) and never fetches; `refresh()` fetches, with a 1-hour skip after a failure.

Rules carried forward verbatim from the programme plan and data-sources-v1.md:
- M2-4: "INSEE Melodi (30 calls/min), MESR Atlas, IGSS file; cached per `(city_key, dataset)` with `source_url`, `licence`, `expires_at`. Refresh jobs: yearly census/students, semi-annual IGSS."
- M2-4 tests: "Metz 18-35 = 38,065; students 20,588; frontaliers 6,330; rate-limit backoff; cache hit skips the call; expired entry refetches."
- §4.4: "`unknown` is a legal value and flows to the API as `null` / `"unknown"`, never as 0."
- M2-5: "missing census → `null` sizes, never 0."
- M2-7: "Numbers from open data are never produced by the model." / "Numbers come only from M2-4/M2-5."
- tech-spec: "`city_open_data` -- shared cache, public data only".
- data-sources §1.1: "All French sources: Licence Ouverte 2.0 (commercial reuse, storage, showing to organizers and ML allowed, with attribution "Source : Insee" etc.)."
- data-sources §1.1 OSM: "**ODbL:** map display = attribution only; merging our venue list with OSM = share-alike; keep separate layers".
- data-sources §4 Do not use: "Merging our venue list with OSM without accepting share-alike; public Overpass as production backend" and "Scraping RA, Shazam, YouTube charts, Beatport"; "Scraping Instagram, Threads, Facebook, TikTok (follower lists, likers, emails)".
- data-sources §1.1 INSEE immigration: "**Aggregates only**" / "Never infer an individual's origin (GDPR art. 9)" (not loaded in this task).
- Runner: no fabricated numbers; a dataset that cannot be fetched leaves values null.
- Runner: no beta gating, no new per-feature enable flags.

Out of scope: TribeSizeCalculator (M2-5), commune centroids / catchment (M2-6), portraits (M2-7), Sirene / licence register / JOAFE / immigration aggregates / LUSTAT / Eurostat loaders (no consumer yet), any endpoint.

Reproduction test: n-a (new code).

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m2-4-public-data` | `./mvnw test` |

## Affected files

api (all new except none):
- `src/main/resources/db/migration/V139__city_open_data.sql` (§4.3 reserved number)
- `src/main/java/com/imin/iminapi/audienceplan/model/CityOpenData.java`
- `src/main/java/com/imin/iminapi/audienceplan/repository/CityOpenDataRepository.java` (`@RepositoryRestResource(exported = false)`)
- `src/main/java/com/imin/iminapi/audienceplan/opendata/`: `OpenDataset`, `OpenDataCity`, `OpenDataCities`, `FetchedFigure`, `OpenDataFetcher`, `OpenDataFetchException`, `InseeMelodiFetcher`, `MelodiThrottle`, `Sleeper`, `MesrAtlasFetcher`, `IgssFrontaliersFetcher`, `XlsxSheetReader`, `OpenDataSeed`, `OpenDataConfig`
- `src/main/java/com/imin/iminapi/audienceplan/service/PublicDataService.java`, `OpenDataValue.java`, `CityOpenDataSeeder.java`, `CityOpenDataRefreshJob.java`
- `src/main/resources/audienceplan/open-data/{README.md,cities.csv,insee_age.csv,students.csv,frontaliers.csv,osm_venues.csv}`
- tests under `src/test/java/com/imin/iminapi/audienceplan/{opendata,service}/` + raw fixtures `src/test/resources/audienceplan/open-data/{melodi_metz_2023.json,mesr_metz.json,igss_f_trimmed.xlsx}`

## Ordered steps

1. Baseline `./mvnw test` on the untouched worktree.
2. Download real data (Melodi, MESR, IGSS xlsx, Overpass once) into the scratchpad; verify 38,065 / 20,588 / 6,330; write seed CSVs + README; save raw fixtures (Melodi Metz JSON, MESR Metz JSON, IGSS xlsx trimmed to Metz/Nancy/Thionville rows keeping Excel's own XML).
3. Migration + entity + repository.
4. Fetchers (Melodi with throttle + 429 backoff, MESR, IGSS with a JDK-only streaming xlsx reader), city registry, seed loader.
5. `PublicDataService.get` (unknown city → empty; fresh → no call; missing/expired → fetch + upsert; fetch fails → stale row flagged, or empty), seeder, refresh job.
6. Tests per branch; `./mvnw test`; rebase onto latest origin/master; `./mvnw test` again.

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m2-4-public-data && ./mvnw test`

## Test impact

New tests only (one per branch):
- `InseeMelodiFetcherTest`: Metz fixture → 38,065 (18-35) and pop_total 122,572, period 2023, exact query URL; latest `TIME_PERIOD` wins when two years are present; no observations → failure (never 0); a `next` page → failure; 429 then 200 → sleeps `Retry-After` then succeeds; 429 without `Retry-After` → default backoff; three 429s → failure; 5xx → failure.
- `MelodiThrottleTest`: first call no wait; second call within 2 s waits the remainder; a call after the interval does not wait.
- `MesrAtlasFetcherTest`: Metz fixture → 20,588 for 2024-25 (older year ignored), exact query; empty results → failure.
- `IgssFrontaliersFetcherTest` (trimmed real xlsx): Metz 6,330 / Nancy 370 / Thionville 10,110 at 2026-03-31 (2025-09-30 rows ignored); commune absent → failure; same commune name in another département not counted; workbook without the source sheet → failure; oversize download → failure.
- `XlsxSheetReaderTest`: shared strings, inline strings, numeric cells, missing cells keep column positions.
- `OpenDataSeedTest`: every seed row has a known city, non-blank source URL, licence, attribution, period; Metz values 38,065 / 20,588 / 6,330; OSM rows carry ODbL + "© OpenStreetMap contributors" and a null headline.
- `PublicDataServiceTest` (unit, fakes): unknown city; fresh row → fetcher never called; missing → fetched + saved with source/licence/expiry; expired → refetched and updated; expired + failure → stale row, `stale=true`; missing + failure → empty; dataset without fetcher: missing → empty, expired → stale, no call.
- `CityOpenDataSeederTest` (SpringBootTest, H2): rows present after startup with the CSV values; an existing row is not overwritten; a second run inserts nothing.
- `CityOpenDataRefreshJobTest`: refreshes only missing/expired rows of fetchable datasets; one failure does not stop the others.
- `CityOpenDataRepository` round trip on H2 via the seeder test (migration applies).

## Live-test

Not needed as a server run: no endpoint, no consumer. The fetchers were exercised against the real endpoints during step 2 (evidence below).

## Contract impact

none

## i18n impact

none (no UI)

## Blast radius

New table and new package only. The seeder writes up to 12 rows at startup (insert-if-absent, errors swallowed and logged). The weekly job makes outbound HTTPS calls to api.insee.fr, data.enseignementsup-recherche.gouv.fr and igss.gouvernement.lu (public, no key); failures leave the stale row.

## Risks

- Source formats change (Melodi JSON, Opendatasoft fields, IGSS xlsx sheet name / header labels): fetch fails → stale row kept, logged; values never invented.
- IGSS xlsx is ~6.6 MB (61 MB uncompressed sheet): streamed with StAX, download capped at 32 MB.
- Melodi limit is per anonymous caller; throttle is per JVM (same caveat as the geocoder).
- `city_key` is `EventNormalization.cityKey` of the display name (e.g. `metz`); only cities in `cities.csv` resolve.

## Definition of done

Migration V139 applies on H2; seed rows for Metz/Nancy/Thionville present with source + licence + period; Metz 38,065 / 20,588 / 6,330 pinned; rate-limit backoff, cache hit, expired refetch tested; `./mvnw test` green after rebase on latest origin/master.

## Live-test evidence

2026-09-27, the real fetchers (OpenDataConfig's client) against the live sources, from a throwaway test that was then deleted:
```
metz       insee 2023 pop_18_35=38065 pop_total=122572 | mesr 2024-25 students=20588 | igss 2026-03-31 6330
nancy      insee 2023 pop_18_35=43154 pop_total=103671 | mesr 2024-25 students=30903 | igss 2026-03-31 370
thionville insee 2023 pop_18_35=10473 pop_total=42658  | mesr 2024-25 students=605   | igss 2026-03-31 10110
```
Identical to the committed seed CSVs. IGSS parsed the full 6.6 MB workbook (61 MB sheet) via the streaming reader.
Baseline `./mvnw test` on untouched origin/master: green. After rebase onto origin/master c6905493: `./mvnw test` 4331 run, 0 failures, 0 errors; `CityOpenDataSeederTest` re-run green after a last clock-pinning edit.

## Review rounds

(appended by /do-task)
