# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Tech Stack

- **Language**: Java 17
- **Framework**: Spring Boot 4.0.5
- **Build Tool**: Maven (via `./mvnw` wrapper)
- **Database**: PostgreSQL 17 (Docker Compose for dev; H2 in PG-compat mode for tests)
- **ORM**: Spring Data JPA + Flyway migrations
- **REST**: Spring Data REST + `@RestController` for custom endpoints; SpringDoc OpenAPI
- **Security**: Spring Security (SAML2 deps present but not wired; `/api/**` routes are currently `permitAll`)
- **AI**: Spring AI `ChatClient` — primary bean points at **OpenRouter** (OpenAI-compatible), not OpenAI directly
- **Image gen**: Replicate → Ideogram V3 Turbo
- **Codegen**: Lombok

## Commands

```bash
# Start the dev PostgreSQL (port 5433, see compose.yaml)
docker compose up -d

# Run the app (dev profile is the default; server on :8085)
./mvnw spring-boot:run

# Build
./mvnw clean package

# Tests — use H2 in-memory, no external services
./mvnw test
./mvnw test -Dtest=ClassName
./mvnw test -Dtest=ClassName#methodName
```

Required env vars for running locally (app will log warnings / fail requests otherwise):
- `OPENROUTER_API_KEY` — LLM calls (concept generation, reference image analysis)
- `REPLICATE_API_TOKEN` — Ideogram image generation (must start with `r8_`); only needed when `imageProvider=REPLICATE` (the default)
- `OPENAI_API_KEY` — required by Spring AI starter even when OpenRouter is `@Primary` (any non-empty value works); also used directly by `OpenAiImageClient` when `imageProvider=OPENAI`
- Optional: `OPENAI_IMAGE_MODEL` (default `gpt-image-1`) — set to e.g. `gpt-image-2` to test a newer model without code changes

Swagger UI: `http://localhost:8085/swagger-ui.html` (dev only; disabled in prod).

## Architecture

The core feature is **AI event poster generation**. Entry point: `IminApiApplication.java` at `src/main/java/com/imin/iminapi/`.

### Request flow: `POST /api/events/ai-create`

```
EventCreatorController
  → EventCreatorService               (transaction boundary, persists GeneratedEvent)
      → AiEventDescriptionService     (1 LLM call → PosterConcept with 3 variants)
      → PosterOrchestrator
          → IdeogramClient            (3 sequential calls, bounded by Semaphore(6))
              → ReplicateClient       (POST /v1/models/{model}/predictions, Prefer: wait=60)
          → PosterImageStorage.download  (raw PNG from Replicate CDN)
          → OverlayCompositor         (Java2D: zxing QR + address band only)
          → PosterImageStorage.writePng (local disk → /images/{uuid}.png)
```

Served via `/images/**` → filesystem mapping in `WebConfig` (dir from `replicate.image.storage-dir`, default `./generated-images`).

### Key design decisions (see `docs/decisions/ADR-0001-ideogram-direct.md`)

- **Typography lives inside the generated image**, not in the compositor. Ideogram V3 renders quoted strings as actual typography at ~90–95% accuracy. The prompt builder in `AiEventDescriptionService.buildPrompt` wraps every text element in double quotes.
- **`OverlayCompositor` only draws QR code + address band.** These two must be character-perfect; everything else is trusted to the model. Do not add more overlay logic here without revisiting the ADR.
- **Image-gen provider switch.** `EventCreatorRequest.imageProvider` (enum `REPLICATE` | `OPENAI`, default `REPLICATE`) selects the backend. `PosterOrchestrator.renderVariant` branches between `IdeogramClient` (Replicate) and `OpenAiImageClient` (OpenAI `/v1/images/edits` or `/v1/images/generations`). Same prompts and reference images feed both; aspect-ratio maps in `OpenAiImageClient.mapAspectRatio` squash to OpenAI's supported sizes (1024x1024, 1024x1536, 1536x1024).
- **Local disk storage for Phase 1.** `PosterImageStorage` is the seam — swap it for R2/S3 without touching callers.

### Sub-style tags & reference images

- Valid sub-style tags are a **closed set** declared in `AiEventDescriptionService.VALID_SUB_STYLE_TAGS` (7 tags). Adding a tag requires updates in three places: that constant, `src/main/resources/poster-references.yaml`, and the reference image files under `src/main/resources/reference-images/`.
- `ReferenceImageLibrary` loads classpath images at startup as data URIs and passes them to Ideogram via `style_reference_images`. It also caches a natural-language **style descriptor** per tag in the `style_reference_analysis` table. Cache key = SHA-256 signature over the tag's reference bytes + the analyzer `model_id`; descriptors regenerate automatically when either changes. The descriptor is injected into the concept prompt so the LLM matches the visual aesthetic of the references.
- `ReferenceImageAnalyzer` uses the same `ChatClient` multimodally (image input) — the model must be vision-capable.

### LLM configuration

`OpenRouterConfig` declares the `@Primary ChatClient`. It normalizes `openrouter.base-url` by stripping a trailing `/v1` because Spring AI's OpenAI client appends `/v1` itself — if you change the base URL, keep that normalization in mind. Model id comes from `openrouter.model` (default `openai/gpt-4o-mini`).

### Conventions

- **Database schema**: Flyway SQL migrations only, in `src/main/resources/db/migration/` (`V1__`, `V2__`, …). Never modify an existing migration; always add a new forward migration.
- **Config**: `application.yaml` + profile-specific `application-dev.yaml` / `application-prod.yaml`. Dev is the default profile. Secrets via env vars; never hardcode.
- **REST endpoints**: Spring Data REST for plain CRUD; `@RestController` (as under `controller/`) when the flow has custom logic.
- **Tests**: `src/test/resources/application.yaml` pins `spring.profiles.active=test`, disables docker-compose integration, and uses H2 with PG dialect + Flyway. External services (OpenRouter, Replicate, Ideogram) must be mocked in tests.
- **Security**: `/api/events/**`, `/api/posters/**`, `/images/**`, `/swagger-ui/**` are `permitAll`. If you add new API surface that should be public, update `SecurityConfig` explicitly.

### Planning docs

`docs/superpowers/` contains specs and plans (`specs/…-design.md` + `plans/…`) for feature work — useful context when a task references a plan by date/name. `docs/decisions/` holds ADRs.
