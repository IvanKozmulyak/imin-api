# M1-2 Logic, priors and genres YAML + loader

Slug: `ap-m1-2-logic-yaml` · Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (M1-2, §4.2, §4.4, C1, C4, C5) · Mode: Subagent (autonomous runner)

## Goal and scope

Ship the audience plan's versioned logic files and a fail-fast loader, dark and unused by any endpoint:
`logic-v1.yaml` (spec §6 minus priors/genre data, plus `legal.explicit_sources` and an empty
`legal.organizer_named_text_versions`), `priors-v1.yaml` (spec §6 + data-sources §5 bands, tribe-size rates with
source + year) and `genres-v1.yaml` (the 8 event genre bucket keys, bucket adjacency, forbidden identity terms).
`AudiencePlanLogic` (immutable records) is built by `LogicLoader` as a Spring bean at startup, whether or not the
feature is enabled, so a broken file fails the deploy rather than a request. Reproduction test: n-a (new code).

Out of scope: any consumer of the logic (M1-4 onward), class evaluation, genre-fit helpers.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-2-logic-yaml` | `./mvnw test` |

## Affected files

api:
- new `src/main/resources/audienceplan/{logic-v1,priors-v1,genres-v1}.yaml`
- new `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanLogic.java`, `LogicLoader.java`
- modify `AudiencePlanProperties.java` (+ `logicFile`, `priorsFile`, `genresFile` with classpath defaults),
  `AudiencePlanConfig.java` (+ `@Bean AudiencePlanLogic`)
- modify `src/main/resources/application.yaml`, `src/test/resources/application.yaml` (+ the three keys)
- new `src/test/java/com/imin/iminapi/audienceplan/config/LogicLoaderTest.java`

## Ordered steps

1. Write the three YAML files from spec §6 / data-sources §5 / V32 bucket list.
2. `AudiencePlanLogic` records; `LogicLoader.parse(InputStream x3)` with SafeConstructor SnakeYAML and a `Node`
   helper whose every error names `file.path.key`; `LogicLoader.load(ResourceLoader, props)`.
3. Properties + both yaml files; bean in `AudiencePlanConfig`.
4. `LogicLoaderTest`, one test per validation branch, mutating the shipped files as text.

Choices recorded:
- Classes are structured bounds (`paid_orders_min/max`, `days_since_last_paid_min/max`,
  `days_since_last_contact_max`, `requires_import_basis`) instead of the spec's rule strings, so M1-4 reads
  thresholds instead of parsing expressions. Values equal spec §6.
- Adjacency is a list of symmetric pairs taken from the §4.4 chains link by link (house&techno–club,
  club–bass, hip-hop–latin, pop–club, rock–jazz); house&techno is **not** adjacent to bass (chain read as links,
  not transitively). Flagged as an assumption for M0-5 sign-off.
- `coverage_verdict.on` is written `verdict_on`: YAML 1.1 reads a bare `on` key as boolean `true`.
- `legal.fr_tracking_requires_consent` dropped (D3: tracking off for everyone, no tracking keys).
- Priors also carry per-class `unsub_per_send` and the no-show `show_up_if_buy` band (both data-sources §5).
- Tribe-size "94% music listeners" is attributed to CNM 2023 (data-sources §1.7 step 2 cites CNM 2023 for that step).

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-2-logic-yaml && ./mvnw test`

## Test impact

`LogicLoaderTest` (37 tests): shipped files load with logic/priors/genres version 1; every prior triple equals
spec §6 (six classes, unsub bands, show-up paid/free, tickets per order, genre fit, no-show, meta ads, tribe
rates); logic values equal spec §6 (modes, target 85, classes, exclusions, verdict, experiments, legal);
`organizer_named_text_versions` empty; `instagram_organic: unknown` → `Optional.empty()`, a known value → band;
whitelist equals `EventNormalization.genreKey` of the 8 V32 labels; adjacency symmetric; Spring bean loads from
default property paths; missing file fails startup. Failure branches: low > mid, mid > high, value > 1, negative,
not a triple, `unknown` where a band is required, tickets per order 0, fraction > 1, number as text, class without
prior, missing key, integer as decimal, boolean as text, list entry not a mapping, list as scalar, mapping as scalar,
unknown proof requirement, root not a mapping, version 0 (each of the 3 files), adjacency to a non-whitelisted key,
adjacency entry not a pair, whitelisted key with a forbidden term, tribe low > high, blank source, year 0.
`AudiencePlanAccessTest` unchanged and green (its context now also builds the logic bean).

## Live-test

Not needed: no endpoint, job or behaviour change; startup load is covered by the `@SpringBootTest` suite
(every context boot loads the shipped files) and the `ApplicationContextRunner` tests.

## Contract impact

none

## i18n impact

none

## Blast radius

New bean loaded at every startup: a malformed shipped file would stop the API booting. Mitigated by the shipped-file
tests running in `./mvnw test` before any deploy. No DB, no endpoint, no flag change.

## Risks

- Startup coupling (above). Accepted: fail-fast is the §4.4 requirement.
- Adjacency and priors are assumptions pending M0-5 sign-off; changing them is a YAML edit + test update.

## Definition of done

Three files shipped, loader validates per §4.4, keys in properties + both yaml files, `./mvnw test` green except the
pre-existing flaky test below.

## Live-test evidence

n-a (see Live-test).

Baseline on untouched `origin/master` f1e3d3a4: `./mvnw test` 3323 run, 1 failure —
`CapiTokenCipherTest.rejectsTamperedCiphertext` ("Expecting code to raise a throwable"). Unrelated flake: it flips the
second-to-last base64 character, which can land on padding bits that decode to the same bytes (random IV), so GCM
sees no change. Its own card.

After the change: `./mvnw test` 3360 run, 0 failures, 0 errors, BUILD SUCCESS (the flake passed on this run).

## Review rounds

Round 1 (orchestrator): CLEAN, plus fail-fast hardening applied: duplicate YAML keys rejected
(`setAllowDuplicateKeys(false)`); `verdict_on`, `exclusions`, `default_timing_arms` are enums
(`BandPoint`, `Exclusion`, `TimingArm`); class rules reject `*_min > *_max` and duplicate keys; strings and
string lists reject null / non-text values (no coercion); `music_listeners` and `frequent_goers` are
`derived: true` with a note and no year (derived + year fails; derived without or with blank note fails;
sourced without year fails). `LogicLoaderTest` 52 tests; `./mvnw test` 3375 run, 0 failures.
