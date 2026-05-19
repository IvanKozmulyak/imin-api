# Ticket Issuance — Design

**Status:** Draft
**Date:** 2026-05-19
**Owner:** ivan
**Related:** `V24__orders_and_tickets.sql`, `V25__processed_webhook_events.sql` (uncommitted at time of writing), `StripeWebhookService`, `WebhookEventDedupService` (uncommitted), `StripeCheckoutService`, `FreeCheckoutService`, `PublicOrderController`, `imin-public/app/tickets/[token]/page.tsx`.

**Pre-existing in-flight work this spec builds on (uncommitted on `master` as of writing).** The webhook handler has already been rewired from `checkout.session.completed` to `payment_intent.succeeded` for fulfilment, and `V25__processed_webhook_events.sql` + `WebhookEventDedupService` already provide event-id–level idempotency. This spec adopts those choices as-is and adds Order/Ticket persistence + email + QR + Wallet + redemption + recovery on top.

## 1. Goal

Within 60 seconds of `payment_intent.succeeded`, the buyer receives an email containing a tamper-evident, QR-coded, single-use ticket that is viewable on a tokenized web URL, addable to Apple Wallet, and ready to be redeemed exactly once at the gate. Issuance is idempotent on the source Order, and a buyer who loses the email can self-recover.

That sentence is the spec. The rest is how.

## 2. Scope

**In scope (v1):**
- Persist `Order` + `Ticket` rows when a paid Stripe Checkout session completes.
- Generate a tamper-evident QR payload per ticket (HMAC-signed).
- Send a confirmation email with inline QR images and tokenized web links.
- Render a real QR on the existing `/tickets/{token}` page.
- Generate a signed Apple Wallet `.pkpass` on demand and link to it from the email + ticket page.
- Single-use redemption endpoint for the gate, authenticated as the organizer.
- Self-service recovery endpoint for buyers who lost the email.
- Webhook idempotency on the source order — duplicate Stripe deliveries do not double-issue, double-email, or double-redeem.
- 60-second SLO with Sentry-instrumented spans for each stage.

**Out of scope (v1, called out so we don't accidentally absorb them):**
- Google Wallet pass (different API surface — Wallet Objects + JWT linking; separate spec).
- Apple Wallet Web Service for pass auto-update on redemption (passes will continue to display "Valid" on the device after gate redemption until the user manually refreshes; acceptable for v1 because the gate is authoritative).
- Refunds / cancellation (separate flow).
- Transfer of tickets between buyers.
- Multi-event "pack" tickets.
- SMS delivery.
- iOS deep-link via universal links from the QR (gate operator opens the redeem URL in the browser).

## 3. Existing artifacts we reuse

| Artifact | What it gives us |
|---|---|
| `orders` table (V24) | `id`, `token`, `event_id`, `org_id`, `email`, `total_minor`, `currency`, `promo_code_id`, `payment_method`, `stripe_session_id`, `created_at`. The `stripe_session_id` column is our idempotency key. |
| `tickets` table (V24) | `id`, `token`, `order_id`, `event_id`, `tier_id`, `tier_name` (snapshotted), `state` (default `'pre'`), `created_at`. We extend it with redemption columns. |
| `FreeCheckoutService.issueFreeOrder` | Template for the paid-path equivalent — same persistence shape, same email pattern, same token generator. |
| `StripeWebhookService.onPaymentIntentSucceeded` (in-flight) | Now keys off `payment_intent.succeeded` (the goal's literal trigger event), reads `tier_id` / `qty` / `promo_id` from the PI's metadata, confirms inventory, and increments promo `used_count`. This is where we plug in. |
| `WebhookEventDedupService` + `processed_webhook_events` table (in-flight, V25) | Provides Stripe `event.id`-level dedup via INSERT + duplicate-key. We rely on this rather than building a second dedup layer. |
| `PublicOrderController` | Already serves `/api/v1/public/orders/{token}` and `/api/v1/public/tickets/{token}`. We add a redeem endpoint and a wallet endpoint alongside; recovery is a sibling controller. |
| `EmailService` (Resend) | Already used by `FreeCheckoutService` and by auth. We add a `TicketIssuanceEmailer` that uses it. |
| `OverlayCompositor` zxing usage | Pattern for QR rendering — `QRCodeWriter` with `ErrorCorrectionLevel.H`. Reused by the ticket QR renderer. |
| `imin-public/app/tickets/[token]/page.tsx` | Page already exists with a placeholder block (`"real QR generation is a follow-up"`) — we replace the placeholder with the rendered QR. |

## 4. Architecture

Component diagram (logical; all on `imin-api` unless noted):

```
Stripe (payment_intent.succeeded)
   │
   ▼
StripeWebhookController
   └─► StripeWebhookService.handleV1   [@Transactional REQUIRED]
         ├─► WebhookEventDedupService.tryRecord(event.id, type)  (V25 — already exists)
         │     └─ false → log + ack 200, no work
         └─► onPaymentIntentSucceeded   (already routes; we extend it)
               ├─► inventoryService.confirmSold(tierId, qty)     (already called)
               ├─► promos.incrementUsedCount(promoId)            (already called)
               └─► PaidCheckoutService.issuePaidOrder(pi)        [NEW]
                     ├─ short-circuit: orders.findByPaymentIntentId(pi.id).isPresent() → return
                     ├─ insert Order (UNIQUE on payment_intent_id; UNIQUE on stripe_session_id too if known)
                     ├─ insert N Ticket rows (state='issued')
                     └─ tx-commit → publish TicketsIssuedEvent
                           └─► [@TransactionalEventListener AFTER_COMMIT, @Async]
                                 TicketIssuanceEmailer.send
                                   ├─ QrPayloadSigner.sign(ticket)  → "imin1.<token>.<hmac>"
                                   ├─ QrImageRenderer.render(payload) → PNG bytes
                                   └─ Resend send(html, text, attachments=[QR PNGs as cid:])

GET /api/v1/public/tickets/{token}                       (read; existing — adds qrPayload)
GET /api/v1/public/tickets/{token}/qr.png                (NEW — server-rendered QR for the FE)
GET /api/v1/public/tickets/{token}/apple-wallet.pkpass   (NEW — signed pass, generated on demand)
POST /api/v1/public/orders/recover                       (NEW — email-based self-recovery)
POST /api/v1/orgs/{orgId}/events/{eventId}/tickets/redeem (NEW — authenticated gate redemption)
```

`imin-public` changes are limited to:
- `/tickets/[token]/page.tsx` — swap the placeholder for an `<img src="…/qr.png">` and an "Add to Apple Wallet" button.
- `/order/[token]/page.tsx` — show one QR per ticket (or "View ticket" link).
- New `lib/api/public-events.ts` helper for `recoverOrder({email, eventId?})`.

## 5. Data model evolution

V25 is taken by the in-flight `processed_webhook_events` migration. New Flyway migration `V26__ticket_issuance.sql`:

```sql
-- Stripe PaymentIntent id stored on Order to serve as the source-of-truth
-- idempotency key. We previously stored only the Session id; now that
-- fulfilment fires on payment_intent.succeeded, the PI id is what the
-- webhook actually has in hand.
ALTER TABLE orders ADD COLUMN stripe_payment_intent_id VARCHAR(128);
ALTER TABLE orders ADD CONSTRAINT orders_stripe_payment_intent_id_unique UNIQUE (stripe_payment_intent_id);
-- (NULLs allowed for the free path; PG UNIQUE treats multiple NULLs as distinct.)
-- The pre-existing stripe_session_id column stays — populated when retrievable
-- from PI.latestCharge/PI.invoice, useful for organizer-side cross-reference, but
-- NOT the idempotency key.

-- Ticket state machine extension.
ALTER TABLE tickets ADD COLUMN redeemed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE tickets ADD COLUMN redeemed_by_user_id UUID;
ALTER TABLE tickets ALTER COLUMN state SET DEFAULT 'issued';
-- Backfill: free-flow tickets currently default to 'pre'. Treat 'pre' as a synonym
-- for 'issued' going forward; we leave existing rows untouched and translate at the
-- API boundary (PublicTicketResponse maps pre→issued for display). Avoids a noisy
-- UPDATE on the production table and lets us deprecate 'pre' over a release.

-- Self-recovery rate limiting. Simple table-counter approach; we don't want to
-- pull Redis into a flow that runs <100x/day. Cleaned up by a TTL job (or never;
-- the row count stays small).
CREATE TABLE order_recovery_attempts (
    id          UUID PRIMARY KEY,
    email       VARCHAR(254) NOT NULL,
    ip_hash     VARCHAR(64) NOT NULL,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_recovery_email_time ON order_recovery_attempts (email, attempted_at);
CREATE INDEX idx_recovery_ip_time    ON order_recovery_attempts (ip_hash, attempted_at);
```

(The `processed_webhook_events` table lives in V25, already drafted in-flight. We do not duplicate it here.)

JPA entity updates:
- `Ticket`: add `redeemedAt`, `redeemedByUserId`; introduce a `TicketState` enum (`ISSUED`, `REDEEMED`, `REVOKED`) — repository continues to read/write the column as a string but the service layer converts. Legacy `'pre'` is parsed as `ISSUED`.
- `Order`: no field changes; new repository method `findByStripeSessionId`.

## 6. The webhook → issuance flow

**Trigger event.** `payment_intent.succeeded` — matches the goal verbatim and is what the in-flight webhook code already routes. The PI carries the same `tier_id` / `qty` / `event_id` / `promo_id` metadata that `StripeCheckoutService` stamps onto the Session, **provided** the Session-create call mirrors that metadata onto `payment_intent_data.metadata`. The in-flight webhook diff claims it does; the actual `StripeCheckoutService` on disk does not yet (see §6a). Fixing that mirror is a prerequisite step in the implementation plan.

**Buyer email.** PI does not carry `customer_details.email` directly. We get it by either:
- Retrieving the `latest_charge` and reading `billing_details.email`, or
- Listing the checkout sessions for the PI (`Session.list({payment_intent: pi.id})`) and reading `customer_details.email`.

We prefer the charge-side lookup — one extra Stripe call, but it carries the buyer email Stripe collected. If both routes fail (e.g. async payment method where the charge isn't ready), the issuance service throws and the webhook returns 500; Stripe retries within seconds. The recovery flow is the last-resort safety net.

**`PaidCheckoutService.issuePaidOrder(PaymentIntent pi)`** is invoked from `onPaymentIntentSucceeded` after the existing inventory/promo work succeeds:

1. **Short-circuit if already issued.** `orders.findByStripePaymentIntentId(pi.id)` — if present, log and return. Stripe retries with the same PI id land here. The UNIQUE constraint on `stripe_payment_intent_id` is the belt; this is the suspenders.
2. **Read PI metadata** — `tier_id`, `qty`, `event_id`, optional `promo_id`. (Same metadata `StripeCheckoutService` already stamps on the Session — see §6a for the prerequisite metadata-mirror fix.)
3. **Resolve buyer email** — call `stripeClient.charges().retrieve(pi.latestCharge)` and read `billingDetails.email`. If missing, fall back to listing checkout sessions for the PI. If still missing, throw — Stripe will retry the webhook.
4. **Inside the same `@Transactional` handler:** (the dedup INSERT already opened the tx)
   - Persist `Order` (`payment_method = "stripe"`, `stripe_payment_intent_id = pi.id`, `stripe_session_id = sessionId if resolved`, `total_minor = pi.amount`, `currency = pi.currency`, `promo_code_id` from metadata if present, `email = buyer email lowercased`).
   - Persist N `Ticket` rows (state `ISSUED`).
   - **Note:** `confirmSold` and `incrementUsedCount` have already run earlier in `onPaymentIntentSucceeded` — we do NOT call them again. They share the same transaction as our inserts, so a failure here rolls all of it back and Stripe retries.
5. **On `DataIntegrityViolationException` for `orders_stripe_payment_intent_id_unique`**: swallow, log "duplicate PI delivery", return.
6. **Publish `TicketsIssuedEvent(orderId)`** via Spring's `ApplicationEventPublisher`. Returns.

After the transaction commits, a `@TransactionalEventListener(phase = AFTER_COMMIT)` method tagged `@Async("ticketEmailExecutor")` picks up the event and dispatches the email. The webhook controller has already returned 200 to Stripe at this point.

**Dedupe ledger.** `WebhookEventDedupService.tryRecord(event.id, type)` is already called as the first step of `handleV1` (V25, in-flight). When the dedup INSERT raises a duplicate key, the handler ack's 200 and skips all work. This protects against Stripe retrying the *exact same `event.id`*; our `stripe_payment_intent_id` UNIQUE protects against two different `event.id`s carrying the same PI (rare, but possible across Stripe API version migrations or dashboard replays).

### 6a. Prerequisite: mirror Session metadata onto PaymentIntentData

`StripeCheckoutService.createCheckoutSession` currently calls `.putMetadata("tier_id", …)` on the Session, but the in-flight PI-driven webhook reads from `pi.getMetadata()` — which is empty unless the Session-create call *also* sets `payment_intent_data.metadata`. Today it does not. The Session builder must add:

```java
.setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
    .setApplicationFeeAmount(applicationFee)
    .setTransferData(...)
    .putMetadata("tier_id", tierId.toString())
    .putMetadata("qty", String.valueOf(quantity))
    .putMetadata("event_id", eventId.toString())
    // plus promo_id when present
    .build())
```

Without this, the PI handler's `parseTierMeta` will see no metadata and skip both inventory confirmation and order persistence — every paid order would silently no-op. Step 1 of the implementation plan.

## 7. QR payload format

```
imin1.<ticketToken>.<hmacB64UrlNoPad>
```

- `imin1` — version prefix. If we ever change the signing scheme, gate scanners can tell what to verify.
- `<ticketToken>` — the existing `tickets.token` (32-char URL-safe base64, 24 random bytes). Already unguessable.
- `<hmacB64UrlNoPad>` — first 16 bytes of `HMAC-SHA256(IMIN_TICKET_SIGNING_SECRET, "v1|" + ticketToken)`, base64url no-pad (22 chars).

Total payload length ≈ 60 chars — fits comfortably in a QR with `ErrorCorrectionLevel.H` at a scan-friendly size (~256 px).

**Why HMAC, not JWT.** Shorter, no per-issuance signing cost worth measuring, scanner can verify offline with one shared secret, no exp/nbf claims we'd just have to fight. JWT buys nothing here.

**Why include the token plaintext.** The gate scanner needs to look up the row to atomically transition state. The HMAC is the integrity check; the token is the lookup key. Splitting the two costs nothing.

**Tamper-evidence.** A scanner that doesn't have the HMAC secret cannot forge a payload for an arbitrary token. A scanner that does have the secret can detect any modification — including swapping in a token that wasn't paid for. Because the token is unguessable to begin with (24 random bytes), forging is already infeasible; the HMAC is defense-in-depth and gives offline verifiability.

**Single source of truth.** Both the email PNG and the Apple Wallet pass barcode encode the exact same payload string. The redeem endpoint accepts that payload as input.

`QrPayloadSigner` (new) lives in `service/ticket/`. The secret comes from `IMIN_TICKET_SIGNING_SECRET` — required at boot; app fails fast if missing. Rotation: append a new version prefix (`imin2.…`) and accept both during a deprecation window.

## 8. Redemption (gate scan)

`POST /api/v1/orgs/{orgId}/events/{eventId}/tickets/redeem`
- **Auth:** organizer JWT, must be a member of `orgId`.
- **Body:** `{ "qrPayload": "imin1.<token>.<hmac>" }`.
- **200 responses, by case:**
  - `{ "result": "redeemed", "ticket": { token, tierName, ordinal, totalInOrder, redeemedAt } }` — atomic UPDATE succeeded (state `ISSUED` → `REDEEMED`). `ordinal`/`totalInOrder` (e.g. "Ticket 2 of 4") is computed at the API boundary from the order's tickets sorted by `created_at`; no schema column needed.
  - `{ "result": "already_redeemed", "ticket": { …, redeemedAt } }` — the same ticket was already redeemed. We surface this distinctly so the gate UI can show "Already used at 21:43".
  - `{ "result": "wrong_event" }` — token is valid (HMAC verifies, row exists) but its `event_id` does not match the path `eventId`. We do **not** echo the actual event id — that would leak which event the ticket belongs to.
  - `{ "result": "revoked" }`.
  - `{ "result": "invalid" }` — HMAC mismatch, payload malformed, or token unknown. We deliberately fold these into one result so a bad scanner can't probe the token space.

**Atomicity.** The state transition is a single UPDATE:

```sql
UPDATE tickets
   SET state = 'redeemed',
       redeemed_at = CURRENT_TIMESTAMP,
       redeemed_by_user_id = :userId
 WHERE token = :token
   AND state IN ('issued', 'pre');
```

Affected-row count of 1 → fresh redemption. 0 → either already redeemed, revoked, or token doesn't exist. The follow-up `SELECT` disambiguates and shapes the response.

**Idempotency at the gate.** A scanner that double-fires the same payload (network retry, double-tap) gets `already_redeemed` on the second try with the same `redeemedAt`. The gate UI treats both `redeemed` and `already_redeemed (within last 5s)` as success — but only `redeemed` triggers the success chime.

**Authorization scope.** A user authenticated for org A cannot redeem a ticket for an event in org B. The endpoint scopes to `orgId` + `eventId` from the path and rejects mismatches before the UPDATE.

## 9. Apple Wallet `.pkpass`

`GET /api/v1/public/tickets/{token}/apple-wallet.pkpass`
- Public (token is the auth).
- Generates a signed `.pkpass` on demand. Not cached; build cost is ~50 ms; `Cache-Control: private, no-store`.
- Returns `503 SERVICE_UNAVAILABLE` if wallet certs are not configured, so we never serve a malformed pass.

**Implementation.** Library: `de.brendamour:jpasskit` (MIT, maintained, supports current `pass.json` schema). Pass shape:

- `passTypeIdentifier` = `pass.com.imin.ticket` (registered in our Apple Developer account).
- `teamIdentifier` from env.
- `serialNumber` = `tickets.token` (Apple requires uniqueness per pass type; the token already is).
- `organizationName` = the event's org display name.
- `description` = `"<Event name> — <Tier name>"`.
- Pass style: `eventTicket`.
- `primaryFields`: event name. `secondaryFields`: date, venue. `auxiliaryFields`: tier name (no attendee number — see §8 on the ordinal).
- `barcodes[0]`: `{ format: "PKBarcodeFormatQR", message: "<QR payload>", messageEncoding: "iso-8859-1", altText: "<ticket token>" }`.
- Branding: logo, icon (1x, 2x, 3x) — bundled in `imin-api` resources. No event-specific images in v1.

**Signing.** PKCS#7 detached signature over `manifest.json` using the Pass Type ID cert (`.p12`) + WWDR intermediate. Provided via env (base64-encoded):

- `APPLE_WALLET_PASS_TYPE_ID` (e.g. `pass.com.imin.ticket`)
- `APPLE_WALLET_TEAM_ID`
- `APPLE_WALLET_CERT_P12_BASE64`
- `APPLE_WALLET_CERT_PASSWORD`
- `APPLE_WALLET_WWDR_PEM_BASE64`

When any are missing, `AppleWalletPassService.isConfigured()` is false; the endpoint returns 503 and the email template suppresses the "Add to Apple Wallet" CTA. Dev environments can skip wallet entirely.

**No Web Service.** We do NOT register a pass-update web service URL. The pass on the device shows the QR forever (or until the buyer deletes it). The gate is the source of truth. Adding the web service is a v2 — it requires an authenticated callback endpoint for Apple's servers and per-device APNs registration.

## 10. Email

`TicketIssuanceEmailer.send(orderId)` runs `@Async`:

1. Load the Order, the Event, and all Tickets for the Order.
2. For each Ticket: compute QR payload via `QrPayloadSigner`, render PNG via `QrImageRenderer` (zxing, 320×320, `ErrorCorrectionLevel.H`, transparent or white background, 16-px quiet zone).
3. Render an HTML email using a Thymeleaf template (or hand-rolled — keep parity with the existing auth emails in `EmailTemplateRenderer`). Content:
   - Greeting + event name/date/venue.
   - One section per ticket: tier name, attendee number, inline QR image (`<img src="cid:ticket-<token>">`), "Open this ticket" link → `/tickets/{token}`, "Add to Apple Wallet" button → `/api/v1/public/tickets/{token}/apple-wallet.pkpass` (omitted if wallet not configured).
   - Footer: "View your order" → `/order/{token}`, recovery hint.
4. Send via `EmailService.send` with `attachments` (CID-inline PNGs).

**Why CID-inline, not data URLs.** Some clients (Gmail's email-render path) drop data-URL images. CID attachments render reliably across Gmail, Apple Mail, Outlook, Spark. Resend's Java SDK supports CID attachments.

**Email subject.** `"Your tickets for <Event name>"` (or `"Your ticket"` singular for qty=1).

**Failure mode.** Async listener catches all exceptions, logs to Sentry, and persists nothing — but does NOT retry. The recovery endpoint is the safety net (most loss modes are also "user lost the email", which is the same UX). If observability shows missed sends becoming material, upgrade to an outbox (Approach B below — explicitly deferred to v2).

## 11. Self-service recovery

`POST /api/v1/public/orders/recover`
- **Body:** `{ "email": "buyer@…", "eventId": "uuid?" }`.
- **Response:** always `204 No Content`, regardless of whether anything was found. Prevents an attacker from probing whether an email purchased a particular event.

**Logic:**
1. Read `email` lowercased + trimmed.
2. Rate-limit: count rows in `order_recovery_attempts` where `email = ? AND attempted_at > now() - interval '1 hour'`. If ≥ 5, return 204 silently (and don't send). Same check on hashed IP. Always insert a row regardless of outcome.
3. `orders.findByEmailAndEventIdAndCreatedAtAfter(email, eventId or null, now - 90 days)` — recovery only re-sends recent orders.
4. If any matches: send a "Recover your tickets" email containing links — `/order/{token}` for each match. Same templating module.
5. Always return 204.

**No tokens in the response body.** Even on success, we don't tell the caller what we found.

**Why 90 days.** Avoid re-sending links to ancient orders the buyer may have intentionally archived. Tunable.

## 12. Idempotency analysis (the goal's hardest requirement)

The chain of duplicate-delivery risks and how each is neutered:

| Risk | Mechanism |
|---|---|
| Stripe redelivers the same `event.id` (retry, dashboard replay) | `processed_webhook_events` PK violation in `WebhookEventDedupService.tryRecord` → handler ack's 200 and skips. |
| Stripe sends multiple `event.id`s referring to the same `payment_intent.id` (API version migration, replay across environments) | `orders.stripe_payment_intent_id UNIQUE` raises `DataIntegrityViolationException` on the second insert; handler treats it as success and returns. |
| `payment_intent.succeeded` arrives twice in parallel | Optimistic short-circuit in step 1 (read-then-skip). Pessimistic: the UNIQUE constraint above is the backstop. |
| `checkout.session.completed` also fires alongside `payment_intent.succeeded` | The webhook explicitly logs and ignores `checkout.session.completed` (already wired in the in-flight diff) — fulfilment is single-sourced on PI. |
| Concurrent issuance + concurrent gate redemption | Redemption is a single atomic UPDATE with a state predicate. Issuance is in its own tx; redemption can only succeed once the issuance tx commits. |
| Buyer scans the same QR twice at the gate | UPDATE returns 0 on the second call → `already_redeemed`. |
| Recovery email sent during a webhook retry | Recovery sends links, not tickets. Tickets remain single-use; recovery just re-delivers the same URLs. |

## 13. 60-second SLO

End-to-end stages, with the Sentry spans we add to each:

- `webhook.received` (Stripe → controller)
- `webhook.dedupe_check`
- `issuance.persist_order_and_tickets`
- `email.async_dispatch` (queued)
- `email.qr_render` (per ticket, in the async worker)
- `email.resend_send` (Resend API call)

The async dispatcher uses a small dedicated executor (core/max=4, queue=64) so we don't starve other `@Async` work during a Stripe burst. The Resend p95 latency in the existing auth flow is ~1.5 s; expect end-to-end p95 well under 10 s, headroom against the 60 s SLO.

If Resend fails, we record `email.failed` with the exception. The buyer recovery flow is the user-visible safety net.

## 14. Public API additions (summary)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/api/v1/public/tickets/{token}` | none | Existing. Response gains `qrPayload` (string) and `walletAvailable` (bool). |
| `GET` | `/api/v1/public/tickets/{token}/qr.png` | none | NEW. Server-rendered QR PNG. `Cache-Control: private, no-store`. |
| `GET` | `/api/v1/public/tickets/{token}/apple-wallet.pkpass` | none | NEW. Signed `.pkpass`. 503 if not configured. |
| `POST` | `/api/v1/public/orders/recover` | none | NEW. Always 204. |
| `POST` | `/api/v1/orgs/{orgId}/events/{eventId}/tickets/redeem` | organizer JWT | NEW. Single-use redemption. |

`SecurityConfig` needs three new `permitAll` entries for the public paths.

## 15. Frontend (`imin-public`)

- `/tickets/[token]/page.tsx`: replace the placeholder dashed block with `<img src="/api/v1/public/tickets/{token}/qr.png" />` and an "Add to Apple Wallet" anchor (shown only when `walletAvailable` is true).
- `/order/[token]/page.tsx`: per-ticket "Open ticket" links already exist. Add a single "Resend by email" link → calls `/orders/recover` with the same email as the order (this is the "I'm logged in to my order page but I lost the email I forwarded to my colleague" case).
- New page `/recover/page.tsx`: small form that calls `/orders/recover`. Linked from "Lost your tickets?" copy on event detail and order pages.
- `lib/api/public-events.ts`: add `recoverOrder` and `getTicketQrUrl` helpers.

## 16. Approaches considered

**A — Synchronous webhook persistence + async email (recommended).** Webhook persists Order + Tickets inside the existing transaction, then publishes an `AFTER_COMMIT` async event for email. Idempotency via UNIQUE on `stripe_session_id` + `processed_webhook_events`. Smallest delta from the current code; reuses the `FreeCheckoutService` shape; meets the 60 s SLO comfortably; the recovery flow is the safety net for the (small) "process crashed before Resend ack'd" window.

**B — Outbox pattern.** Webhook writes an outbox row inside the same transaction; a poller picks it up and sends. Crash-safe; harder to operate (poller, backoff, dead-letter); strictly more durable than A but overkill for current volume. Documented as the v2 upgrade path if Sentry shows missed sends becoming material.

**C — Stripe-driven email entirely.** Use Stripe's built-in receipt with a custom template. Cuts most of this design out — but eliminates QR, wallet, tokenized URL, idempotency control, and a redeemable artifact. Doesn't meet the goal. Mentioned for completeness.

**Recommendation:** A. See §6, §10, §12.

## 17. Configuration

New env vars (defaults marked):

- `IMIN_TICKET_SIGNING_SECRET` — required at boot. 32+ bytes of entropy, hex or base64.
- `APPLE_WALLET_PASS_TYPE_ID` — optional. When absent, wallet endpoint returns 503 and email omits the CTA.
- `APPLE_WALLET_TEAM_ID` — optional, same.
- `APPLE_WALLET_CERT_P12_BASE64` — optional, same.
- `APPLE_WALLET_CERT_PASSWORD` — optional, same.
- `APPLE_WALLET_WWDR_PEM_BASE64` — optional, same.
- `IMIN_TICKET_RECOVERY_WINDOW_DAYS` — default `90`.
- `IMIN_TICKET_RECOVERY_MAX_PER_HOUR` — default `5`.

`application.yaml` gains an `imin.ticket` section exposing the above through `@ConfigurationProperties`.

## 18. Testing strategy

Unit-level (H2, no Stripe network):
- `PaidCheckoutService.issuePaidOrder` — happy path persists Order + Tickets + bumps promo. Duplicate session id is a no-op. Missing buyer email throws.
- `QrPayloadSigner.sign` / `verify` — round-trip; mutated payload fails verification; missing version prefix rejected.
- Redemption service — state machine matrix: ISSUED → REDEEMED returns `redeemed`; REDEEMED → REDEEMED returns `already_redeemed`; REVOKED returns `revoked`; unknown token returns `invalid`; wrong-event scope returns `wrong_event`; wrong-org JWT 403s before touching the DB.
- Recovery service — sends N emails for N matches; sends 0 for non-existent email; returns 204 in both cases; rate limit at 5/hour.
- Apple Wallet generator — `isConfigured` false when any env var missing; `.pkpass` is a valid zip with `manifest.json`, `signature`, and `pass.json`; barcode message matches `QrPayloadSigner.sign(ticket)`.

Webhook flow (MockMvc + Stripe SDK fixtures):
- `checkout.session.completed` paid → Order created, Tickets created, async email captured.
- Same event delivered twice → exactly one Order.
- Two different events for the same session id → exactly one Order (UNIQUE belt-and-suspenders).

Manual / staging:
- Trigger a real test-mode Stripe checkout, confirm email arrives in <60 s with QR rendered, open the QR in a scanner app, hit the redeem endpoint with the payload, confirm the gate UI flips to "Checked in".
- Open the `.pkpass` on iOS Safari, confirm it adds to Wallet and the barcode is scannable from the Wallet card.
- Lose the email; hit `/recover`; confirm the link arrives.

## 19. Migration / rollout

0. Land the in-flight V25 + `WebhookEventDedupService` + PI-driven webhook commit (already authored, just not committed at time of writing).
1. Mirror Session metadata onto `payment_intent_data.metadata` in `StripeCheckoutService` (§6a) — without this, the PI handler is a silent no-op. Tiny diff, ships in the same commit as V25.
2. Ship V26 migration (PI id on Order, ticket state evolution, recovery attempts).
3. Ship `QrPayloadSigner` + the new env var.
4. Ship `PaidCheckoutService.issuePaidOrder` + `TicketsIssuedEvent` + the email path. No feature flag — previous behavior was "no Order, no email", so it's purely additive.
5. Ship `/qr.png` + the FE swap on `/tickets/[token]`.
6. Ship `/apple-wallet.pkpass` + wallet certs in prod env.
7. Ship `/orders/recover` + the FE recovery page.
8. Ship the gate `/tickets/redeem` endpoint. (Organizer scanner UI is a separate `imin-webapp` spec.)

Each release after step 0 is independently deployable. Steps 1–6 hit the 60 s + tokenized URL + wallet half of the goal; step 8 closes the redemption half; step 7 covers self-recovery.

## 20. Open questions

None that block writing the implementation plan. The following are explicitly deferred:
- Google Wallet (separate spec).
- Apple Wallet push updates (web service URL — v2).
- Organizer scanner UI in `imin-webapp` (separate spec).
- Refunds (separate spec).
