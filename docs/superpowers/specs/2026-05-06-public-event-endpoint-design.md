# Public event endpoint

**Status:** Draft
**Date:** 2026-05-06

## Goal

Expose a single read-only public endpoint that returns one published event plus its visible ticket tiers, suitable for an unauthenticated event-detail page (the "buy tickets" landing page). Specifically:

`GET /api/v1/public/events/{id}` — no auth, returns the event if and only if it is published and publicly visible. Returns operator-safe fields only — never leaks organizer email, internal IDs, or unpublished sibling events.

A non-goal of this spec is implementing CDN/edge cache invalidation. The endpoint emits `Cache-Control` headers; integration with Cloudflare (or any other CDN) — including tag-based purge on organizer mutations — is a separate spec.

A non-goal is cart/checkout, promo-code redemption, or "more from this organizer" listings. Those are separate endpoints.

## Background

The `events` schema already exists (V6). Relevant columns:

- `status VARCHAR(16)` — values `DRAFT / LIVE / PAST / CANCELLED` (see `EventStatus`).
- `visibility VARCHAR(16)` — values `PUBLIC / PRIVATE` (see `EventVisibility`).
- `published_at TIMESTAMP` — set when the organizer publishes the event; never cleared.
- `deleted_at TIMESTAMP` — soft-delete marker.
- `slug VARCHAR(255)` — unique per `(org_id, slug)`, NOT globally unique.

Tiers (`ticket_tiers`):

- `enabled BOOLEAN` — organizer-controlled retraction.
- `quantity INTEGER`, `sold INTEGER` — raw inventory; `remaining = quantity - sold`.
- `sale_closes_at TIMESTAMP` — optional tier-level cutoff.
- `sort_order INTEGER` — display order.

Organizations (`organizations`) currently have **no `slug` column**; this spec adds one (V15 migration) so the public response can include a stable identifier for the organizer that does not leak the internal UUID.

The closest existing precedent for an exposed read-only response shape is `EventDto`, which is operator-only and includes fields that must not appear in the public response (`createdBy`, `revenueMinor`, `sold`, `prediction`, `promoCodes`, etc.). The public DTO is therefore a separate type with its own allow-listed fields, not a subclass / variant of `EventDto`.

## Decisions taken during brainstorming

For traceability:

1. **Lookup key:** UUID, not slug. URL is `/api/v1/public/events/{id}` (slugs are not globally unique; rather than make them so, we look up by id).
2. **Eligibility:** `published_at IS NOT NULL` AND `status <> DRAFT` AND `visibility = PUBLIC` AND `deleted_at IS NULL`. Past and cancelled events stay reachable so share links don't 404.
3. **Tiers:** all `enabled = true` tiers are returned, with computed `onSale / soldOut / closed` booleans. The frontend renders strike-throughs / "sold out" badges; the API does not hide tiers based on those states.
4. **Inventory:** `remaining` is exposed (drives "only N left" UI). Raw `quantity` and `sold` are not.
5. **Organization:** response includes `{ name, slug }` only — no `id`, no `contactEmail`, no plan info. Slug column is added in this spec.
6. **Caching:** plain `Cache-Control` headers, no `Cache-Tag` emission, no purge integration. CDN integration is out of scope here.

## Architecture

### Data model change — Flyway V15

Add a slug column to `organizations` and backfill existing rows:

```sql
-- V15__organization_slug.sql
ALTER TABLE organizations ADD COLUMN slug VARCHAR(255);

-- Backfill using a single UPDATE with a CTE that:
--   1. Slugifies the name (lower, ASCII-fold approximation, non-alnum -> '-', collapse hyphens, trim).
--   2. Numbers ties with ROW_NUMBER OVER (PARTITION BY slugified_name ORDER BY created_at).
--   3. Appends '-N' for rn > 1.
-- Concrete query is straightforward in PostgreSQL with regexp_replace + lower; the H2/PG-compat
-- test profile uses Flyway's PG dialect so the same SQL runs in tests.
UPDATE organizations SET slug = ... ;  -- spelled out in the migration

ALTER TABLE organizations ALTER COLUMN slug SET NOT NULL;
ALTER TABLE organizations ADD CONSTRAINT uq_organizations_slug UNIQUE (slug);
CREATE INDEX ix_organizations_slug ON organizations (slug);
```

Code changes that ride along:

- `Organization` entity — add `slug` field with getter/setter.
- `OrganizationDto` — expose `slug` in the existing operator-facing DTO.
- New utility `com.imin.iminapi.util.Slugger` — confirmed there is no existing slugifier (`EventService.generateSlug()` is a random placeholder for drafts, not a name-based slugifier). Implementation: lower-case + Unicode NFD normalize and strip combining marks + replace non-`[a-z0-9]+` runs with `-` + trim leading/trailing hyphens + truncate to 200 chars. No external deps.
- Org creation path (`AuthService.signup` is what creates orgs today) — call `Slugger.slugify(name)`, then on `DataIntegrityViolationException` from the unique constraint, retry with `-2`, `-3`, ... up to a small bound (e.g. 10). Past that, fall back to `slug-<6-hex>` random suffix.

This migration is the only schema change; no new tables.

### New package `com.imin.iminapi.controller.publicapi`

Java reserves `public`, so the package is named `publicapi/` for parity with the other `controller/auth/`, `controller/event/`, etc. groupings.

- **`PublicEventController`** — single `@RestController` mapped at `/api/v1/public/events`. One method:
  ```java
  @GetMapping("/{id}")
  public ResponseEntity<PublicEventResponse> getEvent(@PathVariable UUID id);
  ```
  Returns `200` with the response body and `Cache-Control: public, s-maxage=60, stale-while-revalidate=30` set explicitly via `ResponseEntity.headers(...)`. On miss, throws `ApiException(404, ErrorCode.NOT_FOUND, "Event not found")` — same envelope used elsewhere.

### New service: `service/event/PublicEventService`

- `PublicEventResponse get(UUID id)` — single method, transactional `readOnly = true`.
- Loads the event via a new repository method, throws 404 if absent.
- Loads enabled tiers via existing `TicketTierRepository.findByEventIdAndEnabledTrueOrderBySortOrderAsc` (add if missing — current repo may already have it).
- Loads the organization via `OrganizationRepository.findById(event.orgId).orElseThrow()` — required because the response includes org `name` and `slug`.
- Maps to `PublicEventResponse` via static `from(...)` factory on the DTO.

Why a separate service and not a method on `EventService`: keeps the public DTO assembly out of the operator-facing service, avoids accidentally pulling in operator-only fields, and gives a clean unit boundary for the eligibility predicate (the easy place for that logic to drift if mixed into the larger `EventService`).

### Repository changes: `EventRepository`

Add one method:

```java
@Query("""
    SELECT e FROM Event e
     WHERE e.id = :id
       AND e.deletedAt IS NULL
       AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
       AND e.publishedAt IS NOT NULL
       AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
""")
Optional<Event> findPublic(@Param("id") UUID id);
```

This single query encodes the entire eligibility predicate. No code path outside the repository can reach the event without going through this method.

### Tier repository

`TicketTierRepository` already has `findByEventIdOrderBySortOrderAsc`; this spec adds an enabled-only sibling:

```java
List<TicketTier> findByEventIdAndEnabledTrueOrderBySortOrderAsc(UUID eventId);
```

### `SecurityConfig` change

Add to permitAll matchers, before the catch-all `/api/v1/**` requires-authenticated rule:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
```

Using `/api/v1/public/**` (plural-future-proof) so that future public read endpoints (organizer profile, event listings, etc.) ride the same gate.

### DTOs

All public DTOs are records under `dto/publicapi/`. They are **allow-list shapes** — they declare every field they expose, no Jackson tricks, no `@JsonIgnore` on a shared parent. If a future field is added to `Event` that should not be public, the public DTO simply does not declare it.

#### `PublicEventResponse`

```java
public record PublicEventResponse(
        UUID id,
        String slug,
        String name,
        String status,        // wireValue() — "live" | "past" | "cancelled"
        Instant publishedAt,
        String genre,
        String type,
        String description,
        Instant startsAt,
        Instant endsAt,
        String timezone,
        PublicVenueDto venue,
        String coverUrl,
        String posterUrl,
        String videoUrl,
        String currency,
        Instant onSaleAt,
        Instant saleClosesAt,
        boolean squadsEnabled,
        int minSquadSize,
        int squadDiscountPct,
        PublicOrganizationDto organization,
        List<PublicTierDto> tiers
) {
    public static PublicEventResponse from(Event e, Organization org, List<PublicTierDto> tiers) { ... }
}
```

#### `PublicVenueDto`

```java
public record PublicVenueDto(String name, String street, String city, String postalCode, String country) {}
```

Identical shape to existing operator-facing `VenueDto`; kept as a separate type so the public surface has its own type lineage and we don't accidentally widen it later.

#### `PublicOrganizationDto`

```java
public record PublicOrganizationDto(String name, String slug) {}
```

Explicitly excludes `id`, `contactEmail`, `country`, `timezone`, `plan`, `planMonthlyEuros`, `currency`, `createdAt`, `updatedAt`.

#### `PublicTierDto`

```java
public record PublicTierDto(
        UUID id,
        String name,
        String kind,           // wireValue() — "earlyBird" | "standard" | "lateBird" | "custom"
        int priceMinor,
        String currency,       // echoed from event for client convenience
        Instant saleClosesAt,
        int sortOrder,
        int remaining,
        boolean onSale,
        boolean soldOut,
        boolean closed
) {
    public static PublicTierDto from(TicketTier t, Event e, Instant now) { ... }
}
```

Excludes `quantity`, `sold`, `enabled` (always true for visible tiers).

#### Computed flag semantics — single source of truth

The factory `PublicTierDto.from(...)` is the **only** place these are derived. Implementation:

```java
int remaining   = Math.max(0, t.getQuantity() - t.getSold());
boolean soldOut = remaining == 0;

boolean tierClosed  = t.getSaleClosesAt() != null && !now.isBefore(t.getSaleClosesAt());
boolean eventClosed = e.getSaleClosesAt() != null && !now.isBefore(e.getSaleClosesAt());
boolean closed      = tierClosed || eventClosed;

boolean tierOpened  = e.getOnSaleAt() == null || !now.isBefore(e.getOnSaleAt());
boolean eventOver   = e.getStatus() == EventStatus.PAST || e.getStatus() == EventStatus.CANCELLED;
boolean onSale      = !eventOver && tierOpened && !closed && !soldOut;
```

Past/cancelled events force `onSale = false` regardless of windows or inventory (so a tier on a cancelled event still renders price + "Cancelled" rather than a buy button).

### Caching

Single header set on every successful response:

```
Cache-Control: public, s-maxage=60, stale-while-revalidate=30
```

No `Cache-Tag` emission, no purge integration. Implications:

- Organizer-driven changes (publish, edit, cancel, tier price/quantity edit) become visible to fresh clients within at most 60s, plus up to 30s of stale serving while the CDN revalidates in the background.
- `remaining` may also be up to 60s stale on the public page. The actual checkout path validates inventory atomically against the database; the public listing is presentational and is allowed to drift.
- 404 responses are **not** cached (no `Cache-Control` header set on errors, default `no-store` from Spring).

### Error handling

| Condition                                      | Response |
|-----------------------------------------------|----------|
| Event id matches no row                        | 404 `NOT_FOUND` |
| Event id matches but `deleted_at IS NOT NULL` | 404 `NOT_FOUND` |
| Event id matches but `status = DRAFT`          | 404 `NOT_FOUND` |
| Event id matches but `visibility = PRIVATE`   | 404 `NOT_FOUND` |
| Event id matches but `published_at IS NULL`    | 404 `NOT_FOUND` |
| Path id not a UUID                             | 400 — `MethodArgumentTypeMismatchException` mapped by existing `com.imin.iminapi.security.GlobalExceptionHandler` |

All `404` responses use the same `ApiError(NOT_FOUND, "Event not found")` envelope. We deliberately do not differentiate "draft" vs "private" vs "deleted" — surfacing those would let an attacker enumerate event states.

### Testing

Three layers of test coverage:

**1. `PublicEventServiceTest`** — service unit tests with `@DataJpaTest` + real (H2) repository. Cover:

- Returns event when published+public+live.
- Returns event when published+public+past (share-link case).
- Returns event when published+public+cancelled (share-link case).
- 404 when draft.
- 404 when private.
- 404 when soft-deleted.
- 404 when `published_at IS NULL` (unlikely but defensive — the persistence path should never produce this combo, but the predicate must still exclude it).
- Tier filtering: disabled tiers excluded; enabled tiers ordered by `sort_order`.
- Tier flag computation:
  - Sold-out tier → `soldOut=true`, `onSale=false`, `remaining=0`.
  - Tier with `sale_closes_at < now` → `closed=true`, `onSale=false`.
  - Event with `on_sale_at > now` → all tiers `onSale=false`.
  - Event with `status=CANCELLED` → all tiers `onSale=false` regardless of windows.

**2. `PublicEventControllerTest`** — `@WebMvcTest` mocking `PublicEventService`. Cover:

- 200 with full body shape on happy path.
- 404 on service throw.
- `Cache-Control: public, s-maxage=60, stale-while-revalidate=30` header is present.
- **Negative leak assertion** — a snapshot test asserts the JSON response keys are exactly the allow-listed set. If a future change adds a field to `PublicEventResponse`, this test breaks until the keys are explicitly updated. This is the core defense against accidental field leaks.
- Endpoint reachable without auth (no `Authorization` header in the request).

**3. `SecurityConfigTest` (existing)** — extend if not already covered: `GET /api/v1/public/events/{any-uuid}` returns 404 (not 401), proving the permitAll rule is in place.

## Out of scope

The following are deliberately deferred:

- **CDN integration / tag-based purge.** No `Cache-Tag` headers emitted, no `CachePurger` interface, no Cloudflare client. Will be addressed in a separate spec once a CDN is provisioned.
- **Public event listing** (e.g. `GET /api/v1/public/orgs/{slug}/events`). The single-event endpoint is enough to support a share-link landing page; listings come later.
- **Promo-code lookup.** Promo codes are validated at checkout, not exposed publicly.
- **Cart / checkout endpoints.**
- **Visibility = `UNLISTED`.** If/when "shareable but not in listings" is needed, it's a new visibility value with signed-link semantics — a separate spec.
- **Rate limiting.** The existing rate-limit infrastructure (used for `/auth/resend-verification`, `/auth/forgot-password`) can be applied later if abuse appears; the public read endpoint is cacheable and low-cost so this is not urgent.
- **Pagination of tiers.** Tier counts per event are bounded by organizer behavior (~5–20 tiers max in practice), so all enabled tiers are returned in one shot.

## Migration order

1. V15 migration + `Organization` entity field + slug-on-insert plumbing.
2. `PublicTierDto` + `PublicVenueDto` + `PublicOrganizationDto` + `PublicEventResponse` records.
3. `EventRepository.findPublic` query method + tier repository confirmation.
4. `PublicEventService` + tests.
5. `PublicEventController` + tests.
6. `SecurityConfig` permitAll matcher.
7. Manual smoke: publish a dev event, hit the endpoint, confirm response shape and headers.

## Risks / open questions

- **Slug backfill collisions** — the V15 migration must produce unique slugs for any existing org rows. In dev/local this is trivial (one or two rows). The implementation plan should include a step to verify the backfill on a production-shaped dataset before cutting the migration.
- **`published_at` semantics over time** — current code sets `published_at` once and never clears it; the eligibility predicate assumes that. If a future "unpublish" feature is added, it must clear `published_at` (or introduce a separate boolean) — the predicate above will need to be revisited.
- **Slug exposure on response** — we expose `event.slug` even though we look up by id. This is for client convenience (canonical URLs, breadcrumb display). Slugs are not globally unique but are stable per-org, so leaking them is fine.
