# Notifications Badge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Depends on:** `2026-04-23-foundation.md`, `2026-04-23-auth.md`.

**Goal:** Implement `GET /api/v1/notifications/unread-count` per contract §9. The FE polls this on every route change to render a topbar badge. The full `/notifications` list and `mark-read` endpoints are explicitly **reserved but not called by the FE in V1** (contract §12) — out of scope.

**Architecture:** A `notifications` table keyed by `user_id` holds rows produced by future event-driven flows (ticket sold, squad formed, etc.). For V1 there are **no producers**, so the table is always empty and the endpoint always returns `{count: 0}`. The endpoint exists to keep the FE's polling loop healthy and to give us the schema seam for V2.

**Tech Stack:** Java 17, Spring Boot 4.0.5, Spring Data JPA + Flyway, JUnit 5, MockMvc.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `src/main/resources/db/migration/V9__notifications.sql` | Create | `notifications` table |
| `src/main/java/com/imin/iminapi/model/Notification.java` | Create | JPA entity |
| `src/main/java/com/imin/iminapi/repository/NotificationRepository.java` | Create | `countByUserIdAndReadAtIsNull` |
| `src/main/java/com/imin/iminapi/dto/NotificationCountResponse.java` | Create | `{count}` |
| `src/main/java/com/imin/iminapi/controller/notification/NotificationController.java` | Create | endpoint |
| `src/test/java/com/imin/iminapi/controller/notification/NotificationControllerTest.java` | Create | MockMvc |

---

## Task 1: Flyway V9 — notifications table

**Files:**
- Create: `src/main/resources/db/migration/V9__notifications.sql`

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE notifications (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind        VARCHAR(64)  NOT NULL,
    title       VARCHAR(255) NOT NULL,
    body        TEXT,
    link        TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at     TIMESTAMP
);
CREATE INDEX ix_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;
```

> The partial index on PostgreSQL keeps the per-user unread count cheap. H2 will ignore the `WHERE` clause syntax — adjust if the test profile rejects it. (Spring's H2 in PG-compat mode generally accepts partial indexes; if it doesn't, drop the `WHERE` clause and accept a slightly larger index.)

- [ ] **Step 2: Apply + commit**

Run: `./mvnw -q -DskipTests spring-boot:run` (Ctrl-C after Flyway)

```bash
git add src/main/resources/db/migration/V9__notifications.sql
git commit -m "db: V9 notifications table with per-user unread index"
```

> If Flyway fails on H2 because of the partial-index syntax, fall back to: `CREATE INDEX ix_notifications_user_unread ON notifications (user_id, read_at);`. Re-apply, re-commit.

---

## Task 2: Notification entity + repository + DTO

**Files:**
- Create: `src/main/java/com/imin/iminapi/model/Notification.java`
- Create: `src/main/java/com/imin/iminapi/repository/NotificationRepository.java`
- Create: `src/main/java/com/imin/iminapi/dto/NotificationCountResponse.java`

- [ ] **Step 1: Notification entity**

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 64)
    private String kind;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Column(columnDefinition = "TEXT")
    private String link;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "read_at")
    private Instant readAt;
}
```

- [ ] **Step 2: NotificationRepository**

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    long countByUserIdAndReadAtIsNull(UUID userId);
}
```

- [ ] **Step 3: NotificationCountResponse**

```java
package com.imin.iminapi.dto;

public record NotificationCountResponse(long count) {}
```

- [ ] **Step 4: Compile + commit**

Run: `./mvnw -q -DskipTests compile`

```bash
git add src/main/java/com/imin/iminapi/model/Notification.java src/main/java/com/imin/iminapi/repository/NotificationRepository.java src/main/java/com/imin/iminapi/dto/NotificationCountResponse.java
git commit -m "notifications: entity + repo + count DTO"
```

---

## Task 3: NotificationController + MockMvc test

**Files:**
- Create: `src/main/java/com/imin/iminapi/controller/notification/NotificationController.java`
- Create: `src/test/java/com/imin/iminapi/controller/notification/NotificationControllerTest.java`

- [ ] **Step 1: Test**

```java
package com.imin.iminapi.controller.notification;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class NotificationControllerTest {

    @Autowired MockMvc mvc;
    @MockBean NotificationRepository repo;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubFactory.class)
    public @interface WithStubUser {}

    public static class StubFactory implements WithSecurityContextFactory<WithStubUser> {
        @Override public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubUser ann) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    @Test
    @WithStubUser
    void unread_count_returns_zero_when_empty() throws Exception {
        when(repo.countByUserIdAndReadAtIsNull(USER)).thenReturn(0L);
        mvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @WithStubUser
    void unread_count_returns_value() throws Exception {
        when(repo.countByUserIdAndReadAtIsNull(USER)).thenReturn(3L);
        mvc.perform(get("/api/v1/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }
}
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest=NotificationControllerTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

```java
package com.imin.iminapi.controller.notification;

import com.imin.iminapi.dto.NotificationCountResponse;
import com.imin.iminapi.repository.NotificationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationRepository repo;

    public NotificationController(NotificationRepository repo) { this.repo = repo; }

    @GetMapping("/unread-count")
    public NotificationCountResponse unreadCount(@CurrentUser AuthPrincipal p) {
        return new NotificationCountResponse(repo.countByUserIdAndReadAtIsNull(p.userId()));
    }
}
```

- [ ] **Step 4: Re-run, expect pass**

Run: `./mvnw -q test -Dtest=NotificationControllerTest`
Expected: 2 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/controller/notification/NotificationController.java src/test/java/com/imin/iminapi/controller/notification/NotificationControllerTest.java
git commit -m "notifications: GET /unread-count (V1: always 0 until producers exist)"
```

---

## Task 4: Smoke

**Files:** none

- [ ] **Step 1: Boot, signup, hit endpoint**

```bash
docker compose up -d && ./mvnw -q -DskipTests spring-boot:run &
sleep 6
TOKEN=$(curl -s -X POST http://localhost:8085/api/v1/auth/signup -H "Content-Type: application/json" \
  -d '{"email":"notif-smoke@example.com","password":"lovelace12","orgName":"X","country":"GB"}' | jq -r .token)
curl -s http://localhost:8085/api/v1/notifications/unread-count -H "Authorization: Bearer $TOKEN" | jq
```
Expected: `{"count": 0}`. Ctrl-C the app afterwards.

---

## Self-Review

- **Spec coverage (§9):** `GET /notifications/unread-count` ✓. The bell-dropdown `GET /notifications` is reserved per §12 — not implemented.
- **Placeholder scan:** none. `count: 0` is the correct response when no producers exist; documented in the architecture summary.
- **Type consistency:** `{count: long}` matches the contract.
- **Gap:** producers (ticket-sold, squad-formed, etc.) are post-V1. When they're added, push rows into `notifications` with `read_at = NULL`.
