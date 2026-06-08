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
- **Image gen**: native Ideogram V3 API (generate at QUALITY, corrective remix at TURBO)
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
- `OPENROUTER_API_KEY` — LLM calls (art-director concept generation, reference image analysis, and the vision text/style gates via `openai/gpt-4o-mini`)
- `IDEOGRAM_API_KEY` — native Ideogram V3 poster rendering (generate + remix). Required for poster generation.
- `OPENAI_API_KEY` — required by the Spring AI starter even when OpenRouter is `@Primary` (any non-empty value works)
- Optional: `IDEOGRAM_GENERATE_RENDERING_SPEED` (default `QUALITY`), `IDEOGRAM_REMIX_RENDERING_SPEED` (default `TURBO`), `IDEOGRAM_REMIX_IMAGE_WEIGHT` (default `70`), `IDEOGRAM_MAX_REFERENCES` (default `3`)
- `RESEND_API_KEY` — Resend API key (required to send any auth email; signup/verify/resend/forgot-password fail without it)
- `IMIN_EMAIL_FROM_ADDRESS` — sender email address (default `noreply@imin.local` — must be a verified Resend sender in prod)
- `IMIN_EMAIL_FROM_NAME` — sender display name (default `imin`)
- `IMIN_EMAIL_REPLY_TO` — optional reply-to header
- `IMIN_APP_BASE_URL` — frontend base URL used to build password-reset links (default `http://localhost:3000`)

Object storage (Cloudflare R2, S3-compatible) for generated poster images and event-media uploads. Bound to `imin.media.*`; consumed by `R2Config` (builds the `S3Client`) and `R2MediaStorage`, both gated on `imin.media.enabled`:
- `MEDIA_ENABLED` — master switch for R2 object storage (default `true`). When `true`, `PosterImageStorage` uploads PNGs to R2 under key `ai-posters/{uuid}.png` and returns the R2 public URL; when `false`, posters are written to local disk and served via `/images/**`. **For local dev without R2 credentials, set `MEDIA_ENABLED=false`** — the `S3Client` is built eagerly at startup, so `MEDIA_ENABLED=true` with an empty/invalid `R2_ENDPOINT` fails app startup.
- `R2_ENDPOINT` — R2 S3 API endpoint, e.g. `https://<account-id>.r2.cloudflarestorage.com` (no default; required when `MEDIA_ENABLED=true`)
- `R2_ACCESS_KEY_ID` — R2 access key id (no default; required when `MEDIA_ENABLED=true`)
- `R2_SECRET_ACCESS_KEY` — R2 secret access key (no default; required when `MEDIA_ENABLED=true`)
- `R2_BUCKET` — target bucket (default `imin-media-dev`)
- `R2_REGION` — region passed to the SDK; R2 ignores it but one is required (default `auto`)
- `R2_PUBLIC_URL_PREFIX` — public read base URL for the bucket (R2 public bucket domain or custom domain); returned image URLs are `<prefix>/<key>` (no default; required when `MEDIA_ENABLED=true`)

Swagger UI: `http://localhost:8085/swagger-ui.html` (dev only; disabled in prod).

## Architecture

The core feature is **AI event poster generation**. Entry point: `IminApiApplication.java` at `src/main/java/com/imin/iminapi/`.

### Request flow: `POST /api/v1/ai/events/concept`

```
ConceptController → ConceptStudioService
  → AiEventDescriptionService     (Sonnet 4.6 via OpenRouter → PosterConcept: 3 hero-typed variants)
  → PosterOrchestrator            (3 variants in parallel, bounded by Semaphore)
      per variant:
        IdeogramV3Client.generate (native API, multipart, QUALITY, 4x5, magic_prompt OFF,
                                   one style control: reference images | style_preset)
        → text gate (hard)  → on fail: IdeogramV3Client.remix(failing image + correction
                              prompt, TURBO) up to the regen budget, then best-effort
        → style gate (soft) → best-effort on fail
      → PosterImageStorage.writePng (R2 when MEDIA_ENABLED, else local disk → /images/{uuid}.png)
```

Text is fully baked by the model — there is no QR/address/Satori overlay; the downloaded PNG is final.
The native Ideogram render flow is specced in `docs/superpowers/specs/2026-06-08-ideogram-v3-native-render-design.md`.

### Key design decisions (see `docs/decisions/ADR-0001-ideogram-direct.md`)

- **Typography lives inside the generated image**, not in a compositor. Ideogram V3 renders the required event text as actual typography; the art-director prompt writes the exact strings into the render prompt, and the vision **text gate** verifies legibility (re-rendering via remix on failure).
- **Exactly one style control.** Ideogram V3 accepts reference images OR a style preset OR style codes — never combined. This flow attaches the vibe's 1–3 curated reference images (`style_reference_images`, ≤10 MB); the per-vibe `ideogram_style_preset` is the no-refs fallback only.
- **No post-render overlay.** All text is model-baked, so there is no QR/address band or Satori real-font layer. (`ImageProvider` enum + the Recraft style-training endpoint remain in the tree but are off the render path.)
- **Object storage (Cloudflare R2) with local-disk fallback.** `PosterImageStorage` is the seam: when `MEDIA_ENABLED=true` it uploads PNGs via the `MediaStorage` bean (`R2MediaStorage`, key `ai-posters/{uuid}.png`) and returns the R2 public URL; otherwise — or if a put fails — it writes to local disk and returns an absolute `/images/{uuid}.png` URL. The R2 `S3Client` is built in `R2Config`, and both it and `R2MediaStorage` are gated on `imin.media.enabled`. See the R2 env vars above.

### Sub-style tags & reference images

- Valid sub-style tags are a **closed set** declared in `AiEventDescriptionService.VALID_SUB_STYLE_TAGS` (7 tags). Adding a tag requires updates in three places: that constant, `src/main/resources/poster-references.yaml`, and the reference image files under `src/main/resources/reference-images/`.
- `ReferenceImageLibrary` loads classpath images at startup as data URIs and passes them to Ideogram via `style_reference_images`. It also caches a natural-language **style descriptor** per tag in the `style_reference_analysis` table. Cache key = SHA-256 signature over the tag's reference bytes + the analyzer `model_id`; descriptors regenerate automatically when either changes. The descriptor is injected into the concept prompt so the LLM matches the visual aesthetic of the references.
- `ReferenceImageAnalyzer` uses the same `ChatClient` multimodally (image input) — the model must be vision-capable.

### Stripe Connect

Per-org Stripe v2 connected accounts (one acct_... per `Organization`). Tickets sync to platform-level Stripe Products+Prices. Buyers go through hosted Checkout via destination charges that transfer to the org's connected account minus a platform application fee (default 5%, configurable). See `com.imin.iminapi.stripe.*`.

Required env vars:
- `STRIPE_SECRET_KEY` — sk_test_... locally, sk_live_... in prod. App fails to start if missing.
- `STRIPE_WEBHOOK_SECRET_V1` — whsec_... for the V1 payments webhook endpoint (`/api/v1/stripe/webhook/v1`).
- `STRIPE_WEBHOOK_SECRET_V2` — whsec_... for the V2 thin-events webhook endpoint (`/api/v1/stripe/webhook/v2`).
- Optional: `STRIPE_APPLICATION_FEE_BPS` (default 500 = 5%), `STRIPE_PUBLIC_RETURN_URL_BASE` (default `http://localhost:3000`), `STRIPE_RETURN_URL_BASE` (default `http://localhost:5173`), `STRIPE_CHECKOUT_SESSION_TTL_MINUTES` (default 30, Stripe's documented minimum).

Endpoints:
- `POST /api/v1/orgs/{orgId}/stripe/connect` — idempotent create. Empty body.
- `POST /api/v1/orgs/{orgId}/stripe/account-session` — short-lived `clientSecret` for the FR embedded onboarding (`@stripe/connect-js`).
- `POST /api/v1/orgs/{orgId}/stripe/onboarding-link` — one-shot URL for the organizer's browser (non-FR redirect flow).
- `GET  /api/v1/orgs/{orgId}/stripe/status` — reads the local mirror (one-shot lazy sync on the first read). Returns `accountId`, `state`, `readyToReceivePayments`, `detailsSubmitted`, `currentlyDue`, `pastDue`, `disabledReason`.
- `POST /api/v1/public/events/{eventId}/checkout` — public; returns a hosted Checkout URL.
- `POST /api/v1/stripe/webhook/v1` — signature-verified V1 payments webhook receiver.
- `POST /api/v1/stripe/webhook/v2` — signature-verified V2 thin-events webhook receiver.

**FR vs non-FR onboarding (revised 2026-05-15).** `StripeConnectService.getOrCreateAccount` no longer branches on country — all orgs use the same v2 create payload (`identity.country`, `configuration.recipient.capabilities.stripe_balance.stripe_transfers.requested=true`, EUR defaults inferred from country, fees/losses-collector=APPLICATION). The earlier FR-specific account-token path was scrapped because Stripe.js v9 only mints v1 tokens which the v2 Accounts API rejects (`v1_token_invalid_in_v2`).

PSD2 compliance for FR orgs is now delivered via **embedded onboarding** with `@stripe/connect-js` instead. After the account is created, the FE calls `POST /api/v1/orgs/{orgId}/stripe/account-session` to get a short-lived `client_secret` from `/v1/account_sessions` with `components.account_onboarding.enabled=true`, then renders Stripe's `<ConnectAccountOnboarding>` web component which iframes the onboarding flow — the user's PII goes straight from browser to Stripe, never through our server.

Non-FR orgs continue to use the hosted AccountLink redirect (`POST /stripe/onboarding-link`) — the same flow as before.

Webhook setup (two endpoints, two signing secrets):

V1 and V2 events ship in structurally different JSON payloads (Stripe's dashboard flags mixed endpoints with "you've selected events with two different payload styles"). We split them: each format gets its own URL and its own signing secret. Configure two endpoints in the Stripe Dashboard → Developers → Webhooks:

1. **`POST /api/v1/stripe/webhook/v1`** — secret env `STRIPE_WEBHOOK_SECRET_V1`. Subscribe to:
   - `payment_intent.succeeded` — fulfilment (confirm sold, issue Order + Tickets, increment promo usage)
   - `payment_intent.payment_failed` — release the inventory hold for declines / 3DS failure
   - `checkout.session.expired` — release the inventory hold when the buyer abandons the 30-minute session
   - `refund.updated` **and** `refund.failed` — refund status transitions (pending → succeeded/failed) for **all** refund types. These are the unified events (Acacia 2024-10-28); subscribe to both.
   - `charge.refund.updated` — legacy alias kept for "selected payment methods"; handled too (deduped). Subscribe alongside the `refund.*` events, do not rely on it alone.
   - Do NOT subscribe to `checkout.session.completed`; fulfilment is driven by `payment_intent.succeeded` because the PI is what proves money moved. The handler intentionally no-ops on `completed`.

2. **`POST /api/v1/stripe/webhook/v2`** — secret env `STRIPE_WEBHOOK_SECRET_V2`. Subscribe to (Stripe uses **bracket notation** in `event.type` — the literal strings below):
   - `v2.core.account[requirements].updated`
   - `v2.core.account[configuration.recipient].capability_status_updated`
   - `v2.core.account[configuration.recipient].updated`, `v2.core.account[future_requirements].updated`, `v2.core.account.updated` — recommended extra coverage; the handler matches the whole `v2.core.account…` family by prefix (`StripeWebhookService.V2_ACCOUNT_STATE_TYPES`).

For local dev with the Stripe CLI, point two `stripe listen` processes at the two endpoints (each will print its own `whsec_...` secret to paste into the matching env var). Or use `stripe listen --load-from-webhooks-api --forward-to http://localhost:8085/api/v1/stripe/webhook/v1` once per endpoint after the dashboard config is in place.

Reservation lifecycle + safety net: every checkout writes a `ticket_reservations` row in `HELD` state with `expires_at` mirroring the Stripe session TTL. The `reservation_id` is stamped onto both the Session and PaymentIntent metadata, so the V1 webhook handler resolves the right hold deterministically. If a webhook misses (signature mismatch, endpoint down, Stripe gives up after retries), the `ReservationSweeper` `@Scheduled` job releases any `HELD` row past its `expires_at` every 60 seconds — webhooks are the fast path, the sweeper is the source of truth. ShedLock serializes the sweep across replicas via the `shedlock` table.

Promo code redemption tracking: when a buyer uses a promo code at checkout, the session is created with `metadata.promo_id`. On `payment_intent.succeeded` (paid), the webhook atomically increments `promo_codes.used_count`. At-least-once delivery is bounded by the `processed_webhook_events` dedup table (V25 migration) — the dedup INSERT and the increment share a transaction, so a Stripe retry sees the marker and skips.

State model: account ids are persisted (`organizations.stripe_account_id`, `ticket_tiers.stripe_product_id`, `ticket_tiers.stripe_price_id`). Connect onboarding/capability state is **mirrored locally** to `organizations.stripe_connect_state` (enum: `NOT_STARTED` / `ONBOARDING` / `PENDING_VERIFICATION` / `RESTRICTED` / `DISABLED` / `ACTIVE`) plus `stripe_payouts_enabled`, `stripe_details_submitted`, `stripe_requirements_currently_due` (jsonb), `stripe_requirements_past_due` (jsonb), `stripe_disabled_reason`, `stripe_connect_status_updated_at`. The mirror is driven by the v2 webhook (`v2.core.account[requirements].updated`, `v2.core.account[configuration.recipient].capability_status_updated`) via `StripeConnectStatusMirror`, with the `StripeConnectStatusSweeper` (`@Scheduled` + ShedLock) re-reconciling any non-terminal org whose mirror has gone stale as the backstop for missed webhooks.

**Submission/verification is derived from the recipient `stripe_transfers` capability, NOT from `minimum_deadline.status`.** The v2 `requirements…minimum_deadline.status` is only ever `currently_due`/`eventually_due`/`past_due` — it never carries `verified`/`pending_verification`. So `StripeConnectStatusMirror.applyTo` reads `configuration.recipient.capabilities.stripe_balance.stripe_transfers.{status,status_details}`: `active` ⇒ payouts/ACTIVE; `pending` or `status_details.code=requirements_pending_verification` ⇒ submitted/PENDING_VERIFICATION; `unsupported` or `status_details.resolution=contact_stripe` ⇒ terminal DISABLED. `details_submitted` is sticky — once observed true, never un-flagged. `DISABLED` is terminal/unrecoverable from the organizer side (the FE shows "contact support", not a retry CTA).

`GET /stripe/status` reads the mirror (one-shot lazy sync on the first read). The buyer-checkout readiness gate uses `StripeConnectService.getStatusLive`, which force-refreshes from Stripe when the cached state isn't a recently-synced `ACTIVE` (degrading to the mirror if Stripe is unreachable) so a freshly verified org isn't wrongly blocked and a just-disabled one isn't wrongly allowed.

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
