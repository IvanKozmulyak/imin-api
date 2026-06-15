# Event Sales Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an organizer-facing, per-event, live sales dashboard — live tiles (tickets sold / net revenue / capacity % / check-in rate), donut + stacked tier breakdown with top-converting tiers, a 3-stage conversion funnel with per-stage drop-off, and a full attendee CSV export.

**Architecture:** `imin-api` adds an append-only `event_funnel_events` table, a public `track` beacon endpoint, a polled `sales/live` dashboard endpoint, and a CSV export endpoint. `imin-webapp` rebuilds the existing `EventAnalyticsTab` to poll `sales/live` every 5s and download the CSV. `imin-public` fires fire-and-forget beacons for the two top funnel stages. Everything the dashboard shows comes from one `sales/live` response so tiles and the tier breakdown cannot drift; reconciliation is enforced because headline totals are summed from the same per-tier ticket aggregates.

**Tech Stack:** Java 17 · Spring Boot 4 · Spring Data JPA · Flyway · PostgreSQL/H2 · JUnit5/AssertJ (api) | Vite · React 19 · TypeScript · TanStack Query · Recharts · Vitest (webapp) | Next.js 16 App Router · React 19 (public).

**Spec:** `docs/superpowers/specs/2026-06-15-event-sales-dashboard-design.md`

**Reconciliation contract (load-bearing):** A ticket counts as *sold* when `state NOT IN ('refunded','revoked')` (i.e. `issued | redeemed | pre`). Per-tier `sold`, `grossRevenueMinor`, and `redeemed` are computed by grouping these tickets by `tier_id`. The headline tiles `ticketsSold`, `grossRevenueMinor`, `checkedIn` are the **sums of those same per-tier aggregate rows** — so `Σ tiers == headline` holds by construction. `netRevenueMinor` reuses the exact repository methods `EventOverviewService` already uses (`OrderRepository.sumTotalMinorByEventId` − `RefundRepository.sumSucceededRefundMinorByEventId`) so the money figure agrees with the overview tab.

**Percent convention:** all `*Pct` fields are 0–100 doubles (not 0–1 ratios).

---

## File structure

### imin-api (branch `feat/event-sales-dashboard`, already created)
- Create `src/main/resources/db/migration/V41__event_funnel_events.sql` — funnel events table.
- Create `src/main/java/com/imin/iminapi/model/FunnelEvent.java` — JPA entity.
- Create `src/main/java/com/imin/iminapi/repository/FunnelEventRepository.java` — insert + distinct-count.
- Create `src/main/java/com/imin/iminapi/dto/event/SalesDashboardResponse.java` — dashboard DTO (nested records).
- Create `src/main/java/com/imin/iminapi/dto/event/TrackRequest.java` — beacon request body.
- Create `src/main/java/com/imin/iminapi/service/event/FunnelTrackingService.java` — validates + inserts beacon rows.
- Create `src/main/java/com/imin/iminapi/service/event/SalesDashboardService.java` — composes the snapshot.
- Create `src/main/java/com/imin/iminapi/service/event/AttendeeExportService.java` — builds CSV.
- Create `src/main/java/com/imin/iminapi/controller/event/FunnelTrackingController.java` — `POST /api/v1/public/events/{id}/track`.
- Create `src/main/java/com/imin/iminapi/controller/event/SalesDashboardController.java` — dashboard + export endpoints.
- Modify `src/main/java/com/imin/iminapi/repository/OrderRepository.java` — add `countByEventId`.
- Modify `src/main/java/com/imin/iminapi/repository/TicketRepository.java` — add tier-aggregate + CSV-join queries.
- Modify `src/main/java/com/imin/iminapi/config/SecurityConfig.java` — permit the public `track` POST.
- Create tests: `FunnelEventRepositoryTest`, `FunnelTrackingServiceTest`, `FunnelTrackingControllerTest`, `SalesDashboardServiceTest`, `AttendeeExportServiceTest`.

### imin-webapp (new branch `feat/event-sales-dashboard`)
- Modify `src/shared/api/types.ts` — add dashboard types.
- Modify `src/shared/api/client.ts` — add `apiFetchBlob`.
- Create `src/features/events/useSalesDashboard.ts` — polling query hook.
- Modify `src/features/events/EventAnalyticsTab.tsx` — rebuild as the live dashboard.
- Create `src/features/events/EventAnalyticsTab.test.tsx` — render test.

### imin-public (new branch `feat/event-sales-dashboard`)
- Create `lib/funnel-tracking.ts` — beacon util.
- Modify `components/buyer/event-detail.tsx` — fire `PAGE_VIEW`.
- Modify `components/buyer/buy-modal.tsx` — fire `CHECKOUT_START`.
- Modify `docs/PUBLIC_PAGE_API.md` — document the `track` endpoint.

---

# Phase A — imin-api backend

> Run all commands from `/Users/ivan/imin/imin-api`. Tests use H2 (no external services). Test command: `./mvnw test -Dtest=ClassName`. You are already on branch `feat/event-sales-dashboard`.

## Task A1: Funnel events table, entity, repository

**Files:**
- Create: `src/main/resources/db/migration/V41__event_funnel_events.sql`
- Create: `src/main/java/com/imin/iminapi/model/FunnelEvent.java`
- Create: `src/main/java/com/imin/iminapi/repository/FunnelEventRepository.java`
- Test: `src/test/java/com/imin/iminapi/repository/FunnelEventRepositoryTest.java`

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V41__event_funnel_events.sql`:

```sql
-- V41__event_funnel_events.sql
-- Append-only instrumentation log for the top two stages of the per-event
-- conversion funnel (PAGE_VIEW, CHECKOUT_START). The PAYMENTS_COMPLETED stage
-- is NOT stored here — it is derived from the orders table at read time so
-- webhook replays cannot double-count it. Rows are written by the public
-- /track beacon endpoint; per-stage counts use COUNT(DISTINCT anon_id).

CREATE TABLE event_funnel_events (
    id          UUID PRIMARY KEY,
    event_id    UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    stage       VARCHAR(32) NOT NULL,   -- 'PAGE_VIEW' | 'CHECKOUT_START'
    anon_id     VARCHAR(64) NOT NULL,   -- per-session id from the buyer's sessionStorage
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_funnel_events_event_stage ON event_funnel_events(event_id, stage);
```

- [ ] **Step 2: Write the entity**

Create `src/main/java/com/imin/iminapi/model/FunnelEvent.java`:

```java
package com.imin.iminapi.model;

import com.imin.iminapi.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per instrumented funnel occurrence (a buyer viewing the public event
 * page, or starting checkout). Append-only — never updated. See
 * V41__event_funnel_events.sql.
 */
@Entity
@Table(name = "event_funnel_events")
@Getter
@Setter
public class FunnelEvent {

    /** The two stages we instrument client-side. PAYMENTS_COMPLETED is derived from orders. */
    public static final String STAGE_PAGE_VIEW = "PAGE_VIEW";
    public static final String STAGE_CHECKOUT_START = "CHECKOUT_START";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 32)
    private String stage;

    @Column(name = "anon_id", nullable = false, length = 64)
    private String anonId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Times.nowMicros();
}
```

> Verify the import path for `Times`: it is used in `Ticket.java` as `Times.nowMicros()`. Confirm the package with `grep -rn "class Times" src/main/java` and fix the import if it is not `com.imin.iminapi.util.Times`.

- [ ] **Step 3: Write the repository**

Create `src/main/java/com/imin/iminapi/repository/FunnelEventRepository.java`:

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.FunnelEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface FunnelEventRepository extends JpaRepository<FunnelEvent, UUID> {

    /**
     * Distinct-session counts per stage for an event. Tuple shape:
     * {@code [String stage, Long distinctAnonCount]}. Stages with zero rows are
     * simply absent from the result — the caller defaults them to 0.
     */
    @Query("""
            select e.stage, count(distinct e.anonId) from FunnelEvent e
             where e.eventId = :eventId
             group by e.stage
            """)
    List<Object[]> countDistinctAnonByStage(@Param("eventId") UUID eventId);
}
```

- [ ] **Step 4: Write the failing repository test**

Create `src/test/java/com/imin/iminapi/repository/FunnelEventRepositoryTest.java`:

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.FunnelEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class FunnelEventRepositoryTest {

    @Autowired FunnelEventRepository funnel;

    @AfterEach
    void tearDown() { funnel.deleteAll(); }

    private void insert(UUID eventId, String stage, String anonId) {
        FunnelEvent e = new FunnelEvent();
        e.setEventId(eventId);
        e.setStage(stage);
        e.setAnonId(anonId);
        funnel.save(e);
    }

    @Test
    void counts_distinct_sessions_per_stage() {
        UUID eventId = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        // PAGE_VIEW: 2 distinct sessions (s1 twice + s2)
        insert(eventId, FunnelEvent.STAGE_PAGE_VIEW, "s1");
        insert(eventId, FunnelEvent.STAGE_PAGE_VIEW, "s1");
        insert(eventId, FunnelEvent.STAGE_PAGE_VIEW, "s2");
        // CHECKOUT_START: 1 distinct session
        insert(eventId, FunnelEvent.STAGE_CHECKOUT_START, "s1");
        // a row for a different event must not leak in
        insert(other, FunnelEvent.STAGE_PAGE_VIEW, "s9");

        Map<String, Long> byStage = new HashMap<>();
        for (Object[] row : funnel.countDistinctAnonByStage(eventId)) {
            byStage.put((String) row[0], (Long) row[1]);
        }

        assertThat(byStage.get(FunnelEvent.STAGE_PAGE_VIEW)).isEqualTo(2L);
        assertThat(byStage.get(FunnelEvent.STAGE_CHECKOUT_START)).isEqualTo(1L);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=FunnelEventRepositoryTest`
Expected: PASS (Flyway applies V41, the entity maps, the distinct count is correct).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V41__event_funnel_events.sql \
        src/main/java/com/imin/iminapi/model/FunnelEvent.java \
        src/main/java/com/imin/iminapi/repository/FunnelEventRepository.java \
        src/test/java/com/imin/iminapi/repository/FunnelEventRepositoryTest.java
git commit -m "feat(api): funnel events table, entity, repository (V41)"
```

---

## Task A2: Funnel tracking service, public endpoint, security

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/event/TrackRequest.java`
- Create: `src/main/java/com/imin/iminapi/service/event/FunnelTrackingService.java`
- Create: `src/main/java/com/imin/iminapi/controller/event/FunnelTrackingController.java`
- Modify: `src/main/java/com/imin/iminapi/config/SecurityConfig.java`
- Test: `src/test/java/com/imin/iminapi/service/event/FunnelTrackingServiceTest.java`
- Test: `src/test/java/com/imin/iminapi/controller/event/FunnelTrackingControllerTest.java`

- [ ] **Step 1: Write the request DTO**

Create `src/main/java/com/imin/iminapi/dto/event/TrackRequest.java`:

```java
package com.imin.iminapi.dto.event;

/**
 * Body of the public funnel beacon. {@code stage} must be one of the
 * client-instrumented stages (PAGE_VIEW | CHECKOUT_START); anything else is a
 * no-op. {@code anonId} is the buyer's per-session id from sessionStorage.
 */
public record TrackRequest(String stage, String anonId) {}
```

- [ ] **Step 2: Write the failing service test**

Create `src/test/java/com/imin/iminapi/service/event/FunnelTrackingServiceTest.java`:

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.event.TrackRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class FunnelTrackingServiceTest {

    @Autowired FunnelTrackingService service;
    @Autowired FunnelEventRepository funnel;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;

    private Event publicEvent;

    @BeforeEach
    void setUp() {
        wipe();
        Organization org = new Organization();
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hi@test.example");
        org.setCountry("DE");
        org = orgs.save(org);

        publicEvent = new Event();
        publicEvent.setOrgId(org.getId());
        publicEvent.setName("Public Night");
        publicEvent.setSlug("public-" + UUID.randomUUID().toString().substring(0, 8));
        publicEvent.setVisibility(EventVisibility.PUBLIC);
        publicEvent.setStatus(EventStatus.LIVE);
        publicEvent.setStartsAt(Instant.now().plusSeconds(86_400));
        publicEvent.setCreatedBy(UUID.randomUUID());
        publicEvent.setCurrency("EUR");
        publicEvent = events.save(publicEvent);
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() { funnel.deleteAll(); events.deleteAll(); orgs.deleteAll(); }

    @Test
    void records_a_page_view_for_a_public_event() {
        service.track(publicEvent.getId(), new TrackRequest("PAGE_VIEW", "sess-1"));
        assertThat(funnel.findAll()).hasSize(1);
        assertThat(funnel.findAll().get(0).getStage()).isEqualTo(FunnelEvent.STAGE_PAGE_VIEW);
    }

    @Test
    void unknown_event_is_a_noop() {
        service.track(UUID.randomUUID(), new TrackRequest("PAGE_VIEW", "sess-1"));
        assertThat(funnel.findAll()).isEmpty();
    }

    @Test
    void unknown_stage_is_a_noop() {
        service.track(publicEvent.getId(), new TrackRequest("BOGUS", "sess-1"));
        assertThat(funnel.findAll()).isEmpty();
    }

    @Test
    void blank_anon_id_is_a_noop() {
        service.track(publicEvent.getId(), new TrackRequest("PAGE_VIEW", "  "));
        assertThat(funnel.findAll()).isEmpty();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=FunnelTrackingServiceTest`
Expected: COMPILE FAILURE — `FunnelTrackingService` does not exist yet.

- [ ] **Step 4: Write the service**

Create `src/main/java/com/imin/iminapi/service/event/FunnelTrackingService.java`:

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.TrackRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Records public funnel beacons (PAGE_VIEW, CHECKOUT_START). All failure modes
 * are silent no-ops so the public endpoint can always answer 204 with no
 * information leak: unknown/non-public event, unknown stage, blank session id.
 */
@Service
public class FunnelTrackingService {

    private static final Set<String> ALLOWED_STAGES =
            Set.of(FunnelEvent.STAGE_PAGE_VIEW, FunnelEvent.STAGE_CHECKOUT_START);

    private final EventRepository events;
    private final FunnelEventRepository funnel;

    public FunnelTrackingService(EventRepository events, FunnelEventRepository funnel) {
        this.events = events;
        this.funnel = funnel;
    }

    @Transactional
    public void track(UUID eventId, TrackRequest req) {
        if (req == null || req.stage() == null || !ALLOWED_STAGES.contains(req.stage())) return;
        if (req.anonId() == null || req.anonId().isBlank()) return;

        Event e = events.findActive(eventId).orElse(null);
        if (e == null || e.getVisibility() != EventVisibility.PUBLIC) return;

        FunnelEvent row = new FunnelEvent();
        row.setEventId(eventId);
        row.setStage(req.stage());
        row.setAnonId(req.anonId().trim().substring(0, Math.min(64, req.anonId().trim().length())));
        funnel.save(row);
    }
}
```

- [ ] **Step 5: Run the service test to verify it passes**

Run: `./mvnw test -Dtest=FunnelTrackingServiceTest`
Expected: PASS (all four cases).

- [ ] **Step 6: Write the controller**

Create `src/main/java/com/imin/iminapi/controller/event/FunnelTrackingController.java`:

```java
package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.event.TrackRequest;
import com.imin.iminapi.service.event.FunnelTrackingService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public, unauthenticated funnel beacon. Always 204 — including for unknown or
 * non-public events (no-leak). The body is best-effort; bad input is a no-op.
 */
@RestController
@RequestMapping("/api/v1/public/events")
public class FunnelTrackingController {

    private final FunnelTrackingService tracking;

    public FunnelTrackingController(FunnelTrackingService tracking) {
        this.tracking = tracking;
    }

    @PostMapping("/{id}/track")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void track(@PathVariable UUID id, @RequestBody(required = false) TrackRequest body) {
        tracking.track(id, body);
    }
}
```

- [ ] **Step 7: Permit the endpoint in SecurityConfig**

In `src/main/java/com/imin/iminapi/config/SecurityConfig.java`, find the block of public POST matchers (it contains the `/api/v1/public/events/*/checkout` line) and add the `track` matcher immediately after the checkout one:

```java
                    // Public buyer checkout — unauthenticated POST (validated server-side).
                    .requestMatchers(HttpMethod.POST, "/api/v1/public/events/*/checkout").permitAll()
                    // Public funnel beacon — unauthenticated POST, always 204 (no-leak).
                    .requestMatchers(HttpMethod.POST, "/api/v1/public/events/*/track").permitAll()
```

> The existing `GET /api/v1/public/**` matcher does not cover POST, so this explicit POST permit is required.

- [ ] **Step 8: Write the failing controller (security) test**

Create `src/test/java/com/imin/iminapi/controller/event/FunnelTrackingControllerTest.java`:

```java
package com.imin.iminapi.controller.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class FunnelTrackingControllerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void track_is_public_and_returns_204_even_for_unknown_event() throws Exception {
        mockMvc.perform(post("/api/v1/public/events/{id}/track", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"PAGE_VIEW\",\"anonId\":\"sess-1\"}"))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 9: Run the controller test to verify it passes**

Run: `./mvnw test -Dtest=FunnelTrackingControllerTest`
Expected: PASS (204, reachable without a Bearer token).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/event/TrackRequest.java \
        src/main/java/com/imin/iminapi/service/event/FunnelTrackingService.java \
        src/main/java/com/imin/iminapi/controller/event/FunnelTrackingController.java \
        src/main/java/com/imin/iminapi/config/SecurityConfig.java \
        src/test/java/com/imin/iminapi/service/event/FunnelTrackingServiceTest.java \
        src/test/java/com/imin/iminapi/controller/event/FunnelTrackingControllerTest.java
git commit -m "feat(api): public funnel beacon endpoint /public/events/{id}/track"
```

---

## Task A3: Repository aggregate queries (orders count, tier aggregates, CSV join)

**Files:**
- Modify: `src/main/java/com/imin/iminapi/repository/OrderRepository.java`
- Modify: `src/main/java/com/imin/iminapi/repository/TicketRepository.java`
- Test: `src/test/java/com/imin/iminapi/repository/TicketAggregateQueryTest.java`

- [ ] **Step 1: Add the orders count method**

In `src/main/java/com/imin/iminapi/repository/OrderRepository.java`, add this derived-query method inside the interface (next to the other `findByEventId...` methods):

```java
    /** Number of orders (= completed payments) for an event. Drives the funnel's PAYMENTS_COMPLETED stage. */
    long countByEventId(UUID eventId);
```

- [ ] **Step 2: Add the tier-aggregate and CSV-join queries to TicketRepository**

In `src/main/java/com/imin/iminapi/repository/TicketRepository.java`, add these two methods inside the interface (after `findByOrderIdInOrderByOrderIdAscCreatedAtAsc`):

```java
    /**
     * Per-tier sold aggregates for an event, over the SOLD set
     * ({@code state NOT IN ('refunded','revoked')}). Tuple shape:
     * {@code [UUID tierId, String tierName, Long sold, Long grossRevenueMinor, Long redeemed]}.
     * Summed across rows, these give the headline tiles — so the tier breakdown
     * reconciles with the headline by construction.
     */
    @Query("""
            select t.tierId, t.tierName,
                   count(t),
                   coalesce(sum(t.priceMinor), 0),
                   coalesce(sum(case when t.state = 'redeemed' then 1 else 0 end), 0)
              from Ticket t
             where t.eventId = :eventId
               and t.state not in ('refunded', 'revoked')
             group by t.tierId, t.tierName
            """)
    List<Object[]> tierAggregates(@Param("eventId") UUID eventId);

    /**
     * Every SOLD ticket for an event joined to its order, for the attendee CSV
     * export. Tuple shape: {@code [Ticket ticket, String buyerEmail, String orderToken, Instant purchasedAt]}.
     * Ordered oldest order first.
     */
    @Query("""
            select t, o.email, o.token, o.createdAt
              from Ticket t
              join com.imin.iminapi.model.Order o on o.id = t.orderId
             where t.eventId = :eventId
               and t.state not in ('refunded', 'revoked')
             order by o.createdAt asc, t.createdAt asc
            """)
    List<Object[]> attendeeRows(@Param("eventId") UUID eventId);
```

- [ ] **Step 3: Write the failing aggregate test**

Create `src/test/java/com/imin/iminapi/repository/TicketAggregateQueryTest.java`:

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Ticket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class TicketAggregateQueryTest {

    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;

    private final UUID eventId = UUID.randomUUID();
    private final UUID gaTier = UUID.randomUUID();

    @AfterEach
    void tearDown() { tickets.deleteAll(); orders.deleteAll(); }

    private Order order() {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(eventId);
        o.setOrgId(UUID.randomUUID());
        o.setEmail("buyer@example.com");
        o.setTotalMinor(0);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        return orders.save(o);
    }

    private void ticket(Order o, String state) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setOrderId(o.getId());
        t.setEventId(eventId);
        t.setTierId(gaTier);
        t.setTierName("GA");
        t.setPriceMinor(1500);
        t.setState(state);
        tickets.save(t);
    }

    @Test
    void tier_aggregates_exclude_refunded_and_revoked_and_count_redeemed() {
        Order o = order();
        ticket(o, Ticket.STATE_ISSUED);    // sold
        ticket(o, Ticket.STATE_REDEEMED);  // sold + redeemed
        ticket(o, Ticket.STATE_REFUNDED);  // excluded
        ticket(o, Ticket.STATE_REVOKED);   // excluded

        Map<UUID, Object[]> byTier = new HashMap<>();
        for (Object[] row : tickets.tierAggregates(eventId)) byTier.put((UUID) row[0], row);
        Object[] ga = byTier.get(gaTier);

        assertThat((Long) ga[2]).isEqualTo(2L);     // sold
        assertThat((Long) ga[3]).isEqualTo(3000L);  // gross = 2 * 1500
        assertThat((Long) ga[4]).isEqualTo(1L);     // redeemed

        assertThat(tickets.attendeeRows(eventId)).hasSize(2); // only sold rows
        assertThat(orders.countByEventId(eventId)).isEqualTo(1L);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=TicketAggregateQueryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/repository/OrderRepository.java \
        src/main/java/com/imin/iminapi/repository/TicketRepository.java \
        src/test/java/com/imin/iminapi/repository/TicketAggregateQueryTest.java
git commit -m "feat(api): tier-aggregate, attendee-join, and order-count queries"
```

---

## Task A4: SalesDashboardService + DTO + endpoint

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/event/SalesDashboardResponse.java`
- Create: `src/main/java/com/imin/iminapi/service/event/SalesDashboardService.java`
- Create: `src/main/java/com/imin/iminapi/controller/event/SalesDashboardController.java`
- Test: `src/test/java/com/imin/iminapi/service/event/SalesDashboardServiceTest.java`

- [ ] **Step 1: Write the response DTO**

Create `src/main/java/com/imin/iminapi/dto/event/SalesDashboardResponse.java`:

```java
package com.imin.iminapi.dto.event;

import java.util.List;

/**
 * One snapshot of a single event's live sales dashboard: headline tiles, the
 * per-tier breakdown (+ top-converting ordering), and the conversion funnel.
 * All {@code *Pct} fields are 0–100 doubles.
 */
public record SalesDashboardResponse(
        String currency,
        Tiles tiles,
        List<TierBreakdown> tiers,
        List<TierBreakdown> topConvertingTiers,
        Funnel funnel) {

    public record Tiles(
            int ticketsSold,
            long netRevenueMinor,
            long grossRevenueMinor,
            int capacity,
            double capacityPct,
            int checkedIn,
            double checkInRatePct) {}

    public record TierBreakdown(
            String tierId,
            String name,
            int sold,
            int redeemed,
            long grossRevenueMinor,
            int quantity,
            double sellThroughPct) {}

    public record Funnel(List<Stage> stages, List<DropOff> dropOff) {

        public record Stage(String stage, long count) {}

        public record DropOff(String from, String to, long lostCount, double lostPct) {}
    }
}
```

- [ ] **Step 2: Write the failing service test**

Create `src/test/java/com/imin/iminapi/service/event/SalesDashboardServiceTest.java`:

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.event.SalesDashboardResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class SalesDashboardServiceTest {

    @Autowired SalesDashboardService service;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;
    @Autowired RefundRepository refunds;
    @Autowired FunnelEventRepository funnel;

    private Organization org;
    private User owner;
    private Event event;
    private TicketTier ga;
    private TicketTier vip;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        wipe();
        org = new Organization();
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hi@test.example");
        org.setCountry("DE");
        org = orgs.save(org);

        owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);

        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Night");
        event.setSlug("night-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setStartsAt(Instant.now().plusSeconds(86_400));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);

        ga = newTier("GA", 1500, 100);
        vip = newTier("VIP", 5000, 20);

        principal = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        funnel.deleteAll(); refunds.deleteAll(); tickets.deleteAll();
        orders.deleteAll(); tiers.deleteAll(); events.deleteAll();
        users.deleteAll(); orgs.deleteAll();
    }

    private TicketTier newTier(String name, int price, int qty) {
        TicketTier t = new TicketTier();
        t.setEventId(event.getId());
        t.setName(name);
        t.setPriceMinor(price);
        t.setQuantity(qty);
        t.setReserved(0);
        t.setSold(0);
        t.setEnabled(true);
        return tiers.save(t);
    }

    private Order newOrder(long totalMinor) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail("b@example.com");
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        o.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        return orders.save(o);
    }

    private void newTicket(Order o, TicketTier tier, String state) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setOrderId(o.getId());
        t.setEventId(o.getEventId());
        t.setTierId(tier.getId());
        t.setTierName(tier.getName());
        t.setPriceMinor(tier.getPriceMinor());
        t.setState(state);
        tickets.save(t);
    }

    private void funnelRow(String stage, String anon) {
        FunnelEvent e = new FunnelEvent();
        e.setEventId(event.getId());
        e.setStage(stage);
        e.setAnonId(anon);
        funnel.save(e);
    }

    @Test
    void cross_org_returns_404_leak_safe() {
        AuthPrincipal other = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(),
                UserRole.OWNER, UUID.randomUUID());
        assertThatThrownBy(() -> service.dashboard(other, event.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void tiers_reconcile_with_headline_and_check_in_rate_is_correct() {
        Order o1 = newOrder(3000);
        newTicket(o1, ga, Ticket.STATE_ISSUED);
        newTicket(o1, ga, Ticket.STATE_REDEEMED);
        Order o2 = newOrder(5000);
        newTicket(o2, vip, Ticket.STATE_ISSUED);
        // noise that must NOT count toward sold:
        newTicket(o2, vip, Ticket.STATE_REFUNDED);

        SalesDashboardResponse r = service.dashboard(principal, event.getId());

        // headline: 3 sold (2 GA + 1 VIP), gross = 1500+1500+5000 = 8000
        assertThat(r.tiles().ticketsSold()).isEqualTo(3);
        assertThat(r.tiles().grossRevenueMinor()).isEqualTo(8000L);
        assertThat(r.tiles().checkedIn()).isEqualTo(1);
        assertThat(r.tiles().checkInRatePct()).isEqualTo(100.0 / 3.0);
        assertThat(r.tiles().capacity()).isEqualTo(120);

        // reconciliation invariant
        int tierSold = r.tiers().stream().mapToInt(SalesDashboardResponse.TierBreakdown::sold).sum();
        long tierGross = r.tiers().stream()
                .mapToLong(SalesDashboardResponse.TierBreakdown::grossRevenueMinor).sum();
        assertThat(tierSold).isEqualTo(r.tiles().ticketsSold());
        assertThat(tierGross).isEqualTo(r.tiles().grossRevenueMinor());

        // top-converting: GA 2/100 = 2% vs VIP 1/20 = 5% → VIP first
        assertThat(r.topConvertingTiers().get(0).name()).isEqualTo("VIP");
    }

    @Test
    void net_revenue_subtracts_only_succeeded_refunds() {
        Order o = newOrder(10000);
        newTicket(o, ga, Ticket.STATE_ISSUED);
        Refund r1 = new Refund();
        r1.setOrderId(o.getId());
        r1.setStripePaymentIntentId(o.getStripePaymentIntentId());
        r1.setAmountMinor(3000);
        r1.setCurrency("eur");
        r1.setApplicationFeeRefundMinor(0);
        r1.setReason(RefundReason.OTHER);
        r1.setStatus(RefundStatus.SUCCEEDED);
        r1.setInitiatedByUserId(owner.getId());
        r1.setIdempotencyKey("k-" + UUID.randomUUID());
        refunds.save(r1);

        SalesDashboardResponse r = service.dashboard(principal, event.getId());
        assertThat(r.tiles().netRevenueMinor()).isEqualTo(7000L); // 10000 - 3000
    }

    @Test
    void funnel_counts_distinct_sessions_and_payments_and_dropoff() {
        // 3 distinct page-view sessions, 2 distinct checkout sessions
        funnelRow(FunnelEvent.STAGE_PAGE_VIEW, "a");
        funnelRow(FunnelEvent.STAGE_PAGE_VIEW, "a");
        funnelRow(FunnelEvent.STAGE_PAGE_VIEW, "b");
        funnelRow(FunnelEvent.STAGE_PAGE_VIEW, "c");
        funnelRow(FunnelEvent.STAGE_CHECKOUT_START, "a");
        funnelRow(FunnelEvent.STAGE_CHECKOUT_START, "b");
        // 1 completed payment (= 1 order)
        Order o = newOrder(1500);
        newTicket(o, ga, Ticket.STATE_ISSUED);

        SalesDashboardResponse r = service.dashboard(principal, event.getId());

        assertThat(r.funnel().stages()).extracting(SalesDashboardResponse.Funnel.Stage::stage)
                .containsExactly("PAGE_VIEW", "CHECKOUT_START", "PAYMENTS_COMPLETED");
        assertThat(r.funnel().stages()).extracting(SalesDashboardResponse.Funnel.Stage::count)
                .containsExactly(3L, 2L, 1L);
        // drop-off: view→checkout lost 1 of 3 (33.33%); checkout→payment lost 1 of 2 (50%)
        assertThat(r.funnel().dropOff().get(0).lostCount()).isEqualTo(1L);
        assertThat(r.funnel().dropOff().get(1).lostCount()).isEqualTo(1L);
        assertThat(r.funnel().dropOff().get(1).lostPct()).isEqualTo(50.0);
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=SalesDashboardServiceTest`
Expected: COMPILE FAILURE — `SalesDashboardService` does not exist.

- [ ] **Step 4: Write the service**

Create `src/main/java/com/imin/iminapi/service/event/SalesDashboardService.java`:

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.SalesDashboardResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.FunnelEvent;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds one snapshot of an event's live sales dashboard. Tiles and the tier
 * breakdown are summed from the same per-tier ticket aggregates so they
 * reconcile by construction; net revenue reuses the same order/refund repo
 * methods as {@code EventOverviewService} so the money figure agrees.
 */
@Service
public class SalesDashboardService {

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final TicketRepository tickets;
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final FunnelEventRepository funnel;

    public SalesDashboardService(EventRepository events,
                                 TicketTierRepository tiers,
                                 TicketRepository tickets,
                                 OrderRepository orders,
                                 RefundRepository refunds,
                                 FunnelEventRepository funnel) {
        this.events = events;
        this.tiers = tiers;
        this.tickets = tickets;
        this.orders = orders;
        this.refunds = refunds;
        this.funnel = funnel;
    }

    @Transactional(readOnly = true)
    public SalesDashboardResponse dashboard(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");

        List<SalesDashboardResponse.TierBreakdown> tierRows = buildTiers(eventId);

        int ticketsSold = tierRows.stream().mapToInt(SalesDashboardResponse.TierBreakdown::sold).sum();
        long grossRevenueMinor = tierRows.stream()
                .mapToLong(SalesDashboardResponse.TierBreakdown::grossRevenueMinor).sum();
        int checkedIn = tierRows.stream().mapToInt(SalesDashboardResponse.TierBreakdown::redeemed).sum();

        long gross = orders.sumTotalMinorByEventId(eventId);
        long refunded = refunds.sumSucceededRefundMinorByEventId(eventId);
        long netRevenueMinor = Math.max(0L, gross - refunded);

        int capacity = tiers.sumQuantityByEventId(eventId);
        double capacityPct = capacity > 0 ? (ticketsSold * 100.0 / capacity) : 0.0;
        double checkInRatePct = ticketsSold > 0 ? (checkedIn * 100.0 / ticketsSold) : 0.0;

        SalesDashboardResponse.Tiles tiles = new SalesDashboardResponse.Tiles(
                ticketsSold, netRevenueMinor, grossRevenueMinor, capacity,
                capacityPct, checkedIn, checkInRatePct);

        List<SalesDashboardResponse.TierBreakdown> topConverting = new ArrayList<>(tierRows);
        topConverting.sort(Comparator.comparingDouble(
                SalesDashboardResponse.TierBreakdown::sellThroughPct).reversed());

        SalesDashboardResponse.Funnel funnelDto = buildFunnel(eventId);

        return new SalesDashboardResponse(e.getCurrency(), tiles, tierRows, topConverting, funnelDto);
    }

    /**
     * Merge the per-tier ticket aggregates with the current tier list. Current
     * tiers (in sort order) appear even with zero sales; aggregates for a tier
     * that was later deleted are appended using the snapshotted tier_name so
     * the totals still reconcile.
     */
    private List<SalesDashboardResponse.TierBreakdown> buildTiers(UUID eventId) {
        Map<UUID, long[]> agg = new HashMap<>();   // tierId -> [sold, gross, redeemed]
        Map<UUID, String> aggName = new HashMap<>();
        for (Object[] row : tickets.tierAggregates(eventId)) {
            UUID tierId = (UUID) row[0];
            aggName.put(tierId, (String) row[1]);
            agg.put(tierId, new long[]{((Number) row[2]).longValue(),
                                       ((Number) row[3]).longValue(),
                                       ((Number) row[4]).longValue()});
        }

        List<SalesDashboardResponse.TierBreakdown> out = new ArrayList<>();
        Map<UUID, Boolean> seen = new LinkedHashMap<>();

        for (TicketTier t : tiers.findByEventIdOrderBySortOrderAsc(eventId)) {
            long[] a = agg.getOrDefault(t.getId(), new long[]{0, 0, 0});
            out.add(toBreakdown(t.getId().toString(), t.getName(),
                    (int) a[0], (int) a[2], a[1], t.getQuantity()));
            seen.put(t.getId(), true);
        }
        // Deleted tiers that still have sold tickets — include so totals reconcile.
        for (Map.Entry<UUID, long[]> en : agg.entrySet()) {
            if (seen.containsKey(en.getKey())) continue;
            long[] a = en.getValue();
            out.add(toBreakdown(en.getKey().toString(), aggName.get(en.getKey()),
                    (int) a[0], (int) a[2], a[1], 0));
        }
        return out;
    }

    private SalesDashboardResponse.TierBreakdown toBreakdown(
            String tierId, String name, int sold, int redeemed, long gross, int quantity) {
        double sellThroughPct = quantity > 0 ? (sold * 100.0 / quantity) : 0.0;
        return new SalesDashboardResponse.TierBreakdown(
                tierId, name, sold, redeemed, gross, quantity, sellThroughPct);
    }

    private SalesDashboardResponse.Funnel buildFunnel(UUID eventId) {
        Map<String, Long> byStage = new HashMap<>();
        for (Object[] row : funnel.countDistinctAnonByStage(eventId)) {
            byStage.put((String) row[0], ((Number) row[1]).longValue());
        }
        long pageViews = byStage.getOrDefault(FunnelEvent.STAGE_PAGE_VIEW, 0L);
        long checkoutStarts = byStage.getOrDefault(FunnelEvent.STAGE_CHECKOUT_START, 0L);
        long payments = orders.countByEventId(eventId);

        List<SalesDashboardResponse.Funnel.Stage> stages = List.of(
                new SalesDashboardResponse.Funnel.Stage("PAGE_VIEW", pageViews),
                new SalesDashboardResponse.Funnel.Stage("CHECKOUT_START", checkoutStarts),
                new SalesDashboardResponse.Funnel.Stage("PAYMENTS_COMPLETED", payments));

        List<SalesDashboardResponse.Funnel.DropOff> drops = List.of(
                dropOff("PAGE_VIEW", pageViews, "CHECKOUT_START", checkoutStarts),
                dropOff("CHECKOUT_START", checkoutStarts, "PAYMENTS_COMPLETED", payments));

        return new SalesDashboardResponse.Funnel(stages, drops);
    }

    private SalesDashboardResponse.Funnel.DropOff dropOff(String from, long fromN, String to, long toN) {
        long lost = Math.max(0L, fromN - toN);          // clamp: unit mismatch can make toN > fromN
        double lostPct = fromN > 0 ? (lost * 100.0 / fromN) : 0.0;
        return new SalesDashboardResponse.Funnel.DropOff(from, to, lost, lostPct);
    }
}
```

> The only nested DTO types referenced are `Tiles`, `TierBreakdown`, `Funnel`, `Funnel.Stage`, `Funnel.DropOff` — all defined in `SalesDashboardResponse` from Task A4 Step 1.

- [ ] **Step 5: Run the service test to verify it passes**

Run: `./mvnw test -Dtest=SalesDashboardServiceTest`
Expected: PASS (all four cases — including the reconciliation invariant).

- [ ] **Step 6: Write the controller**

Create `src/main/java/com/imin/iminapi/controller/event/SalesDashboardController.java`:

```java
package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.event.SalesDashboardResponse;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.event.SalesDashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
public class SalesDashboardController {

    private final SalesDashboardService dashboard;

    public SalesDashboardController(SalesDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/{id}/sales/live")
    public SalesDashboardResponse salesLive(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return dashboard.dashboard(p, id);
    }
}
```

> Confirm the `@CurrentUser` import path matches `EventController.java` (`com.imin.iminapi.security.CurrentUser`). If `EventController` imports it from elsewhere, match that.

- [ ] **Step 7: Run the full event test suite to confirm nothing regressed**

Run: `./mvnw test -Dtest=SalesDashboardServiceTest,EventOverviewServiceTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/event/SalesDashboardResponse.java \
        src/main/java/com/imin/iminapi/service/event/SalesDashboardService.java \
        src/main/java/com/imin/iminapi/controller/event/SalesDashboardController.java \
        src/test/java/com/imin/iminapi/service/event/SalesDashboardServiceTest.java
git commit -m "feat(api): GET /events/{id}/sales/live dashboard endpoint"
```

---

## Task A5: Attendee CSV export

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/event/AttendeeExportService.java`
- Modify: `src/main/java/com/imin/iminapi/controller/event/SalesDashboardController.java`
- Test: `src/test/java/com/imin/iminapi/service/event/AttendeeExportServiceTest.java`

- [ ] **Step 1: Write the failing service test**

Create `src/test/java/com/imin/iminapi/service/event/AttendeeExportServiceTest.java`:

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class AttendeeExportServiceTest {

    @Autowired AttendeeExportService service;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired TicketTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired TicketRepository tickets;

    private Organization org;
    private User owner;
    private Event event;
    private TicketTier ga;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        wipe();
        org = new Organization();
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        org.setContactEmail("hi@test.example");
        org.setCountry("DE");
        org = orgs.save(org);
        owner = new User();
        owner.setEmail("owner-" + UUID.randomUUID() + "@example.com");
        owner.setOrgId(org.getId());
        owner.setRole(UserRole.OWNER);
        owner = users.save(owner);
        event = new Event();
        event.setOrgId(org.getId());
        event.setName("Night");
        event.setSlug("night-" + UUID.randomUUID().toString().substring(0, 8));
        event.setVisibility(EventVisibility.PUBLIC);
        event.setStatus(EventStatus.LIVE);
        event.setStartsAt(Instant.now().plusSeconds(86_400));
        event.setCreatedBy(owner.getId());
        event.setCurrency("EUR");
        event = events.save(event);
        ga = new TicketTier();
        ga.setEventId(event.getId());
        ga.setName("GA");
        ga.setPriceMinor(1500);
        ga.setQuantity(100);
        ga.setEnabled(true);
        ga = tiers.save(ga);
        principal = new AuthPrincipal(owner.getId(), org.getId(), UserRole.OWNER, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() { wipe(); }

    private void wipe() {
        tickets.deleteAll(); orders.deleteAll(); tiers.deleteAll();
        events.deleteAll(); users.deleteAll(); orgs.deleteAll();
    }

    private Order order() {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(event.getId());
        o.setOrgId(org.getId());
        o.setEmail("buyer@example.com");
        o.setTotalMinor(1500);
        o.setCurrency("eur");
        o.setPaymentMethod("stripe");
        return orders.save(o);
    }

    private void ticket(Order o, String state, Instant redeemedAt) {
        Ticket t = new Ticket();
        t.setToken(UUID.randomUUID().toString().replace("-", ""));
        t.setOrderId(o.getId());
        t.setEventId(event.getId());
        t.setTierId(ga.getId());
        t.setTierName("GA");
        t.setPriceMinor(1500);
        t.setState(state);
        t.setRedeemedAt(redeemedAt);
        tickets.save(t);
    }

    @Test
    void cross_org_returns_404() {
        AuthPrincipal other = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(),
                UserRole.OWNER, UUID.randomUUID());
        assertThatThrownBy(() -> service.toCsv(other, event.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void csv_has_header_plus_one_row_per_sold_ticket_with_status() {
        Order o = order();
        ticket(o, Ticket.STATE_ISSUED, null);
        ticket(o, Ticket.STATE_REDEEMED, Instant.now());
        ticket(o, Ticket.STATE_REFUNDED, null); // excluded

        String csv = service.toCsv(principal, event.getId());
        String[] lines = csv.strip().split("\r\n");

        assertThat(lines[0]).isEqualTo(
                "order_ref,buyer_email,tier,status,checked_in_at,price,purchased_at");
        // 2 sold tickets → 2 data rows (refunded excluded)
        assertThat(lines.length).isEqualTo(3);
        assertThat(csv).contains("Issued");
        assertThat(csv).contains("Checked-in");
        assertThat(csv).contains("GA");
        assertThat(csv).contains("buyer@example.com");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=AttendeeExportServiceTest`
Expected: COMPILE FAILURE — `AttendeeExportService` does not exist.

- [ ] **Step 3: Write the service**

Create `src/main/java/com/imin/iminapi/service/event/AttendeeExportService.java`:

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Ticket;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Builds the attendee CSV for an event. One row per SOLD ticket
 * ({@code state NOT IN ('refunded','revoked')}) — so the row count matches the
 * dashboard's ticketsSold tile and the Checked-in rows match its checkedIn tile.
 * "Attendee" is keyed by buyer email (the data model has no per-attendee name).
 */
@Service
public class AttendeeExportService {

    private static final String HEADER =
            "order_ref,buyer_email,tier,status,checked_in_at,price,purchased_at";

    private final EventRepository events;
    private final TicketRepository tickets;

    public AttendeeExportService(EventRepository events, TicketRepository tickets) {
        this.events = events;
        this.tickets = tickets;
    }

    @Transactional(readOnly = true)
    public String toCsv(AuthPrincipal p, UUID eventId) {
        Event e = events.findActive(eventId).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");

        StringBuilder sb = new StringBuilder(HEADER).append("\r\n");
        for (Object[] row : tickets.attendeeRows(eventId)) {
            Ticket t = (Ticket) row[0];
            String email = (String) row[1];
            String orderToken = (String) row[2];
            Instant purchasedAt = (Instant) row[3];

            boolean redeemed = Ticket.STATE_REDEEMED.equals(t.getState());
            sb.append(csv(orderToken)).append(',')
              .append(csv(email)).append(',')
              .append(csv(t.getTierName())).append(',')
              .append(redeemed ? "Checked-in" : "Issued").append(',')
              .append(t.getRedeemedAt() == null ? "" : t.getRedeemedAt().toString()).append(',')
              .append(formatMoney(t.getPriceMinor(), e.getCurrency())).append(',')
              .append(purchasedAt == null ? "" : purchasedAt.toString())
              .append("\r\n");
        }
        return sb.toString();
    }

    private static String formatMoney(int minor, String currency) {
        return currency + " " + String.format("%.2f", minor / 100.0);
    }

    /** RFC-4180 escaping: wrap in quotes and double any embedded quote when needed. */
    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=AttendeeExportServiceTest`
Expected: PASS.

- [ ] **Step 5: Add the export endpoint to the controller**

In `src/main/java/com/imin/iminapi/controller/event/SalesDashboardController.java`, add the import and the new mapping. Add these imports:

```java
import com.imin.iminapi.service.event.AttendeeExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
```

Replace the constructor and add the field + endpoint so the class reads:

```java
@RestController
@RequestMapping("/api/v1/events")
public class SalesDashboardController {

    private final SalesDashboardService dashboard;
    private final AttendeeExportService attendeeExport;

    public SalesDashboardController(SalesDashboardService dashboard,
                                    AttendeeExportService attendeeExport) {
        this.dashboard = dashboard;
        this.attendeeExport = attendeeExport;
    }

    @GetMapping("/{id}/sales/live")
    public SalesDashboardResponse salesLive(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return dashboard.dashboard(p, id);
    }

    @GetMapping(value = "/{id}/attendees/export", produces = "text/csv")
    public ResponseEntity<String> exportAttendees(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        String csv = attendeeExport.toCsv(p, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"attendees-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=utf-8"))
                .body(csv);
    }
}
```

- [ ] **Step 6: Run the whole new suite + a broad regression check**

Run: `./mvnw test -Dtest=FunnelEventRepositoryTest,FunnelTrackingServiceTest,FunnelTrackingControllerTest,TicketAggregateQueryTest,SalesDashboardServiceTest,AttendeeExportServiceTest`
Expected: PASS (all).

Then run the full suite once: `./mvnw test`
Expected: PASS (no regressions).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/event/AttendeeExportService.java \
        src/main/java/com/imin/iminapi/controller/event/SalesDashboardController.java \
        src/test/java/com/imin/iminapi/service/event/AttendeeExportServiceTest.java
git commit -m "feat(api): GET /events/{id}/attendees/export CSV"
```

---

# Phase B — imin-webapp

> Run all commands from `/Users/ivan/imin/imin-webapp`. Test command: `npm test`. **Backend (Phase A) must be merged + deployed before `npm run api:sync`** — `api:fetch` curls the production OpenAPI URL, so do not run api:sync as part of this phase; hand-write the types below now and reconcile after the backend ships.

- [ ] **Step 0: Create the branch**

```bash
cd /Users/ivan/imin/imin-webapp && git checkout -b feat/event-sales-dashboard
```

## Task B1: Types + blob fetch helper

**Files:**
- Modify: `src/shared/api/types.ts`
- Modify: `src/shared/api/client.ts`

- [ ] **Step 1: Add dashboard types**

Append to `src/shared/api/types.ts`:

```typescript
// --- Per-event live sales dashboard (GET /events/:id/sales/live) ---
// All *Pct fields are 0–100.

export interface SalesDashboardTiles {
  ticketsSold: number;
  netRevenueMinor: number;
  grossRevenueMinor: number;
  capacity: number;
  capacityPct: number;
  checkedIn: number;
  checkInRatePct: number;
}

export interface SalesTierBreakdown {
  tierId: string;
  name: string;
  sold: number;
  redeemed: number;
  grossRevenueMinor: number;
  quantity: number;
  sellThroughPct: number;
}

export interface SalesFunnelStage {
  stage: 'PAGE_VIEW' | 'CHECKOUT_START' | 'PAYMENTS_COMPLETED';
  count: number;
}

export interface SalesFunnelDropOff {
  from: string;
  to: string;
  lostCount: number;
  lostPct: number;
}

export interface SalesDashboard {
  currency: string;
  tiles: SalesDashboardTiles;
  tiers: SalesTierBreakdown[];
  topConvertingTiers: SalesTierBreakdown[];
  funnel: {
    stages: SalesFunnelStage[];
    dropOff: SalesFunnelDropOff[];
  };
}
```

- [ ] **Step 2: Add `apiFetchBlob` to the client**

In `src/shared/api/client.ts`, add this exported function after `apiFetch` (it reuses the module-private `API_BASE`, `getToken`, `clearToken`, `readError`, and `ApiError` already used by `apiFetch`):

```typescript
/**
 * Authenticated GET that returns a Blob (for file downloads like CSV export).
 * `apiFetch` JSON-parses the body, so it can't be used for non-JSON responses.
 * Mirrors apiFetch's 401 handling.
 */
export async function apiFetchBlob(
  path: string,
  options: { signal?: AbortSignal } = {},
): Promise<Blob> {
  const token = getToken();
  const headers: Record<string, string> = {};
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(API_BASE + path, { method: 'GET', headers, signal: options.signal });

  if (res.status === 401) {
    clearToken();
    if (!window.location.pathname.startsWith('/auth')) {
      window.location.href = `/auth/login?expired=1&next=${encodeURIComponent(window.location.pathname)}`;
    }
    throw new ApiError(401, 'AUTH_TOKEN_EXPIRED', 'Authentication required');
  }
  if (!res.ok) {
    const err = await readError(res);
    throw new ApiError(res.status, err.code || 'INTERNAL', err.message || res.statusText);
  }
  return res.blob();
}
```

> If `readError` is not in scope at that location, place the function below `readError`'s definition. Confirm `ApiError`, `getToken`, `clearToken`, `API_BASE`, and `readError` are all defined in this file (they are — `apiFetch` uses each).

- [ ] **Step 3: Type-check**

Run: `npx tsc --noEmit`
Expected: no new errors.

- [ ] **Step 4: Commit**

```bash
git add src/shared/api/types.ts src/shared/api/client.ts
git commit -m "feat(webapp): sales dashboard types + apiFetchBlob helper"
```

## Task B2: Polling query hook

**Files:**
- Create: `src/features/events/useSalesDashboard.ts`

- [ ] **Step 1: Write the hook**

Create `src/features/events/useSalesDashboard.ts`:

```typescript
import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '../../shared/api/client';
import type { SalesDashboard } from '../../shared/api/types';

/**
 * Live per-event sales dashboard. Polls every 5s so tiles reflect a purchase or
 * check-in "within seconds"; pauses while the tab is hidden to avoid background
 * churn.
 */
export function useSalesDashboard(eventId: string) {
  return useQuery<SalesDashboard>({
    queryKey: ['events', eventId, 'sales-live'],
    queryFn: () => apiFetch<SalesDashboard>(`/events/${eventId}/sales/live`),
    refetchInterval: 5000,
    refetchIntervalInBackground: false,
  });
}
```

- [ ] **Step 2: Commit**

```bash
git add src/features/events/useSalesDashboard.ts
git commit -m "feat(webapp): useSalesDashboard polling hook"
```

## Task B3: Rebuild EventAnalyticsTab

**Files:**
- Modify: `src/features/events/EventAnalyticsTab.tsx` (full rewrite)
- Test: `src/features/events/EventAnalyticsTab.test.tsx`

- [ ] **Step 1: Rewrite the tab**

Replace the entire contents of `src/features/events/EventAnalyticsTab.tsx` with:

```typescript
import { useQueryClient } from '@tanstack/react-query';
import {
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
} from 'recharts';
import { Button, Card, FunnelChart, type FunnelItem, MetricCard, TabSkeleton, EmptyState } from '../../shared/ui';
import { useChartColors } from '../../shared/theme/useChartColors';
import { apiFetchBlob } from '../../shared/api/client';
import { formatMoney } from '../../shared/lib/format';
import type { Event, SalesDashboard, SalesTierBreakdown } from '../../shared/api/types';
import { useSalesDashboard } from './useSalesDashboard';
import { useState } from 'react';

type Props = {
  eventId: string;
  event: Event;
};

function tierPalette(colors: ReturnType<typeof useChartColors>): string[] {
  return [colors.accent, colors.purple, colors.green, colors.amber, colors.accent2, colors.red];
}

export function EventAnalyticsTab({ eventId, event }: Props) {
  const { data, isLoading, isError, refetch, isFetching } = useSalesDashboard(eventId);
  const colors = useChartColors();
  const qc = useQueryClient();
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<string | null>(null);

  if (isLoading) return <TabSkeleton />;

  if (isError || !data) {
    return (
      <Card title="Sales">
        <EmptyState
          title="Couldn't load sales data"
          action={
            <Button variant="secondary" loading={isFetching} onClick={() => refetch()}>
              Try again
            </Button>
          }
        />
      </Card>
    );
  }

  const onExport = async () => {
    setExporting(true);
    setExportError(null);
    try {
      const blob = await apiFetchBlob(`/events/${eventId}/attendees/export`);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `attendees-${event.slug}.csv`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch {
      setExportError('Export failed. Try again.');
    } finally {
      setExporting(false);
    }
  };

  const palette = tierPalette(colors);
  const donutData = data.tiers
    .filter((t) => t.sold > 0)
    .map((t) => ({ name: t.name, value: t.sold }));

  const funnelItems = toFunnelItems(data);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontSize: 12, color: colors.text3, fontFamily: 'var(--mono)' }}>
          {isFetching ? 'Updating…' : 'Live · updates every 5s'}
        </span>
        <Button variant="secondary" loading={exporting} onClick={onExport}>
          Export attendees (CSV)
        </Button>
      </div>
      {exportError && <div style={{ color: colors.red, fontSize: 13 }}>{exportError}</div>}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
        <MetricCard label="Tickets sold" value={data.tiles.ticketsSold.toLocaleString()} />
        <MetricCard label="Net revenue" value={formatMoney(data.tiles.netRevenueMinor, data.currency)} />
        <MetricCard
          label="Capacity"
          value={`${data.tiles.capacityPct.toFixed(0)}%`}
          sub={`${data.tiles.ticketsSold} / ${data.tiles.capacity}`}
        />
        <MetricCard
          label="Check-in rate"
          value={`${data.tiles.checkInRatePct.toFixed(0)}%`}
          sub={`${data.tiles.checkedIn} checked in`}
        />
      </div>

      <Card title="Tickets by tier">
        <div style={{ display: 'grid', gridTemplateColumns: '220px 1fr', gap: 24, alignItems: 'center' }}>
          <div style={{ width: '100%', height: 200 }}>
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie data={donutData} dataKey="value" nameKey="name" innerRadius={55} outerRadius={85} paddingAngle={2}>
                  {donutData.map((_, i) => (
                    <Cell key={i} fill={palette[i % palette.length]} />
                  ))}
                </Pie>
                <Tooltip
                  contentStyle={{
                    background: colors.surface,
                    border: `1px solid ${colors.border}`,
                    borderRadius: 8,
                    fontSize: 12,
                    color: colors.text,
                  }}
                />
              </PieChart>
            </ResponsiveContainer>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {data.tiers.map((t, i) => (
              <TierRow key={t.tierId} tier={t} color={palette[i % palette.length]} currency={data.currency} />
            ))}
          </div>
        </div>
      </Card>

      <Card title="Top-converting tiers">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {data.topConvertingTiers.map((t) => (
            <div key={t.tierId} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 14 }}>
              <span>{t.name}</span>
              <span style={{ fontFamily: 'var(--mono)', color: colors.text2 }}>
                {t.sold}/{t.quantity} · {t.sellThroughPct.toFixed(0)}%
              </span>
            </div>
          ))}
        </div>
      </Card>

      <Card title="Conversion funnel">
        <FunnelChart items={funnelItems} />
      </Card>
    </div>
  );
}

function TierRow({ tier, color, currency }: { tier: SalesTierBreakdown; color: string; currency: string }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 14 }}>
      <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ width: 10, height: 10, borderRadius: 2, background: color, display: 'inline-block' }} />
        {tier.name}
      </span>
      <span style={{ fontFamily: 'var(--mono)' }}>
        {tier.sold} · {formatMoney(tier.grossRevenueMinor, currency)}
      </span>
    </div>
  );
}

function toFunnelItems(data: SalesDashboard): FunnelItem[] {
  const labels: Record<string, string> = {
    PAGE_VIEW: 'Page views',
    CHECKOUT_START: 'Checkout starts',
    PAYMENTS_COMPLETED: 'Payments completed',
  };
  const top = data.funnel.stages[0]?.count ?? 0;
  return data.funnel.stages.map((s, i) => {
    const pct = top > 0 ? Math.round((s.count / top) * 100) : 0;
    const drop = data.funnel.dropOff[i - 1];
    const dropText = drop ? ` · −${drop.lostPct.toFixed(0)}%` : '';
    return {
      label: labels[s.stage] ?? s.stage,
      value: s.count,
      meta: `${s.count.toLocaleString()} · ${pct}%${dropText}`,
      shade: (Math.min(i, 3)) as 0 | 1 | 2 | 3,
    };
  });
}
```

> If `MetricCard` is not re-exported from `../../shared/ui` (the barrel), import it directly: `import { MetricCard } from '../../shared/ui/MetricCard/MetricCard';`. Confirm via `grep -n "MetricCard" src/shared/ui/index.ts`. Same check for `FunnelChart`/`FunnelItem` (the old stub imported them from `../../shared/ui`, so they are exported there).

- [ ] **Step 2: Write the render test**

Create `src/features/events/EventAnalyticsTab.test.tsx`:

```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { Event, SalesDashboard } from '../../shared/api/types';
import { EventAnalyticsTab } from './EventAnalyticsTab';

const snapshot: SalesDashboard = {
  currency: 'EUR',
  tiles: {
    ticketsSold: 12,
    netRevenueMinor: 800000,
    grossRevenueMinor: 800000,
    capacity: 120,
    capacityPct: 10,
    checkedIn: 3,
    checkInRatePct: 25,
  },
  tiers: [
    { tierId: 't1', name: 'GA', sold: 10, redeemed: 3, grossRevenueMinor: 150000, quantity: 100, sellThroughPct: 10 },
    { tierId: 't2', name: 'VIP', sold: 2, redeemed: 0, grossRevenueMinor: 650000, quantity: 20, sellThroughPct: 10 },
  ],
  topConvertingTiers: [
    { tierId: 't2', name: 'VIP', sold: 2, redeemed: 0, grossRevenueMinor: 650000, quantity: 20, sellThroughPct: 10 },
    { tierId: 't1', name: 'GA', sold: 10, redeemed: 3, grossRevenueMinor: 150000, quantity: 100, sellThroughPct: 10 },
  ],
  funnel: {
    stages: [
      { stage: 'PAGE_VIEW', count: 100 },
      { stage: 'CHECKOUT_START', count: 30 },
      { stage: 'PAYMENTS_COMPLETED', count: 12 },
    ],
    dropOff: [
      { from: 'PAGE_VIEW', to: 'CHECKOUT_START', lostCount: 70, lostPct: 70 },
      { from: 'CHECKOUT_START', to: 'PAYMENTS_COMPLETED', lostCount: 18, lostPct: 60 },
    ],
  },
};

vi.mock('../../shared/api/client', () => ({
  apiFetch: vi.fn(() => Promise.resolve(snapshot)),
  apiFetchBlob: vi.fn(() => Promise.resolve(new Blob())),
}));

const event = { id: 'e1', slug: 'night', status: 'live' } as unknown as Event;

function renderTab() {
  const qc = new QueryClient();
  return render(
    <QueryClientProvider client={qc}>
      <EventAnalyticsTab eventId="e1" event={event} />
    </QueryClientProvider>,
  );
}

describe('EventAnalyticsTab', () => {
  beforeEach(() => vi.clearAllMocks());

  it('renders the four tiles and the funnel from a snapshot', async () => {
    renderTab();
    expect(await screen.findByText('Tickets sold')).toBeInTheDocument();
    expect(screen.getByText('Net revenue')).toBeInTheDocument();
    expect(screen.getByText('Check-in rate')).toBeInTheDocument();
    expect(screen.getByText('Page views')).toBeInTheDocument();
    expect(screen.getByText('Export attendees (CSV)')).toBeInTheDocument();
  });
});
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `npm test -- EventAnalyticsTab`
Expected: PASS.

- [ ] **Step 4: Type-check + commit**

Run: `npx tsc --noEmit`
Expected: no new errors.

```bash
git add src/features/events/EventAnalyticsTab.tsx src/features/events/EventAnalyticsTab.test.tsx
git commit -m "feat(webapp): rebuild EventAnalyticsTab as live sales dashboard"
```

> **After the backend is deployed:** run `npm run api:sync` and reconcile `src/shared/api/types.ts` (the hand-written types above) against the regenerated `generated-types.ts`. Field names must match the Java records exactly.

---

# Phase C — imin-public

> Run all commands from `/Users/ivan/imin/imin-public`. There is no test runner here — verify by `npm run build` and manual check in the browser network tab. `event.id` (UUID) is available in both target components.

- [ ] **Step 0: Create the branch**

```bash
cd /Users/ivan/imin/imin-public && git checkout -b feat/event-sales-dashboard
```

## Task C1: Funnel-tracking beacon util

**Files:**
- Create: `lib/funnel-tracking.ts`

- [ ] **Step 1: Write the util**

Create `lib/funnel-tracking.ts`:

```typescript
// Fire-and-forget funnel beacons for the buyer site. Posts to the public
// /track endpoint via sendBeacon (survives navigation), falling back to a
// keepalive fetch. Best-effort: every failure mode is a silent no-op so it can
// never block or break the buyer flow.

const API_BASE = process.env.NEXT_PUBLIC_API_BASE;

const ANON_KEY = 'imin.anon';
const PAGE_VIEW_PREFIX = 'imin.fv.'; // per-event page-view dedup flag

type Stage = 'PAGE_VIEW' | 'CHECKOUT_START';

function getAnonId(): string | null {
  try {
    let id = sessionStorage.getItem(ANON_KEY);
    if (!id) {
      id = crypto.randomUUID();
      sessionStorage.setItem(ANON_KEY, id);
    }
    return id;
  } catch {
    return null; // private mode / storage disabled → skip tracking
  }
}

function post(eventId: string, stage: Stage, anonId: string): void {
  if (!API_BASE) return;
  const url = `${API_BASE}/api/v1/public/events/${eventId}/track`;
  const payload = JSON.stringify({ stage, anonId });
  try {
    if (typeof navigator !== 'undefined' && navigator.sendBeacon) {
      navigator.sendBeacon(url, new Blob([payload], { type: 'application/json' }));
      return;
    }
  } catch {
    // fall through to fetch
  }
  try {
    void fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: payload,
      keepalive: true,
    });
  } catch {
    // no-op
  }
}

/** Fire once per browser session per event. */
export function trackPageView(eventId: string): void {
  const anonId = getAnonId();
  if (!anonId) return;
  try {
    const flag = PAGE_VIEW_PREFIX + eventId;
    if (sessionStorage.getItem(flag)) return;
    sessionStorage.setItem(flag, '1');
  } catch {
    // if we can't dedup, still send once per call
  }
  post(eventId, 'PAGE_VIEW', anonId);
}

/** Fire on the buy/checkout click, immediately before redirecting to Stripe. */
export function trackCheckoutStart(eventId: string): void {
  const anonId = getAnonId();
  if (!anonId) return;
  post(eventId, 'CHECKOUT_START', anonId);
}
```

- [ ] **Step 2: Commit**

```bash
git add lib/funnel-tracking.ts
git commit -m "feat(public): funnel-tracking beacon util"
```

## Task C2: Fire PAGE_VIEW on the event-detail page

**Files:**
- Modify: `components/buyer/event-detail.tsx`

- [ ] **Step 1: Add the import**

At the top of `components/buyer/event-detail.tsx`, with the other imports, add:

```typescript
import { trackPageView } from "@/lib/funnel-tracking";
```

Ensure `useEffect` is in the existing `react` import (it already imports `useEffect`).

- [ ] **Step 2: Fire the beacon on mount**

Inside the `EventDetail` component body, near the existing hooks (after `const [videoOpen, setVideoOpen] = useState(false);`), add:

```typescript
  useEffect(() => {
    trackPageView(event.id);
  }, [event.id]);
```

- [ ] **Step 3: Build to verify it compiles**

Run: `npm run build`
Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add components/buyer/event-detail.tsx
git commit -m "feat(public): fire PAGE_VIEW funnel beacon on event detail"
```

## Task C3: Fire CHECKOUT_START on the buy click

**Files:**
- Modify: `components/buyer/buy-modal.tsx`

- [ ] **Step 1: Add the import**

At the top of `components/buyer/buy-modal.tsx`, with the other imports, add:

```typescript
import { trackCheckoutStart } from "@/lib/funnel-tracking";
```

- [ ] **Step 2: Fire the beacon right before the redirect**

In `handleContinue`, immediately before `window.location.assign(url);`, add the beacon call:

```typescript
      trackCheckoutStart(event.id);
      // Full-page redirect. For paid flow → Stripe-hosted checkout (different
      // origin); for free flow → `/order/{token}` on this host.
      window.location.assign(url);
```

> Place it after the `await createCheckoutSession(...)` resolves (so we only count real checkout starts that produced a session URL) and before the redirect.

- [ ] **Step 3: Build to verify it compiles**

Run: `npm run build`
Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add components/buyer/buy-modal.tsx
git commit -m "feat(public): fire CHECKOUT_START funnel beacon before redirect"
```

## Task C4: Document the track endpoint

**Files:**
- Modify: `docs/PUBLIC_PAGE_API.md`

- [ ] **Step 1: Add the endpoint to the index table**

In `docs/PUBLIC_PAGE_API.md`, add this row to the "Endpoints:" table (after the `/quote` row):

```markdown
| `POST /api/v1/public/events/{id}/track` — funnel beacon (page view / checkout start) | §13 (Track) |
```

- [ ] **Step 2: Add the section**

At the end of the document, add:

```markdown
## 13. Track (funnel beacon)

```
POST /api/v1/public/events/{id}/track
```

Fire-and-forget instrumentation for the per-event conversion funnel. **No auth.**
The buyer site posts this from the event-detail page (`PAGE_VIEW`, once per
browser session per event) and on the buy/checkout click (`CHECKOUT_START`,
immediately before the Stripe redirect). Sent via `navigator.sendBeacon`.

### 13.1 Request

```http
POST /api/v1/public/events/8c3a91f0-2b54-4e4e-b1d2-9d3c5b7e4f01/track HTTP/1.1
Host: api.imin.wtf
Content-Type: application/json

{
  "stage": "PAGE_VIEW",
  "anonId": "f1d2c3b4-..."
}
```

| Field | Type | Notes |
|---|---|---|
| `stage` | string | `PAGE_VIEW` or `CHECKOUT_START`. Any other value is a silent no-op. |
| `anonId` | string, ≤ 64 chars | Per-session id from the buyer's `sessionStorage`. Counts are `COUNT(DISTINCT anonId)` per stage. |

### 13.2 Response

`204 No Content` — always, including for unknown / non-public events (no-leak,
same stance as the detail endpoint's §5). The body is best-effort; malformed
input is recorded as nothing.

### 13.3 Notes

The `PAYMENTS_COMPLETED` funnel stage is **not** posted here — the backend
derives it from completed orders. Only the two top stages are client-instrumented.
```

- [ ] **Step 3: Commit**

```bash
git add docs/PUBLIC_PAGE_API.md
git commit -m "docs(public): document /public/events/{id}/track funnel beacon"
```

---

# Final verification (all repos)

- [ ] **imin-api:** `./mvnw test` → all green.
- [ ] **imin-webapp:** `npm test` and `npx tsc --noEmit` → green.
- [ ] **imin-public:** `npm run build` → succeeds.
- [ ] **Sequencing reminder:** merge + deploy `imin-api` first, then run `npm run api:sync` in `imin-webapp` and reconcile types, then ship `imin-webapp` and `imin-public`. Until the public beacons ship, the funnel's two upper stages read 0 and the tab still renders (tiles, tiers, payments stage) without error.

# Self-review notes (addressed)

- **Spec coverage:** Pillar 1 (live tiles) → A4 + B3 (5s poll). Pillar 2 (tier donut/stacked + top-converting + reconciliation) → A3/A4 + B3, invariant tested in A4. Pillar 3 (funnel, per-stage instrumentation + drop-off) → A1/A2 (instrumentation), A4 (counting + drop-off), C1–C3 (beacons). Pillar 4 (CSV with tier + check-in, matches counts) → A5, downloaded in B3.
- **Verification notes called out inline (not errors):** the `Times` import-path check in A1, the `@CurrentUser` import-path check in A4, and the `MetricCard`/`FunnelChart` barrel-export check in B3 — each tells the implementer to confirm an import against an existing file before relying on it.
- **Type consistency:** Java records (`SalesDashboardResponse.*`) ↔ TS interfaces (`SalesDashboard*`) use identical field names and the 0–100 `*Pct` convention end to end.
