# Sentry Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Sentry error reporting in `imin-api` for the `prod` profile only, capturing unhandled exceptions and `log.error(...)` events. Dev and test runs send nothing.

**Architecture:** Drop in `sentry-spring-boot-starter-jakarta` 8.40.0 and configure it via YAML. No Java code changes. Base `application.yaml` defaults to `enabled: false`; `application-prod.yaml` flips it on and sets the environment + release tag. The starter wires both Spring MVC's exception path and Logback for us.

**Tech Stack:** Spring Boot 4.0.5 (jakarta namespace), Sentry Java SDK 8.x, Logback (Spring Boot default).

**Spec:** `docs/superpowers/specs/2026-04-29-sentry-config-design.md`

---

## File map

- **Modify** `pom.xml` — add one `<dependency>` block.
- **Modify** `src/main/resources/application.yaml` — add a top-level `sentry:` block with disabled defaults.
- **Modify** `src/main/resources/application-prod.yaml` — add a top-level `sentry:` block with prod overrides.

No new files. No Java source changes. No new tests (config-only; existing test suite continues to pass with Sentry disabled).

---

## Task 1: Add the Sentry starter dependency

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add the dependency**

Open `pom.xml` and add this block alongside the other `<dependency>` entries (e.g. right after `spring-boot-starter-validation` is fine — order isn't load-bearing):

```xml
<dependency>
    <groupId>io.sentry</groupId>
    <artifactId>sentry-spring-boot-starter-jakarta</artifactId>
    <version>8.40.0</version>
</dependency>
```

Version `8.40.0` is the latest stable on Maven Central as of 2026-04-29. If a newer 8.x is available when implementing, it's safe to bump — 8.x is a stable major.

- [ ] **Step 2: Resolve the dependency**

Run:
```bash
./mvnw -q dependency:resolve -DincludesGroupIds=io.sentry
```
Expected: completes with no error and prints `io.sentry:sentry-spring-boot-starter-jakarta:jar:8.40.0:compile` (and transitives like `io.sentry:sentry-spring-jakarta`, `io.sentry:sentry-logback`, `io.sentry:sentry`).

If resolution fails because of a Spring Boot 4 ↔ Sentry 8.x conflict, fall back to manual wiring: replace this dependency with `io.sentry:sentry:8.40.0` plus `io.sentry:sentry-logback:8.40.0`, and configure the Logback appender in `src/main/resources/logback-spring.xml` instead of via the starter properties. (Not expected — both sides use jakarta — but documented so the implementer doesn't get stuck.)

- [ ] **Step 3: Build to confirm the app still compiles**

Run:
```bash
./mvnw -q -DskipTests package
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "Add sentry-spring-boot-starter-jakarta 8.40.0"
```

---

## Task 2: Base Sentry config (disabled by default)

**Files:**
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Add the `sentry:` block**

Append a new top-level `sentry:` block at the end of `src/main/resources/application.yaml` (after the existing `imin:` block):

```yaml
sentry:
  dsn: ${SENTRY_DSN:}
  enabled: false
  send-default-pii: false
  traces-sample-rate: 0.0
```

This keeps dev (which uses this file directly) and test (which has its own `src/test/resources/application.yaml` with no `sentry:` block) silent. The empty default for `dsn` means even if someone later flips `enabled: true` without supplying a DSN, the SDK will warn loudly at startup rather than silently swallowing events.

- [ ] **Step 2: Start the app under the dev profile**

Run:
```bash
./mvnw -q spring-boot:run
```
(Defaults to the `dev` profile per `application.yaml`'s `spring.profiles.default: dev`.)

Expected: the app starts and listens on `:8085`. In the startup logs you should see a Sentry line indicating it is **disabled**, e.g. `Sentry has been disabled` or `Disabling sentry-java because the dsn is empty`. No events are sent.

Stop the app (`Ctrl-C`) once you've confirmed startup.

- [ ] **Step 3: Run the existing test suite**

Run:
```bash
./mvnw -q test
```
Expected: all tests pass. The test profile inherits `sentry.enabled: false` (or never sees the Sentry properties at all, since tests have a different `application.yaml`); either way the SDK no-ops.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application.yaml
git commit -m "Configure Sentry defaults (disabled in dev/test)"
```

---

## Task 3: Production Sentry config

**Files:**
- Modify: `src/main/resources/application-prod.yaml`

- [ ] **Step 1: Add the `sentry:` block**

Append a new top-level `sentry:` block at the end of `src/main/resources/application-prod.yaml` (after the `server:` block):

```yaml
sentry:
  enabled: true
  environment: prod
  release: ${SENTRY_RELEASE:imin-api@${project.version}}
  logging:
    minimum-event-level: error
    minimum-breadcrumb-level: info
```

Notes for the implementer:
- `enabled: true` here overrides the `enabled: false` from `application.yaml` whenever the `prod` profile is active.
- `${project.version}` is the Maven project version, available at runtime because the Spring Boot Maven plugin filters `application.yaml` resources. If your build doesn't filter resources by default, replace `${project.version}` with the literal `0.0.1-SNAPSHOT` for now and add a follow-up to wire CI to inject `SENTRY_RELEASE` properly.
- `dsn` is **not** set here — it picks up `${SENTRY_DSN:}` from the base `application.yaml`. The DSN is supplied at runtime via the `SENTRY_DSN` env var.

- [ ] **Step 2: Start the app under the prod profile (without a DSN) to confirm config loads**

Run:
```bash
SPRING_PROFILES_ACTIVE=prod \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/postgres \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=postgres \
OPENROUTER_API_KEY=dummy \
OPENAI_API_KEY=dummy \
./mvnw -q spring-boot:run
```
(Adjust DB credentials to match your local `compose.yaml` stack — the real Sentry behavior is what we're checking, the DB only needs to be reachable enough for the app to start.)

Expected: app starts. Sentry logs a warning that the DSN is missing/empty and that it is disabling itself. **This is the intended "loud-fail" behavior.** Stop the app.

- [ ] **Step 3: Start the app under the prod profile WITH a DSN, confirm initialization**

Get a DSN from the existing Sentry project. Run:
```bash
SENTRY_DSN='<your-real-dsn>' \
SPRING_PROFILES_ACTIVE=prod \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/postgres \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=postgres \
OPENROUTER_API_KEY=dummy \
OPENAI_API_KEY=dummy \
./mvnw -q spring-boot:run
```
Expected: app starts. Sentry logs initialization with `environment=prod` and the release tag. No warning about empty DSN.

Stop the app.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/application-prod.yaml
git commit -m "Enable Sentry in prod profile"
```

---

## Task 4: End-to-end smoke test

This task verifies an actual exception flows to Sentry. It introduces a temporary throw in an existing controller, observes the event in Sentry, then reverts. **The revert is a required step** — do not commit the throw.

**Files:**
- Temporarily modify: any existing `@RestController` (suggest: `EventCreatorController` since it's the project's main entry point).
- Revert before the final commit of this task.

- [ ] **Step 1: Add a temporary throw**

Pick an existing GET endpoint on `EventCreatorController` (or any other controller exposed under `permitAll` — `/api/events/**` is permitAll per `SecurityConfig`). Add at the very top of one method:

```java
if (true) throw new RuntimeException("sentry smoke test");
```

If no GET endpoint is convenient, add a guard to a POST endpoint and use `curl -X POST` in step 3.

- [ ] **Step 2: Run the app under the prod profile with DSN set**

Same command as Task 3 Step 3.

- [ ] **Step 3: Hit the endpoint**

```bash
curl -i http://localhost:8085/<the-endpoint-path>
```
Expected: HTTP 500 response.

- [ ] **Step 4: Confirm the event in Sentry**

Open the Sentry project's Issues view in the browser. Within ~30 seconds, a new issue titled `RuntimeException: sentry smoke test` should appear with:
- `environment: prod`
- `release: imin-api@0.0.1-SNAPSHOT` (or whatever `${project.version}` resolved to)
- A stack trace pointing into the controller you modified

- [ ] **Step 5: Revert the temporary throw**

Remove the `if (true) throw ...` line. Stop the app.

Run:
```bash
git diff
```
Expected: empty diff (no leftover throw). If the diff isn't empty, finish the revert.

- [ ] **Step 6: No commit needed**

Task 4 leaves the working tree clean. Tasks 1–3 are the entire shipped change.

---

## Self-review notes

- Spec coverage: every section of the spec maps to a task.
  - Spec §1 Maven dep → Task 1.
  - Spec §2 base config → Task 2.
  - Spec §3 prod overrides → Task 3.
  - Spec §4 dev/test silence → verified in Task 2 Steps 2–3.
  - Spec §5 env vars → Task 3 (DSN at runtime), Task 3 Step 1 note (release).
  - Spec "Verification" section → Task 4.
  - Spec fallback for SB4 ↔ Sentry incompatibility → Task 1 Step 2 fallback note.
- No placeholders or "TBD" in any task.
- Version pinned to `8.40.0` consistently across tasks.
- Property names (`enabled`, `dsn`, `environment`, `release`, `logging.minimum-event-level`, `logging.minimum-breadcrumb-level`, `send-default-pii`, `traces-sample-rate`) are the exact keys used by `sentry-spring-boot-starter-jakarta` and match between Tasks 2 and 3.
