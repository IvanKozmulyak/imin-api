# Audience plan M1-1: scaffolding and beta gate
audience-plan-m1-1-beta-gate · Subagent · Notion: none linked

## Goal and scope
This adds the `com.imin.iminapi.audienceplan` package with a config class and a single entry check. `AudiencePlanAccess.requireEnabled(UUID orgId)` throws `ApiException.notFound("Audience plan")` unless `imin.audience-plan.enabled=true` and the org is listed in `imin.audience-plan.beta-org-ids`. It is dark by default: no controller, no job, no migration, so nothing is reachable.

Source: programme plan `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (§M1-1, §4.2, "Decisions taken 2026-09-26"; none of those decisions affect M1-1).

Reproduction test: n-a (new code, no defect).

Decision (YAGNI): of the §4.2 block, M1-1 binds only `enabled` and `beta-org-ids`, the two keys `requireEnabled` reads.
- `sends-enabled` moves to M3-3, `soft-opt-in-enabled` to its first reader, `retention-job-enabled` to M1-12, the `logic/priors/genres-file` keys to M1-2, and `summary-model` to M2-2.
- Reason: a `sends-enabled: false` line with no code behind it reads like enforcement but enforces nothing. The binder ignores unknown `imin.*` keys (`ignoreUnknownFields` defaults to true), so yaml keys added early without fields would not fail, and would not protect anything either.
- Each later task adds its key next to the code that reads it.

Out of scope: any endpoint, `isEnabledFor` boolean variants, the other §4.2 keys, and Railway env changes.

## Repos in ship order
| key | base | worktree | verification command |
|---|---|---|---|
| api | master | /Users/ivan/imin/imin-api/.claude/worktrees/audience-plan-m1-1-beta-gate (@ d3c50089) | `./mvnw test` |

Ships alone; no FE follow-up.

## Affected files (per repo)
api:
- NEW `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanProperties.java`
- NEW `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanConfig.java`: `@Configuration @EnableConfigurationProperties(AudiencePlanProperties.class)`. Required because the app has no `@ConfigurationPropertiesScan` (see the javadoc in `service/analytics/AnalyticsConfig.java` and `push/PushConfig.java`); without it the class is silently never bound. This is a third file beyond the programme plan's two.
- NEW `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanAccess.java`
- MOD `src/main/resources/application.yaml`: new `imin.audience-plan` block after `imin.momentum` (the last `imin.*` block, before `sentry:`).
- MOD `src/test/resources/application.yaml`: the same block after `imin.media` with literal dark values. This file shadows the main one on the test classpath, so it does not merge with it.
- MOD `CLAUDE.md`: one env-var bullet for `IMIN_AUDIENCE_PLAN_ENABLED` / `IMIN_AUDIENCE_PLAN_BETA_ORGS` (defaults, 404 behaviour, and that a malformed UUID fails boot).
- NEW `src/test/java/com/imin/iminapi/audienceplan/config/AudiencePlanAccessTest.java`
- This plan file.

Total: 7 files plus the plan.

## Ordered steps
1. Baseline: run `./mvnw test` on the untouched worktree. If it is red there, that is its own card; stop and report.
2. `AudiencePlanProperties`: a JavaBean following the repo convention (no record-style props exist; compare `AnalyticsProperties` and `GoogleWalletProperties`). It has `@ConfigurationProperties(prefix = "imin.audience-plan")` and these fields:
   - `boolean enabled = false`
   - `Set<UUID> betaOrgIds = Set.of()`. Its setter maps `null` to `Set.of()`, drops `null` elements and stores `Set.copyOf(...)`.
   - Why nulls appear: Boot 4.0.5 `DelimitedStringToCollectionConverter` splits on `,`, trims each element and converts it. Core `StringToUUIDConverter` returns `null` for an empty element, so `"a,"` arrives with a `null` in it. `""` gives an empty array, hence an empty set. A bad UUID makes `UUID.fromString` throw, which becomes a bind failure and a context start failure. This is the same "blank is not an element" pattern as `GoogleWalletProperties.setOrigins`.
   - No `@Validated`: the failures that matter already fail at conversion.
   - Javadoc (1–2 lines): what the flag gates, and that blank means nobody.
3. `AudiencePlanConfig`: as listed above, with no body.
4. `AudiencePlanAccess`: a `@Component` with constructor injection of `AudiencePlanProperties`. `public void requireEnabled(UUID orgId)` throws `ApiException.notFound("Audience plan")` when any of these holds:
   - `!props.isEnabled()`
   - `orgId == null`. This branch is needed because `Set.copyOf(..).contains(null)` throws `NullPointerException`.
   - `!props.getBetaOrgIds().contains(orgId)`

   Otherwise it returns. Signature at `security/ApiException.java:34`: `public static ApiException notFound(String what)` → `new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, what + " not found")`, rendered by `GlobalExceptionHandler.handleApi` as the standard `ApiError` envelope. That is the same body as any other missing resource, so it leaks nothing. The 1–2 line javadoc says: callers invoke this first, before any org or event lookup, so a disabled feature and a missing event look the same.
5. `application.yaml` (main):
   ```yaml
     audience-plan:
       # Dark by default; even when enabled only listed orgs pass. Blank list = nobody.
       enabled: ${IMIN_AUDIENCE_PLAN_ENABLED:false}
       # Comma-separated org UUIDs; a malformed entry fails startup.
       beta-org-ids: ${IMIN_AUDIENCE_PLAN_BETA_ORGS:}
   ```
6. `src/test/resources/application.yaml`: `audience-plan:` with `enabled: false` and `beta-org-ids: ""`.
7. `CLAUDE.md`: one bullet in the env-var list.
8. Tests (see Test impact), then run `./mvnw test`.

## Verification commands
- `cd /Users/ivan/imin/imin-api/.claude/worktrees/audience-plan-m1-1-beta-gate && ./mvnw test`. Run once on the base before the change and once after.
- Focused: `./mvnw test -Dtest=AudiencePlanAccessTest` and `./mvnw test -Dtest=IminApiApplicationTests` (boot with defaults).
- `grep -rn "@RestController\|@Scheduled\|@EventListener" src/main/java/com/imin/iminapi/audienceplan` must return nothing ("nothing reachable").

## Test impact
Reproduction test: n-a (new code, no defect).

Branches in the changed logic:
- requireEnabled: (a) disabled, (b) null orgId, (c) org absent, (d) org listed
- setter: (e) empty value → empty set, (f) blank element dropped
- binding: (g) malformed UUID → startup failure

File: `src/test/java/com/imin/iminapi/audienceplan/config/AudiencePlanAccessTest.java`.
- It uses `ApplicationContextRunner` (spring-boot-test 4.0.5: `withUserConfiguration(Class<?>...)`, `withPropertyValues(String...)`, `run(ContextConsumer)`; `ApplicationContextAssert.hasFailed()` / `getFailure()`) with `withUserConfiguration(AudiencePlanConfig.class, AudiencePlanAccess.class)`.
- Every case therefore goes through the real binder rather than hand-built props. Setup is minimal and needs no `@SpringBootTest`.

One test per branch:
1. `disabled_throws404_evenWhenOrgListed`: `enabled=false`, `beta-org-ids=<A>`, `requireEnabled(A)`. Asserts `ApiException` with status 404, code `NOT_FOUND`, message `"Audience plan not found"`.
2. `enabled_orgAbsent_throws404`: `enabled=true`, ids=`<A>`, `requireEnabled(B)`. Same 404 assertions.
3. `enabled_orgListed_passes`: `enabled=true`, ids=`<A>, <B>` (with a space, which proves trimming). `requireEnabled(B)` does not throw.
4. `enabled_blankList_throws404`: `enabled=true`, `beta-org-ids=`. Asserts 404 for `A`, and `getBetaOrgIds()` is empty.
5. `enabled_trailingComma_dropsBlankElement`: ids=`<A>,`. Asserts `getBetaOrgIds()` equals `{A}` (no null) and `requireEnabled(A)` passes.
6. `nullOrgId_throws404`: `enabled=true`, ids=`<A>`, `requireEnabled(null)`. Asserts 404, not an NPE.
7. `malformedUuid_failsStartup`: ids=`not-a-uuid`. Asserts `context.hasFailed()`, and that the failure's root-cause chain contains a `BindException` (`org.springframework.boot.context.properties.bind.BindException`) mentioning `imin.audience-plan.beta-org-ids`.

Existing: `IminApiApplicationTests` covers boot with the test yaml defaults; no change needed.

## Live-test
Not needed as a runtime test, because there is no runtime surface except boot. Boot check via the `./mvnw test` context load (`IminApiApplicationTests`). After deploy, confirm that Railway deployed and that `curl -s https://imin-api-production.up.railway.app/v3/api-docs.yaml | grep -c AudiencePlan` prints `0` (nothing exposed).

## Contract impact
None. No endpoint and no schema; `/api/v1` and `/api/v1/public` are unchanged. No OpenAPI marker, no `types.ts` change, no `PUBLIC_PAGE_API.md` change.

## i18n impact
None. There are no UI strings. The 404 message is an internal API envelope string, like every other `notFound`.

## Blast radius
- New isolated package; no existing class is changed, and no migration or money/auth/Stripe code is touched.
- Shared config: two new yaml keys. Prod defaults are dark (`false` / empty), and Railway does not set these vars today.
- Boot coupling: a malformed `IMIN_AUDIENCE_PLAN_BETA_ORGS` in Railway fails application startup (fail-fast, as the spec intends). That is a whole-API outage on a bad env edit, not a feature-local failure.

## Risks
- Fail-fast on a typo takes the API down. Mitigations: the default is empty; the CLAUDE.md bullet warns; only set the var with UUIDs copied from `organizations.id`. If a softer failure is wanted later, the alternative (log, drop the entry, keep booting) is a spec change, not this task.
- Deviating from the "§4.2 block" wording by binding only 2 keys: the rationale is in Goal and scope. Later tasks must add their own keys; the programme plan's per-task sections already name them.
- Size: 7 files, one concern. No split needed.

## Definition of done
- The 7 files above; `./mvnw test` is green in the worktree, and the base run was recorded green first.
- `AudiencePlanAccessTest` has 7 tests, one per branch, all through the real binder.
- The app boots with defaults (context-load test green); the grep for controllers, schedulers and listeners in `audienceplan` is empty.
- No Co-Authored-By or AI attribution; comments are 1–2 lines with no ticket references.

## Live-test evidence
_(filled after ship: Railway deploy id, OpenAPI grep `AudiencePlan` = 0)_

## Review rounds
round 1 → CLEAN (SHIP): 0 CRITICAL/HIGH/MEDIUM; LOW: defensive `setBetaOrgIds(null)` branch unreachable through the binder, not tested (no fix needed).
