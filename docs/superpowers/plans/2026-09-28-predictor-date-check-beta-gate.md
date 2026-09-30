# Predictor "Check a date": beta gate (M1-1)
predictor-date-check-beta-gate · api-only, dark by default · Notion: M1-1 card (the main session adds the link)

## Goal and scope
Add the dark-by-default access gate every later "Check a date" task calls first. It is M1-1 of
`/Users/ivan/imin/docs/superpowers/plans/2026-09-28-predictor-date-check.md` §4.

In scope:
- `DateCheckProperties`, bound to `imin.predictor.date-check`: `enabled=false`, `betaOrgIds` empty,
  `researchEnabled=false`, `maxDates=5`, `maxHorizonMonths=18`.
- `DateCheckAccess.requireEnabled(UUID orgId)`, which throws the same 404 as `AudiencePlanAccess`.
- Registration in `PredictorConfig`, the yaml keys, one line in CLAUDE.md, and tests.

Out of scope: controllers, endpoints, migrations, `PredictionSurface.DATE_CHECK` (that is M1-2),
research wiring (M2-4) and any webapp work.

**Semantic difference from the audience plan gate (deliberate):** in `AudiencePlanAccess.isEnabled`,
an empty list means all orgs (`allowed.isEmpty() || allowed.contains(orgId)`). Here an empty list
means **nobody**, as the programme plan requires with `emptyBetaListMeansNobody`. With the flag on
and the list blank, the feature is still closed. Opening it to everyone needs a later, explicit change.

## Repos in ship order
1. `api` (imin-api, base `master` @ `b247546a`). It ships alone and has no FE follow-up.

## Affected files (per repo)
All paths are relative to `/Users/ivan/imin/imin-api/.claude/worktrees/predictor-date-check-beta-gate/`.

- **Create:** `src/main/java/com/imin/iminapi/predictor/config/DateCheckProperties.java`
- **Create:** `src/main/java/com/imin/iminapi/predictor/config/DateCheckAccess.java`
- **Modify:** `src/main/java/com/imin/iminapi/predictor/config/PredictorConfig.java`. Change
  `@EnableConfigurationProperties(PredictorProperties.class)` to
  `@EnableConfigurationProperties({PredictorProperties.class, DateCheckProperties.class})`.
- **Modify:** `src/main/resources/application.yaml`. Add a nested `date-check:` block at the end of
  the `imin.predictor` block, after `system-rescores-per-event-per-day` (line 596) and before
  `ratelimit:`.
- **Modify:** `CLAUDE.md`. Add one env-var bullet after the `IMIN_AUDIENCE_PLAN_ENABLED` bullet (line 48).
- **Create:** `src/test/java/com/imin/iminapi/predictor/config/DateCheckAccessTest.java`. The
  `predictor/config` test directory is new.

That is 6 files in total.

## Ordered steps
0. **Baseline.** In the worktree, run `./mvnw test` once on untouched `b247546a`. If it is already
   red, stop and report; that failure is a separate card.

1. **Write the test first:** `DateCheckAccessTest`, which fails to compile until steps 2 and 3 exist.
   - Mirror `audienceplan/config/AudiencePlanAccessTest.java`:
     - `ApplicationContextRunner().withUserConfiguration(PredictorConfig.class, DateCheckAccess.class)`
     - `withPropertyValues(...)`
     - the `assertNotFound` helper, which checks `HttpStatus.NOT_FOUND`, `ErrorCode.NOT_FOUND` and the
       message `"Date check not found"`
     - `findCause` for the `BindException` case
   - The test list is under "Test impact".

2. **Create `DateCheckProperties`** with `@ConfigurationProperties(prefix = "imin.predictor.date-check")`
   and a plain JavaBean, like `AudiencePlanProperties`.
   - Fields and defaults:
     - `Boolean enabled = FALSE`
     - `Set<UUID> betaOrgIds = Set.of()`
     - `Boolean researchEnabled = FALSE`
     - `Integer maxDates = 5`
     - `Integer maxHorizonMonths = 18`
   - Use package-private `static final int DEFAULT_MAX_DATES = 5` and `DEFAULT_MAX_HORIZON_MONTHS = 18`.
   - Setters are null-safe, so a blank env var binds the safe default. This is the house pattern from
     `AudiencePlanProperties.setSendsEnabled` and `setSummaryDailyCapPerOrg`:
     - `setEnabled` and `setResearchEnabled` store `Boolean.TRUE.equals(v)`.
     - `setMaxDates` and `setMaxHorizonMonths` store the default when `v == null || v < 1`.
     - `setBetaOrgIds` is copied verbatim from `AudiencePlanProperties.setBetaOrgIds`: null gives
       `Set.of()`, null elements (trailing comma) are dropped, and the result is `Set.copyOf`.
   - Add a 1–2 line class javadoc: "Dark by default; an empty org list means no org, unlike the
     audience plan gate."

3. **Create `DateCheckAccess`** as a `@Component` with constructor `DateCheckAccess(DateCheckProperties)`.
   ```java
   /** Call first, before any org or event lookup, so a closed gate and a missing resource return the same 404. */
   public void requireEnabled(UUID orgId) {
       if (!Boolean.TRUE.equals(props.getEnabled()) || orgId == null || !props.getBetaOrgIds().contains(orgId)) {
           throw ApiException.notFound("Date check");
       }
   }
   ```
   - `ApiException.notFound(String what)` exists at `security/ApiException.java:34`. It returns
     `new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, what + " not found")`, which is the
     exception `AudiencePlanAccess.requireEnabled` throws. That keeps the standard 404 envelope, so
     the webapp can hide the surface on 404.
   - Do not add a public `isEnabled`. No caller exists yet, and M1-3/M4-1 can add it with its own test.

4. **Register the properties** in `PredictorConfig`, as listed above. Update its javadoc to "Wires the
   predictor module's properties; the app has no `@ConfigurationPropertiesScan`."

5. **Add the yaml block.** Keep the 4-space nesting under `predictor:` and use `PREDICTOR_*` env
   names, which is the prefix convention of this block:
   ```yaml
       # "Check a date" (predictor v2). Dark: off, and an empty org list means no org (unlike audience-plan).
       date-check:
         enabled: ${PREDICTOR_DATE_CHECK_ENABLED:false}
         # Comma-separated org UUIDs; a malformed entry fails startup.
         beta-org-ids: ${PREDICTOR_DATE_CHECK_BETA_ORGS:}
         # Cite-only web research; stays off until the provider spike and legal review pass.
         research-enabled: ${PREDICTOR_DATE_CHECK_RESEARCH_ENABLED:false}
         max-dates: ${PREDICTOR_DATE_CHECK_MAX_DATES:5}
         max-horizon-months: ${PREDICTOR_DATE_CHECK_MAX_HORIZON_MONTHS:18}
   ```
   Every key is listed on purpose. A field the yaml does not list binds only under its relaxed name
   (`IMIN_PREDICTOR_DATE_CHECK_…`), the trap `PredictorPropertiesBindingTest` documents.

6. **Update CLAUDE.md** with one bullet: `PREDICTOR_DATE_CHECK_ENABLED` (default `false`) and
   `PREDICTOR_DATE_CHECK_BETA_ORGS` (comma-separated org UUIDs, default empty = **no org**).
   - Gate for "Check a date", `imin.predictor.date-check.*`, `DateCheckAccess.requireEnabled`.
   - When off, for a null org, or for an org not listed: the standard `404 NOT_FOUND`.
   - A malformed UUID fails startup.
   - Also: `PREDICTOR_DATE_CHECK_RESEARCH_ENABLED` (default `false`) and
     `PREDICTOR_DATE_CHECK_MAX_DATES` / `_MAX_HORIZON_MONTHS` (5 / 18; blank or < 1 binds the default).

7. **Verify.** Run `./mvnw test -Dtest=DateCheckAccessTest,PredictorPropertiesBindingTest`, then the
   full `./mvnw test`.

**Prefix collision decision (why nesting is safe):** `PredictorProperties` binds `imin.predictor` and
has no `dateCheck` field. `@ConfigurationProperties.ignoreUnknownFields()` defaults to `true` in
spring-boot 4.0.5 (checked in `~/.m2/.../spring-boot-4.0.5.jar`: `ignoreUnknownFields` default `1`,
`ignoreInvalidFields` default `0`), so `PredictorProperties` ignores `date-check.*`. Separately:
- `PredictorPropertiesBindingTest.predictorBlock()` ends the block at the next `\n  [a-z]…:` key.
  The nested `\n    date-check:` does not match that, so the new keys stay inside the predictor block.
- That test only reflects over `PredictorProperties` fields and `PREDICTOR_*` names in
  `PredictorProperties.java`, so it is unaffected. It still runs as a regression check.

The alternative is a top-level `imin.predictor-date-check` prefix. It was rejected because the
programme plan and the webapp contract already name `imin.predictor.date-check.*`.

## Verification commands
- `cd /Users/ivan/imin/imin-api/.claude/worktrees/predictor-date-check-beta-gate && ./mvnw test` on
  the base first (step 0), then after the change.
- Targeted: `./mvnw test -Dtest=DateCheckAccessTest,PredictorPropertiesBindingTest,AudiencePlanAccessTest`

## Test impact
New file: `src/test/java/com/imin/iminapi/predictor/config/DateCheckAccessTest.java`.

**Branch map of `requireEnabled`:** (a) flag off, (b) null org, (c) empty list, (d) org not listed,
(e) org listed.

**Branch map of the setters:** null or blank boolean → false; `betaOrgIds` null, null element, or
malformed; integer null or < 1 → default.

One test per branch, each with the minimal setup that forces it:
1. `offReturns404`: `enabled=false`, `beta-org-ids=A` → `requireEnabled(A)` is a 404. The org being
   listed proves the flag wins. Branch (a).
2. `onButOrgNotInBetaReturns404`: `enabled=true`, `beta-org-ids=A` → `requireEnabled(B)` is a 404. Branch (d).
3. `onAndInBetaPasses`: `enabled=true`, `beta-org-ids=A, B` (space after the comma, as a Railway
   value might have) → `requireEnabled(B)` does not throw. Branch (e).
4. `emptyBetaListMeansNobody`: `enabled=true`, `beta-org-ids=` → the bound `getBetaOrgIds()` is
   empty, and both A and B get a 404. Branch (c), which is the difference from the audience plan gate.
5. `nullOrgReturns404`: `enabled=true`, `beta-org-ids=A` → `requireEnabled(null)` is a 404. Branch (b).
   The list is non-empty so that the null check is what fires.
6. `trailingCommaDropsBlankElement`: `beta-org-ids=A,` → the set equals `Set.of(A)`, and A passes.
7. `malformedUuidFailsStartup`: `beta-org-ids=not-a-uuid` → the context has failed, with a
   `BindException` whose message contains `imin.predictor.date-check.beta-org-ids`.
8. `defaultsAreOff`: plain `new DateCheckProperties()` gives `enabled=false`, `researchEnabled=false`,
   empty list, 5 and 18, and `requireEnabled(A)` is a 404. Plain construction is used so no env var
   can leak in.
9. `blankEnvVarsBindSafeDefaults`: stub `PREDICTOR_DATE_CHECK_ENABLED=`, `..._RESEARCH_ENABLED=`,
   `..._MAX_DATES=`, `..._MAX_HORIZON_MONTHS=` to empty, and bind the shipped placeholders
   (`imin.predictor.date-check.enabled=${PREDICTOR_DATE_CHECK_ENABLED:false}` and so on) → false,
   false, 5, 18.
10. `nonPositiveLimitsFallBackToDefaults`: `max-dates=0`, `max-horizon-months=-1` → 5 and 18.
    Separately, `max-dates=3`, `max-horizon-months=12` bind as given, which proves the setter does not
    always return the default.
11. `shippedYamlIsDarkAndEnumeratesEveryKey`: read `src/main/resources/application.yaml`.
    - It contains each of the five exact lines from step 5.
    - Reflect over `DateCheckProperties` non-static fields and assert that each kebab-case name
      appears as `\n      <key>:` inside the `date-check:` block.
    - Read from source, so a test double cannot satisfy it.

Existing tests that must stay green unchanged: `PredictorPropertiesBindingTest` (it reads the same
yaml block) and `AudiencePlanAccessTest`. No existing test changes.

## Live-test
None. The gate is dark by default and has no HTTP surface: no controller calls `requireEnabled` until
M1-8. After deploy, the only observable signs are that the Railway boot succeeds and that
`/v3/api-docs.yaml` is unchanged.

## Contract impact
None. There is no endpoint, DTO or schema, so there is no OpenAPI marker, no `types.ts` change and no
`PUBLIC_PAGE_API.md` change.

## i18n impact
None. The change is API-only and adds no user-facing string. The 404 message follows the existing
English envelope convention.

## Blast radius
- **Shared module:** `PredictorConfig` is the predictor module's only `@EnableConfigurationProperties`.
  Adding a second class does not change `PredictorProperties` binding.
- **Startup:** a malformed UUID in `PREDICTOR_DATE_CHECK_BETA_ORGS` fails application startup
  (fail-fast, same as `IMIN_AUDIENCE_PLAN_BETA_ORGS`), and a failed boot blocks the Railway deploy.
  The variable is unset in prod today, so the default is empty and cannot fail.
- No money, auth, Stripe, Flyway or `/api/v1` contract impact.

## Risks
- **Opposite empty-list semantics from `AudiencePlanAccess`.** Someone copying env habits could expect
  a blank list to open the feature to everyone. This is mitigated by the yaml comment, the CLAUDE.md
  bullet and test 4.
- **Relaxed-binding trap.** Any future field added to `DateCheckProperties` without a yaml line would
  bind only under `IMIN_PREDICTOR_DATE_CHECK_*`. Test 11 guards this.
- **Binding `"A, B"` with a space.** Spring trims comma-delimited elements before conversion to UUID
  (the audience test `enabled_orgListed_passes` already relies on this). Test 3 pins it.
- The task is 6 files and a single concern, so no split is needed.

## Definition of done
- The 3 new or changed Java main files, the yaml block, the CLAUDE.md bullet and `DateCheckAccessTest`
  (11 tests) are in the worktree, uncommitted.
- `./mvnw test` is green in the worktree, and was also green on the untouched base.
- `requireEnabled` gives a 404 for flag off, null org, empty list and unlisted org, and passes only
  for a listed org with the flag on.
- The shipped yaml defaults are all off, 5, and 18.
- No controller, migration or contract change.

## Live-test evidence
Not applicable: there is no surface. `/ship-imin` records a successful Railway boot and an unchanged
prod OpenAPI.

## Review rounds
(filled by /do-task)

round 1 → PASS (0 CRITICAL/HIGH/MEDIUM; 1 LOW: no combined PredictorProperties+DateCheckProperties binding test, covered indirectly)
