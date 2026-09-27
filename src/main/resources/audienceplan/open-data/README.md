# Open-data seed for `city_open_data`

Small extracts of public, aggregate statistics for Metz, Nancy and Thionville. `CityOpenDataSeeder`
inserts a row per `(city_key, dataset)` at startup only when none exists; `PublicDataService` and the
weekly `CityOpenDataRefreshJob` replace them from the live sources when they expire. No personal data.

All values were downloaded on **2026-09-27** from the official sources below. For `insee_age`,
`students` and `frontaliers` the `source_url` column is the exact request the runtime fetcher sends.
OSM has no fetcher: its `source_url` is the OSM copyright page the attribution must link to, and the
Overpass query that produced the counts is recorded below. Nothing here is estimated or typed by hand;
a figure a source did not give is left empty, never 0.

| File | Dataset key | Source | Licence | Attribution to show |
|---|---|---|---|---|
| `insee_age.csv` | `insee_age` | INSEE, recensement RP2023, "Population détaillée par sexe et âge (POP1)", Melodi dataset `DS_RP_TD_POPULATION_AGESEX_PRINC` (published 25.06.2026). `pop_18_35` = sum of single-year ages 18 to 35, `SEX=_T`, rounded once (INSEE publishes weighted, fractional counts). | Licence Ouverte 2.0 (Etalab) | "Source : Insee, recensement de la population" |
| `students.csv` | `students` | MESR, "Atlas régional des effectifs d'étudiants inscrits" (Opendatasoft API `fr-esr-atlas_regional-effectifs-d-etudiants-inscrits`), `niveau_geographique = Commune`, `regroupement = TOTAL`, latest `annee_universitaire`, `sum(effectif)` grouped by year server-side (a year where any row lacks `effectif` is rejected). | Licence Ouverte 2.0 (Etalab) | "Source : MESR, Atlas régional des effectifs d'étudiants" |
| `frontaliers.csv` | `frontaliers` | IGSS Luxembourg, "Personnes en emploi par commune de résidence en France, genre et statut" (xlsx, sheet "Données source"), linked from data.public.lu dataset `emploi-total-par-commune-de-residence-au-luxembourg-et-dans-les-pays-frontaliers`. Latest reference date, sum over genre and status. Semi-annual. | CC0 1.0 | "Source : IGSS Luxembourg" |
| `osm_venues.csv` | `osm_venues` | OpenStreetMap via one Overpass API query per commune (below), OSM base 2026-09-27T07:51Z. Counts only. | ODbL 1.0 | "© OpenStreetMap contributors" |

OSM query (POST to `https://overpass-api.de/api/interpreter`), one per commune with `<code>` = its
INSEE code (57463, 54395, 57672); the four `out count` totals are nightclub, bar, pub, music_venue.
Re-run for Metz at OSM base 2026-09-27T08:30Z: 3, 69, 14, 1, unchanged.

```
[out:json][timeout:60];
area["ref:INSEE"="<code>"]["boundary"="administrative"]->.c;
nwr["amenity"="nightclub"](area.c);out count;
nwr["amenity"="bar"](area.c);out count;
nwr["amenity"="pub"](area.c);out count;
nwr["amenity"="music_venue"](area.c);out count;
```

Cross-checked against `audience-tool/data-sources-v1.md` §1.1, which states: Metz 18-35 = 38,065,
students 20,588, frontaliers 6,330; Nancy students 30,903, frontaliers 370; Thionville frontaliers
10,110; Metz OSM nightclub 3, bar 69. The other seed figures (Metz `pop_total`, Thionville students,
OSM `pub` and `music_venue`, and every Nancy and Thionville OSM count) have no figure in the spec to
check against; they come only from the queries above.

## Licence notes

- Licence Ouverte 2.0 and CC0 allow storage, commercial reuse and showing the figures to organizers.
  Keep the attribution next to any figure shown. Licence Ouverte also requires the date of last
  update, so a displayed figure reads `<attribution>, <ref_period>, mis à jour le <fetched_at date>`,
  e.g. "Source : Insee, recensement de la population, 2023, mis à jour le 27/09/2026" — the attribution
  is `OpenDataset.attribution()` (stored in the `attribution` column), the rest are the row's own fields.
- **OpenStreetMap is ODbL.** Showing OSM data is a "Produced Work" and needs the attribution
  "© OpenStreetMap contributors" with a link to https://www.openstreetmap.org/copyright. Merging or
  de-duplicating OSM venues into a venue list of ours makes a Derivative Database, which is
  **share-alike**: we would have to offer that database under ODbL. Keep OSM as its own layer (this table
  stores only per-commune counts from OSM, nothing merged).
- A handful of per-commune counts is an insubstantial extract. If the city list grows until these
  counts amount to a substantial part of OSM for a region, the table itself becomes a Derivative
  Database under ODbL and must be offered share-alike; re-check before adding many cities.
- There is **no runtime OSM fetcher**. The public Overpass server must not be a production backend
  (Overpass usage policy); regular refreshes need a self-hosted Overpass or an OSM extract.
  TODO: add an OSM fetcher against a self-hosted endpoint before relying on fresh venue counts.

## Do not add

Scraped data of any kind: Resident Advisor, Shotgun, Instagram, Threads, Facebook, TikTok, Shazam,
Beatport, Spotify pages or API metrics, Google Places. Never individual-level data, and never community
or origin figures below the aggregate level INSEE publishes (GDPR art. 9). See
`audience-tool/data-sources-v1.md` §4.

## Adding a city

Add a line to `cities.csv` (`city_key` = `EventNormalization.cityKey` of the name, INSEE commune code,
département as IGSS spells it). The refresh job fetches its datasets on the next run; seed rows are
optional.
