# M1-8 ResponseModel

Slug: `ap-m1-8-response-model` · Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (M1-8, §4.1, §4.4, §5 Beta, C4) · Mode: autonomous runner

## Goal and scope

Pure engine code that turns a class prior into a purchase-rate band per invitee: class prior band × genre-fit modifier (1.0 / 0.5 / 0.2), calibrated with a Beta-Binomial on IMIN-wide then own observations, then × the no-show band (0.4 / 0.7 / 1.0) when `no_show_n > 0`. Plus a confidence label. No persistence, no REST, no LLM, no sends. M1 binds an empty `CalibrationSource`; M3-5 binds `response_calibration`.

Rules carried forward verbatim from the programme plan:
- (§4.4 title) "Logic files (resources, versioned, never single numbers)"
- (C4) "n = 0: band = YAML `[low, high]`, mid = YAML mid. n > 0: smooth blend, no jump (M1-8): `w = n / (n + 20)`; low = `(1−w)·YAML_low + w·Beta_p10(posterior)`, high likewise with p90; mid = posterior mean (equals YAML mid at n = 0)."
- (M1-8) "Person rate band = class prior band × genre-fit modifier (1.0 / 0.5 / 0.2) × no-show band (0.4 / 0.7 / 1.0 when `no_show_n > 0`). Calibration: prior pseudo-counts `α0 = mid × 20`, `β0 = (1 − mid) × 20`; add IMIN aggregates, then the org's own. n = invited observations added."
- (M1-8) "**Band without a jump (C4):** n = 0 → YAML band exactly. n > 0 → `w = n/(n+20)`; `low = (1−w)·YAML_low + w·p10(Beta(α0+bought, β0+n−bought))`, `high` likewise with p90; `mid` = posterior mean; clamp so `low ≤ mid ≤ high`. Confidence: `own` if own invited ≥ 20 for that class; else `imin` if IMIN invited ≥ 20; else `prior`."
- (§5) "Beta (M1-8): prior loyal Beta(5,15): p10 0.134, p90 0.378. Posterior after 10 invited / 4 bought: Beta(9,21), mean 0.300, p10 0.197, p90 0.409 (± 0.001). Blended band at n = 10 (w = 1/3): low 0.146, mid 0.300, high 0.403."
- (Decisions/runner) No beta gating and no new per-feature enable flag.

Design choices (this task):
- The 20 in `w` and in α0/β0 is `priors.prior_strength_invitations` from `priors-v1.yaml` (= 20), not a literal. The confidence threshold 20 is `ResponseModel.CONFIDENT_INVITATIONS` (tech spec: "`own` (20+ own invitations of this class)").
- The genre-fit modifier is applied **before** calibration (the prior for a (class, fit) cell), matching `response_calibration`'s key `(scope, org_id, class, genre_fit, arm)`; the source sums arms. No-show is applied **after** calibration, bandwise (order is preserved, no clamp needed).
- `CalibrationSource.observations(orgId, class, fit)` returns `imin` (must exclude the org's own) and `own` counts; they are summed (sequential Beta updates = one update on the sum).
- A prior mid of 0 or 1 gives a zero Beta shape: treated as a point mass (quantile 0 or 1) instead of throwing.
- Dependency: `org.apache.commons:commons-math3:3.6.1` (`BetaDistribution.inverseCumulativeProbability`, API checked with `javap` on the resolved jar; constructed with a `null` RNG so no generator is seeded per call).

Reproduction test: n-a (new code).

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-8-response-model` | `./mvnw test` |

## Affected files (per repo)

api:
- new `src/main/java/com/imin/iminapi/audienceplan/engine/ResponseModel.java`
- new `src/main/java/com/imin/iminapi/audienceplan/engine/BetaBand.java`
- new `src/main/java/com/imin/iminapi/audienceplan/engine/CalibrationSource.java`
- mod `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanConfig.java` (beans: empty `CalibrationSource`, `ResponseModel`)
- mod `pom.xml` (+ commons-math3 3.6.1)
- new tests `src/test/java/com/imin/iminapi/audienceplan/engine/{ResponseModelTest,BetaBandTest,CalibrationSourceTest}.java`

## Ordered steps

1. Baseline `./mvnw test` on the untouched worktree.
2. Add commons-math3; write `CalibrationSource` (Counts validation, Observations, `NONE`), `BetaBand.blend/quantile`, `ResponseModel.rate`.
3. Bind `CalibrationSource.NONE` and `ResponseModel` in `AudiencePlanConfig`.
4. Tests one per branch (below); `./mvnw test`.
5. Rebase onto the latest `origin/master` (tagged stash + SHA) and re-run `./mvnw test`.

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-8-response-model && ./mvnw test`

## Test impact

New tests, one per branch:
- `BetaBandTest`: n = 0 returns the YAML band instance; Beta(5,15) p10 0.134 / p90 0.378; Beta(9,21) p10 0.197 / p90 0.409; 10 invited / 4 bought → 0.146 / 0.300 / 0.403 (± 0.001); n = 1 (bought 0 and 1) low/high within 0.01 of YAML and mid = 5/21, 6/21; n = 10,000 → within 0.005 of posterior p10/p90; low clamp; high clamp; α = 0 point mass; β = 0 point mass; prior strength ≤ 0 rejected.
- `CalibrationSourceTest`: `NONE` gives zero counts; `total()` sums; negative invited, negative bought, bought > invited rejected; bought = invited accepted.
- `ResponseModelTest`: prior-only loyal = .12/.25/.40 exactly + `PRIOR`; adjacent halves all three; other × 0.2; no-show bandwise (.048/.175/.40); no-show multiplies the blended band; own 10/4 → fixture band; imin + own summed; calibration starts from the fit-modified prior; source receives (org, class, fit); confidence own 19/imin 0 → prior, own 19/imin 20 → imin, own 20 → own, imin 20/own 0 → imin, imin 19 → prior, both 20 → own; class without prior (`none`) rejected; Spring beans bind `NONE` and a working model.

Continuity note: the programme's "n = 1 band within 0.01 of the YAML band" holds for **low and high**. `mid` is the posterior mean by the same rule, so it moves by one pseudo-observation (5/21 = 0.238 or 6/21 = 0.286 vs 0.25); the test pins those exact values instead.

## Live-test

Not needed: pure engine with no endpoint, job or persistence; covered by unit tests and a context-runner bean test.

## Contract impact

none

## i18n impact

none

## Blast radius

New package `audienceplan/engine` (no callers yet) and two beans in `AudiencePlanConfig`, which every `@SpringBootTest` context loads; both are side-effect free. New runtime dependency commons-math3 (no transitive deps).

## Risks

- commons-math3 is in maintenance mode; the API used (`BetaDistribution`) is stable. Fallback: a hand-rolled bisection on the regularized incomplete beta behind `BetaBand.quantile`.
- M3-5 must return `imin` counts that exclude the org's own, or observations are counted twice (documented on the interface).

## Definition of done

All tests above green in `./mvnw test` after rebasing onto the latest `origin/master`; §5 Beta fixture numbers asserted verbatim; no Spring needed for engine tests.

## Live-test evidence

n-a (see Live-test). Verification: baseline `./mvnw test` on untouched origin/master 87682652 green; after rebase onto origin/master 1b90d117 (pre-rebase stash 92ef2ef2), `./mvnw test` → Tests run: 3582, Failures: 0, Errors: 0, BUILD SUCCESS (new: BetaBandTest 11, ResponseModelTest 17, CalibrationSourceTest 6).

## Review rounds

(appended by the orchestrator)
