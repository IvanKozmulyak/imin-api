# Public event page — API contract

**Audience:** Frontend / integration team
**Status:** Authoritative for the public event pages (detail + listing)
**Last updated:** 2026-05-08

This document covers the public, unauthenticated endpoints that power the event detail page (e.g. `imin.wtf/e/<event-id>`) and the event listing/discovery page (e.g. `imin.wtf/events`). For organizer-facing endpoints (auth, event management, ticket tier CRUD, etc.) see `superpowers/API_CONTRACT.md`.

Two endpoints:

| Endpoint | Section |
|---|---|
| `GET /api/v1/public/events/{id}` — event detail | §1–§8 |
| `GET /api/v1/public/events` — event listing with filters | §9 |

Both share the same eligibility predicate (§2), error envelope (§5), and security stance (no auth required).

---

## 1. Endpoint — detail

```
GET /api/v1/public/events/{id}
```

- `{id}` is the event's UUID.
- **No authentication required.** The endpoint is matched by `requestMatchers(GET, "/api/v1/public/**").permitAll()` in `SecurityConfig`.
- No request body, no query parameters.

---

## 2. Eligibility

The endpoint returns the event if and only if **all four** conditions hold:

1. `published_at IS NOT NULL` — the event has been published at least once.
2. `status` ≠ `draft` — the event is `live`, `past`, or `cancelled`.
3. `visibility` = `public` — private events never appear here.
4. `deleted_at IS NULL` — the event has not been soft-deleted.

If any condition fails → `404 NOT_FOUND` (no leak about *which* condition failed). Past and cancelled events stay reachable so share-links and SEO entries don't 404 when the event ends — the frontend renders the right state from `status`.

---

## 3. Caching

Successful responses (200) carry:

```
Cache-Control: public, s-maxage=60, stale-while-revalidate=30
```

- CDN caches for 60s; serves stale up to 30s while revalidating.
- `remaining` (per-tier inventory) may drift up to ~60s. The actual checkout flow validates inventory atomically against the database, so a stale public listing cannot oversell.
- Organizer-driven changes (publish, edit, cancel, tier price/quantity edit) propagate within the TTL window. **No tag-based purge integration exists yet** — that will be added in a separate spec when a CDN is provisioned.

Error responses (4xx) are **not** cached (default `no-store` from Spring Security).

---

## 4. Response shape

`200 OK`:

```json
{
  "id": "8c3a91f0-2b54-4e4e-b1d2-9d3c5b7e4f01",
  "slug": "summer-fest-2026",
  "name": "Summer Fest 2026",
  "status": "live",
  "publishedAt": "2026-04-01T10:00:00Z",
  "genre": "music",
  "type": "festival",
  "description": "Three stages, fifty acts, one weekend.",
  "startsAt": "2026-07-15T18:00:00Z",
  "endsAt": "2026-07-15T23:00:00Z",
  "timezone": "Europe/Berlin",
  "venue": {
    "name": "Funkhaus",
    "street": "Nalepastraße 18",
    "city": "Berlin",
    "postalCode": "12459",
    "country": "DE"
  },
  "coverUrl": "https://cdn.example/cover.jpg",
  "posterUrl": "https://cdn.example/poster.jpg",
  "videoUrl": null,
  "currency": "EUR",
  "onSaleAt": "2026-04-01T10:00:00Z",
  "saleClosesAt": null,
  "squadsEnabled": true,
  "minSquadSize": 3,
  "squadDiscountPct": 10,
  "organization": {
    "name": "Funkhaus Productions",
    "slug": "funkhaus"
  },
  "tiers": [
    {
      "id": "1f5e0d20-7a1b-4c8a-9b0e-2c8f3a7b6d5c",
      "name": "Early Bird",
      "kind": "earlyBird",
      "priceMinor": 2500,
      "currency": "EUR",
      "saleClosesAt": "2026-06-01T18:00:00Z",
      "sortOrder": 0,
      "remaining": 47,
      "onSale": true,
      "soldOut": false,
      "closed": false
    }
  ]
}
```

### 4.1 Top-level fields

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Stable event identifier (also in URL). |
| `slug` | string | Human-readable. Unique per organizer, **not** globally unique. Use for canonical-URL display, not lookup. |
| `name` | string | Display name. |
| `status` | string | `"live"` \| `"past"` \| `"cancelled"`. (`"draft"` cannot appear — event would 404.) |
| `publishedAt` | ISO-8601 timestamp | When the event was first published. |
| `genre` | string | Free-form (e.g. `"music"`, `"techno"`). |
| `type` | string | Free-form (e.g. `"festival"`, `"concert"`). |
| `description` | string | Plain-text. May be empty. |
| `startsAt` / `endsAt` | ISO-8601 timestamp | UTC. Render in `timezone`. May be `null` for unscheduled drafts (won't actually appear here since draft is filtered). |
| `timezone` | string | IANA tz name (e.g. `"Europe/Berlin"`). |
| `venue` | object | See §4.2. |
| `coverUrl` / `posterUrl` / `videoUrl` | URL or `null` | Pre-resolved CDN URLs. |
| `currency` | string | ISO-4217 (e.g. `"EUR"`). Echoed onto each tier for client convenience. |
| `onSaleAt` / `saleClosesAt` | ISO-8601 or `null` | Event-level sale window. Tier-level `saleClosesAt` may be stricter. |
| `squadsEnabled` | bool | Whether the squad-discount feature is on. |
| `minSquadSize` | int | Minimum squad size (typically 3+). |
| `squadDiscountPct` | int | 0–100. |
| `organization` | object | See §4.3. |
| `tiers` | array | See §4.4. May be empty. |

### 4.2 `venue` object

```ts
{
  name: string | null;       // optional venue name
  street: string;            // may be empty string
  city: string;              // may be empty string
  postalCode: string;        // may be empty string
  country: string | null;    // ISO-3166 alpha-2 (e.g. "DE"), null = unknown
}
```

### 4.3 `organization` object

```ts
{
  name: string;
  slug: string;              // unique, URL-safe
}
```

The org's UUID, contact email, plan, and other operator fields are **never** exposed.

### 4.4 `tiers` array — `PublicTierDto`

Only **enabled** tiers (`enabled = true`) appear. Tiers are sorted by `sortOrder` ascending. Each entry:

```ts
{
  id: string;                // UUID
  name: string;
  kind: "earlyBird" | "standard" | "lateBird" | "custom";
  priceMinor: number;        // integer, in minor units (cents)
  currency: string;          // ISO-4217, mirrors event.currency
  saleClosesAt: string | null;  // ISO-8601 or null
  sortOrder: number;
  remaining: number;         // max(0, quantity - sold)
  onSale: boolean;
  soldOut: boolean;
  closed: boolean;
}
```

Computed flags are evaluated server-side at request time:

| Flag | Meaning |
|---|---|
| `soldOut` | `remaining == 0` |
| `closed` | tier or event `saleClosesAt` is in the past |
| `onSale` | `event.status` is `"live"` AND now ≥ `event.onSaleAt` AND not `closed` AND not `soldOut` |

Past or cancelled events force `onSale = false` on every tier (so a tier on a cancelled event still renders price + a "cancelled" badge rather than a buy button). `soldOut` and `closed` reflect raw state — render whichever badge wins for your UX.

**Inventory leak protection:** raw `quantity` and `sold` are **not** exposed; only `remaining` is. `enabled` is also not exposed (only enabled tiers appear).

---

## 5. Errors

Error envelope (consistent across the API):

```json
{
  "error": {
    "code": "NOT_FOUND",
    "message": "Event not found",
    "fields": null
  }
}
```

### 5.1 Status / code matrix

| HTTP | `code` | When |
|---|---|---|
| `400` | `INVALID_REQUEST` | `{id}` is not a valid UUID. |
| `404` | `NOT_FOUND` | Event missing OR draft OR private OR soft-deleted. Same response for every reason — do not branch UI on it. |

The endpoint never returns 401, 403, or 5xx under normal operation.

---

## 6. Frontend integration notes

- **Lookup is by UUID**, not by slug. Slugs are returned in the body for canonical-URL display only.
- **Polling for `remaining`** is fine but redundant — the 60s edge cache is the practical limit. For real-time inventory ("17 seats left, going fast!"), call directly without cache (e.g. with `?_=<random>` or a dedicated availability endpoint — TBD).
- **Cancelled / past events:** the page should still render. Show a banner above the tiers list driven by `status`. Disable buy buttons everywhere (server already sets all `onSale=false`).
- **Sold-out tier:** still appears in the list. Render with strike-through price + "Sold Out" badge. Do not hide.
- **No promo codes here.** Promo codes are validated at checkout via a separate authenticated endpoint.

---

## 7. Worked example

```http
GET /api/v1/public/events/8c3a91f0-2b54-4e4e-b1d2-9d3c5b7e4f01 HTTP/1.1
Host: api.imin.wtf
Accept: application/json
```

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: public, s-maxage=60, stale-while-revalidate=30

{ "id": "...", "slug": "...", "tiers": [ ... ], ... }
```

Unknown UUID:

```http
HTTP/1.1 404 Not Found
Content-Type: application/json

{ "error": { "code": "NOT_FOUND", "message": "Event not found", "fields": null } }
```

Bad UUID:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{ "error": { "code": "INVALID_REQUEST", "message": "Invalid value for parameter 'id'", "fields": null } }
```

---

## 8. Field stability guarantee

The response shape is enforced by a **leak-guardrail snapshot test** in the backend (`PublicEventControllerTest.get_responseHasOnlyAllowListedKeys`). If a backend developer accidentally adds a field to the response, the test fails loudly until either the field is removed or this contract is updated. So:

- New top-level fields will be announced; treat the listed schema as exhaustive for now.
- Any field documented above that disappears would be a breaking change and must be coordinated.

The leak-guardrail test asserts the exact key set on:
- top-level
- `organization`
- `venue`
- `tiers[*]`

---

## 9. Endpoint — listing

```
GET /api/v1/public/events
```

Returns a paginated list of events matching the detail endpoint's eligibility predicate (§2) **plus one extra exclusion: `status = cancelled` events never appear in the feed.** A cancelled event stays reachable by direct link (the detail endpoint still serves it so the page can render the cancellation banner) but is dropped from the listing and from the `/cities` + `/genres` facets — a browse feed advertising a cancelled night is a bug, not a feature.

Supports filtering by date window, genre/type, location, organizer, and free-text query.

**No authentication required.** Same `Cache-Control: public, s-maxage=60, stale-while-revalidate=30` as the detail endpoint. CDN keys cache by the full query string.

### 9.1 Query parameters

All optional. Bad values → `400 INVALID_REQUEST` with `fields` map.

| Param | Type | Default | Notes |
|---|---|---|---|
| `from` | ISO-8601 timestamp | — | Restrict to events with `startsAt >= from`. |
| `to` | ISO-8601 timestamp | — | Restrict to events with `startsAt < to` (exclusive upper bound). |
| `genre` | string | — | Exact match. |
| `type` | string | — | Exact match. |
| `city` | string | — | Case-insensitive contains on venue city. |
| `country` | string (ISO-3166 α-2) | — | Exact match (uppercased server-side). Must be exactly 2 chars. |
| `orgSlug` | string | — | Filter to events from a specific organizer. Unknown slug → empty result (not 404). |
| `q` | string | — | Case-insensitive contains on event name. **Min length 2** if provided. |
| `onSaleOnly` | boolean | `false` | When `true`, restrict to events whose sale window is currently open: `(onSaleAt IS NULL OR onSaleAt <= now)` AND `(saleClosesAt IS NULL OR saleClosesAt > now)`. |
| `page` | int, ≥ 1 | `1` | 1-based page number. |
| `pageSize` | int, 1–100 | `20` | Clamped silently. |

**Sort:** fixed `startsAt ASC NULLS LAST, id ASC`. No client-supplied sort.

### 9.2 Response shape

`200 OK` — body is `PageResponse<PublicEventListItem>`:

```json
{
  "items": [ /* PublicEventListItem[] */ ],
  "total": 137,
  "page": 1,
  "pageSize": 20
}
```

Each item is a **leaner shape** than the detail endpoint — designed for cards/grids:

```json
{
  "id": "8c3a91f0-2b54-4e4e-b1d2-9d3c5b7e4f01",
  "slug": "summer-fest-2026",
  "name": "Summer Fest 2026",
  "status": "live",
  "publishedAt": "2026-04-01T10:00:00Z",
  "genre": "music",
  "type": "festival",
  "startsAt": "2026-07-15T18:00:00Z",
  "endsAt": "2026-07-15T23:00:00Z",
  "timezone": "Europe/Berlin",
  "venueName": "Funkhaus",
  "venueCity": "Berlin",
  "venueCountry": "DE",
  "coverUrl": "https://cdn.example/cover.jpg",
  "currency": "EUR",
  "priceFromMinor": 2500,
  "organization": { "name": "Funkhaus Productions", "slug": "funkhaus" }
}
```

**Excluded vs the detail shape** (operator-irrelevant for cards): `description`, `posterUrl`, `videoUrl`, `tiers[]`, full `venue` (street/postalCode), `onSaleAt`, `saleClosesAt`, `squadsEnabled`, `minSquadSize`, `squadDiscountPct`. Click through to detail for those.

#### `venueName`

The venue's display name (`Event.venueName`), or `null` when the organizer left it blank. Cards render `"{venueName} · {venueCity}"` when present and fall back to the city alone otherwise. The rest of the venue block (street, postal code) stays detail-only.

#### `priceFromMinor`

**The cheapest amount a buyer can actually pay for one ticket right now, booking fee included.** Drives the "FROM €26.74" card label.

- **Purchasable-aware.** The minimum is taken over tiers that are *buyable at request time* — exactly the `PublicTierDto.onSale` predicate (shared helper `TierAvailability.isPurchasable`): enabled, event not `past`/`cancelled`, both the event-level `onSaleAt` and the tier's `saleStartsAt` reached, neither `saleClosesAt` passed, and `remaining > 0`. Sold-out, not-yet-open and closed tiers are **excluded** — the card no longer advertises a price nobody can buy.
- **Fee-inclusive.** The buyer booking fee for a single ticket is added on top: `p + round(p × 5%) + €0.99`. A €25.00 tier surfaces as `2674`. This is deliberate — the card number and the checkout total now agree.
- **Free tiers stay `0`.** The fee is waived on €0, matching the quote endpoint. Render this as "Free", not "€0".
- **`null` when nothing is purchasable** — no enabled tiers, everything sold out, sales not yet open, or sales ended. Pair it with `soldOut`: when `soldOut` is true and `priceFromMinor` is `null`, show the sold-out chip and **no** price placeholder.
- **Detail-page tier prices remain face-value.** `tiers[].priceMinor` on the detail endpoint is the ticket price without the fee; the fee is broken out by `POST /quote` (`feeMinor`). Only this listing field is fee-inclusive.
- Computed in Java from a single batch fetch of the page's enabled tiers (one round-trip per page, no N+1).

### 9.3 Errors

| HTTP | `code` | When |
|---|---|---|
| `200` + empty `items` | — | No matching events. NOT 404. |
| `400` | `INVALID_REQUEST` | Bad timestamp format on `from`/`to`, `q.length < 2`, `country.length != 2`, non-integer `page`/`pageSize`. The `fields` map names which params failed. |

The endpoint never returns 401, 403, 404, or 5xx under normal operation. Unknown `orgSlug` returns `200` with empty items.

### 9.4 Worked examples

**All upcoming techno events in Berlin, currently on sale, page 1:**

```http
GET /api/v1/public/events?genre=techno&city=Berlin&onSaleOnly=true&from=2026-05-08T00:00:00Z HTTP/1.1
Host: api.imin.wtf
```

**Events from a specific organizer:**

```http
GET /api/v1/public/events?orgSlug=funkhaus&pageSize=10 HTTP/1.1
```

**Search by name:**

```http
GET /api/v1/public/events?q=fest HTTP/1.1
```

### 9.5 Frontend integration notes

- **Don't use this endpoint for real-time inventory.** `priceFromMinor` may be up to 60s stale. The detail page is one click away for live tier data.
- **Pagination:** `total` is total matching events across all pages. UI typically renders pageinfo as `((page-1)*pageSize + 1)..min(page*pageSize, total) of total`.
- **Empty result UX:** show "No events match these filters" with a clear-filters button. Do not 404.
- **Filter combinations:** all filters AND together. Wide queries (no filters) return everything published, ordered by upcoming-first. To exclude past events, pass `from=now`.
- **`q` is `LIKE`, not full-text.** `q=cafe` will not match `Café Müller` (no accent folding in v1).
- **Empty `q` is invalid (min 2 chars).** Don't send `q=` or `q=a`.
- The `items[]` shape is enforced by a **leak-guardrail snapshot test** (`PublicEventControllerTest.list_responseItemKeysAreAllowListed`); same field-stability guarantee as §8.

---

## 10. Endpoint — distinct cities

```
GET /api/v1/public/events/cities
```

Returns the distinct `(city, country)` pairs across all events that match the public-eligibility predicate (§2). Useful for populating the city filter dropdown on the listing page from data that's actually visible to users.

**No authentication required.** Same `Cache-Control: public, s-maxage=60, stale-while-revalidate=30`.

No query parameters in v1.

### 10.1 Response

`200 OK`:

```json
[
  { "city": "Amsterdam", "country": "NL" },
  { "city": "Berlin",    "country": "DE" },
  { "city": "Paris",     "country": "FR" },
  { "city": "Paris",     "country": "US" }
]
```

- Sorted alphabetically by `city`, then by `country`.
- Same `(city, country)` pair appears at most once.
- Different countries with the same city name (Paris/FR vs Paris/US) appear as separate entries — disambiguates duplicates.
- `country` is ISO-3166 α-2.
- Events with empty `venueCity` are excluded. (`venue_city` is `NOT NULL DEFAULT ''` at the schema level.)
- Cancelled events are excluded, matching the listing feed (§9).
- Empty database / no public events → `[]` (NOT 404).

### 10.2 Frontend integration notes

- **Render disambiguated.** When two entries share `city`, render as `"Paris, FR"` / `"Paris, US"` to disambiguate. Use the `country` value to drive the filter when the user picks one.
- **Cache friendly.** Stable across the 60s window — don't poll faster than that.
- **No event count in v1.** If you need "Berlin (12 events)" UX, file a follow-up.

### 10.3 Errors

This endpoint never returns 4xx or 5xx under normal operation. No request body, no params to validate.
