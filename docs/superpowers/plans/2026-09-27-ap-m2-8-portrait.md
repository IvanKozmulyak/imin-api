# M2-8 Portrait endpoint and plan wiring (data-only portrait)

Slug: `ap-m2-8-portrait` · Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` §M2-8 (+ its two rev 2026-09-27 rules, M2-4/M2-5/M2-6, "Programme audit 2026-09-27") · Mode: autonomous runner · Reproduction test: n-a (new code).

## Goal and scope

Serve a portrait of the new people an event could reach, built only from sourced data already in the api, and wire it into the audience plan.

- `GET /api/v1/audience/portrait?genre=&city=` → `AudiencePortraitResponse` (contract marker). Gate: `AudiencePlanAccess.requireEnabled(orgId)` first (kill switch, default open; no other beta gating). `genre` must be one of the 8 bucket keys (case/whitespace folded by `EventNormalization.genreKey`), else 400 `FIELD_INVALID`. `city` is folded by `EventNormalization.cityKey`; blank, over 100 chars or containing control characters → 400. A well-formed but unknown city is a 200 with no catchment and null sizes (M2-8 rule: unknown city keys give null).
- **M2-7 is blocked (D4/D10), so no LLM and no research groups.** Groups come only from:
  1. `genre_first` — `TribeSizeCalculator.genreFirst` over the FR towns of the catchment; `method` = the genre-share key (`electronic_first`), or `no_genre_share_rate` with a null size.
  2. `regulars` — `TribeSizeCalculator.regulars`, same scope and method.
  3. `students` — MESR `students` headline summed over the FR towns of the catchment (`method` `mesr_students`); null when any FR town lacks a figure (same all-or-null rule as the census).
  Each group: `key`, `origin` (`open_data` now; M2-7 adds `research`), `scope` (`fr_catchment` = "French part of your area"), `cityKeys`, `size {low, high}` or `null` (never 0), `method`, `sources[]` (input, city, dataset, label/attribution, period/year, licence, url, note, updatedAt, stale). No identity labels.
- Catchment = towns within the logic radius of the **city's stored centroid** (`CatchmentService.around`), so a portrait is a function of `(genre, city)` only and holds no personal data. Response lists every town with `inScope` (FR only). Non-FR towns are never passed to `TribeSizeCalculator` (rev 2026-09-27, M2-6 review).
- Plan wiring (`PlanService`, additive): the portrait for `(event.genreKey, event.venueCityKey)` feeds `PlanCalculator.Input.tribeSize` = regulars range, so `gapExceedsTribe` is `true` when `gap.low > regulars.high`, `false` when not, `null` when the size is unknown; when true the engine's existing `rethink_target` action is kept by `ActionPlanner.topSteps` (it claims its slot within ≤ 3 steps) and now carries `options` = `smaller_room`, `other_date`, `stronger_lineup` (copy keys; no Prediction Tool link). `newPeople` = the portrait groups, stored on the plan row (`audience_plans.new_people`, JSON) so a reused plan shows the groups its `gapExceedsTribe` came from; the groups JSON joins the inputs hash so refreshed open data recomputes the plan. Groups show in every mode (the gap exists in warm/hot too); cold is where the webapp (M3-W2b) renders them.
- Not in scope: M2-7 research groups, `own_scene`, caching table (computation is a few row reads), webapp.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m2-8-portrait` | `./mvnw test` |

## Affected files (per repo)

api:
- new `src/main/resources/db/migration/V153__audience_plans_new_people.sql` (`audience_plans.new_people TEXT` nullable)
- new `audienceplan/dto/AudiencePortraitResponse.java` (also defines the shared `NewPeopleGroup`, `SizeRange`, `PortraitSource`, `PortraitCatchment`, `PortraitTown`)
- new `audienceplan/service/PortraitService.java`
- new `audienceplan/controller/PortraitController.java`
- mod `audienceplan/dto/AudiencePlanResponse.java` (`newPeople` → `AudiencePortraitResponse.NewPeopleGroup`; `Action.options`)
- mod `audienceplan/engine/ActionPlanner.java` (`RETHINK_TARGET_OPTIONS` constant)
- mod `audienceplan/service/TribeSizeCalculator.java` (`shareKey(genre)` helper reused by `estimate`)
- mod `audienceplan/model/AudiencePlan.java` (`newPeople` column)
- mod `audienceplan/service/PlanService.java` (constructor + prepare/persist/response, additive)
- tests: new `service/PortraitServiceTest.java`, new `controller/PortraitControllerScenarios.java` + `PortraitControllerWebTest.java` + `PortraitControllerPostgresTest.java`; mod `controller/AudiencePlanControllerScenarios.java` (plan wiring cases).
- `CLAUDE.md` (endpoint line).

## Ordered steps

1. Baseline `./mvnw test` on the untouched worktree.
2. `TribeSizeCalculator.shareKey` + `ActionPlanner.RETHINK_TARGET_OPTIONS`.
3. DTOs; `PortraitService` (`forCity` ungated, `portrait(orgId, genre, city)` gated + validated); unit tests per branch.
4. `PortraitController` + web/Postgres scenarios.
5. V153 + model + `PlanService` wiring; plan scenarios (cold groups, true/false/null `gapExceedsTribe`, rethink options, ≤ 3 steps).
6. CLAUDE.md line; full `./mvnw test`.

## Verification commands

- `cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m2-8-portrait && ./mvnw test`

## Test impact

One test per branch:
- PortraitService: Metz house & techno → 3 groups with the catchment sums (FR towns metz, thionville, nancy; Luxembourg/Saarbrücken listed `inScope=false` and absent from `cityKeys`); `method` `electronic_first`; sources carry dataset/licence/period; genre without a share → tribe groups null, students still sized; missing census for one FR town → tribe null; missing students for one FR town → students null; unknown city → no catchment, all sizes null; catchment with only non-FR towns → sizes null, calculator not called; non-bucket genre → 400; blank/over-long/control-char city → 400; case-folded genre accepted; kill switch off → 404 before validation.
- Controller (H2 + Postgres): 200 shape through the API; 400 non-bucket genre; 400 missing genre; 404 kill switch; cross-org: two orgs get the same portrait (shared, no personal data).
- Plan: cold Metz event lists the 3 groups with sizes; `gapExceedsTribe` true + `rethink_target` with the three options (capacity 2000, target 1700 > regulars high 1460); false at capacity 300 (255 ≤ 1460); null for an event without a city (existing tests keep `newPeople` [] and null); stored plan reused with the same groups; ≤ 3 steps with rethink kept.
- `TribeSizeCalculator.shareKey`: both electronic buckets → `electronic_first`, `pop` → empty.
- Engine-level fixture (gap 700 vs 606 → true; 162 vs 606 → false) already pinned in `PlanCalculatorTest`/`GapCalculatorTest`.

## Live-test

Not needed for this card: the endpoint is exercised end-to-end through MockMvc on H2 and Postgres 17 with the committed seed rows (real INSEE/MESR/Wikidata figures). The orchestrator's big live-test covers it with the webapp.

## Contract impact

`/api/v1`: new path `GET /api/v1/audience/portrait`, marker `AudiencePortraitResponse`. Plan schema: `NewPeopleGroup` changes shape (was `label/size/sources[string]`, always empty) and `Action` gains `options`. Webapp hand-written `AudiencePlanNewPeopleGroup` must follow in M3-W2b (reads only `[]` today). Do not run api:sync here.

## i18n impact

None in api. `scope`, `key`, `method` and `options` are copy keys for the webapp (EN/ES/FR/UK in M3-W2b).

## Blast radius

`PlanService` (every plan now reads a few `city_open_data` rows; inputs hash changes once for every plan, so each recomputes on its next read), `AudiencePlanResponse` schema, `audience_plans` +1 nullable column. No shared module outside `audienceplan`.

## Risks

- Concurrent PlanService edits (M3-R5/M4-6): kept to constructor + three additive spots.
- Migration number V153 may collide with a concurrent task; renumber at ship.
- Students are all enrolled students, not a genre audience; the group is labelled by key and source only, never merged with the tribe sizes.

## Definition of done

Endpoint + plan wiring as above, every branch tested, `./mvnw test` green, CLAUDE.md updated.

## Live-test evidence

n-a (see Live-test). Verification 2026-09-27: baseline `./mvnw test` on the untouched worktree 4859 run, 0 failures, 0 errors, 1 skipped; after the change 4907 run, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS (Postgres 17 testcontainer suites ran).

## Review rounds

(appended by /do-task)

### Round 1 → FIX_REQUIRED

Fixes applied (review-fix):
1. HIGH: rebased onto origin/master 11c10e4f (M3-R5/M4-6). `newPeople` is now an argument of the shared `PlanService.inputsHash`, so `prepare`, `isFresh` and `refresh` hash the same inputs; `isFresh` takes the event's portrait and `PlanListService` memoises it per (genre, cityKey) within one list call. Scenarios: a just-computed Metz plan lists `fresh`; a changed census figure lists `stale`; two Metz events read the portrait once.
2. MEDIUM: the hash reads only each group's `key`, `cityKeys`, `size` and `method`, never source `updatedAt`/`stale`. Scenario: moving a source's fetch and expiry dates (stale) reuses the plan.
3. MEDIUM: `NewPeopleGroup.kind` = `audience` (genre_first, regulars; a range) or `context` (students: whole enrolled headcount, low == high, never reachable or summed). Programme plan M3-W2b records the rendering rule.
4. MEDIUM: programme plan M3-W2b records that the webapp types follow the M2-8 shape.

Verification after round-1 fixes (2026-09-27, on origin/master 11c10e4f): `./mvnw test` 4959 run, 0 failures, 0 errors, 1 skipped, BUILD SUCCESS.
