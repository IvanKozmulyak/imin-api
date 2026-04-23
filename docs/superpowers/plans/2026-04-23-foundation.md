# Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the cross-cutting infrastructure that every other V1 plan depends on: the `/api/v1` namespace, the JSON error envelope, DB-backed bearer-token authentication for the React FE, optimistic-locking and idempotency middlewares, Redis-backed rate limiting, and the `User` + `Organization` entities.

**Architecture:** A single `BearerTokenAuthFilter` reads the `Authorization` header, looks up an `AuthSession` row (opaque random token, 30-day TTL, hard-revocable), and populates `SecurityContext` with an `AuthPrincipal`. A `GlobalExceptionHandler` wraps every error path in the contract's `{error:{code,message,fields}}` envelope. `IfMatchSupport` and `IdempotencyKeySupport` are thin Spring components that controllers call directly (no annotations, no proxies). Rate limiting uses Bucket4j on top of a Redis backend wired into `docker-compose`. All new endpoints live under `/api/v1`; the existing `/api/events/**` poster pipeline stays untouched.

**Tech Stack:** Java 17, Spring Boot 4.0.5, Spring Security, Spring Data JPA + Flyway, PostgreSQL (H2 in tests), Bucket4j 8.x with `bucket4j-redis`, Lettuce (Spring Data Redis), BCrypt, JUnit 5, MockMvc.

---

## Decisions locked (resolved 2026-04-23)

- **Token model:** opaque random token in a DB `auth_session` table, 30-day TTL, revocable on `POST /auth/logout`. **Not** JWT.
- **User ↔ Org cardinality:** strict 1 user = 1 org for V1. `users.org_id` is a non-null FK.
- **Org delete:** hard cascade via `ON DELETE CASCADE` on every owned table.
- **Rate limits:** spec values exactly (5 logins / email / 15 min · 10 AI concept / user / hour). Backend = Redis from day 1.
- **Soft-delete:** `deleted_at TIMESTAMP NULL` on `events`. List queries filter `deleted_at IS NULL`. Out of scope for this plan; recorded in the events-core plan.
- **OUT OF SCOPE for V1 (deferred):** Google OAuth, Stripe billing, Stripe Connect payouts, third-party `/integrations/*`. This plan does not touch them.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `pom.xml` | Modify | Add `spring-boot-starter-data-redis`, `bucket4j-core`, `bucket4j-redis`, `lettuce-core`, `jjwt` not needed (DB sessions). Remove unused `spring-boot-starter-security-saml2` if it conflicts (decision deferred — keep for now). |
| `compose.yaml` | Modify | Add a Redis 7 service on port 6379 |
| `src/main/resources/application.yaml` | Modify | `imin.api.base-path: /api/v1`, `spring.data.redis.host`, session TTL, rate-limit knobs |
| `src/test/resources/application.yaml` | Modify | Use embedded Redis (Testcontainers) OR a fake bucket bean — see Task 14 |
| `src/main/resources/db/migration/V5__auth_and_org.sql` | Create | `organizations`, `users`, `auth_sessions`, `idempotency_keys` |
| `src/main/java/com/imin/iminapi/model/Organization.java` | Create | JPA entity |
| `src/main/java/com/imin/iminapi/model/User.java` | Create | JPA entity (incl. role enum) |
| `src/main/java/com/imin/iminapi/model/UserRole.java` | Create | enum `OWNER`, `ADMIN`, `MEMBER` |
| `src/main/java/com/imin/iminapi/model/AuthSession.java` | Create | JPA entity for opaque-token sessions |
| `src/main/java/com/imin/iminapi/model/IdempotencyKey.java` | Create | JPA entity for cached POST responses |
| `src/main/java/com/imin/iminapi/repository/OrganizationRepository.java` | Create | |
| `src/main/java/com/imin/iminapi/repository/UserRepository.java` | Create | `findByEmailIgnoreCase`, `existsByEmailIgnoreCase` |
| `src/main/java/com/imin/iminapi/repository/AuthSessionRepository.java` | Create | `findByTokenHashAndRevokedAtIsNull` |
| `src/main/java/com/imin/iminapi/repository/IdempotencyKeyRepository.java` | Create | `findByOrgIdAndKeyAndRoute` |
| `src/main/java/com/imin/iminapi/security/ErrorCode.java` | Create | enum matching spec §1.4 taxonomy |
| `src/main/java/com/imin/iminapi/security/ApiError.java` | Create | response record `{error:{code,message,fields}}` |
| `src/main/java/com/imin/iminapi/security/ApiException.java` | Create | unchecked exception carrying `HttpStatus` + `ErrorCode` + optional `fields` |
| `src/main/java/com/imin/iminapi/security/GlobalExceptionHandler.java` | Create | `@RestControllerAdvice` covering `ApiException`, `MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `AccessDeniedException`, `Throwable` fallback |
| `src/main/java/com/imin/iminapi/security/AuthPrincipal.java` | Create | record `(userId, orgId, role, sessionId)` |
| `src/main/java/com/imin/iminapi/security/PasswordHasher.java` | Create | `hash`, `verify` over BCrypt strength 12 |
| `src/main/java/com/imin/iminapi/security/TokenService.java` | Create | `issue(user)` → 32-byte URL-safe token; `tokenHash` SHA-256 |
| `src/main/java/com/imin/iminapi/security/BearerTokenAuthFilter.java` | Create | `OncePerRequestFilter` reading `Authorization`, populating `SecurityContext` |
| `src/main/java/com/imin/iminapi/security/CurrentUser.java` | Create | `@AuthenticationPrincipal` shortcut annotation |
| `src/main/java/com/imin/iminapi/config/SecurityConfig.java` | Modify | Wire the filter, lock `/api/v1/**` to authenticated, keep legacy `/api/events/**` permitAll |
| `src/main/java/com/imin/iminapi/web/IfMatchSupport.java` | Create | `requireMatch(String header, Instant entityUpdatedAt)` |
| `src/main/java/com/imin/iminapi/web/IdempotencyKeySupport.java` | Create | `runOrReplay(orgId, route, key, supplier)` |
| `src/main/java/com/imin/iminapi/config/RateLimitConfig.java` | Create | Bucket4j proxy manager + named buckets |
| `src/main/java/com/imin/iminapi/security/RateLimiter.java` | Create | `consume(bucketName, key)` → throws 429 `RATE_LIMITED` |
| `src/test/java/com/imin/iminapi/security/GlobalExceptionHandlerTest.java` | Create | error-envelope shape tests |
| `src/test/java/com/imin/iminapi/security/BearerTokenAuthFilterTest.java` | Create | unauth / valid / expired / revoked |
| `src/test/java/com/imin/iminapi/security/PasswordHasherTest.java` | Create | hash != plaintext, verify round-trip |
| `src/test/java/com/imin/iminapi/security/TokenServiceTest.java` | Create | issued token verifies, hash is deterministic |
| `src/test/java/com/imin/iminapi/web/IfMatchSupportTest.java` | Create | match / mismatch / missing-header |
| `src/test/java/com/imin/iminapi/web/IdempotencyKeySupportTest.java` | Create | first-call runs, replay returns cached |
| `src/test/java/com/imin/iminapi/security/RateLimiterTest.java` | Create | exhausts bucket → throws |

---

## Task 1: Add Redis to docker-compose

**Files:**
- Modify: `compose.yaml`

- [ ] **Step 1: Read current compose.yaml**

Run: `cat compose.yaml`
Expected: a single `postgres` service on port 5433.

- [ ] **Step 2: Append a Redis service**

Add this service block to `compose.yaml` (sibling of `postgres`):

```yaml
  redis:
    image: redis:7-alpine
    container_name: imin-redis
    ports:
      - "6380:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5
```

> Port 6380 (not 6379) on the host avoids clashing with any local Redis the engineer may already run.

- [ ] **Step 3: Bring it up and confirm**

Run: `docker compose up -d redis && docker compose exec redis redis-cli ping`
Expected: `PONG`.

- [ ] **Step 4: Commit**

```bash
git add compose.yaml
git commit -m "infra: add Redis service for rate limiting"
```

---

## Task 2: Add Maven dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add dependencies**

Insert these inside `<dependencies>` (alongside the others):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>8.10.1</version>
</dependency>
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-redis</artifactId>
    <version>8.10.1</version>
</dependency>
```

> BCrypt is already on the classpath via `spring-boot-starter-security`.

- [ ] **Step 2: Verify compile**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "deps: add spring-data-redis and bucket4j for rate limiting"
```

---

## Task 3: Application config additions

**Files:**
- Modify: `src/main/resources/application.yaml`
- Modify: `src/test/resources/application.yaml`

- [ ] **Step 1: Append API and security config to main application.yaml**

Append to `src/main/resources/application.yaml`:

```yaml
imin:
  api:
    base-path: /api/v1
  auth:
    session-ttl-days: 30
  ratelimit:
    login:
      capacity: 5
      window-minutes: 15
    ai-concept:
      capacity: 10
      window-minutes: 60

spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6380}
```

- [ ] **Step 2: Update test application.yaml**

Append to `src/test/resources/application.yaml`:

```yaml
imin:
  api:
    base-path: /api/v1
  auth:
    session-ttl-days: 30
  ratelimit:
    login:
      capacity: 5
      window-minutes: 15
    ai-concept:
      capacity: 10
      window-minutes: 60
```

> Tests will use an in-memory Bucket4j proxy manager (Task 12) — no Redis required.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yaml src/test/resources/application.yaml
git commit -m "config: add api base path, session TTL, rate-limit windows"
```

---

## Task 4: Flyway migration V5 — auth + org schema

**Files:**
- Create: `src/main/resources/db/migration/V5__auth_and_org.sql`

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V5__auth_and_org.sql`:

```sql
CREATE TABLE organizations (
    id              UUID         PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    contact_email   VARCHAR(320) NOT NULL,
    country         VARCHAR(2)   NOT NULL,
    timezone        VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    plan            VARCHAR(32)  NOT NULL DEFAULT 'growth',
    plan_monthly_euros INTEGER   NOT NULL DEFAULT 89,
    currency        VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
    id              UUID         PRIMARY KEY,
    org_id          UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    email           VARCHAR(320) NOT NULL,
    email_lower     VARCHAR(320) NOT NULL,
    name            VARCHAR(255) NOT NULL DEFAULT '',
    password_hash   VARCHAR(255),
    role            VARCHAR(16)  NOT NULL,
    avatar_initials VARCHAR(2)   NOT NULL DEFAULT '',
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_active_at  TIMESTAMP,
    CONSTRAINT uq_users_email_lower UNIQUE (email_lower)
);
CREATE INDEX ix_users_org ON users (org_id);

CREATE TABLE auth_sessions (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash      CHAR(64)     NOT NULL,
    issued_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,
    last_used_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at      TIMESTAMP,
    user_agent      VARCHAR(512),
    CONSTRAINT uq_sessions_token UNIQUE (token_hash)
);
CREATE INDEX ix_sessions_user ON auth_sessions (user_id);

CREATE TABLE idempotency_keys (
    id              UUID         PRIMARY KEY,
    org_id          UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    route           VARCHAR(128) NOT NULL,
    key             VARCHAR(128) NOT NULL,
    response_status INTEGER      NOT NULL,
    response_body   TEXT         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uq_idem_key UNIQUE (org_id, route, key)
);
CREATE INDEX ix_idem_expires ON idempotency_keys (expires_at);
```

- [ ] **Step 2: Run migration in dev DB and confirm**

Run: `docker compose up -d postgres && ./mvnw -q -DskipTests spring-boot:run` (Ctrl-C after Flyway logs `Successfully applied 1 migration`)
Expected: Flyway log line `Migrating schema "public" to version "5 - auth and org"`.

- [ ] **Step 3: Run the test suite to confirm H2 also accepts it**

Run: `./mvnw -q test -Dtest=GeneratedEventRepositoryTest 2>&1 | tail -20` (substitute any cheap existing test)
Expected: tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V5__auth_and_org.sql
git commit -m "db: V5 add organizations, users, auth_sessions, idempotency_keys"
```

---

## Task 5: JPA entities — Organization, User, UserRole

**Files:**
- Create: `src/main/java/com/imin/iminapi/model/Organization.java`
- Create: `src/main/java/com/imin/iminapi/model/User.java`
- Create: `src/main/java/com/imin/iminapi/model/UserRole.java`

- [ ] **Step 1: Create UserRole enum**

```java
package com.imin.iminapi.model;

public enum UserRole {
    OWNER, ADMIN, MEMBER;

    public String wireValue() {
        return name().toLowerCase();
    }

    public static UserRole fromWire(String value) {
        return UserRole.valueOf(value.toUpperCase());
    }
}
```

- [ ] **Step 2: Create Organization entity**

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organizations")
@Getter
@Setter
public class Organization {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(nullable = false, length = 2)
    private String country;

    @Column(nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(nullable = false, length = 32)
    private String plan = "growth";

    @Column(name = "plan_monthly_euros", nullable = false)
    private int planMonthlyEuros = 89;

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
```

- [ ] **Step 3: Create User entity**

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String email;

    @Column(name = "email_lower", nullable = false, unique = true)
    private String emailLower;

    @Column(nullable = false)
    private String name = "";

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;

    @Column(name = "avatar_initials", nullable = false, length = 2)
    private String avatarInitials = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    public void setEmail(String email) {
        this.email = email;
        this.emailLower = email == null ? null : email.toLowerCase();
    }
}
```

- [ ] **Step 4: Compile**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/model/Organization.java src/main/java/com/imin/iminapi/model/User.java src/main/java/com/imin/iminapi/model/UserRole.java
git commit -m "model: add Organization, User, UserRole"
```

---

## Task 6: AuthSession entity + repository

**Files:**
- Create: `src/main/java/com/imin/iminapi/model/AuthSession.java`
- Create: `src/main/java/com/imin/iminapi/repository/AuthSessionRepository.java`
- Create: `src/main/java/com/imin/iminapi/repository/UserRepository.java`
- Create: `src/main/java/com/imin/iminapi/repository/OrganizationRepository.java`

- [ ] **Step 1: Create AuthSession entity**

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_sessions")
@Getter
@Setter
public class AuthSession {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "user_agent", length = 512)
    private String userAgent;
}
```

- [ ] **Step 2: Create the three repositories**

`OrganizationRepository.java`:

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {
}
```

`UserRepository.java`:

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailLower(String emailLower);
    boolean existsByEmailLower(String emailLower);
}
```

`AuthSessionRepository.java`:

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {
    Optional<AuthSession> findByTokenHashAndRevokedAtIsNull(String tokenHash);
}
```

> `@RepositoryRestResource(exported = false)` keeps Spring Data REST from auto-publishing these as `/auth_sessions`, `/users`, etc. — we want only our explicit `@RestController`s exposed.

- [ ] **Step 3: Compile**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/model/AuthSession.java src/main/java/com/imin/iminapi/repository/
git commit -m "model: add AuthSession + repositories for org/user/session"
```

---

## Task 7: Error envelope — ErrorCode, ApiError, ApiException

**Files:**
- Create: `src/main/java/com/imin/iminapi/security/ErrorCode.java`
- Create: `src/main/java/com/imin/iminapi/security/ApiError.java`
- Create: `src/main/java/com/imin/iminapi/security/ApiException.java`

- [ ] **Step 1: Create ErrorCode enum**

```java
package com.imin.iminapi.security;

public enum ErrorCode {
    FIELD_INVALID,
    INVALID_REQUEST,
    AUTH_MISSING,
    AUTH_INVALID_CREDENTIALS,
    AUTH_TOKEN_EXPIRED,
    FORBIDDEN,
    ORG_PLAN_LIMIT,
    NOT_FOUND,
    STALE_WRITE,
    INVALID_STATE,
    DUPLICATE,
    PUBLISH_VALIDATION_FAILED,
    RATE_LIMITED,
    INTERNAL,
    UPSTREAM_UNAVAILABLE
}
```

- [ ] **Step 2: Create ApiError response record**

```java
package com.imin.iminapi.security;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

public record ApiError(Body error) {

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError(new Body(code.name(), message, null));
    }

    public static ApiError of(ErrorCode code, String message, Map<String, String> fields) {
        return new ApiError(new Body(code.name(), message, fields));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String code, String message, Map<String, String> fields) {}
}
```

- [ ] **Step 3: Create ApiException**

```java
package com.imin.iminapi.security;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final ErrorCode code;
    private final Map<String, String> fields;

    public ApiException(HttpStatus status, ErrorCode code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, ErrorCode code, String message, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    public HttpStatus status() { return status; }
    public ErrorCode code() { return code; }
    public Map<String, String> fields() { return fields; }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, what + " not found");
    }
    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, message);
    }
    public static ApiException invalidState(String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE, message);
    }
    public static ApiException staleWrite() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.STALE_WRITE, "Resource modified by another request");
    }
    public static ApiException duplicate(String field, String message) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCode.DUPLICATE, message, Map.of(field, "already exists"));
    }
    public static ApiException rateLimited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, "Too many requests");
    }
}
```

- [ ] **Step 4: Compile**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/security/ErrorCode.java src/main/java/com/imin/iminapi/security/ApiError.java src/main/java/com/imin/iminapi/security/ApiException.java
git commit -m "security: add error code taxonomy, ApiError envelope, ApiException"
```

---

## Task 8: GlobalExceptionHandler

**Files:**
- Create: `src/main/java/com/imin/iminapi/security/GlobalExceptionHandler.java`
- Create: `src/test/java/com/imin/iminapi/security/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.imin.iminapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.DummyController.class)
@Import({GlobalExceptionHandler.class})
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    @RestController
    @RequestMapping("/__test")
    static class DummyController {
        @GetMapping("/notfound")
        String notFound() { throw ApiException.notFound("Event"); }

        @PostMapping(value = "/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
        String validate(@org.springframework.web.bind.annotation.RequestBody @jakarta.validation.Valid Body b) { return "ok"; }

        record Body(@jakarta.validation.constraints.NotBlank String name) {}
    }

    @Test
    void apiException_returns_envelope() throws Exception {
        mvc.perform(get("/__test/notfound").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Event not found"));
    }

    @Test
    void validation_error_returns_field_invalid_with_fields() throws Exception {
        mvc.perform(post("/__test/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FIELD_INVALID"))
                .andExpect(jsonPath("$.error.fields.name").exists());
    }
}
```

- [ ] **Step 2: Run the test, expect failure**

Run: `./mvnw -q test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — `GlobalExceptionHandler` doesn't exist yet.

- [ ] **Step 3: Implement GlobalExceptionHandler**

```java
package com.imin.iminapi.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(ApiError.of(ex.code(), ex.getMessage(), ex.fields()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.FIELD_INVALID, "Validation failed", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ErrorCode.INVALID_REQUEST, "Malformed request body"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(ErrorCode.FORBIDDEN, "Access denied"));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    ResponseEntity<ApiError> handleNoHandler(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ErrorCode.NOT_FOUND, "Route not found"));
    }

    @ExceptionHandler(Throwable.class)
    ResponseEntity<ApiError> handleAny(Throwable ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(ErrorCode.INTERNAL, "Internal server error"));
    }
}
```

- [ ] **Step 4: Re-run the test, expect pass**

Run: `./mvnw -q test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/security/GlobalExceptionHandler.java src/test/java/com/imin/iminapi/security/GlobalExceptionHandlerTest.java
git commit -m "security: global exception handler producing the contract error envelope"
```

---

## Task 9: PasswordHasher + TokenService

**Files:**
- Create: `src/main/java/com/imin/iminapi/security/PasswordHasher.java`
- Create: `src/main/java/com/imin/iminapi/security/TokenService.java`
- Create: `src/test/java/com/imin/iminapi/security/PasswordHasherTest.java`
- Create: `src/test/java/com/imin/iminapi/security/TokenServiceTest.java`

- [ ] **Step 1: Write the PasswordHasher test**

```java
package com.imin.iminapi.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(new BCryptPasswordEncoder(12));

    @Test
    void hashed_password_is_not_plaintext() {
        String hash = hasher.hash("hunter22pwd");
        assertThat(hash).isNotEqualTo("hunter22pwd").startsWith("$2");
    }

    @Test
    void verify_returns_true_for_correct_password() {
        String hash = hasher.hash("hunter22pwd");
        assertThat(hasher.verify("hunter22pwd", hash)).isTrue();
        assertThat(hasher.verify("nope", hash)).isFalse();
    }
}
```

- [ ] **Step 2: Write the TokenService test**

```java
package com.imin.iminapi.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenServiceTest {

    private final TokenService svc = new TokenService();

    @Test
    void issue_returns_url_safe_token_at_least_32_chars() {
        TokenService.IssuedToken t = svc.issue();
        assertThat(t.token()).matches("[A-Za-z0-9_-]{32,}");
        assertThat(t.tokenHash()).hasSize(64);
    }

    @Test
    void hash_is_deterministic() {
        String h1 = svc.hashOf("abc");
        String h2 = svc.hashOf("abc");
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    void hash_differs_for_different_tokens() {
        assertThat(svc.hashOf("aaa")).isNotEqualTo(svc.hashOf("bbb"));
    }
}
```

- [ ] **Step 3: Run both tests, expect failure**

Run: `./mvnw -q test -Dtest='PasswordHasherTest,TokenServiceTest'`
Expected: FAIL — classes don't exist.

- [ ] **Step 4: Implement PasswordHasher**

```java
package com.imin.iminapi.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {
    private final BCryptPasswordEncoder encoder;

    public PasswordHasher(BCryptPasswordEncoder encoder) {
        this.encoder = encoder;
    }

    public String hash(String raw) { return encoder.encode(raw); }
    public boolean verify(String raw, String hash) { return encoder.matches(raw, hash); }
}
```

- [ ] **Step 5: Implement TokenService**

```java
package com.imin.iminapi.security;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class TokenService {
    private final SecureRandom rnd = new SecureRandom();

    public IssuedToken issue() {
        byte[] bytes = new byte[32];
        rnd.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new IssuedToken(token, hashOf(token));
    }

    public String hashOf(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(token.getBytes());
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record IssuedToken(String token, String tokenHash) {}
}
```

- [ ] **Step 6: Add the BCryptPasswordEncoder bean**

Append to `SecurityConfig.java` (new method — full file rewrite happens in Task 11):

```java
@Bean
public org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder bCryptPasswordEncoder() {
    return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12);
}
```

> If the bean already exists from a prior task, skip this step.

- [ ] **Step 7: Re-run the tests, expect pass**

Run: `./mvnw -q test -Dtest='PasswordHasherTest,TokenServiceTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/imin/iminapi/security/PasswordHasher.java src/main/java/com/imin/iminapi/security/TokenService.java src/main/java/com/imin/iminapi/config/SecurityConfig.java src/test/java/com/imin/iminapi/security/PasswordHasherTest.java src/test/java/com/imin/iminapi/security/TokenServiceTest.java
git commit -m "security: BCrypt password hasher and SHA-256 session token service"
```

---

## Task 10: AuthPrincipal + BearerTokenAuthFilter

**Files:**
- Create: `src/main/java/com/imin/iminapi/security/AuthPrincipal.java`
- Create: `src/main/java/com/imin/iminapi/security/BearerTokenAuthFilter.java`
- Create: `src/main/java/com/imin/iminapi/security/CurrentUser.java`
- Create: `src/test/java/com/imin/iminapi/security/BearerTokenAuthFilterTest.java`

- [ ] **Step 1: Create AuthPrincipal record**

```java
package com.imin.iminapi.security;

import com.imin.iminapi.model.UserRole;

import java.util.UUID;

public record AuthPrincipal(UUID userId, UUID orgId, UserRole role, UUID sessionId) {}
```

- [ ] **Step 2: Create CurrentUser annotation**

```java
package com.imin.iminapi.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal
public @interface CurrentUser {}
```

- [ ] **Step 3: Write the filter test**

```java
package com.imin.iminapi.security;

import com.imin.iminapi.model.AuthSession;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BearerTokenAuthFilterTest {

    AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    UserRepository users = mock(UserRepository.class);
    TokenService tokens = new TokenService();
    BearerTokenAuthFilter filter;

    @BeforeEach
    void setup() {
        SecurityContextHolder.clearContext();
        filter = new BearerTokenAuthFilter(sessions, users, tokens);
    }

    @Test
    void no_header_leaves_context_empty() throws Exception {
        var req = new MockHttpServletRequest();
        var resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, resp, chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(req, resp);
    }

    @Test
    void valid_token_populates_principal() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        String raw = "abcdef1234567890abcdef1234567890";
        String hash = tokens.hashOf(raw);

        AuthSession s = new AuthSession();
        s.setId(UUID.randomUUID());
        s.setUserId(userId);
        s.setTokenHash(hash);
        s.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        when(sessions.findByTokenHashAndRevokedAtIsNull(hash)).thenReturn(Optional.of(s));

        User u = new User();
        u.setId(userId);
        u.setOrgId(orgId);
        u.setRole(UserRole.OWNER);
        when(users.findById(userId)).thenReturn(Optional.of(u));

        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + raw);
        var resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(req, resp, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(AuthPrincipal.class);
        AuthPrincipal p = (AuthPrincipal) auth.getPrincipal();
        assertThat(p.userId()).isEqualTo(userId);
        assertThat(p.orgId()).isEqualTo(orgId);
        verify(chain).doFilter(req, resp);
    }

    @Test
    void expired_token_does_not_authenticate() throws Exception {
        String raw = "expiredtoken000000000000000000000";
        String hash = tokens.hashOf(raw);
        AuthSession s = new AuthSession();
        s.setId(UUID.randomUUID());
        s.setUserId(UUID.randomUUID());
        s.setTokenHash(hash);
        s.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(sessions.findByTokenHashAndRevokedAtIsNull(hash)).thenReturn(Optional.of(s));

        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + raw);
        var resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unknown_token_does_not_authenticate() throws Exception {
        when(sessions.findByTokenHashAndRevokedAtIsNull(any())).thenReturn(Optional.empty());
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer whatever");
        filter.doFilter(req, new MockHttpServletResponse(), mock(FilterChain.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
```

- [ ] **Step 4: Run the test, expect failure**

Run: `./mvnw -q test -Dtest=BearerTokenAuthFilterTest`
Expected: FAIL — `BearerTokenAuthFilter` doesn't exist.

- [ ] **Step 5: Implement the filter**

```java
package com.imin.iminapi.security;

import com.imin.iminapi.model.AuthSession;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class BearerTokenAuthFilter extends OncePerRequestFilter {

    private final AuthSessionRepository sessions;
    private final UserRepository users;
    private final TokenService tokens;

    public BearerTokenAuthFilter(AuthSessionRepository sessions, UserRepository users, TokenService tokens) {
        this.sessions = sessions;
        this.users = users;
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String raw = header.substring("Bearer ".length()).trim();
        String hash = tokens.hashOf(raw);
        Optional<AuthSession> maybe = sessions.findByTokenHashAndRevokedAtIsNull(hash);
        if (maybe.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        AuthSession s = maybe.get();
        if (s.getExpiresAt().isBefore(Instant.now())) {
            chain.doFilter(request, response);
            return;
        }
        Optional<User> user = users.findById(s.getUserId());
        if (user.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        AuthPrincipal principal = new AuthPrincipal(user.get().getId(), user.get().getOrgId(), user.get().getRole(), s.getId());
        AbstractAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.get().getRole().name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 6: Re-run the test, expect pass**

Run: `./mvnw -q test -Dtest=BearerTokenAuthFilterTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/imin/iminapi/security/AuthPrincipal.java src/main/java/com/imin/iminapi/security/CurrentUser.java src/main/java/com/imin/iminapi/security/BearerTokenAuthFilter.java src/test/java/com/imin/iminapi/security/BearerTokenAuthFilterTest.java
git commit -m "security: bearer-token filter that resolves DB-backed sessions to AuthPrincipal"
```

---

## Task 11: Wire the filter and lock /api/v1/** in SecurityConfig

**Files:**
- Modify: `src/main/java/com/imin/iminapi/config/SecurityConfig.java`

- [ ] **Step 1: Replace SecurityConfig with the new wiring**

Overwrite `src/main/java/com/imin/iminapi/config/SecurityConfig.java`:

```java
package com.imin.iminapi.config;

import com.imin.iminapi.security.ApiError;
import com.imin.iminapi.security.BearerTokenAuthFilter;
import com.imin.iminapi.security.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   BearerTokenAuthFilter bearerFilter,
                                                   ObjectMapper om) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // Public reads for legacy poster pipeline
                        .requestMatchers("/", "/index.html", "/images/**").permitAll()
                        .requestMatchers("/api/events/**").permitAll()
                        .requestMatchers("/api/posters/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // V1 auth endpoints are public — login etc.
                        .requestMatchers("/api/v1/auth/signup",
                                         "/api/v1/auth/login",
                                         "/api/v1/auth/logout").permitAll()
                        // Everything else under /api/v1 requires a session
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, resp, ex) -> {
                            resp.setStatus(401);
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            om.writeValue(resp.getWriter(),
                                    ApiError.of(ErrorCode.AUTH_MISSING, "Authentication required"));
                        })
                        .accessDeniedHandler((req, resp, ex) -> {
                            resp.setStatus(403);
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            om.writeValue(resp.getWriter(),
                                    ApiError.of(ErrorCode.FORBIDDEN, "Access denied"));
                        })
                );
        return http.build();
    }
}
```

- [ ] **Step 2: Boot the app and curl an unauthenticated /api/v1 endpoint**

Run: `./mvnw -q -DskipTests spring-boot:run` in one terminal; in another:
`curl -s -o /dev/stdout -w "\n%{http_code}\n" http://localhost:8085/api/v1/auth/me`
Expected: `{"error":{"code":"AUTH_MISSING","message":"Authentication required"}}` and HTTP 401.

> Stop the app afterwards with Ctrl-C.

- [ ] **Step 3: Run the existing test suite to make sure legacy endpoints still permit-all**

Run: `./mvnw -q test`
Expected: all existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/config/SecurityConfig.java
git commit -m "security: lock /api/v1/** behind bearer filter; emit AUTH_MISSING envelope"
```

---

## Task 12: Rate-limit infrastructure (Bucket4j + Redis)

**Files:**
- Create: `src/main/java/com/imin/iminapi/config/RateLimitConfig.java`
- Create: `src/main/java/com/imin/iminapi/security/RateLimiter.java`
- Create: `src/test/java/com/imin/iminapi/security/RateLimiterTest.java`

- [ ] **Step 1: Write the rate-limiter test (uses an in-memory bucket)**

```java
package com.imin.iminapi.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.local.LockFreeBucket;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    @Test
    void consumes_until_bucket_is_empty_then_throws() {
        Map<String, Bucket> store = new HashMap<>();
        RateLimiter limiter = (bucket, key) -> {
            Bucket b = store.computeIfAbsent(bucket + ":" + key, k ->
                    Bucket.builder().addLimit(Bandwidth.simple(2, Duration.ofMinutes(15))).build());
            if (!b.tryConsume(1)) throw ApiException.rateLimited();
        };

        limiter.consume("login", "alice@example.com");
        limiter.consume("login", "alice@example.com");
        assertThatThrownBy(() -> limiter.consume("login", "alice@example.com"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many requests");

        // Different key still has budget
        limiter.consume("login", "bob@example.com");
    }
}
```

> The test passes a lambda implementation of `RateLimiter` so it doesn't depend on Redis. The real implementation uses Redis via Bucket4j.

- [ ] **Step 2: Run the test, expect failure**

Run: `./mvnw -q test -Dtest=RateLimiterTest`
Expected: FAIL — `RateLimiter` interface doesn't exist.

- [ ] **Step 3: Define the RateLimiter interface**

```java
package com.imin.iminapi.security;

public interface RateLimiter {
    /**
     * Decrement one token from the bucket identified by (bucketName, key).
     * Throws {@link ApiException} with 429 / RATE_LIMITED if the bucket is empty.
     */
    void consume(String bucketName, String key);
}
```

- [ ] **Step 4: Implement the Redis-backed RateLimitConfig**

```java
package com.imin.iminapi.config;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class RateLimitConfig {

    @Value("${imin.ratelimit.login.capacity}")
    private int loginCapacity;
    @Value("${imin.ratelimit.login.window-minutes}")
    private int loginWindow;
    @Value("${imin.ratelimit.ai-concept.capacity}")
    private int aiCapacity;
    @Value("${imin.ratelimit.ai-concept.window-minutes}")
    private int aiWindow;

    @Bean
    public RedisClient redisClient(@Value("${spring.data.redis.host}") String host,
                                   @Value("${spring.data.redis.port}") int port) {
        return RedisClient.create("redis://" + host + ":" + port);
    }

    @Bean
    public StatefulRedisConnection<String, byte[]> redisConnection(RedisClient client) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        return client.connect(codec);
    }

    @Bean
    public ProxyManager<String> bucketProxyManager(StatefulRedisConnection<String, byte[]> conn) {
        return LettuceBasedProxyManager.builderFor(conn).build();
    }

    @Bean
    public RateLimiter rateLimiter(ProxyManager<String> proxy) {
        Map<String, BucketConfiguration> configs = new ConcurrentHashMap<>();
        configs.put("login", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(loginCapacity, Duration.ofMinutes(loginWindow)))
                .build());
        configs.put("ai-concept", BucketConfiguration.builder()
                .addLimit(Bandwidth.simple(aiCapacity, Duration.ofMinutes(aiWindow)))
                .build());

        return (bucketName, key) -> {
            BucketConfiguration cfg = configs.get(bucketName);
            if (cfg == null) throw new IllegalArgumentException("Unknown bucket " + bucketName);
            String redisKey = "ratelimit:" + bucketName + ":" + key;
            Bucket bucket = proxy.builder().build(redisKey, () -> cfg);
            if (!bucket.tryConsume(1)) throw ApiException.rateLimited();
        };
    }
}
```

- [ ] **Step 5: Re-run the test, expect pass**

Run: `./mvnw -q test -Dtest=RateLimiterTest`
Expected: PASS.

- [ ] **Step 6: Add a test-profile bean override so other tests don't need Redis**

Create `src/test/java/com/imin/iminapi/config/TestRateLimitConfig.java`:

```java
package com.imin.iminapi.config;

import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.RateLimiter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@TestConfiguration
public class TestRateLimitConfig {

    @Bean @Primary
    public RateLimiter testRateLimiter() {
        Map<String, Bucket> buckets = new ConcurrentHashMap<>();
        return (bucketName, key) -> {
            Bucket b = buckets.computeIfAbsent(bucketName + ":" + key, k ->
                    Bucket.builder().addLimit(Bandwidth.simple(1000, Duration.ofMinutes(1))).build());
            if (!b.tryConsume(1)) throw ApiException.rateLimited();
        };
    }
}
```

> Tests that need real rate-limit semantics import `TestRateLimitConfig` and override the bucket per test. The default 1000/min keeps unrelated tests from being throttled.

- [ ] **Step 7: Disable RateLimitConfig in tests**

Edit `RateLimitConfig.java`, add an annotation to the class:

```java
@org.springframework.context.annotation.Profile("!test")
@Configuration
public class RateLimitConfig {
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/imin/iminapi/config/RateLimitConfig.java src/main/java/com/imin/iminapi/security/RateLimiter.java src/test/java/com/imin/iminapi/security/RateLimiterTest.java src/test/java/com/imin/iminapi/config/TestRateLimitConfig.java
git commit -m "ratelimit: Bucket4j+Redis-backed limiter with login and ai-concept buckets"
```

---

## Task 13: IfMatchSupport

**Files:**
- Create: `src/main/java/com/imin/iminapi/web/IfMatchSupport.java`
- Create: `src/test/java/com/imin/iminapi/web/IfMatchSupportTest.java`

- [ ] **Step 1: Write the test**

```java
package com.imin.iminapi.web;

import com.imin.iminapi.security.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IfMatchSupportTest {

    private final IfMatchSupport sut = new IfMatchSupport();

    @Test
    void missing_header_is_a_no_op() {
        assertThatCode(() -> sut.requireMatch(null, Instant.now())).doesNotThrowAnyException();
        assertThatCode(() -> sut.requireMatch("", Instant.now())).doesNotThrowAnyException();
    }

    @Test
    void matching_header_passes() {
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        assertThatCode(() -> sut.requireMatch("\"" + updated.toString() + "\"", updated))
                .doesNotThrowAnyException();
        assertThatCode(() -> sut.requireMatch(updated.toString(), updated))
                .doesNotThrowAnyException();
    }

    @Test
    void mismatched_header_throws_stale_write() {
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        assertThatThrownBy(() -> sut.requireMatch("\"2026-01-01T00:00:00Z\"", updated))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("modified");
    }
}
```

- [ ] **Step 2: Run the test, expect failure**

Run: `./mvnw -q test -Dtest=IfMatchSupportTest`
Expected: FAIL.

- [ ] **Step 3: Implement IfMatchSupport**

```java
package com.imin.iminapi.web;

import com.imin.iminapi.security.ApiException;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class IfMatchSupport {

    /**
     * Compare the header (which the FE serializes from the entity's updatedAt) to the
     * current entity timestamp. Throws 409 STALE_WRITE on mismatch. A null/blank header
     * is treated as the FE opting out of the check.
     */
    public void requireMatch(String header, Instant entityUpdatedAt) {
        if (header == null || header.isBlank()) return;
        String trimmed = header.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        Instant headerInstant;
        try {
            headerInstant = Instant.parse(trimmed);
        } catch (Exception e) {
            throw ApiException.staleWrite();
        }
        if (!headerInstant.equals(entityUpdatedAt)) {
            throw ApiException.staleWrite();
        }
    }
}
```

- [ ] **Step 4: Re-run the test, expect pass**

Run: `./mvnw -q test -Dtest=IfMatchSupportTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/web/IfMatchSupport.java src/test/java/com/imin/iminapi/web/IfMatchSupportTest.java
git commit -m "web: IfMatchSupport — 409 STALE_WRITE on updatedAt mismatch"
```

---

## Task 14: IdempotencyKeySupport

**Files:**
- Create: `src/main/java/com/imin/iminapi/model/IdempotencyKey.java`
- Create: `src/main/java/com/imin/iminapi/repository/IdempotencyKeyRepository.java`
- Create: `src/main/java/com/imin/iminapi/web/IdempotencyKeySupport.java`
- Create: `src/test/java/com/imin/iminapi/web/IdempotencyKeySupportTest.java`

- [ ] **Step 1: Create the IdempotencyKey entity**

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
public class IdempotencyKey {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false, length = 128)
    private String route;

    @Column(name = "key", nullable = false, length = 128)
    private String key;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
```

- [ ] **Step 2: Create the repository**

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {
    Optional<IdempotencyKey> findByOrgIdAndRouteAndKey(UUID orgId, String route, String key);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
```

- [ ] **Step 3: Write the support test**

```java
package com.imin.iminapi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.IdempotencyKey;
import com.imin.iminapi.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IdempotencyKeySupportTest {

    IdempotencyKeyRepository repo = mock(IdempotencyKeyRepository.class);
    ObjectMapper om = new ObjectMapper();
    IdempotencyKeySupport sut = new IdempotencyKeySupport(repo, om);

    @Test
    void first_call_runs_supplier_and_persists() {
        UUID orgId = UUID.randomUUID();
        when(repo.findByOrgIdAndRouteAndKey(orgId, "/x", "k1")).thenReturn(Optional.empty());
        AtomicInteger calls = new AtomicInteger();
        var result = sut.runOrReplay(orgId, "/x", "k1", () -> {
            calls.incrementAndGet();
            return new IdempotencyKeySupport.Cached(201, "\"hello\"");
        });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(201);
        verify(repo).save(any(IdempotencyKey.class));
    }

    @Test
    void second_call_returns_cached_without_running_supplier() {
        UUID orgId = UUID.randomUUID();
        IdempotencyKey existing = new IdempotencyKey();
        existing.setResponseStatus(201);
        existing.setResponseBody("\"hello\"");
        when(repo.findByOrgIdAndRouteAndKey(orgId, "/x", "k1")).thenReturn(Optional.of(existing));
        AtomicInteger calls = new AtomicInteger();
        var result = sut.runOrReplay(orgId, "/x", "k1", () -> {
            calls.incrementAndGet();
            return new IdempotencyKeySupport.Cached(500, "\"oops\"");
        });
        assertThat(calls.get()).isZero();
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.bodyJson()).isEqualTo("\"hello\"");
    }

    @Test
    void null_or_blank_key_skips_idempotency() {
        UUID orgId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        sut.runOrReplay(orgId, "/x", null, () -> {
            calls.incrementAndGet();
            return new IdempotencyKeySupport.Cached(200, "{}");
        });
        sut.runOrReplay(orgId, "/x", "  ", () -> {
            calls.incrementAndGet();
            return new IdempotencyKeySupport.Cached(200, "{}");
        });
        assertThat(calls.get()).isEqualTo(2);
        verifyNoInteractions(repo);
    }
}
```

- [ ] **Step 4: Run the test, expect failure**

Run: `./mvnw -q test -Dtest=IdempotencyKeySupportTest`
Expected: FAIL.

- [ ] **Step 5: Implement IdempotencyKeySupport**

```java
package com.imin.iminapi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.model.IdempotencyKey;
import com.imin.iminapi.repository.IdempotencyKeyRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class IdempotencyKeySupport {

    private static final Duration TTL = Duration.ofHours(24);

    private final IdempotencyKeyRepository repo;
    private final ObjectMapper om;

    public IdempotencyKeySupport(IdempotencyKeyRepository repo, ObjectMapper om) {
        this.repo = repo;
        this.om = om;
    }

    public Cached runOrReplay(UUID orgId, String route, String key, Supplier<Cached> supplier) {
        if (key == null || key.isBlank()) {
            return supplier.get();
        }
        Optional<IdempotencyKey> existing = repo.findByOrgIdAndRouteAndKey(orgId, route, key);
        if (existing.isPresent()) {
            IdempotencyKey k = existing.get();
            return new Cached(k.getResponseStatus(), k.getResponseBody());
        }
        Cached fresh = supplier.get();
        IdempotencyKey row = new IdempotencyKey();
        row.setOrgId(orgId);
        row.setRoute(route);
        row.setKey(key);
        row.setResponseStatus(fresh.status());
        row.setResponseBody(fresh.bodyJson());
        row.setExpiresAt(Instant.now().plus(TTL));
        repo.save(row);
        return fresh;
    }

    /** Helper for callers that have an object — serialise to JSON for storage. */
    public Cached toCached(int status, Object body) {
        try {
            return new Cached(status, om.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise idempotency body", e);
        }
    }

    public record Cached(int status, String bodyJson) {}
}
```

- [ ] **Step 6: Re-run the test, expect pass**

Run: `./mvnw -q test -Dtest=IdempotencyKeySupportTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/imin/iminapi/model/IdempotencyKey.java src/main/java/com/imin/iminapi/repository/IdempotencyKeyRepository.java src/main/java/com/imin/iminapi/web/IdempotencyKeySupport.java src/test/java/com/imin/iminapi/web/IdempotencyKeySupportTest.java
git commit -m "web: IdempotencyKeySupport with 24h cache and run-or-replay semantics"
```

---

## Task 15: Scheduled job to purge expired idempotency keys

**Files:**
- Create: `src/main/java/com/imin/iminapi/web/IdempotencyKeyPurger.java`
- Modify: `src/main/java/com/imin/iminapi/IminApiApplication.java`

- [ ] **Step 1: Enable scheduling on the application class**

Edit `src/main/java/com/imin/iminapi/IminApiApplication.java`. Add the annotation `@org.springframework.scheduling.annotation.EnableScheduling` on the `@SpringBootApplication` class. Final shape:

```java
@SpringBootApplication
@EnableScheduling
public class IminApiApplication { ... }
```

(Keep all other existing annotations / methods.)

- [ ] **Step 2: Create the purger**

```java
package com.imin.iminapi.web;

import com.imin.iminapi.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class IdempotencyKeyPurger {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurger.class);
    private final IdempotencyKeyRepository repo;

    public IdempotencyKeyPurger(IdempotencyKeyRepository repo) { this.repo = repo; }

    @Scheduled(cron = "0 0 * * * *") // top of every hour
    @Transactional
    public void purgeExpired() {
        int n = repo.deleteExpired(Instant.now());
        if (n > 0) log.info("Purged {} expired idempotency keys", n);
    }
}
```

- [ ] **Step 3: Compile + smoke test**

Run: `./mvnw -q test`
Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/IminApiApplication.java src/main/java/com/imin/iminapi/web/IdempotencyKeyPurger.java
git commit -m "web: hourly @Scheduled purge of expired idempotency keys"
```

---

## Task 16: End-to-end smoke — boot, hit /api/v1, see envelope

**Files:** none (manual verification)

- [ ] **Step 1: Bring up dependencies**

Run: `docker compose up -d`
Expected: postgres + redis healthy.

- [ ] **Step 2: Boot the app**

Run: `./mvnw -q -DskipTests spring-boot:run` (Ctrl-C after startup completes)
Expected logs:
- `Successfully applied 1 migration to schema "public"` (if not already applied)
- `Started IminApiApplication`

- [ ] **Step 3: Curl unauthenticated**

```bash
curl -s -i http://localhost:8085/api/v1/auth/me
```
Expected:
- HTTP/1.1 401
- body: `{"error":{"code":"AUTH_MISSING","message":"Authentication required"}}`

- [ ] **Step 4: Curl with garbage bearer**

```bash
curl -s -i -H "Authorization: Bearer xxxxx" http://localhost:8085/api/v1/auth/me
```
Expected: HTTP/1.1 401 with the same envelope.

- [ ] **Step 5: Curl an unknown /api/v1 route**

```bash
curl -s -i http://localhost:8085/api/v1/does-not-exist
```
Expected: HTTP/1.1 401 (filter blocks first) — this is intentional. Authenticated 404 is exercised in later plans.

- [ ] **Step 6: Stop the app and commit any drift**

```bash
git status
```
Expected: clean. If anything snuck in, `git diff` and decide whether to commit.

---

## Self-Review

- **Spec coverage (§1.1–1.10, §11):** §1.1 JSON: handled by Spring MVC default + ObjectMapper. §1.2 Bearer auth: BearerTokenAuthFilter + AuthSession. §1.3 error shape: ApiError + GlobalExceptionHandler. §1.4 codes: ErrorCode enum covers every entry in the taxonomy. §1.5 pagination: deferred to events plan. §1.6 timestamps: entities use `Instant`; Jackson ISO-8601 default. §1.7 money: deferred. §1.8 If-Match: IfMatchSupport. §1.9 Idempotency-Key: IdempotencyKeySupport. §1.10 CORS: not configured in this plan because dev uses Vite proxy; production CORS is a deploy-time concern — flagged for the deployment plan, not blocking V1 dev. §11.1 token refresh: out of scope (long-lived per the resolved decision). §11.3 password policy: enforced in the auth plan.
- **Placeholder scan:** none found.
- **Type consistency:** `AuthPrincipal(userId, orgId, role, sessionId)` is referenced by Tasks 10, 11, and (in later plans) every controller via `@CurrentUser AuthPrincipal`. `RateLimiter.consume(bucketName, key)` referenced consistently. `IfMatchSupport.requireMatch(String, Instant)` and `IdempotencyKeySupport.runOrReplay(UUID, String, String, Supplier)` referenced consistently.
- **CORS gap:** `application-prod.yaml` will need a CORS bean before the FE goes to a different origin. Recorded here so the next plan that touches prod config picks it up.
