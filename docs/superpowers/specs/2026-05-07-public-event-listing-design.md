# Public event listing API

**Status:** Draft
**Date:** 2026-05-07

## Goal

Add a public, unauthenticated **listing** endpoint that returns paginated events filtered by genre, type, date window, location, organizer, free-text query, and on-sale state. Companion to the existing single-event endpoint at `GET /api/v1/public/events/{id}`.

`GET /api/v1/public/events?{filters}&page=1&pageSize=20`

Reuses the same eligibility predicate as the single endpoint (`published_at IS NOT NULL AND status <> DRAFT AND visibility = PUBLIC AND deleted_at IS NULL`). Returns a leaner per-item DTO suitable for card/grid rendering — **no tier list, no description, no video.** Clients fetch full details via `/{id}`.

## Background

The single-event endpoint and its allow-list DTO family already exist on master (PR #5 merged). Pagination via `PageResponse<T>` is the established convention; `EventController.list` is the operator-facing analogue.

Out-of-scope per the predecessor spec is being addressed here:

> Public event listing (e.g. `GET /api/v1/public/orgs/{slug}/events`). The single-event endpoint is enough to support a share-link landing page; listings come later.

## API surface

### `GET /api/v1/public/events`

**Query parameters** — all optional. Server validates each; bad values → `400 INVALID_REQUEST` with `fields` map.

| Param | Type | Notes |
|---|---|---|
| `from` | ISO-8601 timestamp | `events.starts_at >= from`. |
| `to` | ISO-8601 timestamp | `events.starts_at < to`. |
| `genre` | string | exact match on `events.genre`. |
| `type` | string | exact match on `events.type`. |
| `city` | string | case-insensitive **contains** on `venue_city` (LIKE `%city%`). |
| `country` | string (ISO-3166 α-2) | exact match on `venue_country`, uppercased server-side. |
| `orgSlug` | string | exact match on `organizations.slug` of the event's org. |
| `q` | string | case-insensitive **contains** on `events.name` (LIKE `%q%`). Min length 2 if provided. |
| `onSaleOnly` | boolean | `true` → restrict to events whose sale window is currently open: `(on_sale_at IS NULL OR on_sale_at <= NOW()) AND (sale_closes_at IS NULL OR sale_closes_at > NOW())`. Default `false`. |
| `page` | int, ≥ 1 | 1-based page number. Default `1`. |
| `pageSize` | int, 1–100 | Default `20`. Values outside [1,100] are clamped silently (matches existing convention in `EventService.list`). |

**Sort:** fixed `starts_at ASC NULLS LAST, id ASC` (deterministic tie-break by id). No client-supplied sort in v1.

**Response:** `200 OK` — body is `PageResponse<PublicEventListItem>` (see existing `PageResponse<T>`):

```json
{
  "items": [ { /* PublicEventListItem */ } ],
  "total": 137,
  "page": 1,
  "pageSize": 20
}
```

**Errors:**
- `400 INVALID_REQUEST` — bad timestamp format, `q` < 2 chars, `pageSize` non-integer, etc. Server-side validation; rejects with field map.
- Empty result is `200` with `items: []`, not 404.

### Caching

Same `Cache-Control: public, s-maxage=60, stale-while-revalidate=30` as the single endpoint. Cache key includes the query string (the CDN handles this automatically). Listing pages with `onSaleOnly=true` are still cached for 60s — clients accepting this trade-off should poll the single endpoint for real-time inventory.

## DTO — `PublicEventListItem`

New record under `dto/publicapi/`. Allow-list shape, primitive-typed factory args (no entity types in the DTO signature).

```java
public record PublicEventListItem(
        UUID id,
        String slug,
        String name,
        String status,             // wireValue() — "live" | "past" | "cancelled"
        Instant publishedAt,
        String genre,
        String type,
        Instant startsAt,
        Instant endsAt,
        String timezone,
        String venueCity,          // city only (no street/postalCode in list)
        String venueCountry,       // ISO-2
        String coverUrl,
        String currency,
        Integer priceFromMinor,    // MIN(price_minor) over enabled tiers; null if no enabled tier
        PublicOrganizationDto organization
) {}
```

Excluded vs the detail DTO: `description`, `posterUrl`, `videoUrl`, `onSaleAt`, `saleClosesAt`, `squadsEnabled`, `minSquadSize`, `squadDiscountPct`, full `venue` (street/name/postalCode), `tiers[]`. These are operator-irrelevant for cards and easily re-fetched on click-through.

`priceFromMinor` is the cheapest enabled tier's price — drives "From €25" display. `null` when no enabled tier exists. Computed via SQL subquery in the listing query (avoids N+1).

## Architecture

### `EventRepository`

Add the listing query. JPA `@Query` with conditional `WHERE` clauses works here — Spring Data does null parameter checks cleanly. Sketch:

```java
@Query("""
    SELECT e FROM Event e
     WHERE e.deletedAt IS NULL
       AND e.visibility = com.imin.iminapi.model.EventVisibility.PUBLIC
       AND e.publishedAt IS NOT NULL
       AND e.status <> com.imin.iminapi.model.EventStatus.DRAFT
       AND (:from IS NULL OR e.startsAt >= :from)
       AND (:to IS NULL OR e.startsAt < :to)
       AND (:genre IS NULL OR e.genre = :genre)
       AND (:type IS NULL OR e.type = :type)
       AND (:city IS NULL OR LOWER(e.venueCity) LIKE LOWER(CONCAT('%', :city, '%')))
       AND (:country IS NULL OR e.venueCountry = :country)
       AND (:q IS NULL OR LOWER(e.name) LIKE LOWER(CONCAT('%', :q, '%')))
       AND (:orgId IS NULL OR e.orgId = :orgId)
       AND (:onSaleOnly = false OR (
              (e.onSaleAt IS NULL OR e.onSaleAt <= :now)
              AND (e.saleClosesAt IS NULL OR e.saleClosesAt > :now)
            ))
     ORDER BY e.startsAt ASC NULLS LAST, e.id ASC
""")
Page<Event> findPublicListing(
        @Param("from") Instant from,
        @Param("to") Instant to,
        @Param("genre") String genre,
        @Param("type") String type,
        @Param("city") String city,
        @Param("country") String country,
        @Param("q") String q,
        @Param("orgId") UUID orgId,
        @Param("onSaleOnly") boolean onSaleOnly,
        @Param("now") Instant now,
        Pageable pageable);
```

Service resolves `orgSlug` → `orgId` once before the query, via `OrganizationRepository.findBySlug`. If `orgSlug` is given but no matching org → return an empty page (not 400 — bad slug is just "no events here").

### `OrganizationRepository`

Add `Optional<Organization> findBySlug(String slug)`. Should already exist or be trivial to add as a Spring Data derived method.

### `TicketTierRepository`

Add an aggregation method to back `priceFromMinor`:

```java
@Query("""
    SELECT t.eventId, MIN(t.priceMinor) FROM TicketTier t
     WHERE t.enabled = true AND t.eventId IN :eventIds
     GROUP BY t.eventId
""")
List<Object[]> findMinEnabledPriceByEventIds(@Param("eventIds") java.util.Collection<UUID> eventIds);
```

Single round-trip for the page — service builds `Map<UUID, Integer>` and looks up per item.

### Service

New method on `PublicEventService` (existing class):

```java
@Transactional(readOnly = true)
public PageResponse<PublicEventListItem> list(PublicEventListQuery q) { ... }
```

`PublicEventListQuery` is a small record holding the validated, normalized filter set + page/pageSize. The service:

1. Validates / normalizes (uppercases `country`, trims strings, clamps `pageSize`, validates `q.length() >= 2`, parses `from`/`to` if String → Instant).
2. Resolves `orgSlug` → `orgId` if provided.
3. Calls `eventRepository.findPublicListing(...)`.
4. Calls `tierRepository.findMinEnabledPriceByEventIds(pageEventIds)`.
5. Maps each `Event` → `PublicEventListItem` using the price map.
6. Wraps as `PageResponse.from(...)`.

### Controller

Extend `PublicEventController` with one method:

```java
@GetMapping
public ResponseEntity<PageResponse<PublicEventListItem>> list(
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to,
        @RequestParam(required = false) String genre,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String city,
        @RequestParam(required = false) String country,
        @RequestParam(required = false) String orgSlug,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "false") boolean onSaleOnly,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize) {
    var result = publicEventService.list(new PublicEventListQuery(...));
    return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "public, s-maxage=60, stale-while-revalidate=30")
            .body(result);
}
```

Spring auto-binds `Instant` for `from`/`to` from ISO-8601 strings.

### Security

Already covered — `requestMatchers(GET, "/api/v1/public/**").permitAll()` matches both `/{id}` and the bare `/`. No SecurityConfig change needed.

## Testing

Three layers, mirroring the single-endpoint tests.

### `PublicEventServiceListTest` (new file or extend existing)

`@DataJpaTest` slice. Cover with real H2 data:

- **Eligibility filtering** — events that are draft / private / soft-deleted / unpublished are excluded from listing.
- **Genre filter**, **type filter**, **country filter** — exact match.
- **City filter** — case-insensitive contains (`"berl"` matches `"Berlin"`).
- **`q` filter** — case-insensitive contains on name.
- **`from` / `to` window** — boundary tests (exact match included on `from`, excluded on `to`).
- **`orgSlug`** — resolved through OrganizationRepository.
- **`orgSlug`** with unknown slug → empty page (not 400).
- **`onSaleOnly=true`** — excludes events whose `onSaleAt` is in the future, includes events with null `onSaleAt`, excludes events whose `saleClosesAt` is in the past.
- **Pagination** — page 2 of pageSize 5 across 12 published events returns items 6-10.
- **Sort** — events ordered by `startsAt` ASC. Tied `startsAt` broken by `id` ASC.
- **`priceFromMinor`** — populated from cheapest enabled tier; `null` when no enabled tier; ignores disabled tiers.
- **Empty result** — no matches → empty `items[]`, `total: 0`.

### `PublicEventControllerListTest` (extend existing controller test)

`@SpringBootTest @AutoConfigureMockMvc`, mock service. Cover:
- 200 happy-path with full filter set, asserts service called with correctly bound query object.
- `Cache-Control` header present.
- Endpoint reachable without auth (verified via real `SecurityConfig`).
- 400 on bad timestamp format (Spring's auto-binding error → `MethodArgumentTypeMismatchException` → 400 from existing `GlobalExceptionHandler`).
- Snapshot test asserting the JSON `items[0]` keys are exactly the `PublicEventListItem` allow-list (field-leak guardrail mirroring the single endpoint's).

## Out of scope

- **Sort order parameter** — fixed sort in v1.
- **Geo / radius search** — needs lat/lon columns on events (don't exist).
- **Featured / curated lists** — separate endpoint.
- **Past events listing UX** — clients can pass `to=now` to exclude future, or omit filters and let the sort surface them at the bottom.
- **Cursor pagination** — page/pageSize is sufficient for current dataset sizes.
- **Cache-tag invalidation** — same deferral as the single endpoint.
- **Embedding tier counts / tier kinds in list items** — fetch detail endpoint if needed.

## Risks / open questions

- **`venue_city` LIKE search performance** — non-indexed `LOWER(venue_city) LIKE '%...%'` will table-scan. Acceptable while the events table is small. If/when listing latency becomes a concern, options: (a) add a `LOWER(venue_city)` functional index, (b) introduce a tsvector full-text column, (c) move to a search service. Same applies to `q` against `name`.
- **`priceFromMinor` excludes sold-out tiers?** — Decision: it does **not** exclude sold-out tiers — `enabled` is the only filter, matching the single-endpoint detail view. Sold-out cheap tiers still drive "From €25" display; the detail page reveals the sold-out state. If we want "lowest available price", that's a follow-up.
- **`q` is `LIKE` not full-text** — accent-insensitive search is not handled (e.g. `q=cafe` won't match `Café Müller`). Reasonable v1 limitation; revisit if user feedback demands it.
