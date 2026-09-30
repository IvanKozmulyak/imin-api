# M1-5 Question bank v2 + genre profile loader
m1-5-question-bank · feature · Notion: (main session fills)

## Goal and scope
Ship `predictor/question-bank-v2.yaml` and `predictor/genre-profiles-v1.yaml`, plus a loader that validates both at startup (bad file → boot fails, error names file + dotted key). Expose `QuestionBank` as a bean and export its template keys to `src/test/resources/predictor/template-keys.txt` for webapp M3-1.
Out of scope: evaluators (M1-6), scorer/actions/assumptions (M1-7), DB, endpoints, the diaspora/ad-period tables (M1-4).
Base: origin/master (1a6e6c53). This task only depends on M1-1's `PredictorConfig`, which is already on master, and on nothing from M1-2. The worktree can be cut from origin/master and shipped in either order relative to M1-2.

## Repos in ship order
1. api (`imin-api`, base `master`)

## Affected files (per repo)
api (all under `src/main/java/com/imin/iminapi/predictor/` unless shown):
- Create `rules/QuestionBank.java`: records `QuestionBank(int version, int profilesVersion, Thresholds, List<Question>, Map<String,GenreProfile>)`, `Thresholds(int adjustMinRisk, int moveMinRisk, double minCoverage, int maxPointsPerFinding)`, `Question(String id, String family, boolean star, SourceKind source, Set<Kind> kinds, int weight, int maxStrength, Window window, boolean stopFactor, Set<String> countries, Set<String> cities, Map<String,Number> params, String template, List<Action> actions)`, `Action(String key, Kind when, int dueDays)`, `GenreProfile(List<String> subGenres, ProfileField<int[]> audienceAge, ProfileField<List<String>> communities, ProfileField<int[]> typicalPriceEur, ProfileField<Integer> typicalStartHour, ProfileField<Integer> buyingLeadDays)`, `ProfileField<T>(T value, String sourcedUrl, boolean estimate)`. Enums: `SourceKind {STRUCTURED, INTERNAL, WEB, ORGANIZER, INPUT}`, `Kind`, `Window`. Methods: `version()` = `"qb"+version+"-gp"+profilesVersion` (≤ 32 chars for `question_bank_version VARCHAR(32)`), `questionsFor(String country)`, `templateKeys()` (sorted `SortedSet<String>`), `GENRE_BUCKETS` (the 8 keys).
- Create `rules/QuestionBankLoader.java`: `load(ResourceLoader)` and `parse(InputStream bank, InputStream profiles)`. Uses `LoaderOptions.setAllowDuplicateKeys(false)` and `SafeConstructor`, as in `LogicLoader.yaml()`, plus a private `Node` helper trimmed from `LogicLoader.Node`, with error prefix `predictor question-bank`/`genre-profiles`.
- Modify `config/PredictorConfig.java`: `@Bean QuestionBank predictorQuestionBank(ResourceLoader r) { return QuestionBankLoader.load(r); }`.
- Create `src/main/resources/predictor/question-bank-v2.yaml` and `src/main/resources/predictor/genre-profiles-v1.yaml`.
- Create `src/test/java/com/imin/iminapi/predictor/rules/QuestionBankTest.java` and `src/test/resources/predictor/template-keys.txt`.
- Create this plan. That's 8 files in total.

## Ordered steps
1. **Write the YAML shape.**
```yaml
version: 2
thresholds: { adjust_min_risk: 3, move_min_risk: 7, min_coverage: 0.6, max_points_per_finding: 4 }
questions:
  - id: "4.1"                 # ^\d{1,2}\.\d{1,2}$ ; unique per (id, source)
    family: holidays
    star: true
    source: structured        # exactly one scalar: structured|internal|web|organizer|input
    kinds: [risk]             # non-empty subset of risk|opportunity
    weight: 2                 # 1..3
    max_strength: 3           # optional, default 3; web/organizer must be <= 2
    window: week              # night|week|month
    stop_factor: false        # true only for structured|internal
    applies_when: { countries: [FR, NL, DE, ES, UA], cities: [...optional] }
    params: { ... }           # optional, numbers only
    template: predictor.q.4_1 # ^predictor\.q\.\d+_\d+$ ; derived from id and checked for equality
    actions:
      - { key: predictor.a.holiday_early_promo, when: risk, due: "D-28" }  # when ∈ kinds, due ^D-\d+$
```
   `kinds` extends the spec §7 `kind`, because Bohdan marks some questions as both risk and upside, and the UX deck has risk/upside variants for 6.3 and 9.1.
2. **Fill the initial M1 content.** Weights are all estimates (Bohdan's bank gives none; the tech spec §6 gives 2.1 = 3 and 4.7 = 1); windows follow Bohdan's [вечір]/[тиждень]/[місяць] tags. `ALL` = [FR, NL, DE, ES, UA].

| id | source | kinds | w | window | star | countries | actions (key, due) |
|---|---|---|---|---|---|---|---|
| 4.1 public holiday | structured | risk | 2 | week | ✓ | ALL | holiday_early_promo D-28 |
| 4.2 eve of day off / long weekend | structured | risk, opp | 2 | night | ✓ | ALL | eve_of_day_off_theme(opp) D-21 |
| 4.3 pont | structured | risk | 3 | night | ✓ | FR, ES, DE | holiday_early_promo D-28 |
| 4.4 regional holiday | structured | risk | 2 | night | | FR, DE, ES | holiday_early_promo D-28 |
| 4.5 neighbour-country holiday | structured | opp | 1 | night | | FR; cities [metz, thionville, strasbourg, mulhouse, lille] (estimate) | border_guests_promo D-14 |
| 4.7 DST night | structured | risk, opp | 1 | night | | ALL but UA? no, ALL | long_night_theme(opp) D-14 |
| 5.1 diaspora national day | structured | risk, opp | 1 | week | ✓ | ALL | community_day_theme(opp) D-21 |
| 5.2 religious period | structured | risk | 2 | week | ✓ | ALL | religious_period_timing D-28 |
| 7.1 school holidays | structured | risk | 2 | week | ✓ | ALL (UA → not_checked in M1-6) | holiday_early_promo D-28 |
| 3.2 football fixture | structured | risk, opp | 2 | night | ✓ | FR, NL, DE, ES | match_start_time(risk) D-7, match_screening(opp) D-7 |
| 10.3 pricier ads | structured | risk | 1 | month | | ALL | ads_budget_early D-35 |
| 2.1 same genre, same night | internal / organizer(max_strength 2) | risk | 3 | night | ✓ | ALL | competitor_differentiate D-21 |
| 2.2 same genre, that week | internal / organizer(max_strength 2) | risk | 2 | week | ✓ | ALL | competitor_differentiate D-21 |
| 2.7 own event nearby (code ±14 d) | internal | risk | 2 | month | | ALL | own_event_spacing D-30 |
| 2.9 comparables | internal | risk, opp | 2 | month | | ALL | — |
| 10.2 buying lead time (= 1.9) | internal | risk | 1 | month | | ALL | — |
| 10.1 promo time | input | risk | 2 | night | ✓ | ALL; params {small_min_days: 21, big_min_days: 60, big_capacity_min: 800} (tech spec §7.1) | — |

   (For 4.7 the countries are simply ALL.) There are no stop factors in M1: 4.8 and 6.5, the only 🛑 questions besides 6.1, have no M1 source.
   - **Left out until M2 (source `web` or M2 data, spec §9):** 2.1/2.2 web, 2.3, 2.6, 5.3, 6.1, 6.3, 6.9, 9.1.
   - **Left out, no lawful source:** 9.2.
   - **Left out, no §9 row:** 4.6, 4.8, 6.7 weather, 7.2, 8.1.
   - **Left out, handled by 422 validation:** 0.1.
3. **Write the genre-profile YAML.** The 8 bucket keys must equal `QuestionBank.GENRE_BUCKETS`, which matches `audienceplan/genres-v1.yaml` `whitelist`. For each bucket:
```yaml
version: 1
buckets:
  "house & techno":
    sub_genres: [techno, house, hard techno, minimal, melodic techno]   # taxonomy, Bohdan B4
    audience_age:       { value: [21, 35], estimate: true }
    communities:        { value: [], estimate: true }       # ISO-3166 alpha-2; a scene, not a country
    typical_price_eur:  { value: [12, 25], estimate: true }
    typical_start_hour: { value: 23, estimate: true }
    buying_lead_days:   { value: 7, estimate: true }
    # any field may be { value: ..., sourced: "https://..." } instead; exactly one of the two
```
   Initial values are all `estimate: true` and conservative:
   - age: 21–35 for house & techno and bass & hard dance; 20–35 for club/open format and hip-hop & r&b; 20–38 for latin & afrobeats; 22–45 for rock & alternative; 18–35 for pop; 25–55 for jazz & acoustic.
   - start hour: 23 for club buckets, 20 for rock & alternative and pop, 19 for jazz & acoustic.
   - price: €10–25 range, jazz €10–30.
   - buying lead: 7 days.
   - communities are empty everywhere except latin & afrobeats: [NG, GH, CI, SN, CM, CD, CO, VE], estimate.
   Validation: age 16 ≤ lo < hi ≤ 80; price 0 ≤ lo ≤ hi; hour 0..23; lead days 0..120; country codes `^[A-Z]{2}$`; sub_genres lowercase, non-blank, unique across buckets.
4. **Loader validation order**, each failure an `IllegalStateException("predictor <file> <path>.<key>: <problem>")`:
   - version > 0;
   - `thresholds` map present with all 4 keys, and adjust < move, 0 < min_coverage ≤ 1, 1 ≤ max_points ≤ 9;
   - questions non-empty;
   - for each question: source is a single known scalar (missing/empty list → "has no source"; list of ≥ 2 → "has more than one source");
   - id pattern, (id, source) unique;
   - kinds;
   - weight 1..3;
   - max_strength 1..3, and ≤ 2 for web/organizer;
   - window;
   - stop_factor only for structured/internal;
   - countries present, non-empty, `^[A-Z]{2}$`, and cities non-empty when present;
   - template equals `predictor.q.` + id with `.`→`_`, so all entries of one id share it;
   - action key pattern `^predictor\.a\.[a-z0-9_]+$`, `when ∈ kinds`, due `^D-\d+$`;
   - then the profile checks in step 3.
5. **`templateKeys()`** returns, for each id:
   - `predictor.q.<n_m>` if the question has one kind, otherwise `predictor.q.<n_m>.risk` and `predictor.q.<n_m>.opportunity`;
   - `predictor.qShort.<n_m>`;
   - every action key.

   Convention: webapp key = API key with `predictor.` → `dateCheck.` and the question segment prefixed `q` (`predictor.q.4_1` → `dateCheck.q.q4_1`; `predictor.q.4_2.risk` → `dateCheck.q.q4_2.risk`; `predictor.a.x` → `dateCheck.a.x`).
6. **Add the bean** in `PredictorConfig`. It is always loaded, whatever the date-check flag, so a bad file fails every deploy and every `@SpringBootTest`.
7. **Tests** (below). Generate `template-keys.txt` with `-Dpredictor.writeTemplateKeys=true`, then run the full suite.

## Verification commands
- `cd /Users/ivan/imin/imin-api/.claude/worktrees/m1-5-question-bank && ./mvnw test -Dtest=QuestionBankTest`
- `./mvnw test` (run once on untouched origin/master first to confirm the gate is green)

## Test impact
New file `src/test/java/com/imin/iminapi/predictor/rules/QuestionBankTest.java`. It's a plain JUnit test with no Spring; each test builds `validBank()` / `validProfiles()` YAML strings and changes one line.
- **Programme tests:**
  - `loadsShippedBank`: classpath files parse; 16 entries; version stamp "qb2-gp1"; 8 buckets; every profile field has exactly one marker.
  - `rejectsWebStopFactor`
  - `rejectsDoubleSource`
  - `versionIsStampedFromYaml`: bank 7 + profiles 3 → "qb7-gp3".
  - `templateKeysFileIsCurrent`: compares the file lines with `templateKeys()`. On a mismatch it fails with the rerun command; with the system property set it writes the file.
- **Branch tests:**
  - `rejectsOrganizerStopFactor`, `rejectsMissingSource`, `rejectsDuplicateIdAndSource`, `allowsSameIdWithDifferentSources`
  - `rejectsWeightZero`, `rejectsWeightFour`, `rejectsOrganizerStrengthThree`
  - `rejectsEmptyCountries`, `rejectsMissingAppliesWhen`
  - `rejectsDueWithoutDPrefix` (e.g. "28"), `rejectsActionWhenOutsideKinds`
  - `rejectsMissingThresholds`, `rejectsMissingThresholdKey`, `rejectsAdjustNotBelowMove`
  - `rejectsTemplateNotMatchingId`, `rejectsDuplicateYamlKey`
  - `twoKindQuestionExportsRiskAndOpportunityKeys`
  - `rejectsProfileFieldWithBothMarkers`, `rejectsProfileFieldWithNoMarker`, `rejectsMissingBucket`
  - `bucketsMatchAudiencePlanWhitelist`: loads via `LogicLoader.parse` on the audienceplan files.
- Existing tests: no changes. Every `@SpringBootTest` now also builds the bean, which is covered by the full run.

## Live-test
None to click: no endpoint. After deploy, the Railway boot log shows no `predictor question-bank` error and `/actuator/health` is UP. The bean is inert until M1-6.

## Contract impact
None. No DTO or endpoint, so the OpenAPI is unchanged. `template-keys.txt` is a test-resource hand-off to webapp M3-1, not a wire contract.

## i18n impact
None in this task, since the API returns keys, not text. M3-1 must add EN/ES/FR/UK copy for every line of `template-keys.txt`. New action keys not yet in the UX deck §5.8 (the deck has only 4): eve_of_day_off_theme, border_guests_promo, long_night_theme, community_day_theme, religious_period_timing, match_start_time, match_screening, ads_budget_early, own_event_spacing. New question keys beyond the 8 in §5.7 also need copy.

## Blast radius
- A bean in the shared `PredictorConfig` means a YAML error or loader bug fails startup of the whole API, including checkout. This is mitigated by `loadsShippedBank` and the full `@SpringBootTest` suite loading the real files.
- No DB, no money/auth, no `/api/v1` change. Audience-plan code is untouched; the test only reads it.

## Risks
- The weights, windows and profile values are estimates; they are marked `estimate` in the YAML and go to Bohdan for review (M0-6). The UI must show a "Beta" label and never present profile values as facts.
- `input` isn't in M1-2's `source_kind` CHECK (see open questions).
- The `kinds` list and the (id, source) duplication are deviations from the spec §7 shape; M1-6 and M1-7 must read them this way.
- `Node` is duplicated from `LogicLoader`. Pulling it out into a shared helper would touch audienceplan; deferred.
- 8 files, one concern; no split.

## Definition of done
- Both YAMLs are shipped with the content above.
- The loader enforces every step-4 rule, and each rule has a test.
- The bean is wired.
- `template-keys.txt` is committed and current.
- `./mvnw test` is green on the worktree, after being green on untouched base.
- No other files are changed, and the worker has not committed.

## Live-test evidence
(filled in after /ship-imin: Railway deploy id, health UP, no loader error in logs)

## Review rounds
(filled by /do-task)
## Decisions (main session, 2026-09-30)
- `input` source kind: accepted; M1-2's V162 allows source_kind 'input' (being added in that task).
- 0.1 left out of the bank (past dates are a 422 in M1-8): confirmed.
- 2.7 uses `window: month`; the evaluator narrows to ±14 days: confirmed (no window_days field).
- Coverage counts per question id, not per (id, source): confirmed for M1-7.
- Border cities for 4.5 and latin & afrobeats communities are estimates for Bohdan's review.

round 1 → PASS (2 MEDIUM + 5 LOW fixed)
round 2 → PASS (2 LOW fixed in main session: top-level unknown keys, estimate:false branch test)
