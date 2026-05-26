# Buyer-Initiated Refund Requests — Design

**Date:** 2026-05-25
**Status:** Draft for review
**Scope:** Cross-repo. Touches `imin-api`, `imin-public`, `imin-webapp`.
**Related specs:**
- `imin-api/docs/superpowers/specs/2026-05-25-stripe-refunds-design.md` (organizer-initiated Stripe refund flow this builds on)
- `imin-public/docs/PUBLIC_PAGE_API.md` (public-API contract)

## 1. Motivation

Today, refunds are organizer-initiated only: the organizer clicks "Refund" in the dashboard, the backend calls Stripe, and the buyer is emailed afterwards. There is no buyer-facing path to *ask* for a refund. Organizers handle requests over email/DM, then transcribe them into the dashboard manually.

We want a structured request channel:

1. A buyer who wants a refund visits a public page on `imin-public`, enters the email used to buy.
2. The backend emails them a one-time magic link.
3. The link opens a form. The refund is **always for the entire order** (specifically: all tickets that are still refundable — not already refunded, not redeemed). The buyer cannot pick individual tickets. They fill in a reason, a free-text explanation, and an optional phone.
4. Submitting creates a `RefundRequest` row in the DB. Imin's support inbox and the organizer both receive an email.
5. The organizer reviews the request from a new "Refund requests" view in the dashboard. They can **Approve** (which opens a confirm modal showing the exact Stripe refund plan — always whole-order — then triggers the existing refund flow) or **Reject** (with an internal note; an email goes to the buyer).

The existing organizer-side direct refund button (`POST /api/v1/orders/{orderId}/refund`) still supports per-ticket selection. This design only constrains the new buyer-initiated **request** flow; organizers retain manual per-ticket control when they need it.

A correctness bug in the existing refund amount calculation is also in scope: promo-discounted orders are currently over-refunded. The fix lives in `RefundService` and benefits both the new flow and the existing direct-refund button.

## 2. Non-goals

- Self-service / instant refunds (buyer-clicks-button-money-arrives). Approvals are always organizer-mediated.
- Counter-offers, partial-amount negotiation, comments thread. The MVP is a request + a binary decision.
- Refunding free orders. Tickets without a Stripe payment intent are not refundable. The buyer form excludes them.
- Reading/replying to support inbox from inside the app. The IMIN inbox notification is an outbound email only.
- Multilingual emails. Reuse the existing single-locale template scaffolding (`order-recovery`, refund confirmation).

## 3. Architectural overview

Three repos participate; they communicate only through the `imin-api` HTTP surface, never directly with each other. There are two new API "surfaces":

- **Public, unauthenticated, token-gated** (`/api/v1/public/refund-requests/...`) — used by `imin-public`. Magic-link issuance and the single-use token redemption that opens the form.
- **Organizer, JWT-authenticated, org-scoped** (`/api/v1/orgs/{orgId}/refund-requests/...`) — used by `imin-webapp`. List, fetch, approve, reject.

```
┌─────────────────────┐   POST /api/v1/public/refund-requests              ┌─────────────────────┐
│ imin-public         │ ─────────────────────────────────────────────────► │ imin-api            │
│ /refund (form: email)                                                    │   issues magic link │
│                     │ ◄───────────────────────────────────────────────── │   always 200        │
│                     │   email sent to order address                      │                     │
│ /refund/[token]     │                                                    │   token validates,  │
│ (form: reason,      │ POST /api/v1/public/refund-requests/by-token/{t}   │   burns single-use, │
│  text, phone;       │ ─────────────────────────────────────────────────► │   writes Request,   │
│  whole-order only)  │                                                    │   emails IMIN +     │
└─────────────────────┘                                                    │   organizer         │
                                                                           │                     │
┌─────────────────────┐   GET  /api/v1/orgs/{o}/refund-requests            └─────────────────────┘
│ imin-webapp         │ ─────────────────────────────────────────────────►        │
│ /events/.../refunds │                                                           │
│ (list, accept/      │ GET  .../refund-requests/{id}   (returns proposedRefund)  │
│  reject UI)         │ POST .../refund-requests/{id}/approve  (confirm=true) ────┤ uses existing
│                     │ POST .../refund-requests/{id}/reject                       │ RefundService
└─────────────────────┘                                                            ▼
                                                                       Stripe RefundCreate
```

**Separation of concerns: two state machines, one foreign key.**

| Concept | Owns | Status enum |
|---|---|---|
| `RefundRequest` | Buyer/CS lifecycle | `PENDING / APPROVED / REJECTED / WITHDRAWN` |
| `Refund` (existing) | Stripe lifecycle | `REQUESTED / PENDING / SUCCEEDED / FAILED / CANCELED` |

A successful Approve writes `refund_request.refund_id = refunds.id`. If Stripe later returns `FAILED`, the request remains `APPROVED` — failure detail comes through the linked `Refund`. The organizer can retry from the existing per-order refund UI.

Direct (non-request-driven) refunds via the existing dashboard refund button keep working unchanged. The new `RefundRequest` is an upstream artifact that may *invoke* a `Refund`; neither owns the other.

## 4. Promo-code amount fix (in `RefundService`)

### 4.1 The bug

`RefundService.createRefund` at `imin-api/src/main/java/com/imin/iminapi/refund/RefundService.java:127`:

```java
long refundAmountMinor = selected.stream().mapToLong(Ticket::getPriceMinor).sum();
```

`Ticket.priceMinor` is the **tier face price**, not the price the buyer paid. When the buyer used a promo code, `Order.totalMinor < sum(Ticket.priceMinor)`. The current code refunds the face amount, which exceeds what was paid — Stripe rejects the call (`amount_too_large`) for a single-refund full-order case, and silently over-refunds for partial cases that happen to fit under the cap.

### 4.2 The fix

Refund amount must be a function of `Order.totalMinor`, not tier face price.

**Per-refund proportional allocation:**

```
selectedFaceMinor   = sum(Ticket.priceMinor) over the tickets in this refund
orderFaceMinor      = sum(Ticket.priceMinor) over ALL tickets in the order
proposedMinor       = round( Order.totalMinor * selectedFaceMinor / orderFaceMinor )
```

To eliminate drift across many partial refunds:

```
priorRefundedMinor  = sum(amount_minor) over previous SUCCEEDED|PENDING|REQUESTED refunds on this order
remainingMinor      = Order.totalMinor - priorRefundedMinor
finalAmountMinor    = min(proposedMinor, remainingMinor)
```

When the selection covers *all remaining (non-refunded) tickets*, the math collapses to `finalAmountMinor = remainingMinor`, so a full-order refund always equals exactly `Order.totalMinor` regardless of rounding history. This matches the existing comment on `applicationFeeRefundMinor` ("at most N−1 cents rounding error for N refunds") and applies the same discipline to the principal.

### 4.3 Application fee refund

The existing proportional formula stays, but anchored to `Order.totalMinor` (which it already is — that line is correct):

```java
appFeeRefundMinor = round( applicationFeeMinor * finalAmountMinor / totalMinor )
```

### 4.4 Free orders

If `Order.totalMinor == 0` (free ticket, no Stripe PI), the order is not refundable at all. The magic-link issuance step filters these orders out (no link sent), and the existing `RefundService` continues to reject with `ORDER_NOT_REFUNDABLE` for any direct call.

### 4.5 Migration

Pure code change in `RefundService`. No schema migration needed for this fix. Add unit tests covering: full-order with promo, partial with promo, multi-refund drift, last-refund clamp, free-ticket rejection.

## 5. Data model

Two new tables. No changes to existing tables.

### 5.1 `refund_requests`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `order_id` | UUID NOT NULL FK → orders | |
| `org_id` | UUID NOT NULL | Denormalized for fast org-scoped queries. Matches `Order.orgId` at insert time. |
| `event_id` | UUID NOT NULL | Denormalized for dashboard filter-by-event. |
| `buyer_email` | VARCHAR(254) NOT NULL | Lowercased copy of `Order.email` at insert time. |
| `buyer_phone` | VARCHAR(32) NULL | Optional, from form. |
| `reason` | VARCHAR(32) NOT NULL | Buyer-facing reason enum (§5.3). |
| `explanation` | TEXT NOT NULL | Free-text from form. Length cap 2000 chars enforced at the API layer. |
| `status` | VARCHAR(16) NOT NULL | `PENDING / APPROVED / REJECTED / WITHDRAWN` |
| `decision_note` | TEXT NULL | Organizer's note on accept/reject. Visible only to organizer + audit. |
| `decided_by_user_id` | UUID NULL | Set on Approve/Reject. |
| `decided_at` | TIMESTAMP NULL | |
| `refund_id` | UUID NULL FK → refunds | Set on successful Approve. NULL for REJECTED/WITHDRAWN. |
| `pending_marker` | UUID NULL | Set to `order_id` while `status='PENDING'`; cleared to NULL on any terminal transition. UNIQUE-indexed (see below). |
| `created_at` | TIMESTAMP NOT NULL | `Times.nowMicros()` |
| `updated_at` | TIMESTAMP NOT NULL | |

**Indexes:**
- `idx_refund_requests_org_status_created` on `(org_id, status, created_at DESC)` — dashboard list.
- `idx_refund_requests_order_status` on `(order_id, status)` — "one open request per order" check.
- `idx_refund_requests_event_created` on `(event_id, created_at DESC)` — per-event filter.

**`pending_marker` + plain UNIQUE** to enforce "one open request per order":

```sql
ALTER TABLE refund_requests
  ADD COLUMN pending_marker UUID;       -- = order_id when status='PENDING', NULL otherwise
CREATE UNIQUE INDEX uq_refund_requests_one_open_per_order
  ON refund_requests (pending_marker);  -- NULLs are distinct in both Postgres and H2
```

Partial-WHERE indexes (`WHERE status = 'PENDING'`) are Postgres-only — H2 in PG-compat mode (used in tests) doesn't support them. The codebase already uses this `nullable-marker + plain UNIQUE` pattern in V13 for the same reason; we follow it here.

The service writes `pending_marker = order_id` when inserting a `PENDING` row and sets it to `NULL` on transition to `APPROVED`/`REJECTED`/`WITHDRAWN`. A race that tries to insert a second `PENDING` for the same order fails with a unique violation; the service maps that to a clear API error.

### 5.2 `refund_request_tokens`

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `token_hash` | CHAR(64) NOT NULL UNIQUE | SHA-256 of the raw URL token, hex-lowercase. We never store the raw token. |
| `order_id` | UUID NOT NULL FK → orders | |
| `email_normalized` | VARCHAR(254) NOT NULL | The email the magic link was issued to. Must match `Order.email` at redemption time. |
| `expires_at` | TIMESTAMP NOT NULL | Set to `created_at + 60 minutes`. |
| `consumed_at` | TIMESTAMP NULL | Set on first successful POST that opens the form / submits. Single use. |
| `created_at` | TIMESTAMP NOT NULL | |

**Index:** `idx_refund_request_tokens_expires_at` for the scheduled cleanup.

The raw token is a 32-byte random value (256 bits of entropy) Base64-URL-encoded. Stored hashed; cannot be reversed from the DB. Sweeper job (daily) deletes rows where `expires_at < now() - 7 days` to keep the table bounded.

### 5.3 Buyer-facing reason enum

Distinct from the existing `RefundReason` (which mirrors Stripe). Buyer reasons are softer/more descriptive; we map them to `RefundReason` when triggering Stripe.

```java
public enum RefundRequestReason {
    CANT_ATTEND,        // → REQUESTED_BY_CUSTOMER
    EVENT_CHANGED,      // → REQUESTED_BY_CUSTOMER
    DUPLICATE_PURCHASE, // → DUPLICATE
    NOT_AS_DESCRIBED,   // → REQUESTED_BY_CUSTOMER
    OTHER;              // → OTHER
}
```

The mapping is one-way and lives in `RefundRequestService.toStripeReason`. We keep the buyer's choice in `refund_requests.reason` even after approval — Stripe's enum is intentionally narrower than what the buyer sees.

## 6. API surface

All new endpoints. OpenAPI is regenerated by the existing `imin-webapp` `api:sync` script; the public surface is also documented in `imin-public/docs/PUBLIC_PAGE_API.md`.

### 6.1 Public, unauthenticated

#### `POST /api/v1/public/refund-requests`

Issues a magic link if a matching order exists. **Always returns 200**, regardless of whether the email matches anything. Modeled exactly after `OrderRecoveryService`.

```json
Request:  { "email": "buyer@example.com" }
Response: { "ok": true }
```

Behavior:
- Normalize email (trim, lowercase).
- Log an `OrderRecoveryAttempt`-style row (we reuse the same table — no point duplicating). Rate-limit cap = same as recovery (default 5/hour by IP and 5/hour by email; configurable via `ticketProps.recoveryMaxPerHour`).
- If rate-limited, return 200 silently. Same response as success — no enumeration signal.
- Look up the most recent paid `Order` for that email. If none, return 200 silently.
- Generate token + insert `refund_request_tokens` row. Email the buyer with `${emailProps.buyerSiteBaseUrl}/refund/<token>` (same base URL config as `OrderRecoveryService`).

#### `GET /api/v1/public/refund-requests/by-token/{token}`

Loads the form context. Returns the data the form needs to render: order summary, the list of tickets that **will** be refunded (read-only display — refund is always whole-order), the proposed refund amount, the reason enum values.

- 200 + payload if the token is valid (exists, not expired, not consumed) and the order still has at least one refundable ticket.
- 410 `REFUND_TOKEN_EXPIRED_OR_CONSUMED` otherwise (single, leak-safe code — does not distinguish expired vs consumed vs unknown).
- 409 `NO_REFUNDABLE_TICKETS` if the order exists but every ticket is already refunded/redeemed/free.

**This call does NOT consume the token** — only POST does. The form may be re-rendered (back/forward, refresh) before submitting.

```json
Response: {
  "orderId": "uuid",
  "event": { "name": "Acme Show", "startsAt": "...", "venueName": "...", "currency": "EUR" },
  "tickets": [
    { "id": "uuid", "tierName": "GA", "faceMinor": 2500 }
  ],
  "estimatedRefundMinor": 4000,
  "currency": "EUR",
  "reasons": ["cant_attend", "event_changed", "duplicate_purchase", "not_as_described", "other"]
}
```

The `tickets` array is informational — the buyer sees which tickets they are giving up — but it is **not selectable**. The set is determined server-side as *every refundable ticket on the order* (excludes already-refunded, redeemed, free).

`estimatedRefundMinor` is computed via the §4 algorithm so the buyer sees a realistic post-promo figure. It is informational; the authoritative figure is recomputed on Approve.

#### `POST /api/v1/public/refund-requests/by-token/{token}`

Creates the request and burns the token. **The buyer does not pick tickets.** The server resolves the ticket set at submission time as all currently-refundable tickets on the order.

```json
Request: {
  "reason": "cant_attend",             // one of the enum values
  "explanation": "I caught flu...",    // 1..2000 chars
  "phone": "+33 6 12 34 56 78"         // optional, ≤ 32 chars, free-form
}
Response 201:
{
  "id": "uuid",
  "status": "pending",
  "submittedAt": "..."
}
```

Atomicity: the service resolves the refundable-ticket set, inserts the `RefundRequest`, and marks the token `consumed_at = now()` in a single transaction. If the partial-unique-index check fires (another `PENDING` request already exists for this order), respond 409 `REFUND_REQUEST_ALREADY_OPEN`. If by submit time the ticket set has become empty (concurrent redeem/refund), respond 409 `NO_REFUNDABLE_TICKETS` and burn the token. Idempotency for genuine retries: if the **same** token has already been consumed and points to a still-`PENDING` request created in the last 60 seconds, return that row (200, not 201) — a network hiccup retry should not look like an error.

Error codes:
- 410 `REFUND_TOKEN_EXPIRED_OR_CONSUMED`
- 400 `INVALID_REQUEST` (bad reason / explanation length / phone length)
- 409 `REFUND_REQUEST_ALREADY_OPEN`
- 409 `NO_REFUNDABLE_TICKETS`

### 6.2 Organizer, authenticated

All under `/api/v1/orgs/{orgId}/refund-requests`. The `orgId` must match the JWT principal; otherwise 404 (leak-safe).

#### `GET /api/v1/orgs/{orgId}/refund-requests`

List with filters and pagination.

Query params:
- `status` — comma-separated subset of `pending,approved,rejected,withdrawn` (default: all).
- `eventId` — UUID, optional.
- `limit` — default 25, max 100.
- `cursor` — opaque base64 of `(createdAt, id)`; standard keyset pagination matching other list endpoints.

Each row includes: id, orderId, eventId, eventName, buyerEmail, status, reason, createdAt, decidedAt, ticketCount, estimatedRefundMinor, currency, linked refundId+refundStatus when present.

#### `GET /api/v1/orgs/{orgId}/refund-requests/{id}`

Full detail: everything in the list row plus `explanation`, `phone`, `decisionNote`, full ticket list (id, tierName, faceMinor, redemption state), and a *proposed refund plan* if status is PENDING:

```json
{
  // ... summary fields ...
  "proposedRefund": {
    "amountMinor": 4000,
    "appFeeRefundMinor": 200,
    "currency": "EUR",
    "ticketIds": ["uuid", ...]
  }
}
```

This `proposedRefund` field is computed live using the §4 algorithm so the dashboard can render the confirm modal without a separate "preview" round-trip.

#### `POST /api/v1/orgs/{orgId}/refund-requests/{id}/approve`

The preview is already available via `GET .../{id}` (which always returns the live `proposedRefund` for a PENDING request), so the approve endpoint is **single-step**: it requires an explicit `confirm: true` flag to guard against accidental clicks (otherwise responds 400 with the current proposed plan in the error body — same idea as Stripe's CLI confirmation guard).

```json
Request:  { "confirm": true, "note": "Approved per email thread" }
Response: { "status": "approved",
            "refundId": "uuid",
            "refundStatus": "pending" }
```

If `confirm` is missing or false:

```json
Response 400: {
  "code": "REFUND_APPROVAL_NOT_CONFIRMED",
  "message": "Set confirm:true to issue the refund.",
  "fields": { "proposedRefund": { ... } }
}
```

The dashboard renders the modal from the GET response and sends `confirm: true` from the modal's CTA. The 400 branch exists as a defensive backstop for direct API consumers.

`confirm: true` invokes `RefundService.createRefund(...)` with:
- `ticketIds` = the order's currently-refundable tickets, re-resolved at approval time (not stored on the request — see §5.1),
- `reason` = mapped from `RefundRequestReason` via §5.3,
- `idempotencyKey` = `"refund-request-" + requestId` (deterministic per request, so a re-press of "Confirm" is a no-op),
- `principal` = the organizer.

Re-resolving the ticket set at approval time (rather than freezing it at submit) means a ticket that got redeemed between submit and approve is correctly excluded, and the proposed-refund preview always reflects current reality. If the set becomes empty between submit and approve, the approve call returns 409 `NO_REFUNDABLE_TICKETS` and the request stays `PENDING` for organizer rejection.

Inside one transaction: `RefundRequest.status` flips to `APPROVED`, `refund_id` is set, `decided_by_user_id` + `decided_at` + `decision_note` are written. If `RefundService` throws (e.g. Stripe declined), the transaction rolls back and the request stays `PENDING` — the organizer sees an error message and can retry. Stripe-side terminal failures show on the linked Refund once the request *does* get approved + Stripe call goes through and returns FAILED.

Error codes:
- 400 `REFUND_APPROVAL_NOT_CONFIRMED` (missing/false `confirm`; response carries the live `proposedRefund` in `fields`)
- 409 `REFUND_REQUEST_NOT_PENDING` (already approved/rejected/withdrawn)
- 409 `NO_REFUNDABLE_TICKETS` (ticket set became empty between submit and approve)
- 409 `TICKET_ALREADY_REFUNDED` (someone else refunded the same tickets via the dashboard button between submit and approve; surfaced from `RefundService`)
- 502 / 422 — same Stripe error mapping as `RefundController` does today.

#### `POST /api/v1/orgs/{orgId}/refund-requests/{id}/reject`

```json
Request:  { "note": "Past the 48h refund window" }   // note required, ≥ 1 char
Response: { "status": "rejected" }
```

Marks the request `REJECTED`, stamps decision fields, and publishes a `RefundRequestRejectedEvent` (§8) so the rejection email gets sent.

### 6.3 No `withdraw` endpoint in MVP

`WITHDRAWN` is reserved in the enum for a future buyer-initiated cancel. We don't ship the endpoint now (YAGNI — request lifetimes are short and rejection is a fine fallback). The status value is in the schema so we don't need a migration when we add it.

## 7. Email surface

Five new templates (Resend, same renderer as `OrderRecoveryService`). All HTML+text variants.

| Template | To | When |
|---|---|---|
| `refund-request-link` | buyer's order email | POST `/public/refund-requests` succeeded |
| `refund-request-received-buyer` | buyer's order email | POST `/public/refund-requests/by-token/{t}` succeeded |
| `refund-request-notify-organizer` | organizer's primary email | Same trigger as above (one buyer submit → both fire) |
| `refund-request-notify-imin` | `IMIN_REFUND_REQUEST_INBOX` (env, see §9) | Same trigger as above |
| `refund-request-rejected` | buyer's order email | Reject endpoint fired |

The **approval/refund-issued** email to the buyer is already covered by the existing `RefundConfirmationEmailer` (fires on `RefundConfirmedEvent`). We do *not* add a new template for "approved" — the Stripe-confirmed refund email is what the buyer cares about, and it already exists.

Template content principles:
- `refund-request-link`: short, "click here to fill out the form", expires in 60 minutes, links to `${BUYER_SITE_BASE}/refund/<token>`. No order details (leak-safe — anyone with the magic link can already see them).
- Organizer/imin notifications: include order ref, event name, ticket list, reason, full explanation, optional phone, and a dashboard deeplink: `${DASHBOARD_BASE}/events/<eventId>/refund-requests/<id>`. The org email is sent to the user record that owns the org (existing email-routing pattern; same as `RefundFailedEvent` notifications).
- `refund-request-rejected`: short, contains the organizer's `decisionNote` verbatim (HTML-escaped). No CTA — the request is terminal.

## 8. Application events

```java
public record RefundRequestSubmittedEvent(UUID requestId) {}
public record RefundRequestRejectedEvent(UUID requestId) {}
```

`RefundRequestSubmittedEvent` is published from the public submit endpoint and consumed by a `RefundRequestEmailer` that sends all three submit-time emails (buyer ack, organizer notify, imin notify) in parallel. Modeled exactly on the existing `RefundConfirmationEmailer` listening on `RefundConfirmedEvent`.

`RefundRequestRejectedEvent` triggers the rejection email.

No event for approval — `RefundService.createRefund` already publishes `RefundConfirmedEvent` once Stripe confirms, which drives the existing buyer email.

## 9. Configuration

Two new env vars; one new config bean derived from existing.

| Env var | Default | Purpose |
|---|---|---|
| `IMIN_REFUND_REQUEST_INBOX` | falls back to `IMIN_EMAIL_REPLY_TO`, then `IMIN_EMAIL_FROM_ADDRESS` | Imin's internal notification destination. We want this configurable per-environment (`support+refunds@…` in prod, dev mailbox in staging) without code changes. |
| `IMIN_REFUND_REQUEST_TOKEN_TTL_MINUTES` | `60` | Magic-link expiry. Provides a knob for incident response without redeploying. |

No new rate-limit knob — reuses `ticketProps.recoveryMaxPerHour` and the existing `order_recovery_attempts` table (same anti-enumeration model). If we discover request-issuance has a different traffic shape we'll split, but for now we deliberately share the bucket.

## 10. Frontend — `imin-public`

Two new routes under the App Router (Next.js 16.2):

### 10.1 `/refund` — email-entry page

Simple form: order email + submit. Posts to `/api/v1/public/refund-requests`. On any response (success or rate-limited 200), shows the same "If we find your order, you'll get an email shortly" confirmation. No distinction by design (anti-enumeration mirror of `/recover`).

Place it next to the existing `/recover` route. Reuse `Topbar`/`Footer` and the `bx-*` styles.

### 10.2 `/refund/[token]` — request form

Server-fetched via `GET /api/v1/public/refund-requests/by-token/{token}` on initial load. Handles three render branches:

- **Form** (200 response). Renders:
  - Read-only event header (name + date).
  - Read-only summary block: "You are requesting a refund of **€40.00** for your entire order (2 tickets)." Lists the tickets with tier name and face price for the buyer's reference. No selection control.
  - Reason `<select>` (required).
  - Explanation `<textarea>` (1..2000 chars, required).
  - Phone `<input>` (optional, ≤ 32 chars).
  - Submit posts back to the same path. On 201, redirect to `/refund/submitted` (a static "Thanks, we'll be in touch" page).
- **Expired/used** (410). Static page with a link back to `/refund` to start over.
- **No refundable tickets** (409 `NO_REFUNDABLE_TICKETS`). Static explanation page.

### 10.3 Client validation

- Reason required.
- Explanation 1..2000 chars.
- Phone optional, ≤ 32 chars.

Bugs in client validation must not be load-bearing — the API revalidates everything.

### 10.4 SEO

All three refund routes (`/refund`, `/refund/[token]`, `/refund/submitted`) get `robots: { index: false, follow: false }` like `/order/[token]` already does.

## 11. Frontend — `imin-webapp` (organizer dashboard)

### 11.1 Where it lives

A new sub-route under the existing event detail page:

```
/events/[eventId]/refund-requests
/events/[eventId]/refund-requests/[id]
```

Sits alongside the existing orders / refunds tabs on the event detail page. The list view also surfaces a top-nav badge with the count of `PENDING` requests across the org so organizers can spot incoming work without drilling into each event.

### 11.2 List view

Table: buyer email, ticket count, reason, submitted at, status chip, linked refund status (when present). Filters: status (default `pending`), event (preselected from URL). Cursor pagination. Empty state: "No open refund requests."

### 11.3 Detail view

- Order summary (event, buyer, prior order total, refundable tickets at this moment).
- Buyer's reason chip + full explanation + optional phone.
- Linked refund block (when present) showing Stripe refund id + status from the existing `RefundResponse`.
- For `PENDING`:
  - **Approve** button → opens `<ConfirmRefundModal>` rendering `proposedRefund` verbatim: amount, app-fee refund, currency, and the (read-only) list of tickets that will be refunded. Copy emphasises **whole-order**: "Refund **€40.00** for the full order (2 tickets) to buyer@example.com?" Single confirm CTA posts `{ confirm: true, note }`. The note input is optional on approve.
  - **Reject** button → opens `<RejectModal>` with a required note `<textarea>` (1..1000 chars). Posts `{ note }`.
- For `APPROVED` / `REJECTED`: read-only banner with decision metadata. If a linked refund went `FAILED`, surface a "Retry refund" CTA that deeplinks to the existing per-order refund UI (where the organizer can still drop to per-ticket if needed).

### 11.4 Data fetching

TanStack Query as elsewhere. Generated types refreshed via `npm run api:sync` after the backend ships; hand-written types in `src/shared/api/types.ts` extended in lockstep (per the existing API_SYNC.md convention).

## 12. Edge cases & error handling

| Case | Behavior |
|---|---|
| Buyer enters email that has no orders | 200, no email sent. No client-side hint. |
| Buyer hits rate limit | 200, no email sent. Logged server-side. |
| Magic link expired (>60min) | `GET /by-token/{t}` returns 410; FE shows "Link expired, start over." |
| Magic link reused after submit | Same 410 (we don't distinguish; the buyer should see the existing PENDING request in the rejection/approval email when it lands). |
| Buyer submits twice (network hiccup, same token) | 201 idempotency window of 60s returns the same row as 200. |
| Buyer submits twice (deliberately, after first was decided) | Token is consumed → 410. They need to start at `/refund` again. |
| Race: two `PENDING` requests for same order | Partial unique index blocks; second attempt → 409. |
| Organizer approves while organizer's colleague already refunded directly via the per-order button | Stripe call from `RefundService` sees tickets already refunded → 409 `TICKET_ALREADY_REFUNDED`; request stays `PENDING`. Organizer can then `Reject` with a note like "Already refunded directly." |
| Order has zero refundable tickets (all redeemed) | Form returns 409 `NO_REFUNDABLE_TICKETS` on token redeem; buyer gets the explanation page. |
| Order has a mix of refundable and non-refundable tickets (e.g. one ticket already redeemed at the venue) | The buyer sees the partial set with a banner: "1 ticket has been used and is not refundable. Your refund will cover the remaining N tickets." Whole-order *means* whole **refundable** order; the buyer still cannot pick a subset, but they are not blocked outright. |
| Organizer wants to refund only some tickets (not all) for a given request | Not possible via the request flow — they must Reject the request (with a note like "Partial refund issued separately") and use the existing per-order refund UI for the per-ticket action. |
| Free order (no Stripe PI) | Excluded from `findRecentForRecovery`-style lookups at the issuance step; no link issued (returns 200, silent). |
| Promo discount makes per-ticket math irregular | §4 algorithm handles via `Order.totalMinor`-based proportional allocation with drift clamp. |
| Stripe call fails after Approve | Request transactionally rolls back to `PENDING`. Dashboard surfaces the Stripe error. |
| Stripe call returns SUCCEEDED async, then later webhook says FAILED | Existing webhook code path handles this; the linked refund flips status, and the dashboard shows it. Request stays `APPROVED`. |
| Webhook for `charge.refund.updated` arrives late after request is `REJECTED` | Cannot happen — we never call Stripe for a `REJECTED` request. |
| Token table grows | Daily sweep job (ShedLock-protected, modelled on `ReservationSweeper`) deletes rows with `expires_at < now() - 7d`. |

## 13. Security & privacy

- Magic-link token: 32 bytes from `SecureRandom`, Base64-URL encoded, stored as SHA-256 hex. Constant-time comparison via `MessageDigest.isEqual` on the hash.
- Rate-limit shares `order_recovery_attempts` (same anti-enumeration cap; same SHA-256-hashed IP).
- Public endpoints leak no information about which emails do/don't have orders: every code path returns the same 200 shape.
- The token never appears in logs (we log the request id, not the token). Email send path logs only the email address + outcome, same as `OrderRecoveryService`.
- Decision notes are organizer-internal. Only the *rejection note* is forwarded to the buyer, by explicit template choice.

## 14. Observability

Structured log events:
- `[refund-request] issued requestId=… orderId=… email=…` — on submit
- `[refund-request] token-issued orderId=… emailHash=…` — on magic-link send (no plaintext email)
- `[refund-request] approved id=… refundId=… by=…`
- `[refund-request] rejected id=… by=… noteLen=…`

No new dashboards. The existing Refund logs continue to capture Stripe-side activity. If volume becomes meaningful we'll add a Grafana panel later.

## 15. Testing strategy

### 15.1 Backend (`imin-api`)

**Unit:**
- `RefundService` — new tests covering the §4 algorithm: promo-discounted full refund, partial refund, multi-step partial with rounding drift, last-refund-clamps-to-remainder, free-ticket rejection.
- `RefundRequestService` — token issuance, lookup, redemption, single-use enforcement, one-open-per-order, idempotent re-submit window, reason mapping.

**Integration (Spring `@SpringBootTest`, H2-PG):**
- Public submit flow end-to-end (issue → redeem → submit → assert DB rows + outbound emails captured by an in-memory `EmailService` stub).
- Organizer approve flow: assert Stripe is called with the correct args (mock `StripeRefundService`); assert request row links to the refund.
- Organizer reject flow.
- Anti-enumeration: bogus email returns 200 with no email send and an attempts row.
- Rate-limit boundary: 6th attempt within an hour returns 200 but no email.
- Race tests: concurrent submits for the same order → exactly one 201, others 409.

### 15.2 Frontend

**`imin-public`:**
- E2E (Playwright if available; otherwise a Vitest+jsdom integration test): visit `/refund`, submit, see acknowledgement; visit `/refund/[token]` with mocked API responses for happy / 410 / 409 paths; submit the form and assert redirect.

**`imin-webapp`:**
- Component tests for the confirm/reject modals (proposed amount renders correctly, including a promo-discount example fixture).
- Integration test for the list and detail views with mocked TanStack Query data.

### 15.3 Manual

- Trigger a real promo-discounted purchase in dev, run the full request → approve flow, confirm Stripe sandbox shows the post-discount amount (matches `Order.totalMinor`, not face price).
- Same flow on a free order: confirm no magic-link is issued (response is the silent 200).

## 16. Rollout

1. Backend migration + service + tests land first; endpoints behind no flag (they're new, no behavior change for existing users).
2. `imin-webapp` ships the dashboard view in the same release.
3. `imin-public` ships the `/refund` routes in the same release.
4. Cross-repo coordination: per `imin/CLAUDE.md`, fixes spanning the `/api/v1` contract must ship together (this is enforced by the synchronized-cross-repo-changes memory). All three PRs merge in the same window; the OpenAPI sync gate (`npm run api:check`) is the safety net.

The promo-code amount fix in `RefundService` (§4) ships **with** the rest. It is a correctness fix that also benefits the existing direct-refund button, so there is no reason to land it separately, but it is independently reviewable.

## 17. Open questions

None blocking. To verify with the user before/during plan writing:

- Should the rejection email show the organizer's note verbatim, or behind a "the organizer says" framing? (Current draft: verbatim, escaped.)
- Should we send the IMIN notification only in prod, or in all environments? (Current draft: all, controlled by env var — set to dev mailbox in non-prod.)
