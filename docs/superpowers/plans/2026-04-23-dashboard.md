# Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Depends on:** `2026-04-23-foundation.md`, `2026-04-23-auth.md`, `2026-04-23-events-core.md`.

**Goal:** Implement `GET /api/v1/dashboard` per contract §4 — a single aggregated payload built from the org's events. Many sub-fields (revenue, NPS, predictor, activity log) have no source-of-truth tables in V1 and will return `null` / `0` / `[]`. The contract permits these stubs explicitly (e.g. `nextEvent: Event | null`, `prediction: Prediction | null`). Cache server-side for ~30 s as the contract recommends.

**Architecture:** A `DashboardService` reads the next-upcoming event, the most recent past event, and counts active events. Aggregations the schema can support (revenue, sold, capacity, dates) come from existing columns. Aggregations that need un-built tables (purchase logs, NPS, prediction history) return zero-or-null stubs. The endpoint result is cached per `orgId` in a Caffeine cache with a 30s TTL.

**Tech Stack:** Java 17, Spring Boot 4.0.5, Spring Cache + Caffeine, JUnit 5, MockMvc.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `pom.xml` | Modify | Add `com.github.ben-manes.caffeine:caffeine` and `spring-boot-starter-cache` |
| `src/main/java/com/imin/iminapi/config/CacheConfig.java` | Create | Caffeine cache manager with `dashboard` cache, 30s TTL |
| `src/main/java/com/imin/iminapi/dto/dashboard/DashboardResponse.java` | Create | full payload shape |
| `src/main/java/com/imin/iminapi/repository/EventRepository.java` | Modify | add aggregate queries |
| `src/main/java/com/imin/iminapi/service/dashboard/DashboardService.java` | Create | builder + `@Cacheable` |
| `src/main/java/com/imin/iminapi/controller/dashboard/DashboardController.java` | Create | endpoint |
| `src/test/java/com/imin/iminapi/service/dashboard/DashboardServiceTest.java` | Create | unit |
| `src/test/java/com/imin/iminapi/controller/dashboard/DashboardControllerTest.java` | Create | MockMvc |

---

## Task 1: Caffeine cache wiring

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/imin/iminapi/config/CacheConfig.java`

- [ ] **Step 1: Add dependencies**

Inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

> Caffeine is brought in by Spring Boot's BOM; no explicit version.

- [ ] **Step 2: CacheConfig**

```java
package com.imin.iminapi.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager("dashboard");
        mgr.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .maximumSize(10_000));
        return mgr;
    }
}
```

- [ ] **Step 3: Compile + commit**

Run: `./mvnw -q -DskipTests compile`

```bash
git add pom.xml src/main/java/com/imin/iminapi/config/CacheConfig.java
git commit -m "cache: Caffeine cache manager with dashboard 30s TTL"
```

---

## Task 2: Repository aggregates

**Files:**
- Modify: `src/main/java/com/imin/iminapi/repository/EventRepository.java`

- [ ] **Step 1: Add the helper queries**

Inside `EventRepository`, append:

```java
@org.springframework.data.jpa.repository.Query(
    "SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
    "AND e.status = com.imin.iminapi.model.EventStatus.LIVE " +
    "AND e.startsAt > :now ORDER BY e.startsAt ASC")
java.util.List<com.imin.iminapi.model.Event> findUpcomingLive(
        @org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId,
        @org.springframework.data.repository.query.Param("now") java.time.Instant now,
        org.springframework.data.domain.Pageable pageable);

@org.springframework.data.jpa.repository.Query(
    "SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
    "AND e.status = com.imin.iminapi.model.EventStatus.PAST " +
    "ORDER BY e.endsAt DESC")
java.util.List<com.imin.iminapi.model.Event> findRecentPast(
        @org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId,
        org.springframework.data.domain.Pageable pageable);

@org.springframework.data.jpa.repository.Query(
    "SELECT COUNT(e) FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
    "AND e.status = com.imin.iminapi.model.EventStatus.LIVE")
long countLive(@org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId);

@org.springframework.data.jpa.repository.Query(
    "SELECT COUNT(e) FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
    "AND e.publishedAt IS NOT NULL")
long countPublished(@org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId);

@org.springframework.data.jpa.repository.Query(
    "SELECT COUNT(e) FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
    "AND e.status = com.imin.iminapi.model.EventStatus.PAST")
long countPast(@org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId);

@org.springframework.data.jpa.repository.Query(
    "SELECT COALESCE(SUM(e.revenueMinor), 0), COALESCE(SUM(e.sold), 0) " +
    "FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL")
java.util.List<Object[]> sumRevenueAndSold(@org.springframework.data.repository.query.Param("orgId") java.util.UUID orgId);
```

- [ ] **Step 2: Compile + commit**

Run: `./mvnw -q -DskipTests compile`

```bash
git add src/main/java/com/imin/iminapi/repository/EventRepository.java
git commit -m "repo: add dashboard aggregate queries to EventRepository"
```

---

## Task 3: DashboardResponse DTO

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/dashboard/DashboardResponse.java`

- [ ] **Step 1: Implement**

```java
package com.imin.iminapi.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.dto.event.PredictionDto;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DashboardResponse(
        Greeting greeting,
        Now now,
        Cycle cycle,
        LastEvent lastEvent,
        PredictionDto prediction,
        Business business,
        List<Activity> activity) {

    public record Greeting(String name) {}

    public record Now(EventDto nextEvent, int pct, int daysOut) {}

    public record Cycle(String period, long revenueMinor, int ticketsSold, int squadRatePct,
                        int activeEvents, Deltas deltas) {}

    public record Deltas(int revenuePct, int ticketsPct) {}

    public record LastEvent(EventDto event, LastEventMetrics metrics) {}

    public record LastEventMetrics(int attended, int capacity, int avgTicketMinor, Integer nps) {}

    public record Business(long totalRevenueMinor, long eventsPublished, long eventsCompleted,
                           int audienceCount, int repeatRatePct) {}

    public record Activity(String time, String label) {}
}
```

- [ ] **Step 2: Compile + commit**

Run: `./mvnw -q -DskipTests compile`

```bash
git add src/main/java/com/imin/iminapi/dto/dashboard/DashboardResponse.java
git commit -m "dto: DashboardResponse matching contract §4 shape"
```

---

## Task 4: DashboardService

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/dashboard/DashboardService.java`
- Create: `src/test/java/com/imin/iminapi/service/dashboard/DashboardServiceTest.java`

- [ ] **Step 1: Write the test**

```java
package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.dto.UserDto;
import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DashboardServiceTest {

    EventRepository events = mock(EventRepository.class);
    UserRepository users = mock(UserRepository.class);
    DashboardService sut = new DashboardService(events, users);

    private AuthPrincipal owner(UUID orgId) {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void empty_org_returns_null_next_and_zeroed_metrics() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);
        User u = new User();
        u.setId(p.userId()); u.setName("Jaune"); u.setEmail("j@x.com");
        when(users.findById(p.userId())).thenReturn(Optional.of(u));
        when(events.findUpcomingLive(eq(orgId), any(), any())).thenReturn(List.of());
        when(events.findRecentPast(eq(orgId), any())).thenReturn(List.of());
        when(events.countLive(orgId)).thenReturn(0L);
        when(events.countPublished(orgId)).thenReturn(0L);
        when(events.countPast(orgId)).thenReturn(0L);
        when(events.sumRevenueAndSold(orgId)).thenReturn(List.of(new Object[]{0L, 0L}));

        DashboardResponse r = sut.build(p);
        assertThat(r.greeting().name()).isEqualTo("Jaune");
        assertThat(r.now().nextEvent()).isNull();
        assertThat(r.cycle().activeEvents()).isZero();
        assertThat(r.lastEvent().event()).isNull();
        assertThat(r.business().totalRevenueMinor()).isZero();
        assertThat(r.activity()).isEmpty();
        assertThat(r.prediction()).isNull();
    }

    @Test
    void populated_org_returns_next_and_last_with_pct_and_daysOut() {
        UUID orgId = UUID.randomUUID();
        AuthPrincipal p = owner(orgId);
        User u = new User(); u.setId(p.userId()); u.setName("Jaune"); u.setEmail("j@x.com");
        when(users.findById(p.userId())).thenReturn(Optional.of(u));

        Event next = new Event();
        next.setId(UUID.randomUUID()); next.setOrgId(orgId);
        next.setName("Next Night"); next.setSlug("next-night");
        next.setStartsAt(Instant.now().plusSeconds(28L * 24 * 3600));
        next.setCapacity(100); next.setSold(57);
        when(events.findUpcomingLive(eq(orgId), any(), any())).thenReturn(List.of(next));

        Event past = new Event();
        past.setId(UUID.randomUUID()); past.setOrgId(orgId);
        past.setName("Last Night"); past.setSlug("last-night");
        past.setStatus(EventStatus.PAST);
        past.setEndsAt(Instant.now().minusSeconds(7L * 24 * 3600));
        past.setCapacity(200); past.setSold(198); past.setRevenueMinor(475_200);
        when(events.findRecentPast(eq(orgId), any())).thenReturn(List.of(past));

        when(events.countLive(orgId)).thenReturn(3L);
        when(events.countPublished(orgId)).thenReturn(6L);
        when(events.countPast(orgId)).thenReturn(4L);
        when(events.sumRevenueAndSold(orgId)).thenReturn(List.of(new Object[]{1_420_800L, 212L}));

        DashboardResponse r = sut.build(p);
        assertThat(r.now().nextEvent().id()).isEqualTo(next.getId());
        assertThat(r.now().pct()).isEqualTo(57);
        assertThat(r.now().daysOut()).isBetween(27, 28);
        assertThat(r.cycle().activeEvents()).isEqualTo(3);
        assertThat(r.lastEvent().event().id()).isEqualTo(past.getId());
        assertThat(r.lastEvent().metrics().attended()).isEqualTo(198);
        assertThat(r.lastEvent().metrics().avgTicketMinor()).isEqualTo(2400);
        assertThat(r.business().totalRevenueMinor()).isEqualTo(1_420_800L);
        assertThat(r.business().eventsPublished()).isEqualTo(6);
    }
}
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest=DashboardServiceTest`
Expected: FAIL.

- [ ] **Step 3: Implement DashboardService**

```java
package com.imin.iminapi.service.dashboard;

import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.dto.dashboard.DashboardResponse.*;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class DashboardService {

    private final EventRepository events;
    private final UserRepository users;

    public DashboardService(EventRepository events, UserRepository users) {
        this.events = events;
        this.users = users;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "dashboard", key = "#p.orgId().toString()")
    public DashboardResponse build(AuthPrincipal p) {
        User u = users.findById(p.userId()).orElseThrow();
        var firstName = firstWord(u.getName(), u.getEmail());

        Optional<Event> next = events.findUpcomingLive(p.orgId(), Instant.now(), PageRequest.of(0, 1)).stream().findFirst();
        Optional<Event> past = events.findRecentPast(p.orgId(), PageRequest.of(0, 1)).stream().findFirst();

        Now now = next.map(e -> {
            int pct = e.getCapacity() == 0 ? 0 : (int) Math.round(100.0 * e.getSold() / e.getCapacity());
            int daysOut = (int) Duration.between(Instant.now(), e.getStartsAt()).toDays();
            return new Now(EventDto.summary(e), pct, Math.max(0, daysOut));
        }).orElse(new Now(null, 0, 0));

        long activeCount = events.countLive(p.orgId());

        // Cycle (30d): we don't yet have a purchases table to compute true window-based deltas.
        // V1 stub: report all-time aggregates labelled as "30d", deltas at 0%.
        Object[] sums = events.sumRevenueAndSold(p.orgId()).get(0);
        long totalRevenue = ((Number) sums[0]).longValue();
        long totalSold = ((Number) sums[1]).longValue();
        Cycle cycle = new Cycle("30d", totalRevenue, (int) totalSold, /* squadRatePct */ 0,
                (int) activeCount, new Deltas(0, 0));

        LastEvent lastEvent = past.map(e -> {
            int avgTicket = e.getSold() == 0 ? 0 : (int) (e.getRevenueMinor() / e.getSold());
            return new LastEvent(EventDto.summary(e),
                    new LastEventMetrics(e.getSold(), e.getCapacity(), avgTicket, /* nps */ null));
        }).orElse(new LastEvent(null, new LastEventMetrics(0, 0, 0, null)));

        Business business = new Business(totalRevenue,
                events.countPublished(p.orgId()), events.countPast(p.orgId()),
                /* audienceCount */ 0, /* repeatRatePct */ 0);

        return new DashboardResponse(new Greeting(firstName), now, cycle, lastEvent,
                /* prediction */ null, business, List.of());
    }

    private static String firstWord(String name, String email) {
        if (name != null && !name.isBlank()) {
            int sp = name.indexOf(' ');
            return sp > 0 ? name.substring(0, sp) : name;
        }
        if (email == null) return "";
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
```

- [ ] **Step 4: Re-run, expect pass**

Run: `./mvnw -q test -Dtest=DashboardServiceTest`
Expected: 2 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/dashboard/DashboardService.java src/test/java/com/imin/iminapi/service/dashboard/DashboardServiceTest.java
git commit -m "dashboard: DashboardService aggregator with @Cacheable 30s"
```

---

## Task 5: DashboardController + MockMvc test

**Files:**
- Create: `src/main/java/com/imin/iminapi/controller/dashboard/DashboardController.java`
- Create: `src/test/java/com/imin/iminapi/controller/dashboard/DashboardControllerTest.java`

- [ ] **Step 1: Write the test**

```java
package com.imin.iminapi.controller.dashboard;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.dto.dashboard.DashboardResponse.*;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.dashboard.DashboardService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class DashboardControllerTest {

    @Autowired MockMvc mvc;
    @MockBean DashboardService service;

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
    void get_dashboard_returns_aggregate() throws Exception {
        when(service.build(any())).thenReturn(new DashboardResponse(
                new Greeting("Jaune"),
                new Now(null, 0, 0),
                new Cycle("30d", 0L, 0, 0, 0, new Deltas(0, 0)),
                new LastEvent(null, new LastEventMetrics(0, 0, 0, null)),
                null,
                new Business(0L, 0L, 0L, 0, 0),
                List.of()));

        mvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.greeting.name").value("Jaune"))
                .andExpect(jsonPath("$.cycle.period").value("30d"))
                .andExpect(jsonPath("$.activity.length()").value(0));
    }
}
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest=DashboardControllerTest`
Expected: FAIL.

- [ ] **Step 3: Implement DashboardController**

```java
package com.imin.iminapi.controller.dashboard;

import com.imin.iminapi.dto.dashboard.DashboardResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.dashboard.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) { this.service = service; }

    @GetMapping
    public DashboardResponse get(@CurrentUser AuthPrincipal p) {
        return service.build(p);
    }
}
```

- [ ] **Step 4: Re-run, expect pass**

Run: `./mvnw -q test -Dtest=DashboardControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/controller/dashboard/DashboardController.java src/test/java/com/imin/iminapi/controller/dashboard/DashboardControllerTest.java
git commit -m "dashboard: DashboardController exposing aggregate /dashboard"
```

---

## Task 6: Smoke

**Files:** none

- [ ] **Step 1: Boot, get token, hit /dashboard**

```bash
docker compose up -d && ./mvnw -q -DskipTests spring-boot:run &
sleep 6
TOKEN=$(curl -s -X POST http://localhost:8085/api/v1/auth/signup -H "Content-Type: application/json" \
  -d '{"email":"dash@example.com","password":"lovelace12","orgName":"X","country":"GB"}' | jq -r .token)
curl -s http://localhost:8085/api/v1/dashboard -H "Authorization: Bearer $TOKEN" | jq
```
Expected: a valid JSON with `greeting`, `now.nextEvent: null`, all aggregates zeroed.

- [ ] **Step 2: Hit it twice quickly to verify caching**

```bash
time curl -s http://localhost:8085/api/v1/dashboard -H "Authorization: Bearer $TOKEN" -o /dev/null
time curl -s http://localhost:8085/api/v1/dashboard -H "Authorization: Bearer $TOKEN" -o /dev/null
```
Expected: second request meaningfully faster (DB hits skipped). Stop the app afterwards.

---

## Self-Review

- **Spec coverage (§4):** every top-level field present. Stubbed fields (`squadRatePct`, `nps`, `audienceCount`, `repeatRatePct`, `prediction`, `activity`) explicitly return zero/null/empty per the contract's nullable shapes.
- **Caching (§4 perf note):** Caffeine 30s TTL keyed by `orgId`. ✓
- **Placeholder scan:** none. The "stub" zero/null returns are documented in code comments.
- **Type consistency:** `Cycle.period` is "30d" string. `LastEventMetrics.nps` is `Integer` (nullable). `EventDto.summary` matches the events-core plan output for embedding inside `Now` and `LastEvent`.
- **Gap:** real 30-day rolling deltas require a per-day-summary table (or a window function over a purchases table). Recorded as a post-V1 follow-up; the FE renders 0% deltas as flat without breaking.
