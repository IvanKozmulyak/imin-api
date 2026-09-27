# M2-5 TribeSizeCalculator

Slug: `ap-m2-5-tribe-size` · Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (§ M2-5, C18, §5) · Spec: `audience-tool/data-sources-v1.md` §1.7, `tech-spec-v1.md` §7.6.

## Goal and scope

Programme text (verbatim):
> ### M2-5 TribeSizeCalculator [after M2-4]
> - Code only, rates from `priors-v1.yaml` with source and year. Output `{low, high, method, inputs[], sources[]}`.
> - Tests: Metz 1,320-1,732 electronic-first, 462-606 regulars (C18); missing census → `null` sizes, never 0.

C18 (verbatim): "Metz size "~1,300-1,700" (data-sources §1.7) | 38,065 × 0.94 × 0.41 × 0.09 = 1,320 and 38,065 × 0.94 × 0.44 × 0.11 = 1,732 | Tests pin 1,320-1,732 and regulars 462-606; the UI rounds with "~"."

§5 fixture (verbatim): "Tribe size Metz (M2-5): 18-35 = 38,065 → electronic-first 1,320-1,732 → regulars 462-606."

Build `audienceplan/service/TribeSizeCalculator`: people aged 18-35 (the stored INSEE census row from `PublicDataService.get`, never a fetch) × `music_listeners` × `bar_club_concert_goers` × the genre-share rate = genre-first people; × `frequent_goers` = regulars. Low uses every low rate, high every high rate; each size is `Math.round` of the raw product (regulars from the raw genre-first product). Output per tier `{low, high, method, inputs[], sources[]}`. The calculator takes a genre key and one or more city keys (the population is summed, so M2-8 can pass a catchment). No endpoint, no plan wiring (M2-8), no catchment (M2-6, separate runner).

Genre scope: the only genre-share rate in the priors is Ekhoscènes "main live genre is electronic". Which of the 8 buckets it describes is data, so it goes into `priors-v1.yaml` as `genres:` on the `electronic_first` rate: `house & techno` and `bass & hard dance` (assumption, flagged in the YAML). Any other bucket has no share rate yet → null sizes, never borrowed from electronic.

Rules carried forward verbatim:
- §4.4: "`unknown` is a legal value and flows to the API as `null` / `"unknown"`, never as 0."
- §4.4 title: "Logic files (resources, versioned, never single numbers)"; priors: "tribe-size rates with `source` + `year` per number".
- C1: "**Every genre field in this plan (taste, segment rules, portrait `?genre=`, survey, UI bars) uses only these 8 keys.**"
- §4.5: "Numbers shown must trace to a real API field (no sample numbers from the design canvas)." and "Exact numbers stay in the API and in engine/API tests (§5). Only webapp tests assert the rounded display."
- M2-7: "Numbers come only from M2-4/M2-5." (the model never produces a size).
- NO beta gating (runner rule 2026-09-26): no new flag, no beta check.

Reproduction test: n-a (new code).

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m2-5-tribe-size` | `./mvnw test` |

## Affected files (per repo)

api:
- `src/main/resources/audienceplan/priors-v1.yaml` — `genres:` on `electronic_first`.
- `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanLogic.java` — `SourcedRate.genres` (empty set = not a genre share).
- `src/main/java/com/imin/iminapi/audienceplan/config/LogicLoader.java` — parse `genres`, validate whitelisted (cross-file) and each genre on at most one rate.
- `src/main/java/com/imin/iminapi/audienceplan/service/TribeSize.java` (new) — result records.
- `src/main/java/com/imin/iminapi/audienceplan/service/TribeSizeCalculator.java` (new).
- `src/test/java/com/imin/iminapi/audienceplan/config/LogicLoaderTest.java` — shipped value + new failure cases.
- `src/test/java/com/imin/iminapi/audienceplan/service/TribeSizeCalculatorTest.java` (new).

## Ordered steps

1. Tests first: `LogicLoaderTest` shipped `tribeSize` includes `genres`; failures for a non-whitelisted genre and a genre on two rates; a missing `genres` key loads an empty set.
2. `SourcedRate` + loader + YAML.
3. `TribeSizeCalculatorTest` (one per branch, below), then `TribeSize` + `TribeSizeCalculator`.
4. `./mvnw test`.

Branches → tests:
- Metz `house & techno`, 38,065 fresh → genre-first 1,320-1,732, regulars 462-606; method, inputs (population + 4 rates, low/high), sources (INSEE attribution/period/url; CNM 2023; Ekhoscènes 2024; derived rates with year null + note).
- `bass & hard dance` (second genre on the share) → same numbers.
- whitelisted genre without a share (`jazz & acoustic`) → both tiers null low/high, no inputs.
- non-whitelisted genre → `IllegalArgumentException`.
- empty or null city list → `IllegalArgumentException`.
- no stored census row → null sizes (not 0), population input null.
- stored row with null headline → null sizes.
- two cities with data → population summed (both in inputs).
- two cities, one missing → null sizes (never a silent partial sum).
- stale row → numbers served, source flagged `stale`.
- duplicate city key counted once.
- priors missing a required rate key → constructor throws `IllegalStateException`.
- a participation rate carrying `genres` → constructor throws (it would be multiplied twice).

## Verification commands

- `cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m2-5-tribe-size && ./mvnw test`

## Test impact

New `TribeSizeCalculatorTest`; `LogicLoaderTest` expectation for `SourcedRate` gains `genres`. No existing behaviour changes.

## Live-test

Not needed: no endpoint, no wiring; the calculator is pure over stored rows and is exercised by unit tests. M2-8 live-tests the portrait endpoint.

## Contract impact

none

## i18n impact

none (api only, no user-facing strings; `method` is a formula of YAML keys).

## Blast radius

`AudiencePlanLogic.SourcedRate` gains a field (only constructed by the loader and `LogicLoaderTest`). New service bean with no caller yet. No migration, no config key.

## Risks

- `bass & hard dance` in the electronic share is an assumption (Ekhoscènes "électro" is not split by bucket); flagged in YAML, calibrate with door data.
- Inputs are national concert proxies, not club data (data-sources §1.7): the UI must show "~" (C18).

## Definition of done

Metz fixture pinned exactly (1,320-1,732 / 462-606); missing census → null, never 0; `./mvnw test` green.

## Live-test evidence

n/a

## Review rounds

(appended by /do-task)

- round 1 → CLEAN (MEDIUM: M2-8 no-sum rule for shared genre-share rates recorded in programme plan; LOW loader duplicate-in-list message → follow-up card)
