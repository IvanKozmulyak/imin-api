# imin.wtf — Frontend API Contract (V1)

**Audience:** Java backend team
**Status:** Authoritative for the current FE build
**Last updated:** 2026-04-23

This is the **exact** set of endpoints the React frontend consumes in the
current V1 scope (Auth · Dashboard · Events · Settings). Endpoints
documented in the broader design spec but not listed here are **not called
by the FE today**; implement them later as features come online.

All endpoints live under `/api/v1`. The FE's dev server proxies that path
to `VITE_API_BACKEND` (default `http://localhost:8080`). In production the
FE reads `VITE_API_BASE_URL` (default `/api/v1`) — it can be either a
relative path served by a reverse proxy or an absolute URL.

---

## 1. Conventions

### 1.1 Transport
- All requests and responses are **JSON** (`Content-Type: application/json`).
- Exception: file uploads use `multipart/form-data` (see §8).

### 1.2 Authentication
- Bearer token: `Authorization: Bearer <token>` on every authenticated request.
- Token is issued by `/auth/login`, `/auth/signup`, or `/auth/google/callback`.
- FE stores the token in `localStorage` under the key `imin.token`.
- **No refresh flow in V1** — tokens are expected to be long-lived. On any
  `401` the FE clears the token and redirects to `/auth/login?expired=1`.

### 1.3 Error response
All non-2xx responses MUST have this shape:
```json
{
  "error": {
    "code": "FIELD_INVALID",
    "message": "Human-readable message",
    "fields": { "email": "required" }
  }
}
```
- `code` — machine code from the taxonomy below.
- `message` — displayed in a toast when there's no per-field mapping.
- `fields` — per-field validation errors; keys match FE field names. Optional.

### 1.4 Error code taxonomy
The FE reads `error.code` and maps to specific UX.

| HTTP | code | Meaning | FE behavior |
|---|---|---|---|
| 400 | `FIELD_INVALID` | Validation failed | Render `fields` inline |
| 400 | `INVALID_REQUEST` | Malformed request body | Toast |
| 401 | `AUTH_MISSING` | No token | Redirect to login |
| 401 | `AUTH_INVALID_CREDENTIALS` | Bad login | Inline form error |
| 401 | `AUTH_TOKEN_EXPIRED` | Token expired | Clear token, redirect with `?expired=1` |
| 403 | `FORBIDDEN` | Auth'd but not allowed | Toast |
| 403 | `ORG_PLAN_LIMIT` | Plan feature gate | Toast + Upgrade link |
| 404 | `NOT_FOUND` | Resource missing | Page-level error |
| 409 | `STALE_WRITE` | `If-Match` mismatch | "Refresh to see latest" dialog |
| 409 | `INVALID_STATE` | Action disallowed in current state | Toast |
| 409 | `DUPLICATE` | Unique constraint violation | Inline field error |
| 422 | `PUBLISH_VALIDATION_FAILED` | Publish attempt on incomplete draft | Inline on wizard |
| 429 | `RATE_LIMITED` | Too many requests | Toast with cooldown |
| 500 | `INTERNAL` | Server error | Toast + retry |
| 502/503 | `UPSTREAM_UNAVAILABLE` | Stripe/AI down | Toast naming the dependency |

### 1.5 Pagination
List endpoints accept `?page=1&pageSize=20` (both optional). Response:
```json
{ "items": [...], "total": 142, "page": 1, "pageSize": 20 }
```

### 1.6 Timestamps
- Always ISO 8601 with timezone offset: `"2026-05-22T23:00:00+02:00"`.
- Event-level entities also carry a separate `timezone` IANA zone
  (`"Europe/Berlin"`) which is **authoritative for display**; offset in
  `startsAt` / `endsAt` is derived.

### 1.7 Money
All money is `{ amountMinor: integer, currency: "EUR" }`. Minor units
(cents). V1 is EUR-only.

### 1.8 Optimistic locking (`If-Match`)
Mutating endpoints that have a corresponding `updatedAt` MUST honor
`If-Match: <updatedAt>`. If the header is present and doesn't match the
current `updatedAt`, return **409 `STALE_WRITE`**. The FE includes
`If-Match` automatically on wizard autosave.

Required on:
- `PATCH /events/:id`
- `PATCH /org`
- `PATCH /me/notifications`

### 1.9 Idempotency (`Idempotency-Key`)
The FE generates a UUID and sends `Idempotency-Key: <uuid>` on POSTs that
cost money, send messages, or cause irreversible state change. Server
should cache the response for 24 h and return the cached response on
retry of the same key.

Required on:
- `POST /events/:id/publish`
- `POST /events/:id/cancel`
- `POST /events/:id/duplicate` *(not yet called by FE; keep in contract)*
- `POST /events/:id/comps` *(not yet called)*
- `POST /billing/upgrade`
- `POST /payouts/connect`
- `POST /auth/signup`
- `POST /ai/events/concept`

### 1.10 CORS (dev)
In dev, the FE runs on `http://localhost:5173` and hits the backend
through a Vite proxy, so the backend doesn't see cross-origin requests.
In production CORS is determined by deployment topology; if the FE is on
a different origin, the backend MUST allow:
- Origin: the FE production origin
- Methods: `GET, POST, PATCH, PUT, DELETE, OPTIONS`
- Headers: `Authorization, Content-Type, If-Match, Idempotency-Key`

---

## 2. Auth

### `POST /auth/signup` *(idempotent via Idempotency-Key)*
Create an organization and its first user.
```
Request:  { "email": "x@y.com", "password": "≥10 chars 1 letter 1 digit", "orgName": "My Productions", "country": "FR" }
Response: { "token": "<bearer>", "user": User, "org": Organization }
Errors:   400 FIELD_INVALID (fields: email / password / orgName) · 409 DUPLICATE (email taken)
```

### `POST /auth/login`
```
Request:  { "email": "x@y.com", "password": "..." }
Response: { "token": "<bearer>", "user": User, "org": Organization }
Errors:   401 AUTH_INVALID_CREDENTIALS · 429 RATE_LIMITED (after 5 failed attempts / 15 min / email)
```

### `POST /auth/logout`
Fire-and-forget — invalidate the token server-side.
```
Request:  (empty; token in Authorization header)
Response: 204
```

### `GET /auth/me`
Returns the currently authenticated user and their org. Called on app boot
and after login.
```
Response: { "user": User, "org": Organization }
Errors:   401 AUTH_TOKEN_EXPIRED
```

### `GET /auth/google/url`
Returns the URL the FE should redirect the user to in order to start the
Google OAuth flow.
```
Response: { "url": "https://accounts.google.com/..." }
```

### `POST /auth/google/callback`
Exchange the auth code received by the OAuth callback page for a session.
```
Request:  { "code": "<google auth code>" }
Response: { "token": "<bearer>", "user": User, "org": Organization }
Errors:   400 INVALID_REQUEST · 401 AUTH_INVALID_CREDENTIALS
```

---

## 3. Organization & user

### `GET /org`
```
Response: Organization
```

### `PATCH /org` *(If-Match required)*
```
Request:  { "name"?: string, "contactEmail"?: string, "country"?: string, "timezone"?: string }
Response: Organization  (with updated updatedAt)
Errors:   409 STALE_WRITE
```

### `DELETE /org`
Permanently deletes the org and all owned data (events, tiers, promo
codes, audience, payouts history UI, etc.). After success the FE clears
the token and redirects to login.
```
Response: 204
Errors:   403 FORBIDDEN (only owners can delete)
```

### `GET /org/team`
Returns all org members.
```
Response: TeamMember[]
```
Where `TeamMember` is:
```ts
{
  id: string,
  email: string,
  name: string,
  role: 'owner' | 'admin' | 'member',
  avatarInitials: string,
  orgId: string,
  createdAt: string,
  lastActive?: string   // ISO timestamp; renders as "2h ago" via relative formatter
}
```

### `POST /org/team/invite`
```
Request:  { "email": "x@y.com", "role": "admin" | "member" }
Response: { "inviteId": string, "email": string, "role": "admin" | "member" }
Errors:   400 FIELD_INVALID (fields.email) · 409 DUPLICATE
```

### `DELETE /org/team/:userId`
Remove a teammate. Owner cannot be removed.
```
Response: 204
Errors:   403 FORBIDDEN
```

### `GET /me/notifications`
```
Response: NotificationPreferences
```

### `PATCH /me/notifications` *(If-Match optional in V1)*
```
Request:  { "ticketSold"?: bool, "squadFormed"?: bool, "predictorShift"?: bool,
            "fillMilestone"?: bool, "postEventReport"?: bool, "campaignEnded"?: bool,
            "payoutArrived"?: bool }
Response: NotificationPreferences
```

---

## 4. Dashboard

### `GET /dashboard`
Single aggregated call for the dashboard landing page.
```
Response: {
  "greeting": { "name": "Jaune" },
  "now": {
    "nextEvent": Event | null,
    "pct": 57,
    "daysOut": 28
  },
  "cycle": {
    "period": "30d",
    "revenueMinor": 508800,
    "ticketsSold": 212,
    "squadRatePct": 64,
    "activeEvents": 3,
    "deltas": { "revenuePct": 34, "ticketsPct": 28 }
  },
  "lastEvent": {
    "event": Event | null,
    "metrics": {
      "attended": 198,
      "capacity": 200,
      "avgTicketMinor": 2400,
      "nps": 68 | null
    }
  },
  "prediction": Prediction | null,
  "business": {
    "totalRevenueMinor": 1420800,
    "eventsPublished": 6,
    "eventsCompleted": 4,
    "audienceCount": 2847,
    "repeatRatePct": 41
  },
  "activity": [ { "time": "2m ago", "label": "4-person squad bought ANTRUM tickets" } ]
}
```
Performance note: this endpoint is called on every dashboard visit. Cache
aggressively server-side (e.g. 30 s), since the shape is expensive to
assemble.

---

## 5. Events

### `GET /events?status=live|past|draft`
Returns paginated events. `status` is optional; if omitted, returns all.
Summary shape: **no embedded tiers/promoCodes/prediction** (those come
with the detail endpoint).
```
Response: Paginated<Event>
```

### `POST /events`
Creates a draft event. Called eagerly by the FE on wizard mount with
`body: {}`. All fields default (e.g. `status: "draft"`, no name, no date).
Unused drafts older than 24 h MUST be auto-pruned server-side.
```
Request:  Partial<Event>   // may be empty {}
Response: Event            // full object including tiers:[], promoCodes:[]
Status:   201
```

### `GET /events/:id`
Full event with embedded `tiers`, `promoCodes`, and `prediction`.
```
Response: Event
Errors:   404 NOT_FOUND
```

### `PATCH /events/:id` *(If-Match required)*
Partial update. Called by wizard autosave every 1.5 s of inactivity.
Server MUST accept partial drafts without aggressive validation (that
happens on publish).
```
Request:  Partial<Event> (minus id, status, orgId, createdAt, updatedAt)
Response: Event (with new updatedAt)
Errors:   409 STALE_WRITE
```

### `POST /events/:id/publish` *(Idempotency-Key required)*
Publishes a draft event. Server MUST validate all required fields; on
failure return **422 `PUBLISH_VALIDATION_FAILED`** with per-field details.
```
Response: Event (status transitions to "live", publishedAt set)
Errors:   422 PUBLISH_VALIDATION_FAILED · 409 INVALID_STATE (already published)
```

### `GET /events/:id/overview`
Overview tab aggregated data (recent purchases, metrics, prediction,
quick actions).
```
Response: {
  "metrics": {
    "sold": 142,
    "capacity": 250,
    "revenueMinor": 340800,
    "currency": "EUR",
    "squadRatePct": 68,
    "daysOut": 28
  },
  "recentPurchases": [
    { "time": "8m ago", "name": "Yuki Tanaka", "sub": "Started a 5-person squad · €90" }
  ],
  "prediction": Prediction | null,
  "quickActions": [
    { "key": "send_campaign", "icon": "✉️", "label": "Send campaign to audience" },
    { "key": "comp_tickets", "icon": "🎟️", "label": "Generate comp tickets" },
    { "key": "copy_link", "icon": "🔗", "label": "Copy buyer link" },
    { "key": "qr_scanner", "icon": "📱", "label": "Open QR scanner" }
  ]
}
```
Note: `recentPurchases.time` is a human-friendly pre-computed label for V1;
an ISO timestamp field (e.g. `createdAt`) would be preferred if the server
can return one, so the FE can format via `formatDistance`.

---

## 6. AI Studio

### `POST /ai/events/concept` *(Idempotency-Key required)*
Generates an event concept from a vibe prompt + genre.
```
Request:  { "vibe": "Moody Berlin techno...", "genre"?: "Techno", "city"?: string, "capacity"?: number }
Response: {
  "conceptId": "concept-abc123",
  "name": "ANTRUM · Underground Techno",
  "description": "Deep in a raw Berlin warehouse...",
  "posters": [
    { "url": "https://cdn.../poster1.jpg", "label": "VARIANT 01 · TYPOGRAPHIC", "gradient": "linear-gradient(135deg,#1a1a18,#2d5cff)" }
  ],
  "palette": ["#1a1a18", "#2d5cff", "#c03030", "#f2f1ec"],
  "suggestedTiers": [
    { "name": "Early Bird", "priceMinor": 1200, "quantity": 50 },
    { "name": "Standard", "priceMinor": 1800, "quantity": 150 },
    { "name": "Door", "priceMinor": 2400, "quantity": 50 }
  ],
  "suggestedCapacity": 250,
  "confidencePct": 78
}
Errors:   429 RATE_LIMITED (recommend 10 / user / hour) · 502 UPSTREAM_UNAVAILABLE · 400 FIELD_INVALID (vibe too short)
```

### `POST /ai/events/concept/regenerate`
```
Request:  { "conceptId": "concept-abc123", "lock"?: ("name" | "description" | "poster")[] }
Response: same shape as POST /ai/events/concept
```

---

## 7. Billing, payouts, integrations

### `GET /billing/plan`
```
Response: {
  "plan": "growth" | "pro",
  "monthlyEuros": 89,
  "features": ["AI Creator", "Full ticketing", "Marketing hub", "Post-event analytics"],
  "renewsOn"?: "2026-05-01"
}
```

### `POST /billing/upgrade` *(Idempotency-Key required)*
Returns a Stripe Checkout URL; FE redirects.
```
Request:  { "plan": "pro" }
Response: { "checkoutUrl": "https://checkout.stripe.com/..." }
```

### `GET /billing/payment-method`
```
Response: { "brand": "Visa", "last4": "4242", "expMonth": 8, "expYear": 2028 }
```

### `POST /billing/payment-method/update`
Returns a Stripe billing portal URL; FE redirects.
```
Response: { "updateUrl": "https://billing.stripe.com/..." }
```

### `GET /billing/invoices`
```
Response: Paginated<Invoice>
```

### `GET /payouts/status`
```
Response: {
  "stripeConnected": true,
  "accountLast4": "0042",
  "dashboardUrl": "https://dashboard.stripe.com/..."
}
```
When `stripeConnected` is false, `accountLast4` and `dashboardUrl` may be
empty strings (still required fields in V1).

### `POST /payouts/connect` *(Idempotency-Key required)*
Returns a Stripe Connect onboarding URL; FE redirects.
```
Response: { "connectUrl": "https://connect.stripe.com/..." }
```

### `GET /payouts/summary`
All amounts in minor units (cents). Currency comes from Organization.
```
Response: {
  "inTransit": 342000,
  "thisMonth": 508800,
  "pending": 124800,
  "arrivesOnLabel"?: "Mon, Apr 22",
  "thisMonthCount"?: 3
}
```

### `GET /payouts`
Payout history.
```
Response: Paginated<PayoutRow>
```

### `GET /integrations`
```
Response: Integration[]
```

### `POST /integrations/:key/connect`
Either redirects the FE into an OAuth flow or flips the connected flag
server-side depending on the integration.
```
Response: { "authUrl": "https://..." } | { "connected": true }
```

### `POST /integrations/:key/disconnect`
```
Response: 204
```

---

## 8. File uploads (multipart)

Used by the wizard Step 2. The FE sends XMLHttpRequest to track upload
progress (not `fetch`, which lacks upload progress). Request body is
`multipart/form-data` with a single field named `file`.

### `POST /events/:id/media/poster`
### `POST /events/:id/media/video`
### `POST /events/:id/media/cover`
```
Request:  multipart/form-data  (field: "file")
Response: { "url": "https://cdn.../poster-abc.jpg", "sizeBytes": 2456789, "contentType": "image/jpeg", "durationSec"?: 30 }
```
`durationSec` is only present on video uploads.

**Size and format limits (server MUST enforce; FE pre-validates for UX):**
- Poster: ≤ 5 MB, JPG/PNG, recommended 1080×1350 (4:5).
- Video: ≤ 50 MB, MP4, ≤ 30 s, recommended 1080×1920 or 1920×1080.
- Cover: ≤ 5 MB, JPG/PNG, recommended 1920×1080 (16:9).

**URL semantics:** `url` should be a stable, publicly-readable CDN URL.
If it expires, add an `expiresAt` field (currently not consumed by the FE;
FE would need to re-fetch the event before display).

### `DELETE /events/:id/media/:kind`
Where `kind` is `poster` | `video` | `cover`.
```
Response: 204
```

---

## 9. Notifications (topbar badge)

### `GET /notifications/unread-count`
Polled on route change; not real-time in V1.
```
Response: { "count": 3 }
```

### `GET /notifications` *(not yet called in V1, but reserved)*
When the bell dropdown is implemented, this endpoint will back it.
```
Response: Paginated<{ id: string, kind: string, title: string, body: string, link?: string, createdAt: string, readAt?: string }>
```

---

## 10. Data types reference

All types are TypeScript interfaces living in
`src/shared/api/types.ts`. Snapshot for the Java team:

```ts
type UserRole = 'owner' | 'admin' | 'member';

interface User {
  id: string;
  email: string;
  name: string;
  role: UserRole;
  avatarInitials: string;  // 1-2 uppercase letters derived from name
  orgId: string;
  createdAt: string;
}

type OrgPlan = 'growth' | 'pro';

interface Organization {
  id: string;
  name: string;
  contactEmail: string;
  country: string;       // ISO 3166-1 alpha-2, e.g. "FR"
  timezone: string;      // IANA zone, e.g. "Europe/Paris"
  plan: OrgPlan;
  planMonthlyEuros: number;
  currency: string;      // ISO 4217, "EUR" in V1
}

type EventStatus = 'draft' | 'live' | 'past' | 'cancelled';
type EventVisibility = 'public' | 'private';
type EventType = 'Festival' | 'Rave' | 'Club' | 'Concert' | 'Open Air';

interface Venue {
  name?: string;
  street: string;
  city: string;
  postalCode: string;
  country?: string;
}

interface TicketTier {
  id: string;
  eventId: string;
  name: string;
  kind: 'earlyBird' | 'standard' | 'lateBird' | 'custom';
  priceMinor: number;
  quantity: number;
  sold: number;
  saleClosesAt?: string;  // ISO 8601
  enabled: boolean;
  sortOrder: number;
}

interface PromoCode {
  id: string;
  eventId: string;
  code: string;           // always uppercase
  discountPct: number;    // 1–100
  maxUses: number;
  usedCount: number;
  enabled: boolean;
}

interface Prediction {
  eventId: string;
  score: number;           // 0–100
  rangeLow: number;
  rangeHigh: number;
  confidencePct: number;
  insight: string;
  modelVersion: string;
  computedAt: string;
}

interface Event {
  id: string;
  orgId: string;
  name: string;
  slug: string;
  visibility: EventVisibility;
  status: EventStatus;
  genre: string;
  type: EventType | '';      // empty string allowed on drafts
  startsAt: string;          // ISO 8601 with offset
  endsAt: string;
  timezone: string;          // IANA zone
  venue: Venue;
  description: string;       // markdown, ≤ 2000 chars
  posterUrl: string | null;
  videoUrl: string | null;
  coverUrl: string | null;
  capacity: number;
  sold: number;
  revenueMinor: number;
  currency: string;
  squadsEnabled: boolean;
  minSquadSize: number;      // 3–8
  squadDiscountPct: number;  // 0–100
  onSaleAt?: string;
  saleClosesAt?: string;
  createdBy: string;         // userId
  createdAt: string;
  updatedAt: string;         // drives optimistic locking
  publishedAt?: string;
  deletedAt?: string | null;
  // Only present on detail endpoint, not list:
  tiers?: TicketTier[];
  promoCodes?: PromoCode[];
  prediction?: Prediction | null;
}

interface NotificationPreferences {
  userId: string;
  ticketSold: boolean;
  squadFormed: boolean;
  predictorShift: boolean;
  fillMilestone: boolean;
  postEventReport: boolean;
  campaignEnded: boolean;
  payoutArrived: boolean;
}

type Integration =
  | { key: 'stripe_connect', name, description, icon, connected: bool, connectedAt?, managedExternally: bool }
  | { key: 'meta_business' | 'resend' | 'google_analytics' | 'mailchimp' | 'zapier', ... }
  ;

interface Invoice {
  id: string;
  orgId: string;
  date: string;              // ISO date
  description: string;       // e.g. "Growth plan"
  amountMinor: number;
  currency: string;
  pdfUrl: string;            // direct link; FE opens in new tab
}

interface Payout {
  id: string;
  orgId: string;
  amountMinor: number;
  currency: string;
  status: 'pending' | 'in_transit' | 'paid';
  eventIds: string[];
  createdAt: string;
  arrivesOn: string;         // ISO date
  stripePayoutId: string;
}

interface PayoutRow extends Payout {
  eventsLabel: string;       // pre-joined list like "After Dark"
}
```

---

## 11. Open items the Java team should confirm

1. **Token refresh flow.** V1 assumes long-lived tokens. Recommend a
   `POST /auth/refresh` endpoint for V2; define token lifetime now.
2. **Rate limits** per endpoint. Spec recommends:
   - `/auth/login`: 5 attempts / email / 15 min → 429.
   - `/ai/events/concept`: 10 / user / hour → 429.
3. **Password policy.** FE's zod schema enforces ≥10 chars, ≥1 letter,
   ≥1 digit. Java MUST re-validate.
4. **CDN URLs for uploaded media** — are they permanent or signed? If
   signed, add `expiresAt` to upload response.
5. **Org deletion cascade** — confirm what `DELETE /org` removes. FE
   assumes hard cascade on all owned entities.
6. **Soft-delete** — `deletedAt` exists on Event and is FE-visible;
   confirm list endpoints filter out `deletedAt IS NOT NULL` by default.
7. **Multi-org users** — V1 assumes 1 user = 1 org. Confirm.
8. **Google OAuth redirect URI** — Java must register the FE's
   `/auth/callback/google` path with Google.

---

## 12. Endpoints reserved but NOT CALLED by the FE in V1

These appear in the fuller design spec. Implement when the corresponding
feature comes online; they are not blockers:

- `POST /auth/password/request`, `POST /auth/password/reset` (password reset)
- `POST /events/:id/cancel`, `POST /events/:id/duplicate`, `DELETE /events/:id`, `GET /events/:id/share-link`
- `GET/PUT /events/:id/tiers`, `PATCH /events/:id/tiers/:tierId`, `GET/PUT /events/:id/promo-codes`
- `GET /events/:id/sales-velocity`, `GET /events/:id/tickets/summary`, `POST /events/:id/comps`, `GET /events/:id/audience`, `GET /events/:id/audience/export`, `GET /events/:id/analytics`, `GET /events/:id/campaigns`, `GET /events/:id/squads`, `GET /squads/:id`
- `POST /ai/marketing/assets`, `POST /ai/marketing/subject-lines`, `GET /ai/events/:id/post-event-insights`
- `GET /events/:id/prediction`, `POST /events/:id/prediction/refresh`
- All `/audience/*`, `/marketing/*`, `/analytics/*` endpoints (those features are "Coming soon" stubs on the FE today)
- `PATCH /me` (profile update), `PATCH /org/team/:userId` (role change)
- `GET /notifications`, `POST /notifications/:id/mark-read`, `POST /notifications/mark-all-read`

---

## 13. Minimum implementation order to unblock the FE

If the Java team wants to prioritize:

1. **Auth & user**: `/auth/login`, `/auth/signup`, `/auth/logout`, `/auth/me`, `/auth/google/url`, `/auth/google/callback` — unlocks the app shell.
2. **Dashboard**: `/dashboard` — unblocks the landing page.
3. **Events core**: `/events` (list), `/events/:id` (detail), `POST /events` (create draft), `PATCH /events/:id` (autosave), `POST /events/:id/publish`, `/events/:id/overview` — unlocks events list, detail, wizard.
4. **Uploads**: `/events/:id/media/*` — unblocks wizard Step 2.
5. **AI Studio**: `/ai/events/concept`, `/ai/events/concept/regenerate` — unblocks AI flow.
6. **Settings**: `/org`, `/org/team`, `/me/notifications`, `/billing/*`, `/payouts/*`, `/integrations/*` — unblocks Settings tabs.
7. **Notifications badge**: `/notifications/unread-count` — unblocks topbar badge.

Each group is independent of the others after group 1.
