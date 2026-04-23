# AI Event Creator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a `POST /api/events/ai-create` endpoint that takes a natural language vibe and orchestrates OpenRouter LLM + DALL-E + smart pricing to generate and persist a complete event concept.

**Architecture:** A single `EventCreatorService` orchestrator calls OpenRouter via Spring AI's `ChatClient` (Step 1), fires 3 parallel DALL-E `CompletableFuture` calls via `ImageGenerationService` (Step 2), then queries comparable events from the DB via `PricingService` (Step 3). All output is persisted across three tables (`generated_event`, `concept`, `social_copy`) and returned as a `201 Created` response.

**Tech Stack:** Java 17, Spring Boot 4.0.5, Spring AI 2.0.0-M4 (`spring-ai-starter-model-openai`), Spring Data JPA, Liquibase, Lombok, JUnit 5, Mockito, MockMvc.

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `src/main/resources/application.yaml` | Modify | Add Spring AI, Liquibase, OpenRouter config |
| `src/main/resources/db/changelog/db.changelog-master.yaml` | Create | Liquibase master changelog |
| `src/main/resources/db/changelog/001-initial-schema.yaml` | Create | DDL for generated_event, concept, social_copy |
| `config/SecurityConfig.java` | Create | Permit `/api/events/**` (SAML2 deferred to later) |
| `config/OpenRouterConfig.java` | Create | `ChatClient` bean wired to OpenRouter base URL |
| `model/GeneratedEvent.java` | Create | JPA entity for persisted event draft |
| `model/Concept.java` | Create | JPA entity for one event concept |
| `model/SocialCopy.java` | Create | JPA entity for per-platform copy |
| `repository/GeneratedEventRepository.java` | Create | JPQL query for comparable events |
| `dto/EventCreatorRequest.java` | Create | Validated inbound request |
| `dto/LlmEventConcept.java` | Create | Single concept deserialized from LLM JSON |
| `dto/LlmSocialCopy.java` | Create | Single social copy deserialized from LLM JSON |
| `dto/LlmGenerationResult.java` | Create | Full LLM structured response |
| `dto/PricingRecommendation.java` | Create | Pricing output record |
| `dto/EventCreatorResponse.java` | Create | Full HTTP response to client |
| `exception/EventCreationException.java` | Create | Wraps failures, triggers status=FAILED |
| `service/ImageGenerationService.java` | Create | Single DALL-E call per concept |
| `service/PricingService.java` | Create | DB query + genre fallback pricing |
| `service/EventCreatorService.java` | Create | Orchestrates all 3 steps |
| `controller/EventCreatorController.java` | Create | Thin REST controller |
| `src/test/…/service/PricingServiceTest.java` | Create | Unit tests for pricing logic |
| `src/test/…/service/EventCreatorServiceTest.java` | Create | Unit tests for orchestration |
| `src/test/…/controller/EventCreatorControllerTest.java` | Create | MockMvc controller tests |

---

## Task 1: Application Configuration

**Files:**
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Add Spring AI and OpenRouter config to application.yaml**

Replace the full contents of `src/main/resources/application.yaml` with:

```yaml
spring:
  application:
    name: imin-api
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml

  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      image:
        options:
          model: dall-e-3
          size: 1024x1024

openrouter:
  api-key: ${OPENROUTER_API_KEY}
  base-url: https://openrouter.ai/api/v1
  model: openai/gpt-4o
```

> `OPENAI_API_KEY` is used exclusively for DALL-E image generation (direct OpenAI).
> `OPENROUTER_API_KEY` is used for LLM chat via OpenRouter (wired in `OpenRouterConfig`).

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.yaml
git commit -m "config: add Spring AI and OpenRouter application config"
```

---

## Task 2: Liquibase Migration

**Files:**
- Create: `src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `src/main/resources/db/changelog/001-initial-schema.yaml`

- [ ] **Step 1: Create the master changelog**

Create `src/main/resources/db/changelog/db.changelog-master.yaml`:

```yaml
databaseChangeLog:
  - include:
      file: db/changelog/001-initial-schema.yaml
```

- [ ] **Step 2: Create the schema changeset**

Create `src/main/resources/db/changelog/001-initial-schema.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 001-initial-schema
      author: imin
      changes:
        - createTable:
            tableName: generated_event
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: vibe
                  type: TEXT
              - column:
                  name: tone
                  type: VARCHAR(255)
              - column:
                  name: genre
                  type: VARCHAR(255)
              - column:
                  name: city
                  type: VARCHAR(255)
              - column:
                  name: event_date
                  type: DATE
              - column:
                  name: platforms
                  type: TEXT
              - column:
                  name: accent_colors
                  type: TEXT
              - column:
                  name: poster_urls
                  type: TEXT
              - column:
                  name: suggested_min_price
                  type: NUMERIC(10,2)
              - column:
                  name: suggested_max_price
                  type: NUMERIC(10,2)
              - column:
                  name: pricing_notes
                  type: TEXT
              - column:
                  name: status
                  type: VARCHAR(20)
                  constraints:
                    nullable: false
              - column:
                  name: created_at
                  type: TIMESTAMP
                  constraints:
                    nullable: false

        - createTable:
            tableName: concept
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: generated_event_id
                  type: UUID
                  constraints:
                    nullable: false
                    foreignKeyName: fk_concept_event
                    references: generated_event(id)
              - column:
                  name: title
                  type: VARCHAR(255)
              - column:
                  name: description
                  type: TEXT
              - column:
                  name: tagline
                  type: VARCHAR(255)
              - column:
                  name: sort_order
                  type: INTEGER

        - createTable:
            tableName: social_copy
            columns:
              - column:
                  name: id
                  type: UUID
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: generated_event_id
                  type: UUID
                  constraints:
                    nullable: false
                    foreignKeyName: fk_social_copy_event
                    references: generated_event(id)
              - column:
                  name: platform
                  type: VARCHAR(50)
              - column:
                  name: copy_text
                  type: TEXT
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/db/changelog/
git commit -m "db: add Liquibase migration for generated_event, concept, social_copy tables"
```

---

## Task 3: Domain Entities

**Files:**
- Create: `src/main/java/com/imin/iminapi/model/GeneratedEvent.java`
- Create: `src/main/java/com/imin/iminapi/model/Concept.java`
- Create: `src/main/java/com/imin/iminapi/model/SocialCopy.java`

- [ ] **Step 1: Create GeneratedEvent entity**

Create `src/main/java/com/imin/iminapi/model/GeneratedEvent.java`:

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "generated_event")
@Getter
@Setter
public class GeneratedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(columnDefinition = "TEXT")
    private String vibe;

    private String tone;
    private String genre;
    private String city;
    private LocalDate eventDate;
    private String platforms;
    private String accentColors;
    private String posterUrls;

    @Column(precision = 10, scale = 2)
    private BigDecimal suggestedMinPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal suggestedMaxPrice;

    @Column(columnDefinition = "TEXT")
    private String pricingNotes;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "generatedEvent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<Concept> concepts = new ArrayList<>();

    @OneToMany(mappedBy = "generatedEvent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SocialCopy> socialCopies = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
```

- [ ] **Step 2: Create Concept entity**

Create `src/main/java/com/imin/iminapi/model/Concept.java`:

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "concept")
@Getter
@Setter
public class Concept {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_event_id", nullable = false)
    private GeneratedEvent generatedEvent;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String tagline;
    private int sortOrder;
}
```

- [ ] **Step 3: Create SocialCopy entity**

Create `src/main/java/com/imin/iminapi/model/SocialCopy.java`:

```java
package com.imin.iminapi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "social_copy")
@Getter
@Setter
public class SocialCopy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "generated_event_id", nullable = false)
    private GeneratedEvent generatedEvent;

    private String platform;

    @Column(columnDefinition = "TEXT")
    private String copyText;
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/model/
git commit -m "feat: add GeneratedEvent, Concept, SocialCopy JPA entities"
```

---

## Task 4: Repository

**Files:**
- Create: `src/main/java/com/imin/iminapi/repository/GeneratedEventRepository.java`

- [ ] **Step 1: Create the repository with pricing query**

Create `src/main/java/com/imin/iminapi/repository/GeneratedEventRepository.java`:

```java
package com.imin.iminapi.repository;

import com.imin.iminapi.model.GeneratedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface GeneratedEventRepository extends JpaRepository<GeneratedEvent, UUID> {

    @Query("""
            SELECT e FROM GeneratedEvent e
            WHERE e.genre = :genre
              AND e.city = :city
              AND e.eventDate BETWEEN :startDate AND :endDate
              AND e.status = 'COMPLETE'
            """)
    List<GeneratedEvent> findComparableEvents(
            @Param("genre") String genre,
            @Param("city") String city,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/imin/iminapi/repository/
git commit -m "feat: add GeneratedEventRepository with comparable events query"
```

---

## Task 5: DTOs and Exception

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/EventCreatorRequest.java`
- Create: `src/main/java/com/imin/iminapi/dto/LlmEventConcept.java`
- Create: `src/main/java/com/imin/iminapi/dto/LlmSocialCopy.java`
- Create: `src/main/java/com/imin/iminapi/dto/LlmGenerationResult.java`
- Create: `src/main/java/com/imin/iminapi/dto/PricingRecommendation.java`
- Create: `src/main/java/com/imin/iminapi/dto/EventCreatorResponse.java`
- Create: `src/main/java/com/imin/iminapi/exception/EventCreationException.java`

- [ ] **Step 1: Create EventCreatorRequest**

Create `src/main/java/com/imin/iminapi/dto/EventCreatorRequest.java`:

```java
package com.imin.iminapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record EventCreatorRequest(
        @NotBlank String vibe,
        @NotBlank String tone,
        @NotBlank String genre,
        @NotBlank String city,
        @NotNull LocalDate date,
        @NotEmpty List<String> platforms
) {}
```

- [ ] **Step 2: Create LLM response DTOs**

Create `src/main/java/com/imin/iminapi/dto/LlmEventConcept.java`:

```java
package com.imin.iminapi.dto;

public record LlmEventConcept(
        String title,
        String description,
        String tagline
) {}
```

Create `src/main/java/com/imin/iminapi/dto/LlmSocialCopy.java`:

```java
package com.imin.iminapi.dto;

public record LlmSocialCopy(
        String platform,
        String copyText
) {}
```

Create `src/main/java/com/imin/iminapi/dto/LlmGenerationResult.java`:

```java
package com.imin.iminapi.dto;

import java.util.List;

public record LlmGenerationResult(
        List<LlmEventConcept> concepts,
        List<LlmSocialCopy> socialCopy,
        List<String> accentColors
) {}
```

- [ ] **Step 3: Create PricingRecommendation**

Create `src/main/java/com/imin/iminapi/dto/PricingRecommendation.java`:

```java
package com.imin.iminapi.dto;

import java.math.BigDecimal;

public record PricingRecommendation(
        BigDecimal suggestedMinPrice,
        BigDecimal suggestedMaxPrice,
        String pricingNotes
) {}
```

- [ ] **Step 4: Create EventCreatorResponse**

Create `src/main/java/com/imin/iminapi/dto/EventCreatorResponse.java`:

```java
package com.imin.iminapi.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record EventCreatorResponse(
        UUID id,
        String status,
        List<String> accentColors,
        List<String> posterUrls,
        List<ConceptDto> concepts,
        List<SocialCopyDto> socialCopy,
        PricingDto pricing,
        LocalDateTime createdAt
) {
    public record ConceptDto(String title, String description, String tagline, int sortOrder) {}

    public record SocialCopyDto(String platform, String copyText) {}

    public record PricingDto(
            BigDecimal suggestedMinPrice,
            BigDecimal suggestedMaxPrice,
            String pricingNotes
    ) {}
}
```

- [ ] **Step 5: Create EventCreationException**

Create `src/main/java/com/imin/iminapi/exception/EventCreationException.java`:

```java
package com.imin.iminapi.exception;

public class EventCreationException extends RuntimeException {

    public EventCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/ src/main/java/com/imin/iminapi/exception/
git commit -m "feat: add DTOs and EventCreationException"
```

---

## Task 6: OpenRouter and Security Config

**Files:**
- Create: `src/main/java/com/imin/iminapi/config/OpenRouterConfig.java`
- Create: `src/main/java/com/imin/iminapi/config/SecurityConfig.java`

- [ ] **Step 1: Create OpenRouterConfig**

Create `src/main/java/com/imin/iminapi/config/OpenRouterConfig.java`:

```java
package com.imin.iminapi.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class OpenRouterConfig {

    @Value("${openrouter.api-key}")
    private String apiKey;

    @Value("${openrouter.base-url}")
    private String baseUrl;

    @Value("${openrouter.model}")
    private String model;

    @Bean
    @Primary
    public ChatClient openRouterChatClient() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .build())
                .build();
        return ChatClient.builder(chatModel).build();
    }
}
```

- [ ] **Step 2: Create SecurityConfig**

Create `src/main/java/com/imin/iminapi/config/SecurityConfig.java`:

```java
package com.imin.iminapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/events/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated()
                )
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/config/
git commit -m "config: add OpenRouterConfig ChatClient bean and SecurityConfig"
```

---

## Task 7: ImageGenerationService

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/ImageGenerationService.java`

- [ ] **Step 1: Create ImageGenerationService**

Create `src/main/java/com/imin/iminapi/service/ImageGenerationService.java`:

```java
package com.imin.iminapi.service;

import com.imin.iminapi.model.Concept;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private final ImageModel imageModel;

    public String generatePoster(Concept concept, String primaryColor) {
        String prompt = """
                Professional event poster design.
                Event title: %s
                Description: %s
                Tagline: %s
                Primary accent color: %s
                Style: modern, bold typography, atmospheric lighting, concert/event aesthetic.
                """.formatted(
                concept.getTitle(),
                concept.getDescription(),
                concept.getTagline(),
                primaryColor
        );
        return imageModel.call(new ImagePrompt(prompt))
                .getResult()
                .getOutput()
                .getUrl();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/ImageGenerationService.java
git commit -m "feat: add ImageGenerationService for DALL-E poster generation"
```

---

## Task 8: PricingService + Tests

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/PricingService.java`
- Create: `src/test/java/com/imin/iminapi/service/PricingServiceTest.java`

- [ ] **Step 1: Write failing tests first**

Create `src/test/java/com/imin/iminapi/service/PricingServiceTest.java`:

```java
package com.imin.iminapi.service;

import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.repository.GeneratedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock
    private GeneratedEventRepository repository;

    @InjectMocks
    private PricingService pricingService;

    @Test
    void recommend_withComparableEvents_averagesPrices() {
        GeneratedEvent e1 = new GeneratedEvent();
        e1.setSuggestedMinPrice(new BigDecimal("10"));
        e1.setSuggestedMaxPrice(new BigDecimal("20"));
        e1.setEventDate(LocalDate.of(2026, 6, 13)); // Saturday

        GeneratedEvent e2 = new GeneratedEvent();
        e2.setSuggestedMinPrice(new BigDecimal("20"));
        e2.setSuggestedMaxPrice(new BigDecimal("40"));
        e2.setEventDate(LocalDate.of(2026, 6, 6)); // Saturday

        when(repository.findComparableEvents(eq("techno"), eq("Berlin"), any(), any()))
                .thenReturn(List.of(e1, e2));

        PricingRecommendation result = pricingService.recommend("techno", "Berlin", LocalDate.of(2026, 6, 14));

        assertThat(result.suggestedMinPrice()).isEqualByComparingTo("15.00");
        assertThat(result.suggestedMaxPrice()).isEqualByComparingTo("30.00");
        assertThat(result.pricingNotes()).contains("2 comparable");
    }

    @Test
    void recommend_withNoComparables_returnsGenreDefault() {
        when(repository.findComparableEvents(any(), any(), any(), any()))
                .thenReturn(List.of());

        PricingRecommendation result = pricingService.recommend("techno", "Berlin", LocalDate.of(2026, 6, 14));

        assertThat(result.suggestedMinPrice()).isEqualByComparingTo("15.00");
        assertThat(result.suggestedMaxPrice()).isEqualByComparingTo("25.00");
        assertThat(result.pricingNotes()).contains("default");
    }

    @Test
    void recommend_withUnknownGenreAndNoComparables_returnsBaseDefault() {
        when(repository.findComparableEvents(any(), any(), any(), any()))
                .thenReturn(List.of());

        PricingRecommendation result = pricingService.recommend("polka", "Warsaw", LocalDate.of(2026, 6, 14));

        assertThat(result.suggestedMinPrice()).isEqualByComparingTo("15.00");
        assertThat(result.suggestedMaxPrice()).isEqualByComparingTo("30.00");
    }
}
```

- [ ] **Step 2: Run tests — expect them to FAIL (class not found)**

```bash
./mvnw test -Dtest=PricingServiceTest
```

Expected: `COMPILATION ERROR` — `PricingService` does not exist yet.

- [ ] **Step 3: Implement PricingService**

Create `src/main/java/com/imin/iminapi/service/PricingService.java`:

```java
package com.imin.iminapi.service;

import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.repository.GeneratedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PricingService {

    private static final Map<String, BigDecimal[]> GENRE_DEFAULTS = Map.of(
            "techno",       new BigDecimal[]{new BigDecimal("15"), new BigDecimal("25")},
            "house",        new BigDecimal[]{new BigDecimal("15"), new BigDecimal("25")},
            "electronic",   new BigDecimal[]{new BigDecimal("15"), new BigDecimal("25")},
            "hip-hop",      new BigDecimal[]{new BigDecimal("20"), new BigDecimal("35")},
            "jazz",         new BigDecimal[]{new BigDecimal("20"), new BigDecimal("40")},
            "classical",    new BigDecimal[]{new BigDecimal("30"), new BigDecimal("60")},
            "pop",          new BigDecimal[]{new BigDecimal("25"), new BigDecimal("50")}
    );
    private static final BigDecimal[] BASE_DEFAULT = {new BigDecimal("15"), new BigDecimal("30")};

    private final GeneratedEventRepository repository;

    public PricingRecommendation recommend(String genre, String city, LocalDate date) {
        List<GeneratedEvent> comparables = repository.findComparableEvents(
                genre, city, date.minusDays(30), date.plusDays(30));

        if (!comparables.isEmpty()) {
            return fromComparables(genre, comparables);
        }
        return genreDefault(genre);
    }

    private PricingRecommendation fromComparables(String genre, List<GeneratedEvent> comparables) {
        List<BigDecimal> minPrices = comparables.stream()
                .map(GeneratedEvent::getSuggestedMinPrice)
                .filter(Objects::nonNull)
                .toList();
        List<BigDecimal> maxPrices = comparables.stream()
                .map(GeneratedEvent::getSuggestedMaxPrice)
                .filter(Objects::nonNull)
                .toList();

        if (minPrices.isEmpty() || maxPrices.isEmpty()) {
            return genreDefault(genre);
        }

        BigDecimal avgMin = minPrices.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(minPrices.size()), 2, RoundingMode.HALF_UP);

        BigDecimal avgMax = maxPrices.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(maxPrices.size()), 2, RoundingMode.HALF_UP);

        String notes = "Based on %d comparable %s event(s).".formatted(comparables.size(), genre);

        return new PricingRecommendation(avgMin, avgMax, notes);
    }

    private PricingRecommendation genreDefault(String genre) {
        BigDecimal[] range = GENRE_DEFAULTS.getOrDefault(genre.toLowerCase(), BASE_DEFAULT);
        String notes = "Genre default pricing — no comparable events found.";
        return new PricingRecommendation(range[0], range[1], notes);
    }
}
```

- [ ] **Step 4: Run tests — expect them to PASS**

```bash
./mvnw test -Dtest=PricingServiceTest
```

Expected output:
```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/PricingService.java \
        src/test/java/com/imin/iminapi/service/PricingServiceTest.java
git commit -m "feat: add PricingService with comparable event query and genre defaults"
```

---

## Task 9: EventCreatorService + Tests

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/EventCreatorService.java`
- Create: `src/test/java/com/imin/iminapi/service/EventCreatorServiceTest.java`

- [ ] **Step 1: Write failing tests**

Create `src/test/java/com/imin/iminapi/service/EventCreatorServiceTest.java`:

```java
package com.imin.iminapi.service;

import com.imin.iminapi.dto.*;
import com.imin.iminapi.exception.EventCreationException;
import com.imin.iminapi.model.Concept;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.repository.GeneratedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventCreatorServiceTest {

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;
    @Mock private ImageGenerationService imageGenerationService;
    @Mock private PricingService pricingService;
    @Mock private GeneratedEventRepository repository;

    private EventCreatorService service;

    @BeforeEach
    void setUp() {
        service = new EventCreatorService(chatClient, imageGenerationService, pricingService, repository);
    }

    @Test
    void create_successfulRun_persistsAndReturnsCompleteEvent() {
        EventCreatorRequest request = new EventCreatorRequest(
                "underground techno night", "edgy", "techno", "Berlin",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM", "TWITTER"));

        LlmGenerationResult llmResult = new LlmGenerationResult(
                List.of(
                        new LlmEventConcept("Void", "Dark and deep.", "Lose yourself."),
                        new LlmEventConcept("Pulse", "Raw energy.", "Feel the beat."),
                        new LlmEventConcept("Flux", "Industrial vibes.", "Pure noise.")
                ),
                List.of(
                        new LlmSocialCopy("INSTAGRAM", "Join us for Void #techno"),
                        new LlmSocialCopy("TWITTER", "Void is happening. #rave")
                ),
                List.of("#1A1A2E", "#E94560", "#0F3460", "#533483", "#2B2D42")
        );

        PricingRecommendation pricing = new PricingRecommendation(
                new BigDecimal("15"), new BigDecimal("25"), "Genre default.");

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(LlmGenerationResult.class)).thenReturn(llmResult);

        when(imageGenerationService.generatePoster(any(Concept.class), anyString()))
                .thenReturn("https://dalle.example.com/img1.png")
                .thenReturn("https://dalle.example.com/img2.png")
                .thenReturn("https://dalle.example.com/img3.png");

        when(pricingService.recommend(eq("techno"), eq("Berlin"), any())).thenReturn(pricing);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EventCreatorResponse response = service.create(request);

        assertThat(response.status()).isEqualTo("COMPLETE");
        assertThat(response.concepts()).hasSize(3);
        assertThat(response.accentColors()).hasSize(5);
        assertThat(response.posterUrls()).hasSize(3);
        assertThat(response.socialCopy()).hasSize(2);
        assertThat(response.pricing().suggestedMinPrice()).isEqualByComparingTo("15");

        // Verify saved twice: once as DRAFT, once as COMPLETE
        ArgumentCaptor<GeneratedEvent> captor = ArgumentCaptor.forClass(GeneratedEvent.class);
        verify(repository, times(2)).save(captor.capture());
        List<GeneratedEvent> saved = captor.getAllValues();
        assertThat(saved.get(0).getStatus()).isEqualTo("DRAFT");
        assertThat(saved.get(1).getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void create_llmFailure_setsStatusFailedAndThrows() {
        EventCreatorRequest request = new EventCreatorRequest(
                "summer pop concert", "bright", "pop", "London",
                LocalDate.of(2026, 7, 4), List.of("INSTAGRAM"));

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(LlmGenerationResult.class))
                .thenThrow(new RuntimeException("OpenRouter timeout"));

        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(EventCreationException.class)
                .hasMessageContaining("OpenRouter timeout");

        ArgumentCaptor<GeneratedEvent> captor = ArgumentCaptor.forClass(GeneratedEvent.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(e -> "FAILED".equals(e.getStatus()));
    }
}
```

- [ ] **Step 2: Run tests — expect COMPILATION ERROR**

```bash
./mvnw test -Dtest=EventCreatorServiceTest
```

Expected: `COMPILATION ERROR` — `EventCreatorService` does not exist yet.

- [ ] **Step 3: Implement EventCreatorService**

Create `src/main/java/com/imin/iminapi/service/EventCreatorService.java`:

```java
package com.imin.iminapi.service;

import com.imin.iminapi.dto.*;
import com.imin.iminapi.exception.EventCreationException;
import com.imin.iminapi.model.Concept;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.SocialCopy;
import com.imin.iminapi.repository.GeneratedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class EventCreatorService {

    private final ChatClient chatClient;
    private final ImageGenerationService imageGenerationService;
    private final PricingService pricingService;
    private final GeneratedEventRepository repository;

    public EventCreatorResponse create(EventCreatorRequest request) {
        GeneratedEvent event = initDraft(request);

        try {
            // Step 1: LLM — get concepts, social copy, colors
            LlmGenerationResult llmResult = callLlm(request);

            event.setAccentColors(String.join(",", llmResult.accentColors()));
            event.setConcepts(buildConcepts(llmResult.concepts(), event));
            event.setSocialCopies(buildSocialCopies(llmResult.socialCopy(), event));
            repository.save(event);

            // Step 2: Images — 3 parallel DALL-E calls
            String primaryColor = llmResult.accentColors().get(0);
            List<String> posterUrls = generatePosters(event.getConcepts(), primaryColor);
            event.setPosterUrls(String.join(",", posterUrls));

            // Step 3: Pricing
            PricingRecommendation pricing = pricingService.recommend(
                    request.genre(), request.city(), request.date());
            applyPricing(event, pricing);

            event.setStatus("COMPLETE");
            repository.save(event);

            return toResponse(event, pricing);

        } catch (Exception e) {
            event.setStatus("FAILED");
            try { repository.save(event); } catch (Exception ignored) {}
            throw new EventCreationException("Event creation failed: " + e.getMessage(), e);
        }
    }

    private GeneratedEvent initDraft(EventCreatorRequest request) {
        GeneratedEvent event = new GeneratedEvent();
        event.setVibe(request.vibe());
        event.setTone(request.tone());
        event.setGenre(request.genre());
        event.setCity(request.city());
        event.setEventDate(request.date());
        event.setPlatforms(String.join(",", request.platforms()));
        event.setStatus("DRAFT");
        return event;
    }

    private LlmGenerationResult callLlm(EventCreatorRequest request) {
        String prompt = """
                You are an expert event creator. Generate exactly 3 unique event concepts based on:

                Vibe: %s
                Tone: %s
                Genre: %s
                City: %s
                Date: %s
                Platforms: %s

                Requirements:
                - Exactly 3 distinct event concepts, each with a title, 2-3 sentence description, and one punchy tagline
                - Exactly 5 HEX color codes (with # prefix) that match the mood and genre
                - Social media copy for each of these platforms: %s (platform field must match the name exactly as provided)
                """.formatted(
                request.vibe(), request.tone(), request.genre(),
                request.city(), request.date(), String.join(", ", request.platforms()),
                String.join(", ", request.platforms())
        );
        return chatClient.prompt()
                .user(prompt)
                .call()
                .entity(LlmGenerationResult.class);
    }

    private List<Concept> buildConcepts(List<LlmEventConcept> llmConcepts, GeneratedEvent event) {
        List<Concept> result = new ArrayList<>();
        for (int i = 0; i < llmConcepts.size(); i++) {
            LlmEventConcept lc = llmConcepts.get(i);
            Concept c = new Concept();
            c.setGeneratedEvent(event);
            c.setTitle(lc.title());
            c.setDescription(lc.description());
            c.setTagline(lc.tagline());
            c.setSortOrder(i + 1);
            result.add(c);
        }
        return result;
    }

    private List<SocialCopy> buildSocialCopies(List<LlmSocialCopy> llmCopies, GeneratedEvent event) {
        return llmCopies.stream().map(lc -> {
            SocialCopy sc = new SocialCopy();
            sc.setGeneratedEvent(event);
            sc.setPlatform(lc.platform());
            sc.setCopyText(lc.copyText());
            return sc;
        }).toList();
    }

    private List<String> generatePosters(List<Concept> concepts, String primaryColor) {
        List<CompletableFuture<String>> futures = concepts.stream()
                .map(c -> CompletableFuture.supplyAsync(
                        () -> imageGenerationService.generatePoster(c, primaryColor)))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private void applyPricing(GeneratedEvent event, PricingRecommendation pricing) {
        event.setSuggestedMinPrice(pricing.suggestedMinPrice());
        event.setSuggestedMaxPrice(pricing.suggestedMaxPrice());
        event.setPricingNotes(pricing.pricingNotes());
    }

    private EventCreatorResponse toResponse(GeneratedEvent event, PricingRecommendation pricing) {
        List<String> accentColors = List.of(event.getAccentColors().split(","));
        List<String> posterUrls = event.getPosterUrls() != null
                ? List.of(event.getPosterUrls().split(",")) : List.of();

        List<EventCreatorResponse.ConceptDto> conceptDtos = event.getConcepts().stream()
                .map(c -> new EventCreatorResponse.ConceptDto(
                        c.getTitle(), c.getDescription(), c.getTagline(), c.getSortOrder()))
                .toList();

        List<EventCreatorResponse.SocialCopyDto> socialDtos = event.getSocialCopies().stream()
                .map(sc -> new EventCreatorResponse.SocialCopyDto(sc.getPlatform(), sc.getCopyText()))
                .toList();

        EventCreatorResponse.PricingDto pricingDto = new EventCreatorResponse.PricingDto(
                pricing.suggestedMinPrice(), pricing.suggestedMaxPrice(),
                pricing.pricingNotes());

        return new EventCreatorResponse(
                event.getId(), event.getStatus(), accentColors, posterUrls,
                conceptDtos, socialDtos, pricingDto, event.getCreatedAt());
    }
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
./mvnw test -Dtest=EventCreatorServiceTest
```

Expected:
```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/EventCreatorService.java \
        src/test/java/com/imin/iminapi/service/EventCreatorServiceTest.java
git commit -m "feat: add EventCreatorService orchestrating LLM, DALL-E, and pricing"
```

---

## Task 10: EventCreatorController + Tests

**Files:**
- Create: `src/main/java/com/imin/iminapi/controller/EventCreatorController.java`
- Create: `src/test/java/com/imin/iminapi/controller/EventCreatorControllerTest.java`

- [ ] **Step 1: Write failing controller test**

Create `src/test/java/com/imin/iminapi/controller/EventCreatorControllerTest.java`:

```java
package com.imin.iminapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.config.SecurityConfig;
import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.EventCreatorResponse;
import com.imin.iminapi.exception.EventCreationException;
import com.imin.iminapi.service.EventCreatorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.saml2.Saml2RelyingPartyAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = EventCreatorController.class,
        excludeAutoConfiguration = Saml2RelyingPartyAutoConfiguration.class
)
@Import(SecurityConfig.class)
class EventCreatorControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean  private EventCreatorService eventCreatorService;

    @Test
    void create_validRequest_returns201WithBody() throws Exception {
        EventCreatorRequest request = new EventCreatorRequest(
                "underground techno night", "edgy", "techno", "Berlin",
                LocalDate.of(2026, 6, 14), List.of("INSTAGRAM"));

        EventCreatorResponse response = new EventCreatorResponse(
                UUID.randomUUID(), "COMPLETE",
                List.of("#1A1A2E", "#E94560", "#0F3460", "#533483", "#2B2D42"),
                List.of("https://img1.com", "https://img2.com", "https://img3.com"),
                List.of(new EventCreatorResponse.ConceptDto("Void", "Dark vibes.", "Lose yourself.", 1)),
                List.of(new EventCreatorResponse.SocialCopyDto("INSTAGRAM", "Join us #techno")),
                new EventCreatorResponse.PricingDto(
                        new BigDecimal("15"), new BigDecimal("25"), "Genre default."),
                LocalDateTime.now()
        );

        when(eventCreatorService.create(any())).thenReturn(response);

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.concepts").isArray())
                .andExpect(jsonPath("$.accentColors").isArray())
                .andExpect(jsonPath("$.posterUrls").isArray())
                .andExpect(jsonPath("$.pricing.suggestedMinPrice").value(15));
    }

    @Test
    void create_missingVibe_returns400() throws Exception {
        String invalidBody = """
                {"tone":"edgy","genre":"techno","city":"Berlin","date":"2026-06-14","platforms":["INSTAGRAM"]}
                """;

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_serviceThrows_returns500() throws Exception {
        EventCreatorRequest request = new EventCreatorRequest(
                "jazz night", "smooth", "jazz", "NYC",
                LocalDate.of(2026, 8, 1), List.of("TWITTER"));

        when(eventCreatorService.create(any()))
                .thenThrow(new EventCreationException("LLM failed", new RuntimeException()));

        mockMvc.perform(post("/api/events/ai-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError());
    }
}
```

- [ ] **Step 2: Run tests — expect COMPILATION ERROR**

```bash
./mvnw test -Dtest=EventCreatorControllerTest
```

Expected: `COMPILATION ERROR` — `EventCreatorController` does not exist yet.

- [ ] **Step 3: Implement EventCreatorController**

Create `src/main/java/com/imin/iminapi/controller/EventCreatorController.java`:

```java
package com.imin.iminapi.controller;

import com.imin.iminapi.dto.EventCreatorRequest;
import com.imin.iminapi.dto.EventCreatorResponse;
import com.imin.iminapi.service.EventCreatorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventCreatorController {

    private final EventCreatorService eventCreatorService;

    @PostMapping("/ai-create")
    @ResponseStatus(HttpStatus.CREATED)
    public EventCreatorResponse create(@Valid @RequestBody EventCreatorRequest request) {
        return eventCreatorService.create(request);
    }
}
```

- [ ] **Step 4: Run controller tests — expect PASS**

```bash
./mvnw test -Dtest=EventCreatorControllerTest
```

Expected:
```
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

- [ ] **Step 5: Run all tests**

```bash
./mvnw test
```

Expected: all tests pass (the existing `IminApiApplicationTests` context load test may require env vars — if it fails, set `OPENAI_API_KEY=test` and `OPENROUTER_API_KEY=test` in the test environment).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/imin/iminapi/controller/EventCreatorController.java \
        src/test/java/com/imin/iminapi/controller/EventCreatorControllerTest.java
git commit -m "feat: add EventCreatorController POST /api/events/ai-create"
```

---

## Environment Variables Required

| Variable | Purpose |
|---|---|
| `OPENROUTER_API_KEY` | Chat LLM via OpenRouter |
| `OPENAI_API_KEY` | DALL-E image generation (direct OpenAI) |

Set both before running the application:
```bash
export OPENROUTER_API_KEY=sk-or-...
export OPENAI_API_KEY=sk-...
docker compose up -d
./mvnw spring-boot:run
```

Test the endpoint:
```bash
curl -X POST http://localhost:8080/api/events/ai-create \
  -H "Content-Type: application/json" \
  -d '{
    "vibe": "underground techno night in a warehouse",
    "tone": "edgy",
    "genre": "techno",
    "city": "Berlin",
    "date": "2026-06-14",
    "platforms": ["INSTAGRAM", "TWITTER"]
  }'
```
