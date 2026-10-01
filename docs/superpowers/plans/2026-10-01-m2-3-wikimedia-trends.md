# M2-3 Wikimedia pageviews: genre interest trend (9.1)
m2-3-wikimedia-trends · Subagent · Notion: M2-3 card (link from /do-task)

## Goal and scope
Answer bank question 9.1 "Genre interest trend" (model spec §9: Wikimedia Pageviews, CC0, genre + sub-genre articles in the city's language, 12-month trend) as a soft structured finding of strength 1, from a cached per-article monthly table filled by a scheduled sync. Credit the source on the public sources list behind its own gate.

In scope (imin-api only):
- `wikimedia_pageviews_month` table (V164) plus entity, repository and writer.
- `WikimediaPageviewsClient`, whose User-Agent carries a contact (Wikimedia User-Agent policy: https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy — the implementer re-reads it on the day and quotes the rule in the commit message).
- `WikimediaPageviewsJob`: weekly, plus a seed at boot while the table is empty, off the boot thread.
- `predictor/wikimedia-articles-v1.yaml` (country → language; bucket / sub-genre → article per language) and its strict loader.
- `TrendEvaluator` plus the 9.1 bank entry. Bank version goes 2 → 3.
- A `wikimedia` gate, a `sources.yaml` entry, and `DataSourceCatalogTest`.

Out of scope, with a proposed split: the lineup-draw signal (see Risks → M2-3b), Google Trends, and any webapp or imin-public change (see i18n impact and Contract impact).

**Decision: cached table, not fetch at check time.**
- `DateCheckService.create` and `patchAssumptions` are `@Transactional` and run `RuleEngine.evaluate` inside the transaction. A network call there holds a DB connection for up to the read timeout.
- The genre articles are a fixed configured set and the upstream updates monthly.
- Re-scoring after an assumptions patch must return the same answer.
- A Wikimedia outage must never fail or slow a check.
- One weekly sequential pass is polite to the upstream.

## Repos in ship order
1. `api` (imin-api, base `master`). There is no FE step in this task. The follow-ups are named in Contract impact and i18n impact.

## Affected files (per repo)
### imin-api (worktree `/Users/ivan/imin/imin-api/.claude/worktrees/m2-3-wikimedia-trends`)
New (main):
- `src/main/resources/db/migration/V164__wikimedia_pageviews.sql` — table below. V164 is free: the highest on `origin/master` is V163, and no other worktree has a V164.
- `src/main/java/com/imin/iminapi/predictor/model/WikimediaPageviewMonth.java` — JPA entity.
- `src/main/java/com/imin/iminapi/predictor/repository/WikimediaPageviewMonthRepository.java` — `@RepositoryRestResource(exported = false)`, with these queries:
  - `findTop24ByProjectAndArticleOrderByViewMonthDesc`;
  - `findByProjectAndArticleAndViewMonthIn`.
- `src/main/java/com/imin/iminapi/predictor/sources/wikimedia/WikimediaProperties.java`:
  - prefix `imin.predictor.wikimedia`;
  - `enabled` (default false);
  - `userAgent` (default `imin-api/1.0 (+https://imin.wtf; ops@imin.wtf) predictor-trends`; a blank value binds to the default).
- `src/main/java/com/imin/iminapi/predictor/sources/wikimedia/WikimediaConfig.java`:
  - `@EnableConfigurationProperties(WikimediaProperties.class)`;
  - a private RestClient builder with timeouts (connect 5 s, read 30 s), built the same way as `CalendarConfig`;
  - the client, articles and executor beans.
- `src/main/java/com/imin/iminapi/predictor/sources/wikimedia/WikimediaPageviewsClient.java`
- `src/main/java/com/imin/iminapi/predictor/sources/wikimedia/WikimediaArticles.java` — the record plus a static `load`/`parse`.
- `src/main/java/com/imin/iminapi/predictor/sources/wikimedia/WikimediaPageviewsWriter.java`
- `src/main/java/com/imin/iminapi/predictor/sources/wikimedia/WikimediaPageviewsJob.java`
- `src/main/java/com/imin/iminapi/predictor/rules/TrendEvaluator.java`
- `src/main/resources/predictor/wikimedia-articles-v1.yaml`

Edited (main):
- `src/main/resources/predictor/question-bank-v2.yaml`:
  - add the 9.1 entry;
  - `version: 2` → `3`;
  - the header comment becomes "M2-3 adds 9.1 (v3); M2-4 web questions bump again".
- `src/main/java/com/imin/iminapi/predictor/rules/Finding.java` — javadoc facts keys gain `article, project, fromMonth, toMonth, changePct, meanViews`. No code change.
- `src/main/java/com/imin/iminapi/predictor/sources/SourceGates.java`:
  - the constructor takes `WikimediaProperties`;
  - new key `"wikimedia"` = `dateCheck.enabled && wikimedia.enabled`.
- `src/main/java/com/imin/iminapi/predictor/sources/DataSourceCatalog.java` — add `genre_interest` to `USED_FOR`.
- `src/main/resources/predictor/sources.yaml`:
  - new entry `wikimedia-pageviews` with `gate: wikimedia` and no `syncPrefix` (CC0 asks for no update date);
  - `reviewedOn` = the date the implementer verified the CC0 terms.
- `src/main/resources/application.yaml` — under `imin.predictor`:
  `wikimedia: { enabled: ${PREDICTOR_WIKIMEDIA_ENABLED:false}, user-agent: ${PREDICTOR_WIKIMEDIA_USER_AGENT:imin-api/1.0 (+https://imin.wtf; ops@imin.wtf) predictor-trends} }`
- `.env.example` — `PREDICTOR_WIKIMEDIA_ENABLED`, `PREDICTOR_WIKIMEDIA_USER_AGENT` (optional), each with a one-line comment.

New (test):
- `src/test/java/com/imin/iminapi/predictor/sources/wikimedia/WikimediaPageviewsClientTest.java`
- `src/test/java/com/imin/iminapi/predictor/sources/wikimedia/WikimediaArticlesTest.java`
- `src/test/java/com/imin/iminapi/predictor/sources/wikimedia/WikimediaPageviewsWriterTest.java`
- `src/test/java/com/imin/iminapi/predictor/sources/wikimedia/WikimediaPageviewsJobTest.java`
- `src/test/java/com/imin/iminapi/predictor/sources/wikimedia/WikimediaPropertiesTest.java`
- `src/test/java/com/imin/iminapi/predictor/rules/TrendEvaluatorTest.java`

Edited (test):
- `src/test/java/com/imin/iminapi/predictor/DataSourceCatalogTest.java`
- `src/test/java/com/imin/iminapi/predictor/rules/QuestionBankTest.java` — `qb2-gp1` → `qb3-gp1`.
- `src/test/java/com/imin/iminapi/predictor/rules/RuleEngineTest.java` — the 9.1 stub, plus `TrendEvaluator` in `everyShippedQuestionHasAnEvaluator`.
- `src/test/resources/predictor/template-keys.txt` — regenerate.
- `src/test/java/com/imin/iminapi/predictor/DateCheckControllerTest.java` — the existing `notChecked` assertion also contains `"9.1:source_off"`.

## Ordered steps
1. **Base gate.** Run the check command once on the untouched worktree and record the result (a gate already red on base is not this diff's failure).

2. **Migration V164.**
   ```sql
   CREATE TABLE wikimedia_pageviews_month (
     id UUID PRIMARY KEY,
     project VARCHAR(32) NOT NULL,          -- e.g. fr.wikipedia
     article VARCHAR(255) NOT NULL,         -- canonical title, underscores
     view_month DATE NOT NULL,              -- first day of the month
     views BIGINT NOT NULL,
     synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
     CONSTRAINT uq_wikimedia_pageviews_month UNIQUE (project, article, view_month),
     CONSTRAINT ck_wikimedia_pageviews_month_views CHECK (views >= 0));
   ```
   - CC0 data, so no ODbL separation rule applies.
   - No pruning. Growth is roughly articles × 12 rows a year.
   - The limits come from config and the loader enforces them at boot:
     - `project` = `<lang>.wikipedia`, lang from a closed set, so at most 12 chars (≤ 32);
     - `article` ≤ 255 chars (= `VARCHAR(255)`);
     - `views` ≥ 0 (= `ck_wikimedia_pageviews_month_views`), dropped in the client.

3. **Entity, repository, writer.**
   - `WikimediaPageviewsWriter.upsert(project, article, List<MonthViews>, Instant syncedAt)` is `@Transactional`.
   - It reads the existing rows for those months, updates `views` and `synced_at`, and inserts the missing ones. This works on H2 as well; there is no `ON CONFLICT`.
   - `MonthViews(YearMonth month, long views)` is a record.

4. **Properties and config.**
   - `WikimediaProperties` carries the same defaults in Java and in yaml.
   - The javadoc names `PREDICTOR_WIKIMEDIA_ENABLED` and `PREDICTOR_WIKIMEDIA_USER_AGENT`.
   - `WikimediaConfig` builds the request factory exactly as `CalendarConfig` does today:
     `ClientHttpRequestFactoryBuilder.detect().build(HttpClientSettings.defaults().withTimeouts(CONNECT_TIMEOUT, READ_TIMEOUT))` (Spring Boot 4.0.5, already compiled in this repo).
   - It also defines a single-thread `wikimediaSyncExecutor` (queue 1). Its own executor, so it never competes with the calendar seed.

5. **Client** (`WikimediaPageviewsClient(RestClient.Builder builder, WikimediaProperties props)`).
   - The constructor rejects a User-Agent with neither `@` nor `https://` (`IllegalStateException`, which stops boot on a config error only). It then applies `builder.defaultHeader("User-Agent", ua)` itself, so the test that binds `MockRestServiceServer` to the builder checks the production header.
   - `fetch(String project, String article, YearMonth from, YearMonth to)` builds a `java.net.URI`:
     `https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/{project}/all-access/user/{title}/monthly/{fromYYYYMM01}00/{toYYYYMMlastDay}00`
     - The title has spaces turned into `_`, then goes through `UriUtils.encodePathSegment(title, StandardCharsets.UTF_8)`. Signature confirmed in spring-web 7.0.6 sources, `UriUtils.java:200`: `public static String encodePathSegment(String segment, Charset charset)`.
     - A `URI` object is passed so RestClient does not encode it again.
     - The path shape and date format are from the AQS docs as remembered. The implementer confirms them against https://doc.wikimedia.org/generated-data-platform/aqs/analytics-api/ and one manual GET before writing the test fixture (unverified until then).
   - Results by status:
     - 404 → `Result.missing()`: no rows; the job logs WARN naming the title.
     - 429 → throws `WikimediaRateLimitedException`.
     - Other non-2xx, I/O errors, or a body that is not JSON → throws.
     - 200 → reads `items[]`. An item is kept only when all of these hold (the importer rule "filter on every classification field"):
       - `agent == "user"`, `access == "all-access"`, `granularity == "monthly"`;
       - `project` and `article` equal the request;
       - `timestamp` matches `YYYYMM0100`;
       - `views` is an integer ≥ 0.
   - Zero-filling: months in `[from, latestReturned]` with no item become 0 (a 200 answer omits zero months). Months after `latestReturned` are not returned, because they are not published yet and must not become a fabricated 0.

6. **Article map** (`wikimedia-articles-v1.yaml` + `WikimediaArticles`).
   Shape:
   ```yaml
   version: 1
   verified_on: <date the titles were checked>
   languages: { FR: fr, NL: nl, DE: de, ES: es, UA: uk }
   buckets:
     "house & techno":
       article: { fr: <title> }          # optional bucket-level article
       sub_genres:
         techno: { fr: <title>, de: <title> }
   ```
   Loader (SnakeYAML `LoaderOptions.setAllowDuplicateKeys(false)`, same style as `DataSourceCatalog`):
   - Rejects unknown keys at root, bucket and sub-genre levels.
   - Bucket names must be in `QuestionBank.GENRE_BUCKETS`.
   - Sub-genre keys must be in that bucket's `genre-profiles-v1.yaml` `sub_genres` (the bank is passed in).
   - Country keys: two uppercase letters.
   - Languages: closed set {fr, nl, de, es, uk, en}.
   - A title's language must be a value of `languages`.
   - Titles: non-blank, ≤ 255 chars after space → underscore.
   - `version` ≥ 1; `verified_on` is an ISO date.
   - API:
     - `lookup(country, bucket, subGenre)` → `Optional<Article(project, title)>`, sub-genre first, then the bucket article;
     - `distinctArticles()` → the job input.
   - Filling titles: for each title the implementer runs `https://<lang>.wikipedia.org/w/api.php?action=query&titles=<t>&redirects=1&prop=pageprops&format=json` and keeps it only when:
     - the page exists;
     - it is not a disambiguation page (`pageprops.disambiguation` absent);
     - the **canonical target** title is stored (pageviews are counted per exact title; views of a redirect do not count toward its target).
   - Unverified pairs are left out. The evaluator then answers `not_checked("no_article")`. No title is invented.

7. **Job** (`WikimediaPageviewsJob`, modelled on `ReferenceCalendarJob`).
   - `@Scheduled(cron = "0 15 5 * * MON", zone = "Europe/Paris")` + `@SchedulerLock(name = "wikimedia_pageviews_sync", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")`.
   - `run()` returns at once unless `sourceGates.isOn("wikimedia")`.
   - Range: `to` = the previous month relative to Paris today; `from` = `to.minusMonths(12)` (13 months; handles the year boundary through `YearMonth`).
   - For each `distinctArticles()` entry: `client.fetch`, then `writer.upsert`.
   - Failure handling:
     - A per-article exception → WARN, and the run continues.
     - `WikimediaRateLimitedException` → WARN and the rest of this run stops.
     - If every article failed → `log.error(..., lastThrowable)`, so it reaches Sentry with a stack trace.
   - `@EventListener(ApplicationReadyEvent)`: only if the gate is on and `repository.count() == 0`, `executor.execute(self.getObject()::run)`. Both the executor rejection and the run failure are caught and logged at WARN. A network failure never blocks boot.

8. **Bank entry 9.1** in `question-bank-v2.yaml`, after 10.3:
   ```yaml
   # Wikimedia pageviews (CC0) of the genre article in the country's language; thresholds are estimates pending product review.
   - id: "9.1"
     family: music
     source: structured
     kinds: [risk, opportunity]
     weight: 1
     max_strength: 1
     window: month
     applies_when: { countries: [FR, NL, DE, ES, UA] }
     params: { rising_min: 0.25, falling_min: 0.25, min_mean_views: 100, max_age_months: 2 }
     template: predictor.q.9_1
   ```
   - Not a star question, so `Scorer.coverage` is unchanged.
   - No actions: they would need new i18n keys, and M3 owns the copy.
   - Also bump `version: 3`.

9. **`TrendEvaluator`** (`@Component`, `source()` = STRUCTURED, `questionIds()` = {"9.1"}).
   - **Constructor** (fails boot on a bad config):
     - 9.1's params must be exactly {rising_min, falling_min, min_mean_views, max_age_months}, with these bounds:
       - `0 < rising_min ≤ 5`;
       - `0 < falling_min ≤ 1`;
       - `min_mean_views ≥ 1`;
       - `1 ≤ max_age_months ≤ 12`.
     - Every country in 9.1's `applies_when` has a language in `WikimediaArticles`.
   - **`evaluate`**, branches in order:
     1. Gate off (`sourceGates.isOn("wikimedia")` false) → `notChecked(q, "source_off")`.
     2. `articles.lookup(in.country(), in.genreFamily(), in.subGenre())` empty → `notChecked(q, "no_article")`.
     3. Load up to 24 rows. There must be 12 consecutive months ending at the latest stored month; if not (no rows, or a gap) → `notChecked(q, "not_synced")`.
     4. Latest month older than `YearMonth.from(in.today()).minusMonths(max_age_months)` → `notChecked(q, "stale")`.
     5. `mean < min_mean_views` → `notChecked(q, "low_volume")`.
     6. Least-squares slope over x = 0..11. `change = slope × 11 / mean`.
        - `change ≥ rising_min` → `found(q, OPPORTUNITY, 1, facts, url)`.
        - `change ≤ -falling_min` → `found(q, RISK, 1, facts, url)`.
        - Otherwise → `clear(q)`.
   - Facts: `article, project, fromMonth, toMonth, changePct = round(change × 100), meanViews`.
   - `url` = `https://<lang>.wikipedia.org/wiki/<encoded title>`: a link only, no article text is copied.
   - The answer does not depend on the candidate date (the window is `month`). Both `create` and `patchAssumptions` reach it through `RuleEngine.evaluate`, the only caller (`DateCheckService.run`). So the gate is enforced on every path that produces a finding.

10. **Gate and sources.**
    - `SourceGates` gains `"wikimedia"`.
    - `sources.yaml` entry:
      ```yaml
      - id: wikimedia-pageviews
        name: Wikimedia Pageviews
        usedFor: [genre_interest]
        licence: CC0 1.0
        licenceUrl: https://creativecommons.org/publicdomain/zero/1.0/
        creditLine: "Genre interest: Wikimedia Pageviews (Wikimedia Foundation), CC0 1.0"
        url: https://doc.wikimedia.org/generated-data-platform/aqs/analytics-api/
        gate: wikimedia
      ```
    - The licence and URL stay unverified until the implementer confirms the CC0 statement on the official AQS/Analytics page. The commit message quotes it.
    - `DataSourceCatalog.USED_FOR` gains `genre_interest`.

11. **Docs in the same edit:**
    - `.env.example`;
    - `application.yaml` comments;
    - the `Finding` facts javadoc;
    - the `SourceGates` class javadoc (a third gate);
    - the `DataSourceCatalog` USED_FOR comment, if any.

12. **Tests** per Test impact. Then run the full check command.

## Verification commands
```
cd /Users/ivan/imin/imin-api/.claude/worktrees/m2-3-wikimedia-trends
docker info >/dev/null && /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=WikimediaPageviewsClientTest,WikimediaArticlesTest,WikimediaPageviewsWriterTest,WikimediaPageviewsJobTest,WikimediaPropertiesTest,TrendEvaluatorTest,DataSourceCatalogTest,QuestionBankTest,RuleEngineTest,DateCheckControllerTest
docker info >/dev/null && /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test
```
- The `-Dtest` list is comma-separated. Judge a targeted run only by its own `Tests run:` line.
- Run `./mvnw clean` first if the migration is ever renumbered.

## Test impact
**WikimediaPageviewsClientTest** (MockRestServiceServer bound to the builder, as in `OpenHolidaysSyncTest`):
- `userAgentHeaderSent`
- `requestIsMonthlyUserAllAccessOverTheRange`
- `titleEncodedAsOnePathSegment` (`AC/DC & Co` → `AC%2FDC_%26_Co`)
- `notFoundGivesMissing`
- `serverErrorThrows`
- `tooManyRequestsThrowsRateLimited`
- `otherAgentItemDropped`
- `otherAccessItemDropped`
- `otherGranularityItemDropped`
- `otherArticleOrProjectItemDropped`
- `negativeOrMissingViewsDropped`
- `timestampNotFirstOfMonthDropped`
- `omittedMonthBeforeLatestIsZero`
- `unpublishedMonthNotStoredAsZero`
- `userAgentWithoutContactFailsStartup`

**WikimediaArticlesTest:**
- `shippedFileLoads` (every title ≤ 255, every 9.1 country has a language)
- `duplicateKeyRejected`
- `unknownRootKeyRejected`
- `unknownBucketKeyRejected`
- `unknownSubGenreKeyLevelRejected` (unknown key inside a sub-genre map)
- `unknownBucketRejected`
- `subGenreNotInProfileRejected`
- `titleLanguageNotConfiguredRejected`
- `unsupportedLanguageRejected`
- `badCountryCodeRejected`
- `blankTitleRejected`
- `overlongTitleRejected`
- `missingVersionRejected`
- `spacesBecomeUnderscores`
- `lookupPrefersSubGenreThenBucket`
- `distinctArticlesListsEachOnce`

**WikimediaPageviewsWriterTest** (`@SpringBootTest @Transactional`, H2, as in `ReferenceCalendarWriterTest`):
- `insertsNewMonths`
- `updatesExistingMonthAndSyncedAt`
- `otherArticleRowsUntouched`
- `negativeViewsRejectedByCheck`

**WikimediaPageviewsJobTest** (unit, mocks, direct `run()`; each test asserts a positive client/writer interaction, never absences alone):
- `gateOffMakesNoCall`
- `runFetchesEachArticleOverThirteenMonthsEndingLastMonth` (Paris date)
- `rangeCrossesYearEnd` (today 2026-01-15 → 2024-12..2025-12)
- `oneArticleFailureDoesNotStopOthers`
- `tooManyRequestsStopsTheRun`
- `allArticlesFailedLogsErrorWithThrowable` (Logback `ListAppender`)
- `missingArticleWritesNothing`
- `startupSeedsOnlyWhenEmpty`
- `startupSkipsWhenRowsExist`
- `startupExecutorRejectionIsSwallowed`
- `startupRunFailureIsSwallowed`

**WikimediaPropertiesTest:**
- `defaultsWithYamlKeyAbsent` (enabled false, the default User-Agent)
- `blankUserAgentFallsBackToDefault`

**TrendEvaluatorTest** (fixed `today` = 2026-10-01; the series runs 2025-10..2026-09, so the window crosses a year end):
- `gateOffNotChecked`
- `missingArticleNotChecked`
- `subGenreWithoutArticleFallsBackToBucket`
- `noRowsNotChecked`
- `gapInTwelveMonthsNotChecked`
- `staleLatestMonthNotChecked` (latest 2026-07)
- `lowVolumeNotChecked` (mean 50)
- `risingTrendIsOpportunity`:
  - 100, 110 … 210: slope 10, mean 155, change 110/155 = 0.7097;
  - asserts OPPORTUNITY, strength 1, sourceKind STRUCTURED, `changePct` 71, article, project, from/to months, url.
- `fallingTrendIsRisk` (the same series reversed, `changePct` −71)
- `flatTrendIsClear` (150 × 12)
- `thresholdIsInclusive`:
  - 385, 395 … 495: slope exactly 10, mean 440, change exactly 0.25;
  - asserts OPPORTUNITY.
- `unknownParamKeyFailsConstruction`
- `missingParamFailsConstruction`
- `paramOutOfBoundsFailsConstruction`
- `bankCountryWithoutLanguageFailsConstruction`

(All expected values above are hand-computed arithmetic; no outside facts.)

**DataSourceCatalogTest (edits):**
- The `gates(...)` helper gains a wikimedia flag and a `WikimediaProperties` field.
- `sourcesListIncludesEveryConfiguredSource` → adds `wikimedia-pageviews` last.
- New test `wikimediaListedOnlyWhileGateOnWithCc0Credit`: name, `usedFor` `[genre_interest]`, `CC0 1.0`, licence URL, the credit line naming the Wikimedia Foundation, `lastUpdated` null. It is hidden when date check is off, and hidden when the wikimedia flag is off.
- `dateCheckOffHidesCalendarSources`, `calendarSyncOffHidesCalendarSources` (wikimedia stays listed; it does not depend on calendar sync), `weatherOffHidesOpenMeteo`, `allGatesOffReturnsEmpty` and `gatesAreReadAtCallTimeNotAtLoad` update their expected lists.
- `lastUpdatedComesFromTheSyncPrefixLookup` asserts that wikimedia is never asked for a prefix and has a null date.

**QuestionBankTest:** `qb3-gp1`; `templateKeysFileIsCurrent` stays green after `template-keys.txt` is regenerated (+ `predictor.q.9_1.opportunity`, `predictor.q.9_1.risk`, `predictor.qShort.9_1`).

**RuleEngineTest:** the stub list gets `"9.1"` on STRUCTURED; `everyShippedQuestionHasAnEvaluator` gets `new TrendEvaluator(...)`.

**DateCheckControllerTest:** `notChecked` also contains `9.1:source_off`, which proves the evaluator is wired into the real context with the gate off.

## Live-test
The API has no UI surface. The default flag is off, so no Wikimedia call is made and the public sources list is unchanged on deploy; but every FR/NL/DE/ES/UA date check now carries `9.1:source_off` in `notChecked` and is stamped `question_bank_version` `qb3-gp1`.

1. **Local, flag on** (`PREDICTOR_DATE_CHECK_ENABLED=true`, `PREDICTOR_DATE_CHECK_ALL_ORGS=true`, `PREDICTOR_WIKIMEDIA_ENABLED=true`, local Postgres):
   - Boot, then wait for the seed. `select project, article, count(*), max(view_month) from wikimedia_pageviews_month group by 1,2` shows 13 months per configured article.
   - The log line shows the User-Agent.
2. POST `/api/v1/predictions/date-checks` for Paris, `house & techno` / `techno`. 9.1 is `found` or `clear`, with facts and a Wikipedia url.
3. Repeat for `UA` or a sub-genre without a title: `not_checked` with `no_article`.
4. After deploy (flag off in prod): `curl -s https://imin-api-production.up.railway.app/api/v1/public/predictor/sources` has no `wikimedia-pageviews` entry.

## Contract impact
- OpenAPI schema: **none**. `usedFor` stays `string[]`; findings `facts` and `reason` are free-form. No new path, so there is no prod OpenAPI marker to wait for, and webapp `src/shared/api/types.ts` needs no edit.
- `/api/v1/public/predictor/sources`: the closed set of `sources[].usedFor` values (PUBLIC_PAGE_API.md §28) gains `genre_interest`. The response changes only while `PREDICTOR_WIKIMEDIA_ENABLED=true`. **Before that flag is flipped**, an imin-public follow-up must ship:
  - the §28 closed-set line and the status-gate paragraph;
  - the `lib/api/types.ts` comment;
  - `dataSources.usedFor.genre_interest` in EN/ES/FR/UK.

## i18n impact
- No UI strings in this repo.
- New template keys for the webapp (M3 owns them; EN/ES/FR/UK when M3 lands): `predictor.q.9_1.risk`, `predictor.q.9_1.opportunity`, `predictor.qShort.9_1`.
- New not_checked reasons: `source_off`, `no_article`, `not_synced`, `stale`, `low_volume`.
- imin-public label: see Contract impact.

## Blast radius
| File / component | Effect | Covered by |
|---|---|---|
| `SourceGates` constructor | New parameter; Spring injects it; the only manual construction is `DataSourceCatalogTest` | Affected files, DataSourceCatalogTest edit |
| `DataSourceCatalog.USED_FOR` | Public closed set grows; gated | Contract impact |
| `RuleEngine` boot check | Fails boot if 9.1 has no evaluator | TrendEvaluator is a `@Component`; RuleEngineTest |
| Question bank version stamp `qb2-gp1` → `qb3-gp1` | New `date_check.question_bank_version` and ledger rows; old rows keep qb2. Nothing on master compares versions (grep: only set/echoed in `DateCheckService`) | QuestionBankTest |
| `DateCheckService` (create, patchAssumptions) | Reaches TrendEvaluator through `RuleEngine.evaluate`; no code change needed because the gate is in the evaluator. With the flag off, every FR/NL/DE/ES/UA check gains `9.1:source_off` in `notChecked` | DateCheckControllerTest |
| `PublicPredictorSourcesController` | No change: output grows only when the gate is on; default context unchanged | Existing PublicPredictorSourcesControllerTest stays green unedited |
| `PredictorPropertiesBindingTest` | No change: it reflects only `PredictorProperties`; the new properties class has its own test | WikimediaPropertiesTest |
| `Scorer` coverage | No change: 9.1 is not a star question | — |
| Flyway V164 | New table only, additive; `SPRING_FLYWAY_OUT_OF_ORDER=true` in prod | WikimediaPageviewsWriterTest |
| imin-public `/data-sources` | Drops unknown `usedFor` values, so the label is empty until the follow-up | Contract impact follow-up |
| imin-webapp date-check (M3) | Unknown template keys and reasons | i18n impact |

No money, auth or Stripe paths are touched.

## Risks
- **Size and split.** This task touches 29 files (18 main + 11 test: 11 new + 7 edited main, 6 new + 5 edited test) for one concern. If the gate prefers smaller pieces, split into:
  - (a) table, client, job and sources entry — dormant without 9.1;
  - (b) evaluator and bank entry.
  Recommendation: keep one task.
- **Lineup draw → proposed M2-3b** (not in this plan):
  - Bohdan's I5 decision (A/B/C) is open.
  - Resolving an artist honestly needs the exact title in the city-language wiki or enwiki, confirmed through Wikidata `wbgetentities?sites=<lang>wiki&titles=<name>&props=claims`. Accept only when P106 (occupation) or P31 (instance of) is in a YAML list of musician / DJ / musical-group classes (the list is unverified; check at implementation). Plus an optional exact alias map in YAML. Never a fuzzy search.
  - The fetch has to run after access, rate-limit and validation checks but outside the `create` transaction, into the same `wikimedia_pageviews_month` table.
  - An unresolved artist → `not_checked("no_article")`.
  - Wikidata (CC0) is credited with `usedFor: lineup_draw`.
- **Title map coverage.** Only verified titles ship. Many (bucket, sub-genre, language) pairs will answer `no_article`. That is honest but thin, and it can grow with no code change.
- **Draft sub-genres.** The sub-genre taxonomy is a draft (Bohdan B4). Renames fail boot until the map follows. This is intended.
- **Signal granularity.** Pageviews are per language wiki, not per city (fr.wikipedia covers FR, BE, CH and others). The finding facts carry `project` so the copy can say so.
- **Thresholds are estimates** pending product review, marked in the YAML.

## Definition of done
- All steps done.
- The full check command is green, compared with the base run recorded in step 1.
- Every test in Test impact exists and asserts what is listed.
- `.env.example`, yaml comments and javadocs updated.
- The CC0 statement and the User-Agent policy are quoted in the commit message.
- Every shipped title has been checked against MediaWiki, and `verified_on` is set.
- The imin-public follow-up card is open before any flag flip.

## Live-test evidence
_(filled by /do-task)_

## Review rounds
_(filled by /do-task)_

## Decisions (main session, 2026-10-01)
- Cached table + weekly job accepted; one task (no a/b split).
- Lineup draw split to M2-3b (waits on Bohdan I5); not in this task.
- imin-public follow-up (usedFor genre_interest label EN/ES/FR/UK, PUBLIC_PAGE_API §28, types comment) is a separate card that must ship before PREDICTOR_WIKIMEDIA_ENABLED is flipped.
- User-Agent contact: keep ops@imin.wtf for now (main session confirms the mailbox with Ivan).
- family: music accepted. Draft sub-genre renames failing boot is intended.
- Only titles verified live against MediaWiki (exists, not disambiguation, canonical target) ship; record verified_on. Verify the AQS path/date format with one real GET and the CC0 statement + User-Agent policy on the official pages; quote both in the report (commit message text).
