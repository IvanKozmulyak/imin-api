# AI Studio Three-Concept — Backend Implementation Plan (imin-api)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a text-only `POST /api/v1/ai/events/concepts` that returns three distinct named event concepts, each with a description and Instagram/TikTok/X captions, conditioned on the org brand — without touching the poster pipeline.

**Architecture:** A new lightweight LLM path parallel to the existing poster pipeline. `ConceptController` gains a `/concepts` mapping → new `ConceptSetService` → one structured `ChatClient.call().entity(...)` returning 3 concepts → persisted as `Concept` rows under a `GeneratedEvent`. The existing `/concept` poster endpoint is untouched. Spec: `imin-webapp/docs/superpowers/specs/2026-06-13-ai-studio-three-concept-design.md`.

**Tech Stack:** Java 17 · Spring Boot 4 · Spring AI `ChatClient` (OpenRouter `@Primary`) · Spring Data JPA · Flyway · H2 (tests) · JUnit 5 + Mockito.

**Branch:** `feat/ai-studio-three-concept` (already created).

---

## File structure

- Create `dto/ai/ConceptSetRequest.java` — request record.
- Create `dto/ai/ConceptSetResponse.java` — response record (`generatedEventId`, `concepts[]`).
- Create `dto/ai/ConceptCardDto.java` — one concept card in the response.
- Create `dto/ai/CaptionsDto.java` — `{instagram, tiktok, x}`.
- Create `dto/ai/ConceptSet.java` — LLM deserialization record (snake_case `@JsonProperty`), holding `List<LlmConcept>`.
- Create `service/ai/ConceptSetService.java` — prompt build + brand + tiers + persistence.
- Modify `controller/ai/ConceptController.java` — add `@PostMapping("/concepts")`.
- Modify `model/Concept.java` — add `instagramCaption`, `tiktokCaption`, `xCaption` columns.
- Create `src/main/resources/db/migration/V40__concept_captions.sql`.
- Create tests: `service/ai/ConceptSetServiceTest.java`, `controller/ai/ConceptControllerConceptsTest.java` (or a slice test consistent with repo conventions).

Reuse without modifying: `PricingService`, `buildTiers` logic (lift the private helper from `ConceptStudioService` into a small package-visible util or duplicate minimally — see Task 5), `OrganizationRepository`, `GeneratedEventRepository`, `RateLimiter`, the `@Primary` `ChatClient`, `GeneratedEvent`/`GeneratedEventStatus`.

---

## Task 1: DTOs (request + response shapes)

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/ai/CaptionsDto.java`
- Create: `src/main/java/com/imin/iminapi/dto/ai/ConceptCardDto.java`
- Create: `src/main/java/com/imin/iminapi/dto/ai/ConceptSetResponse.java`
- Create: `src/main/java/com/imin/iminapi/dto/ai/ConceptSetRequest.java`

- [ ] **Step 1: Create the records**

```java
// CaptionsDto.java
package com.imin.iminapi.dto.ai;
public record CaptionsDto(String instagram, String tiktok, String x) {}
```

```java
// ConceptCardDto.java
package com.imin.iminapi.dto.ai;
import java.util.List;
import java.util.UUID;
public record ConceptCardDto(
        UUID conceptId,
        String name,
        String description,
        CaptionsDto captions,
        String suggestedGenre,
        String suggestedType,
        Integer suggestedCapacity,
        List<SuggestedTierDto> suggestedTiers) {}
```

```java
// ConceptSetResponse.java
package com.imin.iminapi.dto.ai;
import java.util.List;
import java.util.UUID;
public record ConceptSetResponse(UUID generatedEventId, List<ConceptCardDto> concepts) {}
```

```java
// ConceptSetRequest.java
package com.imin.iminapi.dto.ai;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record ConceptSetRequest(
        @NotBlank @Size(min = 10, max = 500) String vibe,
        String genre,
        String city,
        Integer capacity) {}
```

- [ ] **Step 2: Compile**

Run: `./mvnw -q -o compile` (use `-o` only if deps are cached; else drop it)
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/dto/ai/
git commit -m "feat(ai-studio): concept-set request/response DTOs"
```

---

## Task 2: LLM deserialization record (`ConceptSet`)

**Files:**
- Create: `src/main/java/com/imin/iminapi/dto/ai/ConceptSet.java`

The model returns JSON; this record is what `ChatClient.call().entity(ConceptSet.class)` deserializes into. Snake_case keys map via `@JsonProperty`.

- [ ] **Step 1: Create the record**

```java
package com.imin.iminapi.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Raw LLM output for the 3-concept generation. Mapped to ConceptCardDto by the service. */
public record ConceptSet(List<LlmConcept> concepts) {

    public record LlmConcept(
            String name,
            String description,
            LlmCaptions captions,
            @JsonProperty("suggested_genre") String suggestedGenre,
            @JsonProperty("suggested_type") String suggestedType,
            @JsonProperty("suggested_capacity") Integer suggestedCapacity) {}

    public record LlmCaptions(String instagram, String tiktok, String x) {}
}
```

- [ ] **Step 2: Compile + commit**

```bash
./mvnw -q compile && git add src/main/java/com/imin/iminapi/dto/ai/ConceptSet.java \
  && git commit -m "feat(ai-studio): LLM concept-set deserialization record"
```

---

## Task 3: Caption columns on `Concept` + migration

**Files:**
- Modify: `src/main/java/com/imin/iminapi/model/Concept.java`
- Create: `src/main/resources/db/migration/V40__concept_captions.sql`

- [ ] **Step 1: Migration**

```sql
-- V40__concept_captions.sql
-- Three social captions per concept (Instagram / TikTok / X) for AI Studio.
ALTER TABLE concept ADD COLUMN instagram_caption TEXT;
ALTER TABLE concept ADD COLUMN tiktok_caption    TEXT;
ALTER TABLE concept ADD COLUMN x_caption         TEXT;
```

- [ ] **Step 2: Add fields to the entity**

Add to `Concept.java` (after `tagline` / `sortOrder`; Lombok `@Getter/@Setter` already present):

```java
    @Column(name = "instagram_caption", columnDefinition = "TEXT")
    private String instagramCaption;

    @Column(name = "tiktok_caption", columnDefinition = "TEXT")
    private String tiktokCaption;

    @Column(name = "x_caption", columnDefinition = "TEXT")
    private String xCaption;
```

- [ ] **Step 3: Verify Flyway migrates on test boot**

Run: `./mvnw -q test -Dtest=ConceptSetServiceTest` (will fail until Task 5, but Flyway should apply V40 cleanly — watch for migration errors specifically).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/imin/iminapi/model/Concept.java src/main/resources/db/migration/V40__concept_captions.sql
git commit -m "feat(ai-studio): persist per-concept social captions (V40)"
```

---

## Task 4: `ConceptSetService` — failing test first

**Files:**
- Create: `src/test/java/com/imin/iminapi/service/ai/ConceptSetServiceTest.java`

Mock the `ChatClient` fluent chain to return a fixed `ConceptSet` of 3, mock `OrganizationRepository` (brand) and `PricingService`, use a real or mocked `GeneratedEventRepository`. Follow the existing `ConceptStudioService`/AI test patterns in the repo for how `ChatClient` is stubbed (`chat.prompt().user(...).call().entity(ConceptSet.class)`).

- [ ] **Step 1: Write the failing test**

```java
package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.ai.ConceptSet;
import com.imin.iminapi.dto.ai.ConceptSetRequest;
import com.imin.iminapi.dto.ai.ConceptSetResponse;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConceptSetServiceTest {

    @Test
    void returnsThreeConceptsWithCaptionsAndTiers() {
        // Arrange: stub ChatClient to return 3 concepts; brandless org; pricing defaults.
        // (Wire mocks per repo convention — see ConceptStudioService tests.)
        ConceptSetService svc = TestFixtures.conceptSetServiceReturning(
            new ConceptSet(List.of(
                concept("Warehouse Mass"), concept("Concrete Hours"), concept("After Hours Mass"))));
        AuthPrincipal p = TestFixtures.principal();

        ConceptSetResponse res = svc.create(p, new ConceptSetRequest(
            "Moody Paris techno in a raw warehouse, 200 people, Saturday night", "Techno", "Paris", 200));

        assertThat(res.concepts()).hasSize(3);
        assertThat(res.concepts().get(0).captions().instagram()).isNotBlank();
        assertThat(res.concepts().get(0).captions().tiktok()).isNotBlank();
        assertThat(res.concepts().get(0).captions().x()).isNotBlank();
        assertThat(res.concepts().get(0).suggestedTiers()).isNotEmpty();
        assertThat(res.generatedEventId()).isNotNull();
    }

    private static ConceptSet.LlmConcept concept(String name) {
        return new ConceptSet.LlmConcept(name, "A 40-word second-person description of the night.",
            new ConceptSet.LlmCaptions("ig copy #techno", "tiktok copy", "x copy"),
            "Techno", "Rave", 200);
    }
}
```

> **Implementer note:** `TestFixtures` is illustrative — wire the actual mocks inline following the repo's existing AI service tests (mock `ChatClient`'s `prompt()/user()/call()/entity()` chain, `OrganizationRepository.findById`, `PricingService.recommend`, and a stub/in-memory `GeneratedEventRepository`). Do not introduce a new fixtures class if the repo doesn't already have one.

- [ ] **Step 2: Run — expect compile/fail (service does not exist)**

Run: `./mvnw -q test -Dtest=ConceptSetServiceTest`
Expected: FAIL — `ConceptSetService` not found.

---

## Task 5: `ConceptSetService` — implementation

**Files:**
- Create: `src/main/java/com/imin/iminapi/service/ai/ConceptSetService.java`

- [ ] **Step 1: Implement**

```java
package com.imin.iminapi.service.ai;

import com.imin.iminapi.dto.PricingRecommendation;
import com.imin.iminapi.dto.ai.*;
import com.imin.iminapi.model.Concept;
import com.imin.iminapi.model.GeneratedEvent;
import com.imin.iminapi.model.GeneratedEventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.PricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ConceptSetService {

    private static final Logger log = LoggerFactory.getLogger(ConceptSetService.class);
    private static final int WANT = 3;
    private static final int MAX_ATTEMPTS = 2;

    private final ChatClient chat;
    private final OrganizationRepository orgs;
    private final PricingService pricing;
    private final GeneratedEventRepository repo;

    public ConceptSetService(ChatClient chat, OrganizationRepository orgs,
                             PricingService pricing, GeneratedEventRepository repo) {
        this.chat = chat; this.orgs = orgs; this.pricing = pricing; this.repo = repo;
    }

    @Transactional
    public ConceptSetResponse create(AuthPrincipal p, ConceptSetRequest req) {
        String brandBlock = brandBlock(p.orgId());
        ConceptSet set = generateWithRetry(req, brandBlock);

        PricingRecommendation prices = pricing.recommend(
                req.genre() == null ? "techno" : req.genre(),
                req.city() == null ? "" : req.city(),
                LocalDate.now().plusMonths(2));

        GeneratedEvent staging = new GeneratedEvent();
        staging.setOrgId(p.orgId());
        staging.setVibe(req.vibe());
        staging.setGenre(req.genre());
        staging.setCity(req.city());
        staging.setTone("energetic");
        staging.setEventDate(LocalDate.now().plusMonths(2));
        staging.setPlatforms("instagram,tiktok,x");
        staging.setStatus(GeneratedEventStatus.DRAFT);

        List<ConceptCardDto> cards = new ArrayList<>();
        int sort = 0;
        for (ConceptSet.LlmConcept c : set.concepts()) {
            Concept entity = new Concept();
            entity.setGeneratedEvent(staging);
            entity.setTitle(c.name());
            entity.setDescription(c.description());
            entity.setSortOrder(sort);
            entity.setInstagramCaption(c.captions() == null ? null : c.captions().instagram());
            entity.setTiktokCaption(c.captions() == null ? null : c.captions().tiktok());
            entity.setXCaption(c.captions() == null ? null : c.captions().x());
            staging.getConcepts().add(entity);

            List<SuggestedTierDto> tiers = buildTiers(prices, c.suggestedCapacity());
            CaptionsDto captions = new CaptionsDto(
                    c.captions() == null ? null : c.captions().instagram(),
                    c.captions() == null ? null : c.captions().tiktok(),
                    c.captions() == null ? null : c.captions().x());
            // conceptId is assigned after save; placeholder filled post-persist below.
            cards.add(new ConceptCardDto(null, c.name(), c.description(), captions,
                    c.suggestedGenre(), c.suggestedType(), c.suggestedCapacity(), tiers));
            sort++;
        }
        staging.setStatus(GeneratedEventStatus.COMPLETE);
        repo.save(staging);

        // Re-emit cards with the persisted Concept ids (sortOrder-aligned).
        List<ConceptCardDto> withIds = new ArrayList<>();
        List<Concept> saved = staging.getConcepts();
        for (int i = 0; i < cards.size(); i++) {
            UUID id = i < saved.size() ? saved.get(i).getId() : null;
            ConceptCardDto base = cards.get(i);
            withIds.add(new ConceptCardDto(id, base.name(), base.description(), base.captions(),
                    base.suggestedGenre(), base.suggestedType(), base.suggestedCapacity(), base.suggestedTiers()));
        }
        return new ConceptSetResponse(staging.getId(), withIds);
    }

    private ConceptSet generateWithRetry(ConceptSetRequest req, String brandBlock) {
        ConceptSet last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ConceptSet set = chat.prompt().user(buildPrompt(req, brandBlock)).call().entity(ConceptSet.class);
                if (set != null && set.concepts() != null && set.concepts().size() >= WANT
                        && set.concepts().stream().limit(WANT).allMatch(c ->
                            c.name() != null && !c.name().isBlank()
                            && c.description() != null && !c.description().isBlank())) {
                    return new ConceptSet(set.concepts().subList(0, WANT));
                }
                last = set;
            } catch (Exception e) {
                log.warn("Concept-set generation attempt {} failed: {}", attempt, e.getMessage());
            }
        }
        if (last != null && last.concepts() != null && !last.concepts().isEmpty()) {
            log.warn("Concept-set returned fewer than {} valid concepts; best-effort.", WANT);
            return last;
        }
        throw new com.imin.iminapi.security.ApiException(
                org.springframework.http.HttpStatus.BAD_GATEWAY,
                com.imin.iminapi.security.ErrorCode.UPSTREAM_UNAVAILABLE,
                "Concept generation service unavailable");
    }

    private String buildPrompt(ConceptSetRequest req, String brandBlock) {
        return """
                You are an expert event marketer naming and describing events for a ticketing app.
                Generate exactly THREE DISTINCT event concepts for the same brief — each a different
                creative angle (e.g. one moody/underground, one bold/hype, one intimate/curated).
                Return JSON ONLY matching this schema:

                {
                  "concepts": [
                    {
                      "name": "<short evocative event name, max 6 words>",
                      "description": "<one paragraph, 30-60 words, second person>",
                      "captions": {
                        "instagram": "<Instagram caption, 1-2 sentences + 2-4 hashtags>",
                        "tiktok": "<punchy TikTok caption, 1 sentence + trending-style hashtags>",
                        "x": "<concise X/Twitter post, <=200 chars>"
                      },
                      "suggested_genre": "<music genre>",
                      "suggested_type": "<one of: Festival, Rave, Club, Concert, Open Air>",
                      "suggested_capacity": <integer 50-2000>
                    }
                  ]
                }

                The three concepts must be meaningfully different from each other.
                %s
                Vibe: %s
                Genre: %s
                City: %s
                Capacity hint: %s
                """.formatted(
                        brandBlock,
                        req.vibe(),
                        req.genre() == null ? "(unspecified)" : req.genre(),
                        req.city() == null ? "(unspecified)" : req.city(),
                        req.capacity() == null ? "(unspecified)" : req.capacity().toString());
    }

    /** Brand conditioning block, failure-isolated: brandless org → empty string. */
    private String brandBlock(UUID orgId) {
        try {
            Organization o = orgs.findById(orgId).orElse(null);
            if (o == null) return "";
            List<String> colors = o.getBrandAccentColors() == null ? List.of() : o.getBrandAccentColors();
            boolean blank = (o.getBrandName() == null || o.getBrandName().isBlank()) && colors.isEmpty();
            if (blank) return "";
            StringBuilder sb = new StringBuilder("BRAND (match this identity's voice and palette):\n");
            if (o.getBrandName() != null && !o.getBrandName().isBlank())
                sb.append("Brand name: ").append(o.getBrandName()).append('\n');
            if (!colors.isEmpty())
                sb.append("Brand colors: ").append(String.join(", ", colors)).append('\n');
            return sb.toString();
        } catch (Exception e) {
            log.warn("Brand lookup failed; generating brandless: {}", e.getMessage());
            return "";
        }
    }

    /** Mirrors ConceptStudioService.buildTiers — three tiers from a pricing recommendation + capacity. */
    private static List<SuggestedTierDto> buildTiers(PricingRecommendation prices, Integer capacity) {
        BigDecimal min = prices.suggestedMinPrice() == null ? new BigDecimal("12") : prices.suggestedMinPrice();
        BigDecimal max = prices.suggestedMaxPrice() == null ? new BigDecimal("24") : prices.suggestedMaxPrice();
        BigDecimal mid = min.add(max).divide(new BigDecimal("2"), 2, java.math.RoundingMode.HALF_UP);
        int cap = capacity == null ? 250 : capacity;
        return List.of(
                new SuggestedTierDto("Early Bird", min.movePointRight(2).intValueExact(), Math.max(1, cap / 5)),
                new SuggestedTierDto("Standard",   mid.movePointRight(2).intValueExact(), Math.max(1, cap * 3 / 5)),
                new SuggestedTierDto("Door",       max.movePointRight(2).intValueExact(), Math.max(1, cap / 5)));
    }
}
```

> **Implementer notes:** Verify the actual `AuthPrincipal.orgId()` accessor, `Organization` brand getters (`getBrandName`, `getBrandAccentColors`), `PricingRecommendation` accessor names, and `ErrorCode.UPSTREAM_UNAVAILABLE` against the codebase before finalizing — fix any name mismatches. If the repo prefers `chat.prompt().user(...).call().entity(...)`, keep it; that matches `ConceptOverviewLlm`. If `GeneratedEvent.getConcepts()` returns the saved list with generated ids only after flush, the `repo.save` + read-back used above is sufficient inside the `@Transactional` method; if ids are null, call `repo.saveAndFlush` or `repo.save` then re-read.

- [ ] **Step 2: Run the Task 4 test until green**

Run: `./mvnw -q test -Dtest=ConceptSetServiceTest`
Expected: PASS (3 concepts, captions non-blank, tiers present, id non-null).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/imin/iminapi/service/ai/ConceptSetService.java src/test/java/com/imin/iminapi/service/ai/ConceptSetServiceTest.java
git commit -m "feat(ai-studio): ConceptSetService generates 3 captioned concepts"
```

---

## Task 6: Controller endpoint

**Files:**
- Modify: `src/main/java/com/imin/iminapi/controller/ai/ConceptController.java`

- [ ] **Step 1: Inject `ConceptSetService` and add the mapping**

Add the field + constructor param, then:

```java
    @PostMapping("/concepts")
    public ConceptSetResponse createSet(@CurrentUser AuthPrincipal p,
                                        @Valid @RequestBody ConceptSetRequest body) {
        rateLimiter.consume("ai-concept", p.userId().toString());
        return conceptSet.create(p, body);
    }
```

Add imports for `ConceptSetRequest`/`ConceptSetResponse`. Constructor becomes
`public ConceptController(ConceptStudioService studio, ConceptSetService conceptSet, RateLimiter rateLimiter)`.

- [ ] **Step 2: Controller test (mocked service)**

Create `controller/ai/ConceptControllerConceptsTest.java` following the repo's controller-test convention (MockMvc slice or full context with mocked `ConceptSetService` + `RateLimiter`). Assert: `POST /api/v1/ai/events/concepts` with a valid body returns 200 and a body with `concepts` length 3; a blank `vibe` returns 400 (bean validation).

- [ ] **Step 3: Run + commit**

```bash
./mvnw -q test -Dtest=ConceptControllerConceptsTest
git add src/main/java/com/imin/iminapi/controller/ai/ConceptController.java src/test/java/com/imin/iminapi/controller/ai/ConceptControllerConceptsTest.java
git commit -m "feat(ai-studio): POST /ai/events/concepts endpoint"
```

---

## Task 7: Full verification

- [ ] **Step 1: Full test suite**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS (all green). Investigate any regression.

- [ ] **Step 2: Confirm OpenAPI exposes the new endpoint**

Run: `./mvnw -q spring-boot:run` (with `MEDIA_ENABLED=false` and dummy keys) is optional; otherwise trust the `@RestController` mapping. The FE consumes a hand-authored type, so a deploy is required before `imin-webapp api:sync` reflects this.

- [ ] **Step 3: Final commit if anything outstanding**

---

## Self-review (spec coverage)

- 3 concepts + IG/TikTok/X captions → Tasks 1,2,5. ✅
- Brand conditioning (name + colors) → Task 5 `brandBlock`. ✅
- suggested genre/type/capacity/tiers → Tasks 1,5. ✅
- No poster pipeline → new service never calls `PosterOrchestrator`. ✅
- Persistence (Concept rows + captions) → Tasks 3,5. ✅
- Rate limit reuse → Task 6. ✅
- Error handling (retry, best-effort, 502) → Task 5 `generateWithRetry`. ✅
- Tests → Tasks 4,6,7. ✅

**Deferred (per spec §8):** per-card regenerate/lock; SocialCopy linkage (captions live on `Concept`).
