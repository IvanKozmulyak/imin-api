# AI Event Creator — Design Spec

**Date:** 2026-04-15  
**Status:** Approved

---

## Overview

The AI Event Creator takes a natural language "vibe" description from an organizer and orchestrates multiple AI calls to generate a launch-ready event concept. The result is persisted as a draft `GeneratedEvent` record and returned in full to the caller.

---

## Package Structure

```
com.imin.iminapi/
  controller/
    EventCreatorController.java     # POST /api/events/ai-create
  service/
    EventCreatorService.java        # orchestrates all 3 steps
    ImageGenerationService.java     # Step 2: DALL-E calls
    PricingService.java             # Step 3: smart pricing query
  dto/
    EventCreatorRequest.java        # vibe, tone, platforms, genre, city, date
    EventCreatorResponse.java       # returned to client
    LlmGenerationResult.java        # 3 concepts + social copy + 5 hex colors
    LlmEventConcept.java            # single concept (title, description, tagline)
    PricingRecommendation.java      # suggested price tiers + notes
  model/
    GeneratedEvent.java             # @Entity — persisted draft
    Concept.java                    # @Entity — one of 3 event concepts
    SocialCopy.java                 # @Entity — per-platform copy text
  repository/
    GeneratedEventRepository.java   # JpaRepository — used for pricing queries
  config/
    OpenRouterConfig.java           # ChatClient bean pointed at OpenRouter
```

---

## Data Model

### `generated_event`
| Column | Type | Notes |
|---|---|---|
| id | UUID | PK |
| vibe | TEXT | |
| tone | VARCHAR | |
| genre | VARCHAR | |
| city | VARCHAR | |
| event_date | DATE | |
| platforms | TEXT | comma-separated platform names |
| accent_colors | TEXT | comma-separated hex codes |
| poster_urls | TEXT | comma-separated DALL-E URLs |
| suggested_min_price | NUMERIC | |
| suggested_max_price | NUMERIC | |
| pricing_notes | TEXT | |
| status | VARCHAR | DRAFT, COMPLETE, FAILED |
| created_at | TIMESTAMP | |

### `concept`
| Column | Type | Notes |
|---|---|---|
| id | UUID | PK |
| generated_event_id | UUID | FK → generated_event.id |
| title | VARCHAR | |
| description | TEXT | |
| tagline | VARCHAR | |
| sort_order | INTEGER | 1, 2, or 3 |

### `social_copy`
| Column | Type | Notes |
|---|---|---|
| id | UUID | PK |
| generated_event_id | UUID | FK → generated_event.id |
| platform | VARCHAR | e.g. INSTAGRAM, TWITTER |
| copy_text | TEXT | |

`GeneratedEvent` has `@OneToMany` to both `Concept` and `SocialCopy`. Schema managed via a single Liquibase changeset.

---

## Service Workflow

### `EventCreatorService.create(request)`

**Step 1 — LLM Generation (synchronous)**
- Build a prompt combining `vibe`, `tone`, `genre`, `city`, `date`, and `platforms`.
- Call OpenRouter via Spring AI's `ChatClient` with a `BeanOutputConverter<LlmGenerationResult>`.
- Response is parsed into a typed POJO: 3 `LlmEventConcept` objects, per-platform social copy, and 5 hex accent colors.
- Persist `GeneratedEvent` with `status=DRAFT`.

**Step 2 — Image Generation (parallel, then block)**
- Extract the first accent color as the "primary" color.
- Spawn 3 `CompletableFuture` calls to `ImageGenerationService.generatePoster(concept, primaryColor)`.
- Each call uses Spring AI's `ImageClient` (DALL-E).
- Call `CompletableFuture.allOf(...).join()` to block until all 3 are complete.
- Store the 3 returned URLs on the entity.

**Step 3 — Smart Pricing**
- Call `PricingService.recommend(genre, city, date)`.
- Queries `GeneratedEventRepository` for `GeneratedEvent` records with matching `genre` and `city` within ±30 days of the requested date, status = COMPLETE.
- Averages `suggestedMinPrice` and `suggestedMaxPrice` of comparables.
- If no comparables exist, returns a genre-based static default price range.
- Stores result fields on the entity.

**Finalize**
- Update `status=COMPLETE`, save, return `EventCreatorResponse` DTO.
- On any unrecoverable error, set `status=FAILED`, save, and rethrow.

### `OpenRouterConfig`
- Declares a `ChatClient` bean.
- Sets `spring.ai.openai.base-url=https://openrouter.ai/api/v1` in `application.yaml`.
- Model name configured via `spring.ai.openai.chat.options.model` (e.g. `openai/gpt-4o`).

---

## API Contract

### `POST /api/events/ai-create`

**Request body:**
```json
{
  "vibe": "underground techno night in a warehouse",
  "tone": "edgy",
  "genre": "techno",
  "city": "Berlin",
  "date": "2026-06-14",
  "platforms": ["INSTAGRAM", "TWITTER"]
}
```

**Response `201 Created`:**
```json
{
  "id": "uuid",
  "status": "COMPLETE",
  "accentColors": ["#1A1A2E", "#E94560", "#0F3460", "#533483", "#2B2D42"],
  "posterUrls": ["https://...", "https://...", "https://..."],
  "concepts": [
    { "title": "...", "description": "...", "tagline": "...", "sortOrder": 1 }
  ],
  "socialCopy": [
    { "platform": "INSTAGRAM", "copyText": "..." },
    { "platform": "TWITTER", "copyText": "..." }
  ],
  "pricing": {
    "suggestedMinPrice": 15.00,
    "suggestedMaxPrice": 25.00,
    "pricingNotes": "Based on 3 comparable techno events in Berlin"
  },
  "createdAt": "2026-04-15T10:00:00Z"
}
```

Controller is thin: validates with `@Valid`, delegates to `EventCreatorService`, maps to response DTO.

---

## Configuration (`application.yaml` additions)

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENROUTER_API_KEY}
      base-url: https://openrouter.ai/api/v1
      chat:
        options:
          model: openai/gpt-4o
      image:
        options:
          model: dall-e-3
          size: 1024x1024
```

---

## Error Handling

- LLM parse failure → `status=FAILED`, rethrow as `EventCreationException`
- DALL-E partial failure (1 of 3 fails) → fail the whole request, `status=FAILED`
- No comparable events for pricing → use static genre defaults, still `status=COMPLETE`
