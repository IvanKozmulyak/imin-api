# Sentry Configuration — Design

**Date:** 2026-04-29
**Scope:** Errors only. Production profile only. No performance tracing, no Sentry Logs product.

## Goal

Capture unhandled exceptions and `logger.error(...)` events from `imin-api` running under the `prod` profile, and report them to an existing Sentry project. Dev and test runs send nothing.

## Approach

Use the official `sentry-spring-boot-starter-jakarta` starter. It auto-configures via `application*.yaml`, registers an MVC exception handler so unhandled controller exceptions become Sentry events, and installs a Logback appender so `log.error(...)` calls are also captured.

If the latest `7.x` starter does not resolve cleanly against Spring Boot 4.0.5 (both are jakarta-namespaced, so this is unlikely), fall back to the core SDK + `sentry-logback` appender wired manually. That fallback is recorded here so the implementation plan does not stall on dependency surprises.

## Components

### 1. Maven dependency

Add a single dependency to `pom.xml`. Pinned version (no BOM) — only one Sentry artifact needed.

```xml
<dependency>
    <groupId>io.sentry</groupId>
    <artifactId>sentry-spring-boot-starter-jakarta</artifactId>
    <version>{latest-7.x-pinned-during-implementation}</version>
</dependency>
```

The implementation plan resolves the exact version from Maven Central before committing.

### 2. Base configuration (`application.yaml`)

Defaults that keep dev and test silent. The empty-DSN no-op behavior is belt-and-braces alongside `enabled: false`.

```yaml
sentry:
  dsn: ${SENTRY_DSN:}
  enabled: false
  send-default-pii: false
  traces-sample-rate: 0.0
```

### 3. Production overrides (`application-prod.yaml`)

```yaml
sentry:
  enabled: true
  environment: prod
  release: ${SENTRY_RELEASE:imin-api@${project.version}}
  logging:
    minimum-event-level: error
    minimum-breadcrumb-level: info
```

`minimum-event-level: error` means only ERROR-level log records are sent as events; INFO/WARN/DEBUG attach as breadcrumbs to events that do fire.

### 4. Dev / test profiles

No changes. They inherit `enabled: false` from the base config; the SDK initializes in disabled mode and emits nothing.

### 5. Environment variables

| Var | Where | Required | Notes |
|-----|-------|----------|-------|
| `SENTRY_DSN` | prod runtime | yes | Project DSN from the existing Sentry project. |
| `SENTRY_RELEASE` | prod runtime | no | Defaults to `imin-api@<maven-version>`. CI can inject a git SHA later if useful. |

## Data flow

```
unhandled exception in @RestController
  → Spring MVC ExceptionResolver
  → Sentry starter's resolver  → Sentry event

log.error("...", t)
  → Logback
  → Sentry's Logback appender → Sentry event (level=error)

log.info / log.warn
  → Logback
  → Sentry breadcrumb buffer (attached to the next event only)
```

No application code changes. No new beans. No new endpoints.

## Verification

One-shot manual smoke test, not a permanent route:

1. Set `SENTRY_DSN` and run with `--spring.profiles.active=prod` locally (using a throwaway DB or pointed at a non-prod DB — DSN is the only thing that matters here).
2. Temporarily make any existing `@RestController` method throw a `RuntimeException`.
3. Hit the route, confirm the event appears in Sentry's issues view with `environment=prod` and the expected release tag.
4. Revert the test throw before merging.

No `/debug-sentry` endpoint is added to the codebase.

## Out of scope

The following are deliberately excluded so they don't expand the change set:

- Performance/transaction tracing (`traces-sample-rate > 0`, `TracesSampler`)
- The Sentry Logs product (separate from log → event capture)
- User context, request body capture, or `send-default-pii: true`
- Source bundle / source map upload (Java stack traces use line numbers from `.class` files; not needed)
- CI integration for automatic release creation or commit association
- Tags or contexts beyond `environment` and `release`

If we want any of these later, they are additive and don't conflict with this design.

## Risks

- **Spring Boot 4.0.5 + Sentry starter compatibility.** Both use the jakarta namespace, but Spring Boot 4 is recent. The implementation plan must verify the app starts cleanly before claiming done. Fallback path is the manual core-SDK + Logback-appender setup described under "Approach".
- **Forgotten DSN in prod.** If `SENTRY_DSN` is unset in the prod environment, `enabled: true` still initializes the SDK, but it has nothing to send to and logs a warning at startup. This is loud-fail, not silent-fail, which is what we want.

## Files touched

- `pom.xml` — one dependency added
- `src/main/resources/application.yaml` — `sentry:` block added
- `src/main/resources/application-prod.yaml` — `sentry:` block added

No Java source changes.
