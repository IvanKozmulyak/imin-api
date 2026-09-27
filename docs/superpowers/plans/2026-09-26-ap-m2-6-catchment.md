# ap-m2-6-catchment

Task M2-6 of `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (after M2-4, shipped as api `9a088fee`).

## Goal and scope

`CatchmentService`: from an event's `venue_latitude/longitude`, the known towns within a straight-line radius, each with its straight-line km and an `ownScene` flag. Consumers: M2-8 (plan `newPeople`, `gapExceedsTribe` "tribe size within the catchment") and M2-7 (portrait `catchment`). Nothing reads it yet, so there is no endpoint and no contract change.

Rules carried forward verbatim from the programme plan and the spec:
- M2-6: "Straight-line km from the event's `venue_latitude/longitude` (set by geocoding; null → catchment `null`) to candidate communes (INSEE centroids cached in `city_open_data`), ≤ ~60 min proxy radius from logic YAML; `own_scene` flag from the portrait (null until M2-7)."
- M2-6 tests: "Metz fixture distances; null coordinates → null; radius boundary."
- §5: "Catchment Metz, straight line: Thionville 27 km, Nancy 48 km, Luxembourg 55 km, Saarbrücken 61 km (± 1 km with the coordinates pinned in the test)."
- tech-spec §7.6: "**Catchment:** cities within ~60 minutes (straight-line from coordinates first), with "own scene" flags."
- tech-spec §5: `catchment jsonb -- [{city_key, km_straight, own_scene: bool}]`.
- §4.4: "`unknown` is a legal value and flows to the API as `null` / `"unknown"`, never as 0."
- §4.4 title: "Logic files (resources, versioned, never single numbers)" — the radius is a threshold (like `modes`, `coverage_verdict`), not a rate, so it is one integer; it is flagged as an assumption in the YAML.
- tech-spec: "`city_open_data` -- shared cache, public data only".
- Runner: no fabricated numbers; never call geocoding/Overpass with a personal identifier in headers or User-Agent.
- Runner: no beta gating, no new per-feature enable flags. D10: "M2-4..M2-6 standalone."

Design (deviations from the programme plan wording, with reasons):
- **Centroid source.** "INSEE centroids" cannot cover the §5 fixture: Luxembourg and Saarbrücken are not French communes. One source for every town instead: the Wikidata coordinate (P625, town centre) of each town, **CC0 1.0**, pinned per row by Wikidata revision permalink. Stored as a new `city_open_data` dataset `centroid` (payload `lat_e6`, `lon_e6` in microdegrees, since payload figures are integers; no headline), seed only (no runtime fetcher; towns do not move; TTL 3650 days, and a stale centroid is still used). Downloaded 2026-09-27 with a non-personal User-Agent (`imin-api open-data loader (+https://imin.wtf)`).
- **Cross-border towns in the registry.** `cities.csv` gains Luxembourg (`LU`) and Saarbrücken (`DE`) with blank INSEE code / département. `OpenDataset` gains the countries each dataset covers (census, students, IGSS, OSM counts: `FR`; centroid: `FR`, `LU`, `DE`). `PublicDataService.get/refresh` return empty for a dataset that does not cover the city's country and never call its fetcher (so no INSEE/MESR/IGSS request is ever built for a non-French town); the refresh job skips those pairs.
- **Radius.** `logic-v1.yaml` `catchment.radius_km: 70` (assumption: straight-line proxy for ~60 min by road; Saarbrücken at 61 km straight is ~70 km / under an hour by motorway). A town is in when its **rounded** km ≤ radius, so a shown km never contradicts the radius. Loader: positive integer.
- **Result.** `Optional<Catchment>`: empty (→ `null`) when coordinates are null, not finite or out of range, **or when no known town is within the radius** (the registry is small; an empty list would read as "nobody nearby" when it only means "no data"). `Catchment(radiusKm, towns[])`, towns sorted by km then key, each `Town(cityKey, name, country, kmStraight, ownScene=null)`. The venue's own town is included (km ≈ 0): M2-5/M2-8 count its people in the tribe.
- Great-circle km: haversine on the mean Earth radius 6371.0088 km, `engine/GreatCircle`.

Out of scope: tribe size (M2-5, concurrent runner; its keys in priors/logic YAML untouched), portraits and `own_scene` (M2-7), endpoints/wiring (M2-8), a runtime centroid fetcher, more towns.

Reproduction test: n-a (new code).

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m2-6-catchment` | `./mvnw test` |

## Affected files

api:
- new `src/main/java/com/imin/iminapi/audienceplan/engine/GreatCircle.java`
- new `src/main/java/com/imin/iminapi/audienceplan/service/CatchmentService.java`, `Catchment.java`
- `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanLogic.java` (`Logic.catchment`), `LogicLoader.java`
- `src/main/resources/audienceplan/logic-v1.yaml` (`catchment` key only)
- `src/main/java/com/imin/iminapi/audienceplan/opendata/OpenDataset.java` (`CENTROID`, `countries`)
- `src/main/java/com/imin/iminapi/audienceplan/service/PublicDataService.java`, `CityOpenDataRefreshJob.java` (country guard)
- `src/main/resources/audienceplan/open-data/cities.csv`, new `centroid.csv`, `README.md`
- tests: new `engine/GreatCircleTest`, `service/CatchmentServiceTest`; edited `config/LogicLoaderTest`, `opendata/OpenDatasetTest`, `opendata/OpenDataSeedTest`, `service/PublicDataServiceTest`, `service/CityOpenDataRefreshJobTest`, `service/CityOpenDataSeederTest` (if counts move), `engine/CandidateBuilderTest` + `service/ConsentGateScenarios` (new `Logic` component passed through)

## Ordered steps

1. Baseline `./mvnw test` on the untouched worktree.
2. Tests first: GreatCircle, loader radius, dataset countries, PublicDataService country guard, refresh-job skip, seed invariants per country, CatchmentService branches.
3. `GreatCircle`, `catchment.radius_km` in logic YAML + loader, `OpenDataset.CENTROID` + countries, guard in `PublicDataService` + job, seed rows + README, `CatchmentService`.
4. `./mvnw test`; read the diff for comment rules.

## Verification commands

- `./mvnw test` (from the worktree root)

## Test impact

New, one per branch:
- `GreatCircleTest`: same point 0; Metz→Luxembourg 54.78 km; symmetric.
- `LogicLoaderTest`: shipped radius 70; `radius_km: 0` fails; missing `catchment` fails.
- `OpenDatasetTest`: centroid CC0, no headline, covers FR/LU/DE; census/students/IGSS/OSM cover FR only.
- `PublicDataServiceTest`: `get` of a dataset not covering the city's country is empty even with a stored row; `refresh` of one never calls the fetcher.
- `CityOpenDataRefreshJobTest`: a non-French city is refreshed for centroid only.
- `CatchmentServiceTest` (real seed rows through the seeder into the in-memory repo): Metz fixture (venue pinned at Wikidata Metz 49.119722, 6.176944): Metz 0, Thionville 27, Nancy 48 (±1), Luxembourg 55, Saarbrücken 61, sorted, `ownScene` null, radius 70; null latitude → empty; null longitude → empty; non-finite / out-of-range → empty; radius boundary (radius 61 includes Saarbrücken at 61.15 km, radius 60 excludes it); a town without a centroid row is skipped; no known town in radius → empty; a stale centroid row is still used; `forEvent` reads the event's venue coordinates and null event coordinates → empty.
Edited: `OpenDataSeedTest` "every city has every dataset once" and "source URLs are the fetchers'" now iterate the datasets covering each city's country (the old loops assumed every city is French); centroid seed values pinned. `CandidateBuilderTest`/`ConsentGateScenarios` pass `o.catchment()` through.

## Live-test

Not needed: no endpoint, no wiring; the service is exercised end to end over the real seed rows and the real H2 migration via the seeder test.

## Contract impact

none

## i18n impact

none (api only, no UI strings)

## Blast radius

Audience-plan package only. `PublicDataService` now refuses non-covered (country, dataset) pairs; before this task every registered city was French, so behaviour for Metz/Nancy/Thionville is unchanged. Two new cities add two `centroid` rows each startup-seeded (plus three French centroid rows). No migration.

## Risks

- M2-5 runs concurrently: it may also edit `AudiencePlanLogic`/`LogicLoader` (priors) or read `knownCities()`; a non-French city now has no `insee_age` row, so a sum over the catchment must treat missing census as unknown (M2-5 rule: "missing census → `null` sizes, never 0"). Merge conflicts, if any, are in different records/keys.
- Radius 70 is a guess to calibrate, like every threshold in `logic-v1.yaml`.
- Wikidata points are town centres, not area centroids; for a straight-line travel proxy this is the better point, and it matches the §5 fixture.

- Catchment includes non-FR towns with no census; M2-8 sums tribe size over FR towns only and labels the scope ("French part of your area"), never passes LU/DE keys to TribeSizeCalculator (decided in review r1).

## Definition of done

- `./mvnw test` green; every branch above has a test; §5 catchment fixture pinned.
- No endpoint, no contract change, no migration; no external call at runtime.

## Live-test evidence

n-a

## Review rounds

(appended by /do-task)
- round 1 → SHIP (MEDIUM: M2-8 census scope rule recorded; LOW: README centroid-correction note added)
