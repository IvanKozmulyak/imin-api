# Events Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Depends on:** `2026-04-23-foundation.md` and `2026-04-23-auth.md`.

**Goal:** Implement the V1 Events API surface — list, create-draft, detail, autosave PATCH, publish, overview, soft-delete pruning. Endpoints exposed under `/api/v1/events`. Matches contract §5 and §10 `Event`, `TicketTier`, `PromoCode`, `Prediction`.

**Architecture:** A new `Event` JPA entity (separate from the existing `GeneratedEvent` AI staging table). Tier and PromoCode entities are read-side only in V1 — the FE doesn't yet PUT them, but they appear inside `GET /events/:id`. `Prediction` is a stub that returns `null` in V1 — the contract permits `prediction: null`. An hourly `@Scheduled` job purges empty drafts (`name=''` AND `created_at < now()-24h`). Soft-delete uses `deleted_at`; list/detail filter `deleted_at IS NULL`. Autosave uses the `If-Match` middleware. Publish uses the `Idempotency-Key` middleware and runs full required-field validation, returning `422 PUBLISH_VALIDATION_FAILED` with per-field details on failure.

**Tech Stack:** Java 17, Spring Boot 4.0.5, Spring Data JPA + Flyway, Spring MVC, JUnit 5, MockMvc, Mockito.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `src/main/resources/db/migration/V6__events.sql` | Create | events, ticket_tiers, promo_codes, predictions tables |
| `src/main/java/com/imin/iminapi/model/Event.java` | Create | JPA entity |
| `src/main/java/com/imin/iminapi/model/EventStatus.java` | Create | enum DRAFT/LIVE/PAST/CANCELLED |
| `src/main/java/com/imin/iminapi/model/EventVisibility.java` | Create | enum PUBLIC/PRIVATE |
| `src/main/java/com/imin/iminapi/model/TicketTier.java` | Create | JPA entity |
| `src/main/java/com/imin/iminapi/model/TicketTierKind.java` | Create | enum |
| `src/main/java/com/imin/iminapi/model/PromoCode.java` | Create | JPA entity |
| `src/main/java/com/imin/iminapi/model/Prediction.java` | Create | JPA entity (V1 always-null in API) |
| `src/main/java/com/imin/iminapi/repository/EventRepository.java` | Create | filtered/paginated queries |
| `src/main/java/com/imin/iminapi/repository/TicketTierRepository.java` | Create | |
| `src/main/java/com/imin/iminapi/repository/PromoCodeRepository.java` | Create | |
| `src/main/java/com/imin/iminapi/dto/event/VenueDto.java` | Create | record |
| `src/main/java/com/imin/iminapi/dto/event/TicketTierDto.java` | Create | record |
| `src/main/java/com/imin/iminapi/dto/event/PromoCodeDto.java` | Create | record |
| `src/main/java/com/imin/iminapi/dto/event/EventDto.java` | Create | full + summary mappers |
| `src/main/java/com/imin/iminapi/dto/event/EventPatchRequest.java` | Create | partial-update body |
| `src/main/java/com/imin/iminapi/dto/event/EventOverviewResponse.java` | Create | overview shape |
| `src/main/java/com/imin/iminapi/dto/PageResponse.java` | Create | reusable pagination envelope |
| `src/main/java/com/imin/iminapi/service/event/EventService.java` | Create | CRUD + publish |
| `src/main/java/com/imin/iminapi/service/event/EventValidator.java` | Create | publish-time required-field checks |
| `src/main/java/com/imin/iminapi/service/event/EventOverviewService.java` | Create | overview aggregator |
| `src/main/java/com/imin/iminapi/service/event/DraftPruner.java` | Create | hourly @Scheduled cleanup |
| `src/main/java/com/imin/iminapi/controller/event/EventController.java` | Create | endpoints |
| `src/test/java/com/imin/iminapi/service/event/EventServiceTest.java` | Create | unit tests |
| `src/test/java/com/imin/iminapi/service/event/EventValidatorTest.java` | Create | unit tests |
| `src/test/java/com/imin/iminapi/service/event/DraftPrunerTest.java` | Create | repo test |
| `src/test/java/com/imin/iminapi/controller/event/EventControllerTest.java` | Create | MockMvc |

---

## Task 1: Flyway V6 — events, tiers, promos, predictions

**Files:**
- Create: `src/main/resources/db/migration/V6__events.sql`

- [ ] **Step 1: Write the migration**

```sql
CREATE TABLE events (
    id                  UUID         PRIMARY KEY,
    org_id              UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name                VARCHAR(255) NOT NULL DEFAULT '',
    slug                VARCHAR(255) NOT NULL,
    visibility          VARCHAR(16)  NOT NULL DEFAULT 'public',
    status              VARCHAR(16)  NOT NULL DEFAULT 'draft',
    genre               VARCHAR(64)  NOT NULL DEFAULT '',
    type                VARCHAR(64)  NOT NULL DEFAULT '',
    starts_at           TIMESTAMP,
    ends_at             TIMESTAMP,
    timezone            VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    venue_name          VARCHAR(255),
    venue_street        VARCHAR(255) NOT NULL DEFAULT '',
    venue_city          VARCHAR(255) NOT NULL DEFAULT '',
    venue_postal_code   VARCHAR(32)  NOT NULL DEFAULT '',
    venue_country       VARCHAR(2),
    description         TEXT         NOT NULL DEFAULT '',
    poster_url          TEXT,
    video_url           TEXT,
    cover_url           TEXT,
    capacity            INTEGER      NOT NULL DEFAULT 0,
    sold                INTEGER      NOT NULL DEFAULT 0,
    revenue_minor       BIGINT       NOT NULL DEFAULT 0,
    currency            VARCHAR(3)   NOT NULL DEFAULT 'EUR',
    squads_enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    min_squad_size      INTEGER      NOT NULL DEFAULT 3,
    squad_discount_pct  INTEGER      NOT NULL DEFAULT 0,
    on_sale_at          TIMESTAMP,
    sale_closes_at      TIMESTAMP,
    created_by          UUID         NOT NULL REFERENCES users (id),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at        TIMESTAMP,
    deleted_at          TIMESTAMP,
    CONSTRAINT uq_events_org_slug UNIQUE (org_id, slug)
);
CREATE INDEX ix_events_org_status ON events (org_id, status);
CREATE INDEX ix_events_org_starts_at ON events (org_id, starts_at);
CREATE INDEX ix_events_deleted ON events (deleted_at);

CREATE TABLE ticket_tiers (
    id                  UUID         PRIMARY KEY,
    event_id            UUID         NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name                VARCHAR(128) NOT NULL,
    kind                VARCHAR(32)  NOT NULL,
    price_minor         INTEGER      NOT NULL,
    quantity            INTEGER      NOT NULL,
    sold                INTEGER      NOT NULL DEFAULT 0,
    sale_closes_at      TIMESTAMP,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order          INTEGER      NOT NULL DEFAULT 0
);
CREATE INDEX ix_tiers_event ON ticket_tiers (event_id);

CREATE TABLE promo_codes (
    id                  UUID         PRIMARY KEY,
    event_id            UUID         NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    code                VARCHAR(64)  NOT NULL,
    discount_pct        INTEGER      NOT NULL,
    max_uses            INTEGER      NOT NULL,
    used_count          INTEGER      NOT NULL DEFAULT 0,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_promo_event_code UNIQUE (event_id, code)
);

CREATE TABLE predictions (
    event_id            UUID         PRIMARY KEY REFERENCES events (id) ON DELETE CASCADE,
    score               INTEGER      NOT NULL,
    range_low           INTEGER      NOT NULL,
    range_high          INTEGER      NOT NULL,
    confidence_pct      INTEGER      NOT NULL,
    insight             TEXT         NOT NULL,
    model_version       VARCHAR(64)  NOT NULL,
    computed_at         TIMESTAMP    NOT NULL
);
```

- [ ] **Step 2: Apply and verify**

Run: `./mvnw -q -DskipTests spring-boot:run` (Ctrl-C after Flyway logs)
Expected: `Migrating schema "public" to version "6 - events"`.

- [ ] **Step 3: Confirm tests still pass**

Run: `./mvnw -q test`
Expected: green.

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V6__events.sql
git commit -m "db: V6 events / ticket_tiers / promo_codes / predictions"
```

---

## Task 2: Enums + JPA entities

**Files:**
- Create: `src/main/java/com/imin/iminapi/model/EventStatus.java`
- Create: `src/main/java/com/imin/iminapi/model/EventVisibility.java`
- Create: `src/main/java/com/imin/iminapi/model/TicketTierKind.java`
- Create: `src/main/java/com/imin/iminapi/model/Event.java`
- Create: `src/main/java/com/imin/iminapi/model/TicketTier.java`
- Create: `src/main/java/com/imin/iminapi/model/PromoCode.java`
- Create: `src/main/java/com/imin/iminapi/model/Prediction.java`

- [ ] **Step 1: Create EventStatus**

```java
package com.imin.iminapi.model;

public enum EventStatus {
    DRAFT, LIVE, PAST, CANCELLED;
    public String wireValue() { return name().toLowerCase(); }
    public static EventStatus fromWire(String s) { return valueOf(s.toUpperCase()); }
}
```

- [ ] **Step 2: Create EventVisibility**

```java
package com.imin.iminapi.model;

public enum EventVisibility {
    PUBLIC, PRIVATE;
    public String wireValue() { return name().toLowerCase(); }
    public static EventVisibility fromWire(String s) { return valueOf(s.toUpperCase()); }
}
```

- [ ] **Step 3: Create TicketTierKind**

```java
package com.imin.iminapi.model;

public enum TicketTierKind {
    EARLY_BIRD, STANDARD, LATE_BIRD, CUSTOM;
    public String wireValue() {
        return switch (this) {
            case EARLY_BIRD -> "earlyBird";
            case STANDARD -> "standard";
            case LATE_BIRD -> "lateBird";
            case CUSTOM -> "custom";
        };
    }
    public static TicketTierKind fromWire(String s) {
        return switch (s) {
            case "earlyBird" -> EARLY_BIRD;
            case "standard" -> STANDARD;
            case "lateBird" -> LATE_BIRD;
            case "custom" -> CUSTOM;
            default -> throw new IllegalArgumentException("Unknown tier kind: " + s);
        };
    }
}
```

- [ ] **Step 4: Create Event entity**

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
@Getter
@Setter
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name = "";

    @Column(nullable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventVisibility visibility = EventVisibility.PUBLIC;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventStatus status = EventStatus.DRAFT;

    @Column(nullable = false)
    private String genre = "";

    @Column(nullable = false)
    private String type = "";

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(nullable = false)
    private String timezone = "UTC";

    @Column(name = "venue_name")
    private String venueName;
    @Column(name = "venue_street", nullable = false)
    private String venueStreet = "";
    @Column(name = "venue_city", nullable = false)
    private String venueCity = "";
    @Column(name = "venue_postal_code", nullable = false)
    private String venuePostalCode = "";
    @Column(name = "venue_country", length = 2)
    private String venueCountry;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description = "";

    @Column(name = "poster_url", columnDefinition = "TEXT")
    private String posterUrl;

    @Column(name = "video_url", columnDefinition = "TEXT")
    private String videoUrl;

    @Column(name = "cover_url", columnDefinition = "TEXT")
    private String coverUrl;

    @Column(nullable = false)
    private int capacity = 0;

    @Column(nullable = false)
    private int sold = 0;

    @Column(name = "revenue_minor", nullable = false)
    private long revenueMinor = 0;

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    @Column(name = "squads_enabled", nullable = false)
    private boolean squadsEnabled = false;

    @Column(name = "min_squad_size", nullable = false)
    private int minSquadSize = 3;

    @Column(name = "squad_discount_pct", nullable = false)
    private int squadDiscountPct = 0;

    @Column(name = "on_sale_at")
    private Instant onSaleAt;

    @Column(name = "sale_closes_at")
    private Instant saleClosesAt;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }
}
```

- [ ] **Step 5: Create TicketTier entity**

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket_tiers")
@Getter
@Setter
public class TicketTier {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TicketTierKind kind;

    @Column(name = "price_minor", nullable = false)
    private int priceMinor;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int sold = 0;

    @Column(name = "sale_closes_at")
    private Instant saleClosesAt;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
```

- [ ] **Step 6: Create PromoCode entity**

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "promo_codes")
@Getter
@Setter
public class PromoCode {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(name = "discount_pct", nullable = false)
    private int discountPct;

    @Column(name = "max_uses", nullable = false)
    private int maxUses;

    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Column(nullable = false)
    private boolean enabled = true;
}
```

- [ ] **Step 7: Create Prediction entity**

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "predictions")
@Getter
@Setter
public class Prediction {
    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(nullable = false)
    private int score;

    @Column(name = "range_low", nullable = false)
    private int rangeLow;

    @Column(name = "range_high", nullable = false)
    private int rangeHigh;

    @Column(name = "confidence_pct", nullable = false)
    private int confidencePct;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String insight;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;
}
```

- [ ] **Step 8: Compile**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/imin/iminapi/model/Event.java src/main/java/com/imin/iminapi/model/EventStatus.java src/main/java/com/imin/iminapi/model/EventVisibility.java src/main/java/com/imin/iminapi/model/TicketTier.java src/main/java/com/imin/iminapi/model/TicketTierKind.java src/main/java/com/imin/iminapi/model/PromoCode.java src/main/java/com/imin/iminapi/model/Prediction.java
git commit -m "model: Event, TicketTier, PromoCode, Prediction entities + enums"
```

---

## Task 3: Repositories

**Files:**
- Create: `src/main/java/com/imin/iminapi/repository/EventRepository.java`
- Create: `src/main/java/com/imin/iminapi/repository/TicketTierRepository.java`
- Create: `src/main/java/com/imin/iminapi/repository/PromoCodeRepository.java`
- Create: `src/main/java/com/imin/iminapi/repository/PredictionRepository.java`

- [ ] **Step 1: EventRepository**

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface EventRepository extends JpaRepository<Event, UUID> {

    @Query("SELECT e FROM Event e WHERE e.orgId = :orgId AND e.deletedAt IS NULL " +
           "AND (:status IS NULL OR e.status = :status) ORDER BY e.startsAt DESC NULLS LAST, e.createdAt DESC")
    Page<Event> findVisibleByOrg(@Param("orgId") UUID orgId, @Param("status") EventStatus status, Pageable page);

    @Query("SELECT e FROM Event e WHERE e.id = :id AND e.deletedAt IS NULL")
    Optional<Event> findActive(@Param("id") UUID id);

    @Modifying
    @Query("DELETE FROM Event e WHERE e.status = com.imin.iminapi.model.EventStatus.DRAFT " +
           "AND e.name = '' AND e.createdAt < :cutoff")
    int deleteEmptyDraftsOlderThan(@Param("cutoff") Instant cutoff);
}
```

- [ ] **Step 2: TicketTierRepository, PromoCodeRepository, PredictionRepository**

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.TicketTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface TicketTierRepository extends JpaRepository<TicketTier, UUID> {
    List<TicketTier> findByEventIdOrderBySortOrderAsc(UUID eventId);
}
```

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.PromoCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.List;
import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface PromoCodeRepository extends JpaRepository<PromoCode, UUID> {
    List<PromoCode> findByEventId(UUID eventId);
}
```

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.Prediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import java.util.UUID;

@RepositoryRestResource(exported = false)
public interface PredictionRepository extends JpaRepository<Prediction, UUID> {
}
```

- [ ] **Step 3: Compile + commit**

Run: `./mvnw -q -DskipTests compile`

```bash
git add src/main/java/com/imin/iminapi/repository/
git commit -m "repository: events, tiers, promos, predictions"
```

---

## Task 4: PageResponse + DTOs

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/PageResponse.java`
- Create: `src/main/java/com/imin/iminapi/dto/event/VenueDto.java`
- Create: `src/main/java/com/imin/iminapi/dto/event/TicketTierDto.java`
- Create: `src/main/java/com/imin/iminapi/dto/event/PromoCodeDto.java`
- Create: `src/main/java/com/imin/iminapi/dto/event/PredictionDto.java`
- Create: `src/main/java/com/imin/iminapi/dto/event/EventDto.java`
- Create: `src/main/java/com/imin/iminapi/dto/event/EventPatchRequest.java`

- [ ] **Step 1: PageResponse**

```java
package com.imin.iminapi.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> items, long total, int page, int pageSize) {
    public static <E, T> PageResponse<T> from(Page<E> source, Function<E, T> map) {
        return new PageResponse<>(source.map(map).getContent(),
                source.getTotalElements(), source.getNumber() + 1, source.getSize());
    }
}
```

- [ ] **Step 2: VenueDto**

```java
package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record VenueDto(String name, String street, String city, String postalCode, String country) {}
```

- [ ] **Step 3: TicketTierDto**

```java
package com.imin.iminapi.dto.event;

import com.imin.iminapi.model.TicketTier;

import java.time.Instant;
import java.util.UUID;

public record TicketTierDto(
        UUID id, UUID eventId, String name, String kind,
        int priceMinor, int quantity, int sold,
        Instant saleClosesAt, boolean enabled, int sortOrder) {
    public static TicketTierDto from(TicketTier t) {
        return new TicketTierDto(t.getId(), t.getEventId(), t.getName(), t.getKind().wireValue(),
                t.getPriceMinor(), t.getQuantity(), t.getSold(),
                t.getSaleClosesAt(), t.isEnabled(), t.getSortOrder());
    }
}
```

- [ ] **Step 4: PromoCodeDto**

```java
package com.imin.iminapi.dto.event;

import com.imin.iminapi.model.PromoCode;

import java.util.UUID;

public record PromoCodeDto(UUID id, UUID eventId, String code,
                           int discountPct, int maxUses, int usedCount, boolean enabled) {
    public static PromoCodeDto from(PromoCode p) {
        return new PromoCodeDto(p.getId(), p.getEventId(), p.getCode(),
                p.getDiscountPct(), p.getMaxUses(), p.getUsedCount(), p.isEnabled());
    }
}
```

- [ ] **Step 5: PredictionDto**

```java
package com.imin.iminapi.dto.event;

import com.imin.iminapi.model.Prediction;

import java.time.Instant;
import java.util.UUID;

public record PredictionDto(
        UUID eventId, int score, int rangeLow, int rangeHigh,
        int confidencePct, String insight, String modelVersion, Instant computedAt) {
    public static PredictionDto from(Prediction p) {
        return new PredictionDto(p.getEventId(), p.getScore(), p.getRangeLow(), p.getRangeHigh(),
                p.getConfidencePct(), p.getInsight(), p.getModelVersion(), p.getComputedAt());
    }
}
```

- [ ] **Step 6: EventDto (summary + full mappers)**

```java
package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.imin.iminapi.model.Event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventDto(
        UUID id, UUID orgId, String name, String slug,
        String visibility, String status, String genre, String type,
        Instant startsAt, Instant endsAt, String timezone, VenueDto venue,
        String description, String posterUrl, String videoUrl, String coverUrl,
        int capacity, int sold, long revenueMinor, String currency,
        boolean squadsEnabled, int minSquadSize, int squadDiscountPct,
        Instant onSaleAt, Instant saleClosesAt,
        UUID createdBy, Instant createdAt, Instant updatedAt,
        Instant publishedAt, Instant deletedAt,
        List<TicketTierDto> tiers, List<PromoCodeDto> promoCodes, PredictionDto prediction) {

    /** Summary form used by GET /events (no tiers/promos/prediction). */
    public static EventDto summary(Event e) {
        return new EventDto(e.getId(), e.getOrgId(), e.getName(), e.getSlug(),
                e.getVisibility().wireValue(), e.getStatus().wireValue(), e.getGenre(), e.getType(),
                e.getStartsAt(), e.getEndsAt(), e.getTimezone(), venue(e),
                e.getDescription(), e.getPosterUrl(), e.getVideoUrl(), e.getCoverUrl(),
                e.getCapacity(), e.getSold(), e.getRevenueMinor(), e.getCurrency(),
                e.isSquadsEnabled(), e.getMinSquadSize(), e.getSquadDiscountPct(),
                e.getOnSaleAt(), e.getSaleClosesAt(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(),
                e.getPublishedAt(), e.getDeletedAt(),
                null, null, null);
    }

    /** Detail form including tiers/promos/prediction (prediction may be null). */
    public static EventDto detail(Event e, List<TicketTierDto> tiers,
                                  List<PromoCodeDto> promos, PredictionDto prediction) {
        EventDto base = summary(e);
        return new EventDto(base.id, base.orgId, base.name, base.slug,
                base.visibility, base.status, base.genre, base.type,
                base.startsAt, base.endsAt, base.timezone, base.venue,
                base.description, base.posterUrl, base.videoUrl, base.coverUrl,
                base.capacity, base.sold, base.revenueMinor, base.currency,
                base.squadsEnabled, base.minSquadSize, base.squadDiscountPct,
                base.onSaleAt, base.saleClosesAt,
                base.createdBy, base.createdAt, base.updatedAt,
                base.publishedAt, base.deletedAt,
                tiers, promos, prediction);
    }

    private static VenueDto venue(Event e) {
        return new VenueDto(e.getVenueName(), e.getVenueStreet(), e.getVenueCity(),
                e.getVenuePostalCode(), e.getVenueCountry());
    }
}
```

- [ ] **Step 7: EventPatchRequest**

```java
package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Partial update body. All fields nullable; null = leave unchanged.
 * Server permits incomplete drafts and only validates on publish.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventPatchRequest(
        String name, String slug, String visibility, String genre, String type,
        Instant startsAt, Instant endsAt, String timezone, VenueDto venue,
        String description, String posterUrl, String videoUrl, String coverUrl,
        Integer capacity, String currency,
        Boolean squadsEnabled, Integer minSquadSize, Integer squadDiscountPct,
        Instant onSaleAt, Instant saleClosesAt
) {}
```

- [ ] **Step 8: Compile + commit**

Run: `./mvnw -q -DskipTests compile`

```bash
git add src/main/java/com/imin/iminapi/dto/PageResponse.java src/main/java/com/imin/iminapi/dto/event/
git commit -m "dto: page envelope + event/tier/promo/venue/prediction DTOs and patch request"
```

---

## Task 5: EventValidator (publish-time required-field check)

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/event/EventValidator.java`
- Create: `src/test/java/com/imin/iminapi/service/event/EventValidatorTest.java`

- [ ] **Step 1: Write the test**

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventValidatorTest {

    private final EventValidator sut = new EventValidator();

    private Event valid() {
        Event e = new Event();
        e.setName("Test Night");
        e.setSlug("test-night");
        e.setStartsAt(Instant.parse("2026-06-01T20:00:00Z"));
        e.setEndsAt(Instant.parse("2026-06-02T04:00:00Z"));
        e.setVenueStreet("12 Main St");
        e.setVenueCity("Berlin");
        e.setVenuePostalCode("10115");
        e.setDescription("Stuff happens.");
        e.setCapacity(100);
        return e;
    }

    @Test
    void valid_event_passes() {
        sut.validateForPublish(valid());
    }

    @Test
    void missing_name_yields_FIELD_ERROR() {
        Event e = valid(); e.setName("");
        assertThatThrownBy(() -> sut.validateForPublish(e))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PUBLISH_VALIDATION_FAILED)
                .extracting("fields").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("name");
    }

    @Test
    void multiple_missing_fields_collected_into_one_error() {
        Event e = valid();
        e.setName("");
        e.setStartsAt(null);
        e.setVenueCity("");
        try {
            sut.validateForPublish(e);
            assertThat(false).as("expected throw").isTrue();
        } catch (ApiException ex) {
            assertThat(ex.fields()).containsKeys("name", "startsAt", "venue.city");
        }
    }

    @Test
    void endsAt_before_startsAt_is_invalid() {
        Event e = valid();
        e.setEndsAt(e.getStartsAt().minusSeconds(60));
        assertThatThrownBy(() -> sut.validateForPublish(e))
                .extracting("fields").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("endsAt");
    }
}
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest=EventValidatorTest`
Expected: FAIL.

- [ ] **Step 3: Implement EventValidator**

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class EventValidator {

    public void validateForPublish(Event e) {
        Map<String, String> errors = new LinkedHashMap<>();

        if (isBlank(e.getName())) errors.put("name", "required");
        if (isBlank(e.getSlug())) errors.put("slug", "required");
        if (e.getStartsAt() == null) errors.put("startsAt", "required");
        if (e.getEndsAt() == null) errors.put("endsAt", "required");
        if (e.getStartsAt() != null && e.getEndsAt() != null && e.getEndsAt().isBefore(e.getStartsAt())) {
            errors.put("endsAt", "must be after startsAt");
        }
        if (isBlank(e.getVenueStreet())) errors.put("venue.street", "required");
        if (isBlank(e.getVenueCity())) errors.put("venue.city", "required");
        if (isBlank(e.getVenuePostalCode())) errors.put("venue.postalCode", "required");
        if (isBlank(e.getDescription())) errors.put("description", "required");
        if (e.getCapacity() <= 0) errors.put("capacity", "must be > 0");
        if (e.getDescription() != null && e.getDescription().length() > 2000) {
            errors.put("description", "≤ 2000 chars");
        }

        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    ErrorCode.PUBLISH_VALIDATION_FAILED,
                    "Event missing required fields", errors);
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
```

- [ ] **Step 4: Re-run, expect pass**

Run: `./mvnw -q test -Dtest=EventValidatorTest`
Expected: 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/event/EventValidator.java src/test/java/com/imin/iminapi/service/event/EventValidatorTest.java
git commit -m "events: EventValidator for publish-time required fields"
```

---

## Task 6: EventService — create / list / detail

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/event/EventService.java`
- Create: `src/test/java/com/imin/iminapi/service/event/EventServiceTest.java`

- [ ] **Step 1: Write the test for create + list + detail**

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.dto.event.EventPatchRequest;
import com.imin.iminapi.dto.event.VenueDto;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.web.IfMatchSupport;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EventServiceTest {

    EventRepository events = mock(EventRepository.class);
    TicketTierRepository tiers = mock(TicketTierRepository.class);
    PromoCodeRepository promos = mock(PromoCodeRepository.class);
    PredictionRepository predictions = mock(PredictionRepository.class);
    IfMatchSupport ifMatch = new IfMatchSupport();
    EventValidator validator = new EventValidator();

    EventService sut = new EventService(events, tiers, promos, predictions, validator, ifMatch);

    private AuthPrincipal principal() {
        return new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void create_draft_with_empty_body_returns_event_in_draft_status() {
        AuthPrincipal p = principal();
        when(events.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        EventDto dto = sut.createDraft(p, new EventPatchRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null));

        assertThat(dto.status()).isEqualTo("draft");
        assertThat(dto.orgId()).isEqualTo(p.orgId());
        assertThat(dto.createdBy()).isEqualTo(p.userId());
        assertThat(dto.slug()).isNotBlank();
    }

    @Test
    void list_returns_org_scoped_paginated_summaries() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("x");
        when(events.findVisibleByOrg(eq(p.orgId()), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(e), PageRequest.of(0, 20), 1));

        PageResponse<EventDto> r = sut.list(p, null, 1, 20);
        assertThat(r.total()).isEqualTo(1);
        assertThat(r.items()).hasSize(1);
        assertThat(r.items().get(0).id()).isEqualTo(e.getId());
    }

    @Test
    void detail_404_when_event_in_other_org() {
        AuthPrincipal p = principal();
        Event other = new Event();
        other.setId(UUID.randomUUID()); other.setOrgId(UUID.randomUUID());
        when(events.findActive(other.getId())).thenReturn(Optional.of(other));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.detail(p, other.getId()))
                .isInstanceOf(com.imin.iminapi.security.ApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void detail_returns_event_with_tiers_promos_and_null_prediction() {
        AuthPrincipal p = principal();
        Event e = new Event();
        e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
        e.setName("X"); e.setSlug("x");
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        EventDto dto = sut.detail(p, e.getId());
        assertThat(dto.tiers()).isEmpty();
        assertThat(dto.promoCodes()).isEmpty();
        assertThat(dto.prediction()).isNull();
    }
}
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest=EventServiceTest`
Expected: FAIL.

- [ ] **Step 3: Implement EventService (create / list / detail / patch / publish)**

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.*;
import com.imin.iminapi.model.*;
import com.imin.iminapi.repository.*;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.web.IfMatchSupport;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

@Service
public class EventService {

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final PromoCodeRepository promos;
    private final PredictionRepository predictions;
    private final EventValidator validator;
    private final IfMatchSupport ifMatch;

    public EventService(EventRepository events, TicketTierRepository tiers,
                        PromoCodeRepository promos, PredictionRepository predictions,
                        EventValidator validator, IfMatchSupport ifMatch) {
        this.events = events;
        this.tiers = tiers;
        this.promos = promos;
        this.predictions = predictions;
        this.validator = validator;
        this.ifMatch = ifMatch;
    }

    @Transactional
    public EventDto createDraft(AuthPrincipal p, EventPatchRequest body) {
        Event e = new Event();
        e.setOrgId(p.orgId());
        e.setCreatedBy(p.userId());
        e.setSlug(generateSlug());
        applyPatch(e, body);
        Event saved = events.save(e);
        return EventDto.summary(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<EventDto> list(AuthPrincipal p, EventStatus status, int page, int pageSize) {
        var pg = PageRequest.of(Math.max(0, page - 1), Math.min(100, Math.max(1, pageSize)));
        var result = events.findVisibleByOrg(p.orgId(), status, pg);
        return PageResponse.from(result, EventDto::summary);
    }

    @Transactional(readOnly = true)
    public EventDto detail(AuthPrincipal p, UUID id) {
        Event e = loadOwned(p, id);
        var tiersList = tiers.findByEventIdOrderBySortOrderAsc(id).stream().map(TicketTierDto::from).toList();
        var promosList = promos.findByEventId(id).stream().map(PromoCodeDto::from).toList();
        var prediction = predictions.findById(id).map(PredictionDto::from).orElse(null);
        return EventDto.detail(e, tiersList, promosList, prediction);
    }

    @Transactional
    public EventDto patch(AuthPrincipal p, UUID id, String ifMatchHeader, EventPatchRequest body) {
        Event e = loadOwned(p, id);
        ifMatch.requireMatch(ifMatchHeader, e.getUpdatedAt());
        if (e.getStatus() != EventStatus.DRAFT) {
            // Only drafts are autosavable. Live events use targeted endpoints (out of V1 scope).
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE,
                    "Cannot edit a non-draft event via PATCH /events/:id");
        }
        applyPatch(e, body);
        e.setUpdatedAt(Instant.now()); // ensure ETag changes even when @PreUpdate doesn't fire
        Event saved = events.save(e);
        return EventDto.summary(saved);
    }

    @Transactional
    public EventDto publish(AuthPrincipal p, UUID id) {
        Event e = loadOwned(p, id);
        if (e.getStatus() == EventStatus.LIVE) {
            throw new ApiException(HttpStatus.CONFLICT, ErrorCode.INVALID_STATE, "Already published");
        }
        validator.validateForPublish(e);
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        events.save(e);
        return detail(p, id);
    }

    private Event loadOwned(AuthPrincipal p, UUID id) {
        Event e = events.findActive(id).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");
        return e;
    }

    private void applyPatch(Event e, EventPatchRequest b) {
        if (b == null) return;
        if (b.name() != null) e.setName(b.name());
        if (b.slug() != null) e.setSlug(b.slug().toLowerCase(Locale.ROOT));
        if (b.visibility() != null) e.setVisibility(EventVisibility.fromWire(b.visibility()));
        if (b.genre() != null) e.setGenre(b.genre());
        if (b.type() != null) e.setType(b.type());
        if (b.startsAt() != null) e.setStartsAt(b.startsAt());
        if (b.endsAt() != null) e.setEndsAt(b.endsAt());
        if (b.timezone() != null) e.setTimezone(b.timezone());
        if (b.venue() != null) {
            VenueDto v = b.venue();
            e.setVenueName(v.name());
            if (v.street() != null) e.setVenueStreet(v.street());
            if (v.city() != null) e.setVenueCity(v.city());
            if (v.postalCode() != null) e.setVenuePostalCode(v.postalCode());
            e.setVenueCountry(v.country());
        }
        if (b.description() != null) e.setDescription(b.description());
        if (b.posterUrl() != null) e.setPosterUrl(b.posterUrl());
        if (b.videoUrl() != null) e.setVideoUrl(b.videoUrl());
        if (b.coverUrl() != null) e.setCoverUrl(b.coverUrl());
        if (b.capacity() != null) e.setCapacity(b.capacity());
        if (b.currency() != null) e.setCurrency(b.currency());
        if (b.squadsEnabled() != null) e.setSquadsEnabled(b.squadsEnabled());
        if (b.minSquadSize() != null) e.setMinSquadSize(b.minSquadSize());
        if (b.squadDiscountPct() != null) e.setSquadDiscountPct(b.squadDiscountPct());
        if (b.onSaleAt() != null) e.setOnSaleAt(b.onSaleAt());
        if (b.saleClosesAt() != null) e.setSaleClosesAt(b.saleClosesAt());
    }

    private static final Random SLUG_RND = new Random();
    private static String generateSlug() {
        // Drafts get a placeholder slug. The wizard will overwrite it on autosave.
        return "draft-" + Long.toHexString(System.currentTimeMillis()) + "-" + Long.toHexString(SLUG_RND.nextLong() & 0xffff);
    }
}
```

- [ ] **Step 4: Re-run, expect pass**

Run: `./mvnw -q test -Dtest=EventServiceTest`
Expected: 4 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/event/EventService.java src/test/java/com/imin/iminapi/service/event/EventServiceTest.java
git commit -m "events: EventService for create/list/detail/patch/publish"
```

---

## Task 7: PATCH and publish behaviour tests

**Files:**
- Modify: `src/test/java/com/imin/iminapi/service/event/EventServiceTest.java`

- [ ] **Step 1: Append PATCH and publish tests**

```java
@Test
void patch_with_matching_ifMatch_updates_fields() {
    AuthPrincipal p = principal();
    Event e = new Event();
    e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
    e.setName(""); e.setSlug("draft-x");
    Instant updated = Instant.parse("2026-04-23T10:00:00Z");
    e.setUpdatedAt(updated);
    when(events.findActive(e.getId())).thenReturn(Optional.of(e));
    when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

    EventDto dto = sut.patch(p, e.getId(), "\"" + updated + "\"",
            new EventPatchRequest("New name", null, null, "Techno", null, null, null, null, null,
                    null, null, null, null, 250, null, null, null, null, null, null));

    assertThat(dto.name()).isEqualTo("New name");
    assertThat(dto.genre()).isEqualTo("Techno");
    assertThat(dto.capacity()).isEqualTo(250);
}

@Test
void patch_with_mismatched_ifMatch_throws_STALE_WRITE() {
    AuthPrincipal p = principal();
    Event e = new Event();
    e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
    e.setName(""); e.setSlug("draft-x");
    e.setUpdatedAt(Instant.parse("2026-04-23T10:00:00Z"));
    when(events.findActive(e.getId())).thenReturn(Optional.of(e));

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            sut.patch(p, e.getId(), "\"2026-01-01T00:00:00Z\"",
                    new EventPatchRequest("X", null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null, null)))
            .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.STALE_WRITE);
}

@Test
void publish_on_complete_event_transitions_to_live() {
    AuthPrincipal p = principal();
    Event e = new Event();
    e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
    e.setName("Test"); e.setSlug("test");
    e.setStartsAt(Instant.parse("2026-06-01T20:00:00Z"));
    e.setEndsAt(Instant.parse("2026-06-02T04:00:00Z"));
    e.setVenueStreet("12 Main"); e.setVenueCity("Berlin"); e.setVenuePostalCode("10115");
    e.setDescription("d"); e.setCapacity(100);
    when(events.findActive(e.getId())).thenReturn(Optional.of(e));
    when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
    when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
    when(promos.findByEventId(e.getId())).thenReturn(List.of());
    when(predictions.findById(e.getId())).thenReturn(Optional.empty());

    EventDto dto = sut.publish(p, e.getId());
    assertThat(dto.status()).isEqualTo("live");
    assertThat(dto.publishedAt()).isNotNull();
}

@Test
void publish_already_live_throws_INVALID_STATE() {
    AuthPrincipal p = principal();
    Event e = new Event();
    e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
    e.setStatus(EventStatus.LIVE);
    when(events.findActive(e.getId())).thenReturn(Optional.of(e));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.publish(p, e.getId()))
            .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.INVALID_STATE);
}

@Test
void publish_incomplete_event_throws_PUBLISH_VALIDATION_FAILED() {
    AuthPrincipal p = principal();
    Event e = new Event();
    e.setId(UUID.randomUUID()); e.setOrgId(p.orgId());
    e.setName(""); // missing
    when(events.findActive(e.getId())).thenReturn(Optional.of(e));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.publish(p, e.getId()))
            .hasFieldOrPropertyWithValue("code", com.imin.iminapi.security.ErrorCode.PUBLISH_VALIDATION_FAILED);
}
```

- [ ] **Step 2: Run all EventServiceTest tests, expect pass**

Run: `./mvnw -q test -Dtest=EventServiceTest`
Expected: 9 PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/imin/iminapi/service/event/EventServiceTest.java
git commit -m "test: cover PATCH (If-Match) and publish state transitions"
```

---

## Task 8: EventOverviewService

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/event/EventOverviewResponse.java`
- Create: `src/main/java/com/imin/iminapi/service/event/EventOverviewService.java`

- [ ] **Step 1: EventOverviewResponse**

```java
package com.imin.iminapi.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventOverviewResponse(
        Metrics metrics,
        List<RecentPurchase> recentPurchases,
        PredictionDto prediction,
        List<QuickAction> quickActions) {

    public record Metrics(int sold, int capacity, long revenueMinor, String currency,
                          int squadRatePct, int daysOut) {}

    public record RecentPurchase(String time, String name, String sub) {}

    public record QuickAction(String key, String icon, String label) {}
}
```

- [ ] **Step 2: EventOverviewService**

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventOverviewResponse;
import com.imin.iminapi.dto.event.EventOverviewResponse.Metrics;
import com.imin.iminapi.dto.event.EventOverviewResponse.QuickAction;
import com.imin.iminapi.dto.event.PredictionDto;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.PredictionRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EventOverviewService {

    private static final List<QuickAction> ACTIONS = List.of(
            new QuickAction("send_campaign", "✉️", "Send campaign to audience"),
            new QuickAction("comp_tickets", "🎟️", "Generate comp tickets"),
            new QuickAction("copy_link", "🔗", "Copy buyer link"),
            new QuickAction("qr_scanner", "📱", "Open QR scanner")
    );

    private final EventRepository events;
    private final PredictionRepository predictions;

    public EventOverviewService(EventRepository events, PredictionRepository predictions) {
        this.events = events;
        this.predictions = predictions;
    }

    @Transactional(readOnly = true)
    public EventOverviewResponse overview(AuthPrincipal p, UUID id) {
        Event e = events.findActive(id).orElseThrow(() -> ApiException.notFound("Event"));
        if (!e.getOrgId().equals(p.orgId())) throw ApiException.notFound("Event");

        int daysOut = e.getStartsAt() == null ? 0
                : (int) Duration.between(Instant.now(), e.getStartsAt()).toDays();
        Metrics m = new Metrics(
                e.getSold(), e.getCapacity(), e.getRevenueMinor(), e.getCurrency(),
                /* squadRatePct V1 stub */ 0,
                Math.max(0, daysOut));
        var prediction = predictions.findById(id).map(PredictionDto::from).orElse(null);
        // recentPurchases is sourced from a yet-to-exist purchases table. V1: empty list.
        return new EventOverviewResponse(m, List.of(), prediction, ACTIONS);
    }
}
```

- [ ] **Step 3: Compile**

Run: `./mvnw -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/event/EventOverviewResponse.java src/main/java/com/imin/iminapi/service/event/EventOverviewService.java
git commit -m "events: overview response + service (recentPurchases empty in V1)"
```

---

## Task 9: DraftPruner — hourly @Scheduled

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/event/DraftPruner.java`
- Create: `src/test/java/com/imin/iminapi/service/event/DraftPrunerTest.java`

- [ ] **Step 1: Write the test**

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.repository.EventRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DraftPrunerTest {

    EventRepository repo = mock(EventRepository.class);

    @Test
    void purge_deletes_drafts_older_than_24h() {
        when(repo.deleteEmptyDraftsOlderThan(any())).thenReturn(7);
        DraftPruner sut = new DraftPruner(repo);

        sut.purge();

        verify(repo).deleteEmptyDraftsOlderThan(argThat(cutoff -> {
            Instant now = Instant.now();
            Duration delta = Duration.between(cutoff, now);
            return delta.toHours() >= 23 && delta.toHours() <= 25;
        }));
    }
}
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest=DraftPrunerTest`
Expected: FAIL.

- [ ] **Step 3: Implement DraftPruner**

```java
package com.imin.iminapi.service.event;

import com.imin.iminapi.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class DraftPruner {

    private static final Logger log = LoggerFactory.getLogger(DraftPruner.class);
    private final EventRepository events;

    public DraftPruner(EventRepository events) { this.events = events; }

    @Scheduled(cron = "0 30 * * * *") // 30 minutes past every hour
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        int n = events.deleteEmptyDraftsOlderThan(cutoff);
        if (n > 0) log.info("Pruned {} empty drafts older than 24h", n);
    }
}
```

- [ ] **Step 4: Re-run, expect pass**

Run: `./mvnw -q test -Dtest=DraftPrunerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/event/DraftPruner.java src/test/java/com/imin/iminapi/service/event/DraftPrunerTest.java
git commit -m "events: hourly @Scheduled purge of empty drafts older than 24h"
```

---

## Task 10: EventController + MockMvc tests

**Files:**
- Create: `src/main/java/com/imin/iminapi/controller/event/EventController.java`
- Create: `src/test/java/com/imin/iminapi/controller/event/EventControllerTest.java`

- [ ] **Step 1: Write the controller test**

```java
package com.imin.iminapi.controller.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.*;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.event.EventOverviewService;
import com.imin.iminapi.service.event.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestRateLimitConfig.class)
class EventControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @MockBean EventService eventService;
    @MockBean EventOverviewService overviewService;

    static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Retention(RetentionPolicy.RUNTIME)
    @WithSecurityContext(factory = StubAuthFactory.class)
    public @interface WithStubUser {}

    public static class StubAuthFactory implements WithSecurityContextFactory<WithStubUser> {
        @Override
        public org.springframework.security.core.context.SecurityContext createSecurityContext(WithStubUser annotation) {
            AuthPrincipal p = new AuthPrincipal(USER, ORG, UserRole.OWNER, UUID.randomUUID());
            var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    p, null, List.of(new SimpleGrantedAuthority("ROLE_OWNER")));
            var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(auth);
            return ctx;
        }
    }

    private EventDto sample() {
        return EventDto.summary(eventEntity());
    }

    private com.imin.iminapi.model.Event eventEntity() {
        com.imin.iminapi.model.Event e = new com.imin.iminapi.model.Event();
        e.setId(UUID.randomUUID()); e.setOrgId(ORG); e.setCreatedBy(USER);
        e.setName("X"); e.setSlug("x"); e.setUpdatedAt(Instant.parse("2026-04-23T10:00:00Z"));
        return e;
    }

    @Test
    @WithStubUser
    void post_events_creates_201() throws Exception {
        when(eventService.createDraft(any(), any())).thenReturn(sample());
        mvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"));
    }

    @Test
    @WithStubUser
    void get_events_returns_paginated() throws Exception {
        when(eventService.list(any(), eq(null), eq(1), eq(20)))
                .thenReturn(new PageResponse<>(List.of(sample()), 1L, 1, 20));
        mvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").exists())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @WithStubUser
    void get_events_with_status_filter() throws Exception {
        when(eventService.list(any(), eq(com.imin.iminapi.model.EventStatus.LIVE), eq(1), eq(20)))
                .thenReturn(new PageResponse<>(List.of(), 0L, 1, 20));
        mvc.perform(get("/api/v1/events?status=live"))
                .andExpect(status().isOk());
    }

    @Test
    @WithStubUser
    void patch_event_passes_ifMatch() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventService.patch(any(), eq(id), eq("\"2026-04-23T10:00:00Z\""), any()))
                .thenReturn(sample());
        mvc.perform(patch("/api/v1/events/" + id)
                        .header("If-Match", "\"2026-04-23T10:00:00Z\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "Renamed"))))
                .andExpect(status().isOk());
    }

    @Test
    @WithStubUser
    void publish_returns_event() throws Exception {
        UUID id = UUID.randomUUID();
        when(eventService.publish(any(), eq(id))).thenReturn(sample());
        mvc.perform(post("/api/v1/events/" + id + "/publish")
                        .header("Idempotency-Key", "abc-123"))
                .andExpect(status().isOk());
    }

    @Test
    @WithStubUser
    void overview_returns_metrics() throws Exception {
        UUID id = UUID.randomUUID();
        when(overviewService.overview(any(), eq(id)))
                .thenReturn(new EventOverviewResponse(
                        new EventOverviewResponse.Metrics(0, 100, 0, "EUR", 0, 30),
                        List.of(), null,
                        List.of(new EventOverviewResponse.QuickAction("copy_link", "🔗", "Copy buyer link"))));
        mvc.perform(get("/api/v1/events/" + id + "/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.capacity").value(100))
                .andExpect(jsonPath("$.quickActions[0].key").value("copy_link"));
    }
}
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest=EventControllerTest`
Expected: FAIL — controller doesn't exist.

- [ ] **Step 3: Implement EventController**

```java
package com.imin.iminapi.controller.event;

import com.imin.iminapi.dto.PageResponse;
import com.imin.iminapi.dto.event.*;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.CurrentUser;
import com.imin.iminapi.service.event.EventOverviewService;
import com.imin.iminapi.service.event.EventService;
import com.imin.iminapi.web.IdempotencyKeySupport;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService eventService;
    private final EventOverviewService overviewService;
    private final IdempotencyKeySupport idempotency;
    private final com.fasterxml.jackson.databind.ObjectMapper om;

    public EventController(EventService eventService,
                           EventOverviewService overviewService,
                           IdempotencyKeySupport idempotency,
                           com.fasterxml.jackson.databind.ObjectMapper om) {
        this.eventService = eventService;
        this.overviewService = overviewService;
        this.idempotency = idempotency;
        this.om = om;
    }

    @GetMapping
    public PageResponse<EventDto> list(@CurrentUser AuthPrincipal p,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int pageSize) {
        EventStatus s = (status == null || status.isBlank()) ? null : EventStatus.fromWire(status);
        return eventService.list(p, s, page, pageSize);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventDto create(@CurrentUser AuthPrincipal p, @RequestBody(required = false) EventPatchRequest body) {
        return eventService.createDraft(p, body);
    }

    @GetMapping("/{id}")
    public EventDto detail(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return eventService.detail(p, id);
    }

    @PatchMapping("/{id}")
    public EventDto patch(@CurrentUser AuthPrincipal p,
                          @PathVariable UUID id,
                          @RequestHeader(value = "If-Match", required = false) String ifMatch,
                          @RequestBody EventPatchRequest body) {
        return eventService.patch(p, id, ifMatch, body);
    }

    @PostMapping("/{id}/publish")
    public EventDto publish(@CurrentUser AuthPrincipal p,
                            @PathVariable UUID id,
                            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key == null || key.isBlank()) return eventService.publish(p, id);
        var route = "POST /api/v1/events/:id/publish";
        var cached = idempotency.runOrReplay(p.orgId(), route, key,
                () -> idempotency.toCached(200, eventService.publish(p, id)));
        try {
            return om.readValue(cached.bodyJson(), EventDto.class);
        } catch (Exception e) {
            throw new IllegalStateException("Could not deserialise cached publish response", e);
        }
    }

    @GetMapping("/{id}/overview")
    public EventOverviewResponse overview(@CurrentUser AuthPrincipal p, @PathVariable UUID id) {
        return overviewService.overview(p, id);
    }
}
```

- [ ] **Step 4: Re-run controller test, expect pass**

Run: `./mvnw -q test -Dtest=EventControllerTest`
Expected: 6 PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/controller/event/EventController.java src/test/java/com/imin/iminapi/controller/event/EventControllerTest.java
git commit -m "events: EventController for /api/v1/events surface"
```

---

## Task 11: End-to-end smoke

**Files:** none

- [ ] **Step 1: Bring up infra and boot**

Run: `docker compose up -d && ./mvnw -q -DskipTests spring-boot:run`

- [ ] **Step 2: Get a token via auth signup**

```bash
TOKEN=$(curl -s -X POST http://localhost:8085/api/v1/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"events-smoke@example.com","password":"lovelace12","orgName":"Smoke Co","country":"GB"}' \
  | jq -r .token)
```

- [ ] **Step 3: Create draft**

```bash
EVENT=$(curl -s -X POST http://localhost:8085/api/v1/events \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}')
EID=$(echo "$EVENT" | jq -r .id)
ETAG=$(echo "$EVENT" | jq -r .updatedAt)
echo "Event $EID created with updatedAt $ETAG"
```

- [ ] **Step 4: PATCH with If-Match**

```bash
curl -s -X PATCH "http://localhost:8085/api/v1/events/$EID" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "If-Match: \"$ETAG\"" \
  -d '{"name":"ANTRUM","genre":"Techno","capacity":250,"description":"Underground","startsAt":"2026-06-01T20:00:00Z","endsAt":"2026-06-02T04:00:00Z","venue":{"street":"12 RAW","city":"Berlin","postalCode":"10115"}}' | jq
```
Expected: 200, returned body shows updated fields.

- [ ] **Step 5: List by status=draft**

```bash
curl -s "http://localhost:8085/api/v1/events?status=draft" -H "Authorization: Bearer $TOKEN" | jq
```
Expected: `total: 1` and the event in `items`.

- [ ] **Step 6: Publish**

```bash
curl -s -X POST "http://localhost:8085/api/v1/events/$EID/publish" \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" | jq
```
Expected: 200, `status: live`, `publishedAt` set.

- [ ] **Step 7: Overview**

```bash
curl -s "http://localhost:8085/api/v1/events/$EID/overview" -H "Authorization: Bearer $TOKEN" | jq
```
Expected: `metrics.capacity = 250`, `quickActions` populated.

- [ ] **Step 8: Stop**

Ctrl-C the foreground spring-boot.

---

## Self-Review

- **Spec coverage (§5):** GET list ✓, POST create ✓, GET detail ✓, PATCH autosave ✓, POST publish ✓, GET overview ✓.
- **Spec coverage (§1.5 pagination):** PageResponse matches `{items,total,page,pageSize}`. ✓
- **Spec coverage (§1.6 timestamps):** `Instant` serialises to ISO-8601 with `Z` offset by default. The contract requires offset (`+02:00` examples). Jackson default for `Instant` is `2026-04-23T10:00:00Z` which IS valid ISO-8601 with offset (Z = UTC). FE converts via `timezone` field. ✓
- **Spec coverage (§1.8 If-Match):** PATCH /events/:id wired through `IfMatchSupport`. ✓
- **Spec coverage (§1.9 Idempotency-Key):** publish wired through `IdempotencyKeySupport`. ✓
- **Spec coverage (auto-prune §5):** hourly `@Scheduled` purge in `DraftPruner`. ✓
- **Spec coverage (soft-delete §11.6):** `Event.deletedAt` exists, `findActive` and `findVisibleByOrg` filter on `deleted_at IS NULL`. DELETE endpoint is reserved (not V1). ✓
- **Placeholder scan:** none. `recentPurchases` returns empty list with explicit comment — that's a documented V1 stub since the purchases table doesn't exist yet, not a placeholder.
- **Type consistency:** `EventStatus.wireValue()` ("draft"/"live"/...) used everywhere; `EventDto` summary vs detail distinction documented; `EventPatchRequest` field names match `EventDto` for the FE's autosave round-trip.
- **Gap:** the `slug` is auto-generated for drafts. If the FE wants to set a final slug, it patches it; the unique constraint `(org_id, slug)` will throw a JPA constraint violation that needs translating to `DUPLICATE` — flagged for a follow-up in the events plan if FE testing surfaces it. Not blocking V1.
