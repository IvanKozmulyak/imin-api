# Public event page — API contract

**Audience:** Frontend / integration team
**Status:** Authoritative for the public event detail page
**Last updated:** 2026-05-07

This document covers the single backend endpoint that powers the public, unauthenticated event detail page (e.g. `imin.wtf/e/<event-id>`). For organizer-facing endpoints (auth, event management, ticket tier CRUD, etc.) see `superpowers/API_CONTRACT.md`.

---

## 1. Endpoint

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
