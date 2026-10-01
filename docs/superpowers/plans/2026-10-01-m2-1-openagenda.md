# M2-1 Open-data events → weekly counts (2.6, 5.3, 2.3)
m2-1-openagenda · Subagent · Notion: M2-1 card (link from /do-task)

## Goal and scope
Feed three date-check questions from public event listings (model spec §9 rows 2.3, 2.6, 5.3; date-check plan §M2-1):
- **2.6** this week's count of events in the check's genre bucket, compared with the city's norm (the median of the last 12 closed weeks; `not_checked` until 12 comparable weeks exist);
- **5.3** a local city-wide event listed on the night;
- **2.3** a recurring party in the genre (weekly or nth/last weekday of the month, at least 3 times) whose pattern lands on the date.

Sources available now:
- **OpenAgenda v2.** `OPENAGENDA_API_KEY` (public `oa_pk_…`) is already set on Railway.
- **Que Faire à Paris** (opendata.paris.fr, no key).

**DATAtourisme** has no key yet. This plan designs `OpenEventSource` so it plugs in without a migration (the source CHECK value and a `credit` column for its mandatory `hasBeenCreatedBy`). Its client is not built.

Stored data, per plan: titles, URLs, night dates, matched genre and community keys, and a licence on every row. No descriptions, images or addresses. Events that match no genre or community are never stored.

**Split (recommended):**
- **M2-1a — ingest (part A below):** table, clients, matcher, cities config, writer, job, gates, flags. It touches no predictor output, so it can ship early. Turning the flags on starts the 12-week history (plus a backfill where the source serves past events).
- **M2-1b — answer (part B):** evaluator, recurrence detector, bank v4, `sources.yaml` entries, `usedFor: local_events`.
- **M2-1c (not planned here):** 2.3 from imin's own history (same org, same city and genre, weekday-of-month at least 3 times) as an `internal` row.

Out of scope:
- the DATAtourisme client;
- feeding 2.1/2.2 from open listings;
- sub-genre counts (rows are written at family level, `sub_genre = ''`);
- the webapp and imin-public changes (follow-up cards, see Contract impact and i18n impact).

## Repos in ship order
1. `api` (imin-api, base `master`): M2-1a, then M2-1b.

There is no FE step in either task. Follow-ups that must ship before any flag is turned on:
- imin-public: `local_events` label in EN/ES/FR/UK, plus PUBLIC_PAGE_API §28;
- imin-webapp: copy for 2.6/5.3/2.3, the `too_far_ahead` reason, `templateFacts`, `template-keys.txt`.

## Affected files (per repo)
### imin-api — worktree `/Users/ivan/imin/imin-api/.claude/worktrees/m2-1-openagenda`
Paths below are relative to `src/main/java/com/imin/iminapi/` unless they start with `src/` or `.`.

**Part A (M2-1a), 31 files**

New main (14):
1. `src/main/resources/db/migration/V165__open_event_occurrence.sql`. V165 is free: origin/master tops at V164, and the only other worktree (`dashboard-truth-bugs`) has no V165.
2. `predictor/model/OpenEventOccurrence.java` — entity.
3. `predictor/repository/OpenEventOccurrenceRepository.java` — `@RepositoryRestResource(exported = false)`.
4. `predictor/sources/openevents/OpenEventSource.java` — interface plus the `RawEvent`/`Fetch` records.
5. `predictor/sources/openevents/OpenAgendaClient.java`
6. `predictor/sources/openevents/QueFaireAParisClient.java`
7. `predictor/sources/openevents/OpenEventCities.java` — strict loader for `open-events-v1.yaml`.
8. `predictor/sources/openevents/GenreMatcher.java` — strict loader for `genre-keywords-v1.yaml`, plus matching.
9. `predictor/sources/openevents/OpenEventsProperties.java`
10. `predictor/sources/openevents/OpenEventsConfig.java` — RestClient builder with timeouts, the `openEventsSyncExecutor`, and the loader beans.
11. `predictor/sources/openevents/OpenEventsWriter.java`
12. `predictor/sources/openevents/OpenEventsJob.java`
13. `src/main/resources/predictor/open-events-v1.yaml`
14. `src/main/resources/predictor/genre-keywords-v1.yaml`

Edited main (6):
15. `predictor/sources/SourceGates.java` — constructor takes `OpenEventsProperties`; new keys `openagenda` and `quefaireaparis`; class javadoc.
16. `predictor/repository/GenreWeekCountRepository.java` — queries:
    - `findByCityKeyAndGenreFamilyAndSubGenreAndWeekStartBetween`
    - `findByCityKeyAndWeekStartIn`
    - `findTopByCityKeyOrderByUpdatedAtDesc`
    - `findTopByCityKeyOrderByWeekStartDesc`
17. `predictor/model/GenreWeekCount.java` — javadoc gives the `sources_json` shape. No code change.
18. `src/main/resources/application.yaml` — `imin.predictor.open-events` block.
19. `.env.example` — `PREDICTOR_OPENAGENDA_ENABLED`, `OPENAGENDA_API_KEY`, `PREDICTOR_QUEFAIREAPARIS_ENABLED`.
20. `CLAUDE.md` — config entry: flags, key, table, job, ShedLock, executor.

New test (9):
21. `src/test/java/com/imin/iminapi/predictor/sources/openevents/OpenAgendaClientTest.java`
22. `.../openevents/QueFaireAParisClientTest.java`
23. `.../openevents/OpenEventCitiesTest.java`
24. `.../openevents/GenreMatcherTest.java`
25. `.../openevents/OpenEventsWriterTest.java`
26. `.../openevents/OpenEventsJobTest.java`
27. `.../openevents/OpenEventsPropertiesTest.java`
28. `src/test/resources/predictor/openevents/openagenda-events.json` — a real response, trimmed.
29. `src/test/resources/predictor/openevents/quefaireaparis-records.json` — a real response, trimmed.

Edited test (2):
30. `src/test/java/com/imin/iminapi/predictor/DataSourceCatalogTest.java` — builds `SourceGates` by hand, so it gets the new constructor argument. Expected lists are unchanged in part A (no `sources.yaml` entry yet).
31. `src/test/java/com/imin/iminapi/migration/DateCheckMigrationScenarios.java` — V165 `CheckCase`s for the source CHECK, the licence CHECK and the unique key. These run on H2 and Postgres through `DateCheckMigrationH2Test` / `DateCheckMigrationPostgresTest`.

**Part B (M2-1b), 15 files**

New main (2):
1. `predictor/rules/OpenEventsEvaluator.java` — STRUCTURED; answers {"2.6", "5.3", "2.3"}; `evaluateAll` shares one load per date.
2. `predictor/rules/RecurrenceDetector.java` — package-private static logic, tested directly.

Edited main (5):
3. `src/main/resources/predictor/question-bank-v2.yaml` — the three entries; `version: 3` → `4`; header comment.
4. `predictor/rules/Finding.java` — the facts-keys javadoc gains `norm, weekStart, lastSeen`. No code change.
5. `src/main/resources/predictor/sources.yaml` — entries `openagenda` and `que-faire-a-paris`; `reviewedOn`.
6. `predictor/sources/DataSourceCatalog.java` — `USED_FOR` gains `local_events`.
7. `CLAUDE.md` — evaluator paragraph (reasons, thresholds, the "before flipping" list).

New test (2):
8. `src/test/java/com/imin/iminapi/predictor/rules/OpenEventsEvaluatorTest.java`
9. `src/test/java/com/imin/iminapi/predictor/rules/RecurrenceDetectorTest.java`

Edited test (6):
10. `.../predictor/rules/QuestionBankTest.java` — `qb3-gp1` → `qb4-gp1`.
11. `.../predictor/rules/RuleEngineTest.java` — stub list gains "2.6", "5.3", "2.3" on STRUCTURED; `everyShippedQuestionHasAnEvaluator` gains `new OpenEventsEvaluator(...)`; its inline bank `version: 3` stays (it is a fixture).
12. `.../predictor/DateCheckControllerTest.java` — the Paris/FR `notChecked` assertion also contains `2.6:source_off`, `5.3:source_off`, `2.3:source_off`.
13. `.../predictor/DataSourceCatalogTest.java` — new expectations for the two entries.
14. `src/test/resources/predictor/template-keys.txt` — regenerate (`-Dpredictor.writeTemplateKeys=true`).
15. `src/test/java/com/imin/iminapi/predictor/PublicPredictorSourcesControllerTest.java` — needs no edit if it runs with default gates (flags off → output unchanged). The implementer greps it for the catalog id list and edits it only if it asserts the full YAML id list.

## Ordered steps
### Part A (M2-1a)
A0. **Base gate and upstream check.**
- Run the check command on the untouched worktree and record the result.
- Then check, with one real GET each, and record each result in the commit message. Until then every item below is **unverified**.
- **OpenAgenda v2** (developers.openagenda.com):
  - base `https://api.openagenda.com/v2`;
  - `GET /agendas?search=<city>&official=1` for discovery;
  - `GET /agendas/{agendaUid}/events` with the key in the **`key` header** (never `?key=`, which ends up in logs);
  - parameters `size` (max 300?), `timings[gte]`, `timings[lte]`, `after[]` cursor, `monolingual=fr`, `includeFields[]`, `detailed`;
  - response `{ total, events[], after }`;
  - event fields `uid, slug, title, keywords, timings[{begin,end}], location{city,postalCode,countryCode}, status, attendanceMode, state` and the meaning of each status/mode code;
  - the public event-page URL pattern;
  - rate limits (none published per the keys guide);
  - the data licence: the OpenAgenda CGU statement for published agendas (keys guide says Licence Ouverte), and each chosen agenda's own page.
- **Que Faire à Paris:**
  - dataset `que-faire-a-paris-` on `opendata.paris.fr`;
  - the Explore v2.1 `records` endpoint, its `where` syntax and limit/offset caps (offset+limit ≤ 10000?);
  - fields `id, url, title, date_start, date_end, occurrences, tags/qfap_tags, address_city, address_zipcode, access_type` (or their real names);
  - whether past events stay in the dataset (decides `backfills()`);
  - the licence (ODbL assumed) and the attribution text;
  - anonymous quotas.

A1. **Migration V165.**
```sql
-- V165: open-data event nights (OpenAgenda, Que Faire à Paris; DATAtourisme later) that matched a genre or local-event keyword.
-- Titles and links only, pruned after 200 days; licence kept per row so ODbL rows stay separable.
CREATE TABLE open_event_occurrence (
  id UUID PRIMARY KEY,
  source VARCHAR(32) NOT NULL,
  source_event_id VARCHAR(64) NOT NULL,
  city_key VARCHAR(100) NOT NULL,
  night_date DATE NOT NULL,
  title VARCHAR(255) NOT NULL,
  title_key VARCHAR(255) NOT NULL,          -- normalised title, cross-source dedup and recurrence grouping
  url VARCHAR(512) NOT NULL,
  genre_keys TEXT NOT NULL DEFAULT '[]',    -- JSON array of bucket names
  community BOOLEAN NOT NULL DEFAULT FALSE,
  licence VARCHAR(32) NOT NULL,
  credit VARCHAR(255),                      -- per-row author credit (DATAtourisme hasBeenCreatedBy); null today
  synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uq_open_event_occurrence UNIQUE (source, source_event_id, night_date),
  CONSTRAINT ck_open_event_occurrence_source CHECK (source IN ('openagenda','quefaireaparis','datatourisme')),
  CONSTRAINT ck_open_event_occurrence_licence CHECK (licence IN ('Licence Ouverte 2.0','ODbL 1.0')));
CREATE INDEX ix_open_event_occurrence_city_night ON open_event_occurrence (city_key, night_date);
```
Each limit is enforced at load or write time:
- `source` comes from `OpenEventSource.id()` constants; `OpenEventsConfig` fails boot if a source bean's id is outside `ck_open_event_occurrence_source`.
- `licence`: `OpenEventCities` rejects any agenda licence outside `ck_open_event_occurrence_licence` at boot; the QFAP licence is a code constant asserted in a test.
- `city_key` ≤ 100: the loader rejects longer keys.
- `title` is cut at 255 chars.
- A `url` over 512 chars drops the row (counted in the WARN).
- A `source_event_id` over 64 chars drops the row.

`genre_week_count` (V162, shipped) is unchanged. Its existing constraints apply: `uq_genre_week_count UNIQUE (city_key, genre_family, sub_genre, week_start)` and `ck_genre_week_count_nonneg CHECK (event_count >= 0)`.

A2. **Entity and repository.** `OpenEventOccurrenceRepository`:
- `findByCityKeyAndNightDateBetween(String, LocalDate, LocalDate)`;
- `deleteBySourceAndCityKeyAndNightDateGreaterThanEqual(String, String, LocalDate)`;
- `deleteByNightDateBefore(LocalDate)`;
- `existsByCityKey(String)`.

No nullable String parameters in any query (H2/PG bytea trap).

A3. **Properties** (`imin.predictor.open-events`; the same defaults in Java and yaml):
```yaml
open-events:
  openagenda-enabled: ${PREDICTOR_OPENAGENDA_ENABLED:false}
  openagenda-api-key: ${OPENAGENDA_API_KEY:}
  quefaireaparis-enabled: ${PREDICTOR_QUEFAIREAPARIS_ENABLED:false}
```
- Blank binds `""` / false.
- `openagenda-enabled=true` with a blank key fails startup.
- `openagenda-enabled=true` with a key that does not start with `oa_pk_` fails startup. A secret `oa_sk_` key can write, and imin never needs that.
- Both messages name the variables, never the value.
- `toString` masks the key.

A4. **`OpenEventSource` interface** (the DATAtourisme plug point):
```java
public interface OpenEventSource {
  String id();                 // ck_open_event_occurrence_source value
  String gate();               // SourceGates key
  boolean backfills();         // serves past events (OpenAgenda true; QFAP per A0)
  boolean covers(CityConfig c);
  List<RawEvent> fetch(CityConfig c, LocalDate from, LocalDate to); // throws OpenEventsRateLimitedException on 429
}
record RawEvent(String sourceEventId, String title, String url, List<LocalDate> nights,
                List<String> keywords, String licence, String credit) {}
```
- `nights` are already filtered and converted to night dates by the client (see A5).
- A future `DatatourismeClient` implements this with `gate()` = `datatourisme`, licence `Licence Ouverte 2.0`, and `credit` = `hasBeenCreatedBy`.

A5. **Clients.** Both use `RestClient` with connect 5 s and read 30 s, built like `WikimediaConfig`/`CalendarConfig`.

Every upstream classification field is filtered, with one test per excluded value. A row that is missing a classification field is dropped as unknown, never stored as the general kind.

`OpenAgendaClient`:
- Calls each configured agenda UID for the city, sequentially, following the cursor up to `MAX_PAGES = 20` per agenda. Hitting the cap → WARN; the city is marked partial.
- Kept only when all of these hold:
  - status ∈ {scheduled, rescheduled, full}; dropped: postponed, cancelled, moved-online, unknown or missing;
  - attendanceMode ∈ {offline, mixed}; online or missing is dropped;
  - `state` is published (if returned);
  - `location.countryCode` is `FR`;
  - `cityKey(location.city)` ∈ the city's `aliases`; a blank or missing city is dropped and never defaulted to the agenda's city;
  - the title (fr, else first language) is non-blank;
  - each timing lasts ≤ 24 h (exhibitions and season-long runs are dropped).
- Night date = begin in Europe/Paris; a begin before 06:00 belongs to the previous day (same rule as `FootballFixturesSync`).
- `keywords` = the event's fr keywords. The description is never read.
- `url` = the A0-verified event-page pattern.
- 429 → `OpenEventsRateLimitedException`. Other non-2xx, I/O errors or non-JSON bodies throw.

`QueFaireAParisClient`:
- `covers` only `paris`.
- Pages the records endpoint with a date `where` over [from, to], within the A0-verified offset cap. Reaching the cap → WARN plus partial.
- Kept only when:
  - `address_zipcode` matches `75\d{3}` or `cityKey(address_city)` = `paris`;
  - the occurrences parse (else `date_start`/`date_end` ≤ 24 h);
  - the title is non-blank;
  - it is not online-only (the field named at A0).
- Keywords = tags / qfap_tags.
- Licence constant `ODbL 1.0` (A0).
- 429 → rate-limited exception.

A6. **`open-events-v1.yaml` + `OpenEventCities`.**
```yaml
version: 1
verified_on: <date the agendas were checked>
cities:
  metz:
    country: FR
    aliases: [metz]
    openagenda:
      - { uid: <verified>, slug: <verified>, name: "<agenda title>", licence: "Licence Ouverte 2.0" }
  paris:
    country: FR
    aliases: [paris]
    quefaireaparis: true
    openagenda: []
```
- Candidate cities: the FR rows of `audienceplan/open-data/cities.csv` (metz, nancy, thionville), the keys of `CalendarRegions.BORDER_NEIGHBOURS` (metz, thionville, strasbourg, mulhouse, lille), and paris. That is 7 cities.
- A city ships only with agendas found through `/v2/agendas?search=<city>&official=1` whose licence is confirmed on the agenda page. No UID is invented; a city with none is left out, and the evaluator answers `no_source`.

Loader rules (SnakeYAML with `setAllowDuplicateKeys(false)`):
- unknown keys are rejected at root, city and agenda level;
- city key = `EventNormalization.cityKey(key)` and ≤ 100 chars;
- `country` is `FR` only;
- `aliases` are non-empty, already normalised, and unique across cities;
- `uid` is a positive integer, unique;
- `slug` is non-blank;
- `licence` ∈ {`Licence Ouverte 2.0`} for openagenda, which is the subset of `ck_open_event_occurrence_licence` that `sources.yaml` credits;
- `quefaireaparis` is allowed only on `paris`;
- a city needs at least one source;
- `version` ≥ 1; `verified_on` is an ISO date.

A7. **`genre-keywords-v1.yaml` + `GenreMatcher`.**
```yaml
version: 1
# Whole-word, accent-insensitive matches on title + upstream keywords/tags; never the description.
buckets:
  "house & techno": [techno, house music, deep house, tech house, minimal, acid house, rave]
  # ... one list per QuestionBank.GENRE_BUCKETS entry
exclude_phrases: [open house, portes ouvertes, house party privee]
local_events: [carnaval, braderie, fete de la musique, nuit blanche, marche de noel, fete foraine, parade, defile, fete de la ville]
```
Loader rules:
- duplicate keys rejected; unknown root keys rejected;
- bucket names must equal `QuestionBank.GENRE_BUCKETS` exactly (missing and extra both fail);
- keywords are lowercase, ASCII after NFD accent strip, 2–64 chars, unique within their list;
- a keyword in two buckets is allowed;
- `version` ≥ 1.

`match(title, keywords)`:
- normalise: NFD, strip marks, lowercase, non-alphanumerics → single spaces;
- if any `exclude_phrases` entry is present, return no genre;
- return the set of buckets with any whole-phrase hit (an event can count in more than one bucket);
- `community` = any `local_events` hit.

An event with no genre and no community match is not stored (`unmatchedGenreIgnored`). The keyword lists are product estimates, and the file header says so.

A8. **Writer** (`OpenEventsWriter`, `@Transactional` methods, no `ON CONFLICT` so it runs on H2).
- `replaceFuture(source, cityKey, from, rows, syncedAt)`:
  1. delete that source's city rows with `night_date ≥ from`;
  2. insert the matched rows, one per (event, night);
  3. a duplicate (source, id, night) in one batch is kept once.
- `insertBackfill(...)`: past nights only, and only when the city has no rows yet.
- `recount(cityKey, weeks, sourceSet, syncedAt)`:
  - for each ISO week (Monday start) in `weeks` and each bucket in `GENRE_BUCKETS`, upsert `genre_week_count(city, bucket, '', week)`;
  - `event_count` = the number of distinct `(title_key, night_date)` pairs in that week whose `genre_keys` contain the bucket, across all sources (dedup across OpenAgenda and Que Faire à Paris);
  - `sources_json` = `[{"source":"openagenda","licence":"Licence Ouverte 2.0","events":n}, …]` for **every source in `sourceSet`**, including those with 0 events, so the row records coverage as well as credit;
  - zero counts are written explicitly;
  - counts are always re-derived from stored rows, never applied as a delta.
- `prune(today)`: delete `night_date < today − 200`.

A9. **Job** (`OpenEventsJob`, modelled on `WikimediaPageviewsJob`).
- `@Scheduled(cron = "0 45 5 * * MON", zone = "Europe/Paris")` + `@SchedulerLock(name = "open_events_sync", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")`.
- `run()` returns at once unless `gates.isOn("openagenda") || gates.isOn("quefaireaparis")`.
- Planned work = every (city, source) pair whose source gate is on and that covers the city.
- Window: `from` = Monday of the previous ISO week (Paris date); `to` = today + 120 days.
- When the city has no rows yet and the source `backfills()`, also fetch [today − 182, from).
- Per city:
  - fetch each source;
  - success → `replaceFuture` (and backfill);
  - a source failure (or a page cap) leaves that source's rows untouched and marks the city partial;
  - `recount` runs over the window's weeks only if every planned source for the city succeeded, else WARN "counts kept from last run". Backfilled weeks are recounted with only the backfilling sources in `sourceSet`.
- 429 → that source is skipped for the rest of the run, and each remaining pair counts as failed.
- Then `prune`.
- Log level, judged against planned pairs:
  - no pair stored and ≥ 1 failed → `log.error(..., lastThrowable)`;
  - some failed → WARN;
  - all succeeded with zero matched rows → WARN;
  - else INFO with counts per drop reason.
- Startup: `@EventListener(ApplicationReadyEvent)`. If a gate is on and `open_event_occurrence` is empty, `openEventsSyncExecutor.execute(self.getObject()::run)`. Both the executor rejection and a run failure are caught and logged at WARN.
- The single-thread executor has queue 1.

A10. **Gates.** `SourceGates` gains:
- `"openagenda"` → `dateCheck.enabled && props.openagendaEnabled && !key.isBlank()`;
- `"quefaireaparis"` → `dateCheck.enabled && props.quefaireaparisEnabled`.

The javadoc lists the new keys. The job is the only network path. Evaluators (part B) also check the gates, so every path that reads or writes this data is gated.

A11. **Docs in the same edit:**
- `.env.example`: three variables with one-line comments.
- `application.yaml` comments.
- `CLAUDE.md` config entry (flags + key, `open_event_occurrence` V165 purpose, `genre_week_count` writer, job cron and ShedLock `open_events_sync`, `openEventsSyncExecutor`, drop rules, log levels).
- `GenreWeekCount` javadoc.

A12. **Tests** per Test impact. Then run the full check command.

### Part B (M2-1b)
B1. **Bank v4** (append after 9.1). All thresholds are estimates pending product review, and the YAML comment says so.
```yaml
  # Open listings (OpenAgenda, Que Faire à Paris), counted per ISO week by OpenEventsJob; estimates pending review.
  - id: "2.6"
    family: competition
    source: structured
    kinds: [risk, opportunity]
    weight: 1
    max_strength: 1
    window: week
    applies_when: { countries: [FR] }
    params: { min_weeks: 12, max_lead_days: 14, busy_ratio: 1.5, quiet_ratio: 0.5, min_norm: 2, max_age_days: 10 }
    template: predictor.q.2_6
  - id: "5.3"
    family: communities
    source: structured
    kinds: [risk]
    weight: 1
    max_strength: 1
    window: night
    applies_when: { countries: [FR] }
    params: { max_age_days: 10 }
    template: predictor.q.5_3
  - id: "2.3"
    family: competition
    source: structured
    kinds: [risk]
    weight: 2
    max_strength: 2
    window: night
    applies_when: { countries: [FR] }
    params: { min_occurrences: 3, lookback_days: 182, weekly_max_gap_days: 14, max_projection_days: 120, max_age_days: 10 }
    template: predictor.q.2_3
```
- None of the three is star (see Risks: a gated-off star lowers coverage on every FR check).
- No actions: existing action copy needs `{name}` or a theme that does not fit.

B2. **`RecurrenceDetector.match(List<LocalDate> nights, LocalDate date, params)`** → `Optional<Match(int count, LocalDate lastSeen)>`. Only nights in `[date − lookback_days, date]` are used.
- **Monthly** (checked first):
  - position = (weekday, ordinal `(dom−1)/7+1`, isLast `dom+7 > lengthOfMonth`);
  - a night matches when it has the same weekday and (same ordinal, or both are last);
  - hit when matching nights before `date` fall in ≥ `min_occurrences` distinct months and `date − latest ≤ max_projection_days`.
- **Weekly:**
  - take the same-weekday nights before `date`;
  - hit when the latest `min_occurrences` of them have consecutive gaps ≤ `weekly_max_gap_days` and `date − latest ≤ max_projection_days`.
- `count` = matching nights before `date`; `lastSeen` = the latest one strictly before `date`.

B3. **`OpenEventsEvaluator`** (`@Component`, STRUCTURED, {"2.6", "5.3", "2.3"}).

The constructor validates each question's params against its exact key set and these bounds, or fails boot:
- 2.6:
  - `4 ≤ min_weeks ≤ 26`;
  - `1 ≤ max_lead_days ≤ 60`;
  - `1 < busy_ratio ≤ 10`;
  - `0 < quiet_ratio < 1`;
  - `min_norm ≥ 1`;
  - `1 ≤ max_age_days ≤ 30`.
- 5.3: `1 ≤ max_age_days ≤ 30`.
- 2.3:
  - `2 ≤ min_occurrences ≤ 10`;
  - `28 ≤ lookback_days ≤ 182` (182 = the job's backfill and ≤ the 200-day retention);
  - `7 ≤ weekly_max_gap_days ≤ 28`;
  - `1 ≤ max_projection_days ≤ 182`;
  - `1 ≤ max_age_days ≤ 30`.

Shared branches, in order, for every question:
1. No source gate on for any source that covers the city → `source_off`.
2. City not in `OpenEventCities` → `no_source`.
3. No `genre_week_count` row for the city → `not_synced`.
4. Newest `updated_at` for the city older than `max_age_days` → `stale`.

**2.6:**
- `date − today > max_lead_days` → `too_far_ahead` (new reason).
- No row for (city, family, '', weekStart(date)) → `not_synced`.
- Closed weeks = `weekStart + 6 < today`, among the latest 26, whose `sources_json` source set equals the target row's; fewer than `min_weeks` → `no_data`.
- `norm` = median of the latest `min_weeks` closed counts; an even list takes the mean of the middle two.
- `norm < min_norm` → `low_volume`.
- `count/norm ≥ busy_ratio` → found RISK 1.
- `≤ quiet_ratio` → found OPPORTUNITY 1.
- Else clear.
- Facts: `date` = weekStart, `count`, `norm` (1 decimal), `n` = weeks used.
- `url` = the city's first agenda page (or the QFAP dataset page for Paris).

**5.3:**
- `date` after the city's newest `week_start + 6` → `too_far_ahead`.
- Rows for the city with `night_date = date` and `community = true` → found RISK 1.
- Facts: `name` = first title by `title_key` order, `date`, `count` = distinct `title_key`; `url` = that row's url.
- Else clear.

**2.3:**
- Group the city's rows in `[date − lookback, date]` whose `genre_keys` contain `in.genreFamily()` by `title_key`.
- The first group with a `RecurrenceDetector` hit, ordered by count desc then `title_key`, → found RISK 1.
- Facts: `name`, `count`, `lastSeen`, `date`; `url` = the latest row's url.
- Else clear.
- No genre family on the input → `not_provided`.

The evaluator reads tables only, with no network call inside the `@Transactional` check. `RuleEngine.evaluate` is the only caller, reached from both `create` and `patchAssumptions`.

B4. **`sources.yaml`.** Licence lines stay unverified until A0, and the commit message quotes the source.
```yaml
  # Licence per OpenAgenda CGU and each configured agenda page, checked <date>; per-row licence in open_event_occurrence.
  - id: openagenda
    name: OpenAgenda
    usedFor: [local_events]
    licence: Licence Ouverte 2.0
    licenceUrl: https://www.etalab.gouv.fr/licence-ouverte-open-licence/
    creditLine: "Événements : OpenAgenda (openagenda.com), Licence Ouverte 2.0"
    url: https://openagenda.com/
    gate: openagenda
  - id: que-faire-a-paris
    name: Que Faire à Paris (Ville de Paris)
    usedFor: [local_events]
    licence: ODbL 1.0
    licenceUrl: https://opendatacommons.org/licenses/odbl/1-0/
    creditLine: "Événements à Paris : contient des données « Que faire à Paris ? » de la Ville de Paris (opendata.paris.fr), ODbL"
    url: https://opendata.paris.fr/explore/dataset/que-faire-a-paris-/
    gate: quefaireaparis
```
- `DataSourceCatalog.USED_FOR` gains `local_events`.
- No `syncPrefix`: these sources are not in `reference_calendar`, so `lastUpdated` stays null.

B5. **Docs:** `Finding` javadoc, the `CLAUDE.md` evaluator paragraph, and the "Before flipping" list (imin-public label + §28; webapp copy + reason + `templateFacts`).

B6. **Tests**, then the full check command.

## Verification commands
```
cd /Users/ivan/imin/imin-api/.claude/worktrees/m2-1-openagenda
# Part A
docker info >/dev/null && /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=OpenAgendaClientTest,QueFaireAParisClientTest,OpenEventCitiesTest,GenreMatcherTest,OpenEventsWriterTest,OpenEventsJobTest,OpenEventsPropertiesTest,DataSourceCatalogTest,DateCheckMigrationH2Test,DateCheckMigrationPostgresTest
# Part B
docker info >/dev/null && /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=RecurrenceDetectorTest,OpenEventsEvaluatorTest,QuestionBankTest,RuleEngineTest,DateCheckControllerTest,DataSourceCatalogTest,PublicPredictorSourcesControllerTest
# Both, full gate
docker info >/dev/null && /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test
```
- The `-Dtest` list is comma-separated. Judge a targeted run only by its own `Tests run:` line.
- Run `./mvnw clean` if V165 is ever renumbered.

## Test impact
Branch map first. Each test forces one branch with the smallest setup that reaches it.

**OpenAgendaClientTest** (`MockRestServiceServer` bound to the builder; fixture = trimmed real response from A0):
- `keySentInKeyHeaderNotQuery`
- `pagesFollowAfterCursor`
- `pageCapStopsAndReportsPartial`
- `cancelledDropped`
- `postponedDropped`
- `movedOnlineDropped`
- `unknownStatusDropped`
- `onlineAttendanceDropped`
- `missingAttendanceDropped`
- `nonFrCountryDropped`
- `otherCityDropped`
- `blankCityDroppedNotDefaulted`
- `blankTitleDropped`
- `timingOver24hDropped`
- `beforeSixAmBelongsToPreviousNight` (Paris zone, DST date included)
- `tooManyRequestsThrowsRateLimited`
- `serverErrorThrows`
- `descriptionNeverRead` (fixture description holds a genre word; no match)

**QueFaireAParisClientTest:**
- `onlyParisCovered`
- `nonParisZipDropped`
- `occurrencesSplitIntoNights`
- `fallsBackToStartEndWhenNoOccurrences`
- `longRunDropped`
- `onlineOnlyDropped` (field per A0)
- `blankTitleDropped`
- `licenceIsOdbl`
- `offsetCapStopsAndReportsPartial`
- `tooManyRequestsThrowsRateLimited`

**OpenEventCitiesTest:**
- `shippedFileLoads` (every licence is in the CHECK set, every key ≤ 100)
- `duplicateKeyRejected`
- `unknownRootKeyRejected`
- `unknownCityKeyRejected`
- `unknownAgendaKeyRejected`
- `nonFrCountryRejected`
- `licenceOutsideCheckRejected`
- `duplicateUidRejected`
- `aliasInTwoCitiesRejected`
- `quefaireOutsideParisRejected`
- `cityWithoutSourceRejected`
- `overlongCityKeyRejected`
- `missingVersionRejected`

**GenreMatcherTest:**
- `shippedFileLoads`
- `bucketsMustEqualBankBuckets` (missing one; extra one)
- `duplicateKeyRejected`
- `unknownRootKeyRejected`
- `nonAsciiKeywordRejected`
- `duplicateKeywordInListRejected`
- `wholeWordOnly` (`housewarming` does not match `house`)
- `accentInsensitive` (`Fête de la Musique` → community)
- `excludePhraseWins` (`Open House techno` → none)
- `twoBucketsBothReturned`
- `unmatchedGenreIgnored`

**OpenEventsWriterTest** (`@SpringBootTest @Transactional`, H2):
- `replaceFutureKeepsPastRows`
- `replaceFutureOnlyTouchesItsSource`
- `recountDedupsSameTitleSameNightAcrossSources`
- `recountWritesZeroRowsForEveryBucket`
- `odblSourcesRecordedPerRow` (`sources_json` lists both sources with licences and per-source events, including a 0)
- `recountIsIdempotent`
- `pruneDeletesOlderThan200Days`
- `titleCutAt255`

**DateCheckMigrationScenarios** (H2 + PG): `ck_open_event_occurrence_source` (`'other'`), `ck_open_event_occurrence_licence` (`'CC-BY'`), `uq_open_event_occurrence`.

**OpenEventsJobTest** (unit, mocks, direct `run()`, each asserting a positive client/writer call):
- `gatesOffMakesNoCall`
- `windowIsLastMondayToPlus120` (today 2026-10-01 → from 2026-09-21, to 2027-01-29)
- `firstRunBackfills182DaysForBackfillingSourceOnly`
- `sourceFailureKeepsCountsAndWarns`
- `rateLimitSkipsSourceRestOfRun`
- `earlyStopCountsRemainingAsFailed` → ERROR when nothing stored
- `allPairsFailedLogsErrorWithThrowable` (Logback `ListAppender`)
- `allSucceededNothingMatchedWarns`
- `startupSeedsOnlyWhenEmpty`
- `startupSkipsWhenRowsExist`
- `startupExecutorRejectionIsSwallowed`
- `startupRunFailureIsSwallowed`

Date arithmetic for these tests: 2026-10-01 is a Thursday; previous Monday 2026-09-21; +120 days = 2027-01-29.

**OpenEventsPropertiesTest** (own instances, never the shared bean):
- `defaultsWithYamlKeyAbsent`
- `enabledWithBlankKeyFailsStartup`
- `enabledWithSecretKeyFailsStartup`
- `messageNamesVariablesNotValue`
- `toStringMasksKey`

**DataSourceCatalogTest (A):** the `SourceGates` constructor gains `OpenEventsProperties`. Existing expectations are unchanged.

**RecurrenceDetectorTest** (weekdays computed: 2026-01-01 is a Thursday, 2027-01-01 a Friday):
- `lastFridayPatternDetected`: 2026-07-31, 08-28, 09-25 → 2026-10-30, count 3, lastSeen 2026-09-25.
- `secondFridayNotMatchedByLastFridays`: same nights → 2026-10-09 is empty.
- `weeklyPatternDetected`: 2026-09-11, 09-18, 09-25 → 2026-10-16.
- `weeklyGapTooWideNotDetected`: 2026-08-14, 09-11, 09-25 → 2026-10-09 is empty.
- `patternCrossesYearEnd`: 2026-10-30, 11-27, 12-25 → 2027-01-29.
- `projectionTooFarNotDetected`: latest + 121 days.
- `nightsOutsideLookbackIgnored`.

**OpenEventsEvaluatorTest** (today 2026-10-01; date 2026-10-10 → weekStart 2026-10-05; 12 closed weeks 2026-07-06…2026-09-21):
- `gateOffSourceOff`
- `uncoveredCityNoSource`
- `noRowsNotSynced`
- `staleCity` (newest `updated_at` 11 days old)
- `tooFarAheadForWeekNorm` (date 2026-10-16: lead 15)
- `normNotCheckedBefore12Weeks` (11 closed weeks → `no_data`)
- `normIgnoresWeeksWithDifferentSourceSet`
- `lowNormLowVolume` (norm 1.5)
- Median and ratio cases, closed counts [2,3,3,4,4,4,4,5,5,5,6,6] → norm 4.0:
  - `busyWeekIsRisk`: count 6, ratio exactly 1.5 (inclusive); asserts RISK, strength 1, facts `date` 2026-10-05, `count` 6, `norm` 4.0, `n` 12, url.
  - `quietWeekIsOpportunity`: count 2, ratio exactly 0.5.
  - `usualWeekClear`: count 5.
- `localEventOnNightIsRisk` (facts `name`, `date`, `count`, url)
- `localEventOtherNightClear`
- `localEventBeyondSyncedHorizonTooFarAhead`
- `recurringGenrePartyIsRisk` (facts `name`, `count`, `lastSeen`)
- `recurringOtherGenreIgnored`
- `noGenreNotProvided`
- Construction failures: `unknownParamKeyFails`, `missingParamFails`, `paramOutOfBoundsFails` (one per bound).

All expected values above are hand-computed date or arithmetic facts; no outside source.

**QuestionBankTest:** `qb4-gp1`. `templateKeysFileIsCurrent` passes after regeneration, which adds:
- `predictor.q.2_6.risk`
- `predictor.q.2_6.opportunity`
- `predictor.q.5_3`
- `predictor.q.2_3`
- `predictor.qShort.2_6`
- `predictor.qShort.5_3`
- `predictor.qShort.2_3`

**RuleEngineTest**, **DateCheckControllerTest**, **DataSourceCatalogTest (B):**
- DataSourceCatalogTest: `sourcesListIncludesEveryConfiguredSource` adds both ids; new `openAgendaListedOnlyWhileGateOn` and `queFaireListedOnlyWhileGateOn` (shown, hidden when date check is off, hidden when the flag is off); every all-gates list updated.
- PublicPredictorSourcesControllerTest: grep first; edit only if it asserts the YAML id list.

Existing tests that touch `genre_week_count` (`DateCheckSchemaTest`, `DateCheckMigrationScenarios` V162 cases) need no change: the table is unchanged.

## Live-test
No UI. Defaults are off, so deploy changes nothing visible. After part B, every FR check gains `2.6/5.3/2.3:source_off` in `notChecked` and the `qb4-gp1` stamp.

1. Local Postgres. Set `PREDICTOR_DATE_CHECK_ENABLED=true`, `PREDICTOR_DATE_CHECK_ALL_ORGS=true`, `PREDICTOR_OPENAGENDA_ENABLED=true`, `OPENAGENDA_API_KEY=oa_pk_…`, `PREDICTOR_QUEFAIREAPARIS_ENABLED=true`. Boot and wait for the seed.
2. Check storage:
   - `select source, city_key, count(*), min(night_date), max(night_date) from open_event_occurrence group by 1,2`;
   - `select city_key, week_start, genre_family, event_count, sources_json from genre_week_count order by 2 desc limit 20`;
   - the INFO line with drop reasons.
3. Part B: POST `/api/v1/predictions/date-checks` for Paris `house & techno` on a date 9 days out. 2.6 is found, clear or `no_data` (depending on backfill), with facts. Check that 5.3 and 2.3 answer.
4. Repeat for a city with no agenda → `no_source`.
5. After deploy (flags off): `curl -s https://imin-api-production.up.railway.app/api/v1/public/predictor/sources` has no `openagenda` or `que-faire-a-paris` entry.

## Contract impact
- OpenAPI: **none**. There is no new path or schema, so there is no prod marker to wait for, and webapp `src/shared/api/types.ts` needs no edit. Finding `facts` and `reason` are free-form.
- `/api/v1/public/predictor/sources`: the closed `usedFor` set gains `local_events`, and two entries appear only while their gates are on. **Before any flag is flipped**, an imin-public follow-up ships:
  - the PUBLIC_PAGE_API.md §28 closed-set line and the gate paragraph;
  - the `lib/api/types.ts` comment;
  - `dataSources.usedFor.local_events` in EN/ES/FR/UK.

## i18n impact
No strings in imin-api.

Follow-up card for imin-webapp, EN/ES/FR/UK in one task. It must ship before either flag is turned on:
- add the 7 new template keys to the webapp's `template-keys.txt` fixture and `templateFacts`:
  - `2_6.risk/opportunity`: `['date','count','norm']`
  - `5_3`: `['date','name']`
  - `2_3`: `['name','count','lastSeen']`
  - `qShort` ×3: `NONE`
- add `too_far_ahead` to `notChecked.reason`, with a case in `resultText.test.ts`.

The "reads 53 api template keys" assertion becomes 60.

The imin-public label is covered in Contract impact.

### Copy ledger (proposed EN; the webapp card writes ES/FR/UK)
| String (key) | Field(s) behind it (api) | Meaning, scope, range | Rendered next to |
|---|---|---|---|
| `q.2_6.risk`: "Busy week: {count} events matching your genre are listed in your city's open agendas for the week of {date}. A usual week has about {norm}." | `OpenEventsEvaluator` 2.6 facts `count`, `norm`, `date` (B3) | `count`: distinct title+night pairs in this city, genre bucket and ISO week (Mon–Sun), from the gated sources in `sources_json`; keyword-matched, so approximate. `norm`: median of the latest 12 closed weeks with the same source set, 1 decimal, ≥ min_norm 2. `date` = that week's Monday. Only when the date is ≤ 14 days ahead. Never claims attendance or size | finding row and timeline week marker |
| `q.2_6.opportunity`: "Quiet week: {count} events matching your genre are listed in your city's open agendas for the week of {date}. A usual week has about {norm}." | same | same; ratio ≤ 0.5 | same |
| `qShort.2_6`: "genre events this week" | question id | label only | breakdown chip |
| `q.5_3`: "{name} is listed in your city's public agenda on {date}. Local events like this can draw your crowd." | 5.3 facts `name`, `date` (`count` not printed) | `name`: one listed title (first by normalised title) on that night that matched the local-event keyword list. No size or attendance is known, so the copy must never say "big" | finding row plus source link (`url`) |
| `qShort.5_3`: "local event" | question id | label only | chip |
| `q.2_3`: "{name} has run {count} times on a matching night, most recently on {lastSeen}. It may be on your date too." | 2.3 facts `name`, `count`, `lastSeen` | `count`: past nights in the last 182 days that fit the weekly or same-weekday-of-month pattern; `lastSeen`: the latest one strictly before the date. A projection, not an announcement ("may") | finding row plus link |
| `qShort.2_3`: "recurring night" | question id | label only | chip |
| `notChecked.reason.too_far_ahead`: "Listings for this date aren't out yet" | `notChecked(q, "too_far_ahead")` (2.6 lead > 14 d; 5.3 beyond the synced horizon) | per question per date | "Not checked" list |
| reused `source_off` "This source is paused for now" | gate off (B3 step 1) | exact | same list |
| reused `no_source` "We don't have a source for this here yet" | city not in `open-events-v1.yaml` | exact | same list |
| reused `not_synced` "This data hasn't been loaded yet" | no count row for the city or week | exact | same list |
| reused `stale` "Our copy of this data is out of date" | newest count `updated_at` > 10 days | exact | same list |
| reused `no_data` "Not enough data for this yet" | < 12 comparable closed weeks | exact | same list |
| reused `low_volume` "Too little data to read a trend" | norm < 2 | close: norm is a level, not a trend; the webapp card may reword this reason to "Too few events to compare" for all questions | same list |
| reused `not_provided` "Needs a detail this check doesn't have" | 2.3 without a genre family | exact | same list |
| imin-public `dataSources.usedFor.local_events`: "Local event listings" | `sources.yaml` `usedFor` | label for the source's purpose | `/data-sources` row |
| `sources.yaml` creditLines (two, B4) | `sources.yaml` | public credit; licence unverified until A0 | `/data-sources` row |

## Blast radius
| File / component | Effect | Covered by |
|---|---|---|
| V165 `open_event_occurrence` | new table only, additive; `SPRING_FLYWAY_OUT_OF_ORDER=true` in prod | A1, DateCheckMigrationScenarios |
| `genre_week_count` (V162, shipped) | first writer; schema unchanged | A8, OpenEventsWriterTest |
| `GenreWeekCountRepository` / `GenreWeekCount` | new queries, javadoc | Affected files 16–17 |
| `SourceGates` constructor | new parameter; the only manual construction is DataSourceCatalogTest | A10, DataSourceCatalogTest |
| `DataSourceCatalog.USED_FOR` + `sources.yaml` | public closed set grows (gated) | B4, Contract impact |
| `RuleEngine` boot check | fails boot if 2.6/5.3/2.3 lack an evaluator | `OpenEventsEvaluator` is a `@Component`; RuleEngineTest |
| Bank stamp `qb3-gp1` → `qb4-gp1` | new rows only; nothing compares versions | QuestionBankTest |
| `DateCheckService` create / patchAssumptions | reaches the evaluator through `RuleEngine.evaluate` only; the gate is in the evaluator, so no code change is needed; table reads inside the transaction, no network | DateCheckControllerTest |
| `Scorer` coverage | unchanged: none of the three is star | B1 |
| `PublicPredictorSourcesController` / test | output grows only with gates on | grep step, Affected files B15 |
| `DateCheckSchemaTest`, `DateCheckMigrationScenarios` (V162 cases) | read `genre_week_count`, whose schema is unchanged | stay green; V165 cases added |
| `.env.example`, `application.yaml`, `CLAUDE.md` | new variables, job and table | A11, B5 |
| imin-public `/data-sources` | drops unknown `usedFor` until the label ships | follow-up before flag flip |
| imin-webapp date-check | unknown template keys and reason fall back to generic copy | follow-up before flag flip |

No money, auth, Stripe or `/api/v1` schema paths are touched. Outbound network goes only to OpenAgenda and opendata.paris.fr from one weekly job.

## Risks
- **Size.** Part A is 31 files and part B is 15, which is over the ~15 guideline, so the plan is split. Recommendation: ship A then B as two /do-task runs, and M2-1c (2.3 from imin's own history) later.
- **Nothing upstream verified** (A0). Upstream shape, codes, limits and licences are all unconfirmed. If an agenda's licence is not Licence Ouverte 2.0, it is left out; the loader enforces this.
- **Que Faire à Paris may drop past events.** Then it does not backfill. Its first weeks would have a different source set from the backfilled OpenAgenda weeks, and 2.6 for Paris answers `no_data` until 12 comparable weeks exist. This is honest, but slow.
- **Incomplete future listings make weeks look quiet.** This is why 2.6 is limited to ≤ 14 days of lead time. The thresholds are estimates.
- **Keyword noise.** Whole-word matching, exclude phrases, titles and tags only. Strength is capped at 1 (2 for 2.3), and every finding links to its listing.
- **Date-check flag.** If `PREDICTOR_DATE_CHECK_ENABLED` is off in prod, no data is collected even with the source flags on (open question).
- **Agenda coverage.** Some product cities may have no official agenda; they get `no_source`.

## Definition of done
- Part A and part B steps done (or part A only for M2-1a).
- The full check command is green against the A0 base run.
- Every test in Test impact exists and asserts what is listed.
- The A0 facts (OpenAgenda shape, header auth, status codes, licence statements; QFAP fields, licence and past-event behaviour) are quoted in the commit messages.
- Every shipped agenda UID was checked live, and `verified_on` is set.
- `.env.example`, `application.yaml` comments, `CLAUDE.md` and javadocs are updated in the same edit.
- The imin-public and imin-webapp follow-up cards are open before any flag is turned on.

## Live-test evidence
_(filled by /do-task)_

## Review rounds
_(filled by /do-task)_

## Decisions (main session, 2026-10-01)
- Split accepted: THIS task = Part A (M2-1a, steps A0–A12). Part B (evaluator, bank v4, sources.yaml) is M2-1b. 2.3 from imin's own history is M2-1c (later).
- PREDICTOR_DATE_CHECK_ENABLED=true and ALL_ORGS=true in prod; gates requiring date-check are fine.
- Separate PREDICTOR_QUEFAIREAPARIS_ENABLED flag — accepted. Both default false; prod stays off until M2-1b + webapp/imin-public follow-ups ship.
- 2.3 not star; 5.3 = local city-wide listed event, never "big"; 2.6 whole Mon–Sun week, ≤14 days lead — accepted (for M2-1b).
- A0 upstream verification is mandatory: one real GET each. For OpenAgenda use the Railway key without ever printing it: `cd /Users/ivan/imin/imin-api && railway run -s imin-api -- sh -c 'curl -s -H "key: $OPENAGENDA_API_KEY" "https://api.openagenda.com/v2/agendas?search=metz&official=1"'` (adjust path/params to the verified docs; never echo/log/commit the key; check it starts with oa_pk_ without printing it, e.g. `case "$OPENAGENDA_API_KEY" in oa_pk_*) echo public;; *) echo NOT-public;; esac`). Quote docs/licence lines in your report. Only ship agenda UIDs you verified live with licence confirmed; cities without one are left out.
