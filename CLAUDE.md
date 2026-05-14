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
- `RESEND_API_KEY` — Resend API key (required to send any auth email; signup/verify/resend/forgot-password fail without it)
- `IMIN_EMAIL_FROM_ADDRESS` — sender email address (default `noreply@imin.local` — must be a verified Resend sender in prod)
- `IMIN_EMAIL_FROM_NAME` — sender display name (default `imin`)
- `IMIN_EMAIL_REPLY_TO` — optional reply-to header
- `IMIN_APP_BASE_URL` — frontend base URL used to build password-reset links (default `http://localhost:3000`)

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

### Stripe Connect

Per-org Stripe v2 connected accounts (one acct_... per `Organization`). Tickets sync to platform-level Stripe Products+Prices. Buyers go through hosted Checkout via destination charges that transfer to the org's connected account minus a platform application fee (default 5%, configurable). See `com.imin.iminapi.stripe.*`.

Required env vars:
- `STRIPE_SECRET_KEY` — sk_test_... locally, sk_live_... in prod. App fails to start if missing.
- `STRIPE_WEBHOOK_SECRET` — whsec_... from `stripe listen` output. Only needed for the webhook endpoint.
- Optional: `STRIPE_APPLICATION_FEE_BPS` (default 500 = 5%), `STRIPE_PUBLIC_RETURN_URL_BASE` (default `http://localhost:3000`), `STRIPE_RETURN_URL_BASE` (default `http://localhost:5173`).

Endpoints:
- `POST /api/v1/orgs/{orgId}/stripe/connect` — idempotent create.
- `POST /api/v1/orgs/{orgId}/stripe/onboarding-link` — one-shot URL for the organizer's browser.
- `GET  /api/v1/orgs/{orgId}/stripe/status` — live (never cached). Returns `readyToReceivePayments`, `onboardingComplete`, `requirementsStatus`.
- `POST /api/v1/public/events/{eventId}/checkout` — public; returns a hosted Checkout URL.
- `POST /api/v1/stripe/webhook` — signature-verified webhook receiver.

**FR vs non-FR onboarding (added 2026-05-14).** `StripeConnectService.getOrCreateAccount` branches on `organization.country`:
- **FR orgs (PSD2):** the `POST /stripe/connect` body must include `accountToken` + `personToken` minted by Stripe.js in the organizer dashboard (`@stripe/stripe-js` → `stripe.createToken('account', ...)` + `stripe.createToken('person', ...)`). The v2 create call sends only `accountToken`, dashboard, and defaults (responsibilities + locale) — no `identity` or `configuration` server-side, and no explicit currency (Stripe picks it from the account's country). The person token is consumed via `v2().core().accounts().persons().create(...)` immediately after the account is created. v0 supports `business_type=company` only.
- **Non-FR orgs:** `POST /stripe/connect` body must be empty (token bodies are strictly rejected with `400 INVALID_REQUEST`). The v2 create includes `identity.country = org.country`, `configuration.recipient.capabilities.stripe_balance.stripe_transfers.requested=true`, and defaults (responsibilities + locale, currency inferred from country).

Both paths still finish through the hosted `/stripe/onboarding-link` redirect — that picks up ID-document uploads, bank account, and any leftover requirements the tokens didn't cover.

Webhook dev setup:

The single endpoint at `/api/v1/stripe/webhook` handles both webhook formats — V2 thin events for Connect account state, and V1 events for payment lifecycle. Routing is by JSON peek (see `StripeWebhookService.looksLikeV2ThinEvent`). One signing secret covers both because Stripe signs both with the same HMAC scheme — so configure ONE endpoint in your Stripe dashboard (or one `stripe listen --load-from-webhooks-api`) with the union of these event types:

- V2 (Connect account): `v2.core.account.requirements.updated`, `v2.core.account.recipient.capability_status_updated`
- V1 (payments): `checkout.session.completed`

For ad-hoc local listening without dashboard config, two separate `stripe listen` commands work but each produces a different signing secret — easier to do `stripe listen --load-from-webhooks-api --forward-to http://localhost:8085/api/v1/stripe/webhook` against a dashboard endpoint set up once.

Promo code redemption tracking: when a buyer uses a promo code at checkout, the session is created with `metadata.promo_id`. On `checkout.session.completed` (paid), the webhook atomically increments `promo_codes.used_count`. At-least-once delivery means a Stripe redelivery can over-count slightly — bounded, but the proper fix is a `processed_webhook_events` dedup table (not yet wired).

State model: account ids are persisted (`organizations.stripe_account_id`, `ticket_tiers.stripe_product_id`, `ticket_tiers.stripe_price_id`); onboarding/capability state is fetched live from Stripe on every status read.

### LLM configuration

`OpenRouterConfig` declares the `@Primary ChatClient`. It normalizes `openrouter.base-url` by stripping a trailing `/v1` because Spring AI's OpenAI client appends `/v1` itself — if you change the base URL, keep that normalization in mind. Model id comes from `openrouter.model` (default `openai/gpt-4o-mini`).

### Conventions

- **Database schema**: Flyway SQL migrations only, in `src/main/resources/db/migration/` (`V1__`, `V2__`, …). Never modify an existing migration; always add a new forward migration.
- **Config**: `application.yaml` + profile-specific `application-dev.yaml` / `application-prod.yaml`. Dev is the default profile. Secrets via env vars; never hardcode.
- **REST endpoints**: Spring Data REST for plain CRUD; `@RestController` (as under `controller/`) when the flow has custom logic.
- **Tests**: `src/test/resources/application.yaml` pins `spring.profiles.active=test`, disables docker-compose integration, and uses H2 with PG dialect + Flyway. External services (OpenRouter, Replicate, Ideogram) must be mocked in tests.
- **Security**: `/api/events/**`, `/api/posters/**`, `/images/**`, `/swagger-ui/**` are `permitAll`. If you add new API surface that should be public, update `SecurityConfig` explicitly.
- **Auth flows email users via Resend.** Signup persists the user with `verified_at = NULL` and emails a 4-digit code; login is hard-blocked with `403 EMAIL_NOT_VERIFIED` until verification. Password reset uses a long random token link. See `docs/superpowers/specs/2026-05-04-resend-integration-design.md`.

### Planning docs

`docs/superpowers/` contains specs and plans (`specs/…-design.md` + `plans/…`) for feature work — useful context when a task references a plan by date/name. `docs/decisions/` holds ADRs.
