# Stripe Connect Refunds — Design Spec

**Status**: Draft — pending review
**Date**: 2026-05-25
**Driver**: Phase A live-money acceptance gate. Organizers must be able to issue full or partial Stripe refunds (via Connect) that release ticket inventory.

## Goal

Let organizers issue full or partial Stripe refunds on paid orders, with funds reversed from their connected account, the platform application fee returned proportionally, ticket inventory released back into the tier, and the buyer notified by email once the refund is confirmed by Stripe.

## Non-goals (Phase A)

- Goodwill partial-amount refunds on a single ticket (e.g., "refund $10 of a $50 ticket"). Refund unit is whole tickets only.
- Importing refunds initiated from the Stripe dashboard (out-of-band). Log + skip.
- Refund reconciliation poller for stuck `pending` refunds. Manual reconciliation for Phase A.
- Refunding `redeemed` tickets. Blocked at API layer.
- Organizer alert email on `failed` refunds — log only for Phase A; future enhancement.
- Free orders (`payment_method=free`) — out of scope.
- A top-level `/orders` page in the dashboard. Refund UI lives on the existing `EventDetailPage` as a new "Orders" tab.

## Locked decisions

| # | Decision | Rationale |
|---|---|---|
| 1 | Refund unit = list of ticket IDs (not freeform amount). Full refund = all non-refunded tickets in the order. | Orders have no `order_items` table — tickets are the line items. Eliminates "user typed $20.51 on $100 order" ambiguity. |
| 2 | Always set `reverse_transfer=true` and `refund_application_fee=false`. Always issue a separate, explicit `ApplicationFee.Refund.create` call with our computed proportional amount — even on a one-shot full refund. | Industry-standard ticket-platform behavior. Organizer is not charged platform fee on refunded sales. Reverses connected-account balance. Single formula avoids the "second partial refund treated as 'full of remaining' double-refunds the fee" trap. |
| 3 | Pure webhook-driven inventory release. The synchronous `POST` never decrements `tier.sold` or flips `ticket.state`; only the `charge.refund.updated` → `SUCCEEDED` webhook does. | Stripe refunds can stay `pending` for hours on debit cards. One code path = no double-release risk. |
| 4 | Dedicated `refunds` table + `refund_tickets` join. Not fields on `orders`. | Orders can have multiple partial refunds over time, each with its own Stripe ID and status. Mirrors Stripe data model. |
| 5 | Subscribe to `charge.refund.updated` only (not `charge.refunded`). | Sync API call seeds the row; the webhook is the sole authority for status transitions. |
| 6 | Two-layer idempotency. Client supplies `Idempotency-Key` header (UUID generated when the modal opens). Server uses a deterministic Stripe `Idempotency-Key = "refund_" + refund.id` on `refunds.create`. | Survives double-click, network retry, server crash mid-call. |
| 7 | New `Ticket.state = 'REFUNDED'`. Refunded tickets cannot be redeemed. | A refunded ticket's QR code must not scan at the door. |
| 8 | Application-fee refund computed by us (`round(orig_fee × refund_amount / charge_amount)`), not Stripe-auto for partials. | Stripe `refund_application_fee=true` is all-or-nothing. Partials require an explicit `ApplicationFee.Refund` call. |
| 9 | Refund UI = "Orders" tab on `EventDetailPage`. No `/orders` route. | No orders page exists today; product is event-centric. Lowest-cost surface. |
| 10 | Refund-confirmation email via `@Async` event listener mirroring the existing `TicketsIssuedEvent` pattern. | Webhooks must return 200 fast; Resend latency must not block ack. |

## Architecture overview

```
[Dashboard]                          [imin-api]                          [Stripe]
   │                                     │                                  │
   │  POST /api/v1/orders/{id}/refund    │                                  │
   │  Idempotency-Key: <uuid>            │                                  │
   ├────────────────────────────────────►│                                  │
   │  { ticketIds, reason }              │                                  │
   │                                     │ authz: order.orgId == principal  │
   │                                     │ validate tickets refundable      │
   │                                     │ compute amount + fee refund      │
   │                                     │ INSERT refund (status=REQUESTED) │
   │                                     │ INSERT refund_tickets            │
   │                                     │                                  │
   │                                     │  refunds.create(...)             │
   │                                     │  Idempotency-Key: refund_<id>    │
   │                                     ├─────────────────────────────────►│
   │                                     │  Refund{id, status: pending}     │
   │                                     │◄─────────────────────────────────┤
   │                                     │ UPDATE refund.stripe_refund_id,  │
   │                                     │        status=PENDING            │
   │  202 Accepted, RefundResponse       │                                  │
   │◄────────────────────────────────────┤                                  │
   .                                     .                                  .   (async)
   │                                     │  POST /api/v1/stripe/webhook/v1  │
   │                                     │◄─────────────────────────────────┤
   │                                     │  charge.refund.updated SUCCEEDED │
   │                                     │ dedup(event.id) — skip if seen   │
   │                                     │ conditional UPDATE refund        │
   │                                     │   WHERE status=PENDING           │
   │                                     │   → if won race:                 │
   │                                     │      lock tier FOR UPDATE        │
   │                                     │      decrement tier.sold         │
   │                                     │      set ticket.state=REFUNDED   │
   │                                     │      publish RefundConfirmedEvent│
   │                                     │  200 OK                          │
   │                                     ├─────────────────────────────────►│
   │   @Async listener                   │                                  │
   │   ResendEmailService.send(...)      │                                  │
```

## Data model — V28 migration

```sql
-- V28__refunds.sql

CREATE TABLE refunds (
  id                            UUID PRIMARY KEY,
  order_id                      UUID NOT NULL REFERENCES orders(id),
  stripe_refund_id              VARCHAR(255) UNIQUE,           -- null until create returns
  stripe_charge_id              VARCHAR(255),                  -- denorm, webhook lookup uses refund_id
  stripe_payment_intent_id      VARCHAR(255) NOT NULL,         -- snapshot from order
  amount_minor                  BIGINT NOT NULL,
  currency                      VARCHAR(8) NOT NULL,
  application_fee_refund_minor  BIGINT NOT NULL DEFAULT 0,
  reason                        VARCHAR(32) NOT NULL,          -- requested_by_customer|duplicate|fraudulent|other
  status                        VARCHAR(16) NOT NULL,          -- REQUESTED|PENDING|SUCCEEDED|FAILED|CANCELED
  failure_code                  VARCHAR(64),
  failure_message               VARCHAR(500),
  initiated_by_user_id          UUID NOT NULL REFERENCES users(id),
  idempotency_key               VARCHAR(128) NOT NULL,
  created_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX refunds_order_idem_unique
  ON refunds (order_id, idempotency_key);
CREATE INDEX refunds_stripe_charge_id_idx ON refunds (stripe_charge_id);
CREATE INDEX refunds_order_id_idx ON refunds (order_id);

CREATE TABLE refund_tickets (
  refund_id  UUID NOT NULL REFERENCES refunds(id),
  ticket_id  UUID NOT NULL REFERENCES tickets(id) UNIQUE,    -- a ticket can be refunded at most once
  PRIMARY KEY (refund_id, ticket_id)
);
```

Ticket state enum extension is code-only (the column is `VARCHAR` with no DB constraint to alter):

```
TicketState = ISSUED | REDEEMED | REVOKED | REFUNDED
```

## Code structure

```
src/main/java/com/imin/iminapi/
├── refund/
│   ├── Refund.java                       @Entity
│   ├── RefundTicket.java                 @Entity, composite key
│   ├── RefundRepository.java
│   ├── RefundTicketRepository.java
│   ├── RefundStatus.java                 enum: REQUESTED|PENDING|SUCCEEDED|FAILED|CANCELED
│   ├── RefundReason.java                 enum mapped to Stripe values
│   ├── RefundService.java                business chokepoint
│   ├── RefundController.java             POST /orders/{id}/refund, GET /orders/{id}/refunds
│   ├── dto/
│   │   ├── CreateRefundRequest.java      record(ticketIds, reason)
│   │   └── RefundResponse.java           record(id, status, amount, ...)
│   └── event/
│       ├── RefundConfirmedEvent.java
│       └── RefundFailedEvent.java
├── refund/email/
│   └── RefundConfirmationEmailer.java    @Async @EventListener → ResendEmailService
├── stripe/
│   └── StripeRefundService.java          wrapper around stripeClient.refunds()
└── stripe/StripeWebhookService.java      modify: add onChargeRefundUpdated
```

`RefundService` is the chokepoint. `RefundController` is dumb. `StripeRefundService` isolates SDK calls for testability (mirrors `StripeCheckoutService`).

## API contract

### POST /api/v1/orders/{orderId}/refund

**Auth**: Bearer token; resolves `AuthPrincipal.orgId`. Returns 404 if `order.orgId != principal.orgId` (leak-safe).

**Headers**:
- `Idempotency-Key: <client UUID>` — required; 400 `MISSING_IDEMPOTENCY_KEY` if absent.

**Request body**:
```json
{
  "ticketIds": ["uuid", "uuid"],
  "reason": "requested_by_customer"
}
```

`ticketIds`: required, non-empty, must all belong to the order. "Full refund" from the UI just means "all non-refunded ticket IDs".
`reason`: `requested_by_customer` | `duplicate` | `fraudulent` | `other`.

**Response 202 Accepted** (intentionally 202 — refund is async):
```json
{
  "id": "uuid",
  "orderId": "uuid",
  "stripeRefundId": "re_...",
  "amountMinor": 5000,
  "currency": "eur",
  "applicationFeeRefundMinor": 349,
  "status": "pending",
  "reason": "requested_by_customer",
  "ticketIds": ["uuid", "uuid"],
  "createdAt": "2026-05-25T..."
}
```

Idempotent replay: same `Idempotency-Key` returns 200 with existing `RefundResponse` (not 202). Callers treat both as success.

**Errors** (via existing `ApiError` envelope):

| HTTP | code | when |
|---|---|---|
| 400 | `MISSING_IDEMPOTENCY_KEY` | header absent |
| 400 | `INVALID_TICKET_SELECTION` | ticketIds empty, contain duplicates, or include tickets from another order |
| 404 | `ORDER_NOT_FOUND` | order missing or belongs to another org |
| 409 | `ORDER_NOT_REFUNDABLE` | `payment_method='free'`, missing `stripe_payment_intent_id`, or all tickets already refunded |
| 409 | `TICKET_ALREADY_REFUNDED` | any selected ticket is in a prior REQUESTED/PENDING/SUCCEEDED refund |
| 409 | `TICKET_REDEEMED` | any selected ticket is `REDEEMED` |
| 422 | `STRIPE_REFUND_FAILED` | Stripe returned an error (`failure_message` in `fields`) |
| 502 | `UPSTREAM_UNAVAILABLE` | Stripe API unreachable; row persisted at `REQUESTED` → caller can safely retry |

### GET /api/v1/orders/{orderId}/refunds

Returns `RefundResponse[]` for the order (authz: same org rule). Used by the UI for refund history and status.

### Webhook subscription

Add `charge.refund.updated` to V1 webhook switch in `StripeWebhookService.handleV1Transactional`. (Will also need to be added to the Stripe Dashboard webhook endpoint configuration — implementation plan includes that as a manual step.)

## RefundService — critical paths

### createRefund

```java
@Transactional
public Refund createRefund(UUID orderId, AuthPrincipal principal,
                           String idempotencyKey, List<UUID> ticketIds,
                           RefundReason reason) {

  // 1. Idempotency short-circuit
  Optional<Refund> existing = repo.findByOrderIdAndIdempotencyKey(orderId, idempotencyKey);
  if (existing.isPresent()) return existing.get();

  // 2. Load + authorize order (404 on cross-org leak)
  Order order = orderRepo.findById(orderId).orElseThrow(() -> notFound("ORDER_NOT_FOUND"));
  if (!order.getOrgId().equals(principal.orgId())) throw notFound("ORDER_NOT_FOUND");
  if (order.getStripePaymentIntentId() == null) throw conflict("ORDER_NOT_REFUNDABLE");

  // 3. Validate ticket selection
  List<Ticket> tickets = ticketRepo.findAllByIdAndOrderId(ticketIds, orderId);
  if (tickets.size() != ticketIds.size()) throw badRequest("INVALID_TICKET_SELECTION");
  Set<UUID> already = refundTicketRepo.findRefundedTicketIds(ticketIds);
  if (!already.isEmpty()) throw conflict("TICKET_ALREADY_REFUNDED");
  if (tickets.stream().anyMatch(t -> t.getState() == TicketState.REDEEMED))
    throw conflict("TICKET_REDEEMED");

  // 4. Compute amounts
  //    Per-refund proportional formula — applied uniformly to every refund
  //    (full or partial, first or Nth). Sum of all refunds' app-fee refunds
  //    will equal the original app fee when the order is fully refunded
  //    (rounding aside; documented acceptable error of ≤ N cents for N refunds).
  long refundAmountMinor = sumTicketPriceMinor(tickets);   // see "tier price snapshot" precursor
  long origAppFee = order.getApplicationFeeMinor();        // snapshot on order at checkout; see precursor
  long appFeeRefundMinor = Math.round(
    (double) origAppFee * refundAmountMinor / order.getTotalMinor());

  // 5. Persist refund + refund_tickets BEFORE Stripe call.
  //    Unique constraint on refund_tickets.ticket_id catches concurrent refunds
  //    on the same ticket → DataIntegrityViolation → translated to TICKET_ALREADY_REFUNDED.
  Refund refund = new Refund(
    UUID.randomUUID(), orderId, /* stripeRefundId */ null,
    /* stripeChargeId */ null, order.getStripePaymentIntentId(),
    refundAmountMinor, order.getCurrency(), appFeeRefundMinor,
    reason, RefundStatus.REQUESTED, principal.userId(), idempotencyKey
  );
  refundRepo.save(refund);
  refundTicketRepo.saveAll(tickets.stream()
    .map(t -> new RefundTicket(refund.getId(), t.getId())).toList());

  // 6. Call Stripe. Deterministic idempotency key protects against retries that
  //    cross step 5/7 — Stripe returns the same Refund object on replay.
  //    StripeRefundService internally issues TWO calls (refund + application fee refund),
  //    both with idempotency keys derived from refund.id.
  StripeRefund sr = stripeRefundService.create(
    order.getStripePaymentIntentId(),
    refundAmountMinor, order.getCurrency(),
    reason, appFeeRefundMinor,
    /* idempotencyKey */ "refund_" + refund.getId()
  );

  // 7. Persist Stripe IDs + initial status
  refund.setStripeRefundId(sr.getId());
  refund.setStripeChargeId(sr.getCharge());
  refund.setStatus(mapStripeStatus(sr.getStatus()));  // PENDING or SUCCEEDED
  refundRepo.save(refund);

  // 8. Inventory release waits for the webhook even if Stripe returned SUCCEEDED here.
  //    Keeps one code path for state transitions.
  return refund;
}
```

### onChargeRefundUpdated (webhook)

```java
@Transactional
public void onChargeRefundUpdated(Event event) {
  // Dedup gate already applied upstream in handleV1Transactional via WebhookEventDedupService.
  StripeRefund sr = extractRefundFromCharge(event);
  Refund refund = repo.findByStripeRefundId(sr.getId()).orElse(null);
  if (refund == null) {
    log.warn("Webhook refund {} not in DB; skipping (likely dashboard-initiated)", sr.getId());
    return;
  }

  RefundStatus newStatus = mapStripeStatus(sr.getStatus());
  if (refund.getStatus() == newStatus) return;
  if (refund.getStatus().isTerminal()) return;     // SUCCEEDED/FAILED terminal; ignore late events

  // Race-safe transition
  int rows = repo.updateStatusIfCurrent(refund.getId(), refund.getStatus(), newStatus);
  if (rows == 0) return;

  if (newStatus == RefundStatus.SUCCEEDED) {
    releaseInventoryAndMarkTickets(refund);
    eventPublisher.publishEvent(new RefundConfirmedEvent(refund.getId()));
  } else if (newStatus == RefundStatus.FAILED) {
    refund.setFailureCode(sr.getFailureReason());
    refund.setFailureMessage(/* derived */);
    repo.save(refund);
    eventPublisher.publishEvent(new RefundFailedEvent(refund.getId()));   // log only in Phase A
  }
}

private void releaseInventoryAndMarkTickets(Refund refund) {
  List<UUID> ticketIds = refundTicketRepo.findTicketIdsByRefundId(refund.getId());
  List<Ticket> tickets = ticketRepo.findAllById(ticketIds);
  Map<UUID, Long> qtyByTier = tickets.stream()
    .collect(groupingBy(Ticket::getTierId, counting()));
  for (var entry : qtyByTier.entrySet()) {
    TicketTier tier = tierRepo.findByIdForUpdate(entry.getKey()).orElseThrow();
    tier.setSold(tier.getSold() - entry.getValue().intValue());
    tierRepo.save(tier);
  }
  tickets.forEach(t -> t.setState(TicketState.REFUNDED));
  ticketRepo.saveAll(tickets);
}
```

Idempotency stack on the webhook path:
1. `WebhookEventDedupService.tryRecord(event.id)` — INSERT-on-conflict at top.
2. Status conditional UPDATE — only one transaction wins `PENDING→SUCCEEDED`.
3. Terminal-state check — late events no-op.

## Stripe call details — StripeRefundService

```java
StripeRefund create(String paymentIntentId, long amountMinor, String currency,
                    RefundReason reason, long appFeeRefundMinor,
                    String idempotencyKey) {

  // (1) Refund the charge. Reverse the transfer to pull funds back from connected acct.
  //     refund_application_fee = false; we issue an explicit ApplicationFee.Refund below.
  RefundCreateParams params = RefundCreateParams.builder()
    .setPaymentIntent(paymentIntentId)
    .setAmount(amountMinor)
    .setReason(reason.toStripe())
    .setReverseTransfer(true)
    .setRefundApplicationFee(false)
    .putMetadata("order_id", /* order id */)
    .build();
  RequestOptions opts = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
  StripeRefund r = stripeClient.refunds().create(params, opts);

  // (2) Refund the application fee proportionally. Skipped when appFeeRefundMinor == 0.
  if (appFeeRefundMinor > 0) {
    String chargeId = r.getCharge();
    String appFeeId = stripeClient.charges().retrieve(chargeId).getApplicationFee();
    FeeRefundCreateOnApplicationFeeParams feeParams = FeeRefundCreateOnApplicationFeeParams.builder()
      .setAmount(appFeeRefundMinor).build();
    RequestOptions feeOpts = RequestOptions.builder()
      .setIdempotencyKey(idempotencyKey + "_fee").build();
    stripeClient.applicationFees().refunds().create(appFeeId, feeParams, feeOpts);
  }
  return r;
}
```

**Note on rounding**: across N partial refunds that together cover the full order, the per-refund proportional formula can leave a cumulative rounding error of at most N−1 cents in the application fee. Acceptable for Phase A. If precision matters later, switch to "compute the last refund's fee as `orig_app_fee − sum_of_prior_fee_refunds`".

**Stripe API version**: spec recommends pinning the SDK version (e.g., `2024-12-18.acacia` or whatever is current at implementation time) in `StripeConfig`. Currently unpinned — implementation plan flags this as a small precursor change to protect against schema drift.

## Email — RefundConfirmationEmailer

`@Async @EventListener(RefundConfirmedEvent)`. Renders via existing `EmailTemplateRenderer`. Sends via `ResendEmailService.send(to, subject, html, text)`.

Template fields:
- Order short code (first 8 of `order.id`)
- Event name + date
- Refunded ticket count + tier names
- Refund amount + currency
- Payment-method tail (last 4) if available from `Charge.payment_method_details`
- "Expected to appear in 5–10 business days" boilerplate
- Organizer contact email (from `Organization`)
- imin support footer

To: `order.email`. Subject: `"Refund confirmed for {Event Name}"`.

## Webapp UI

New files:
```
src/features/events/orders/
  EventOrdersTab.tsx
  RefundOrderDialog.tsx
  ordersApi.ts
```

`EventOrdersTab` is added as a tab on `EventDetailPage`. Table columns:
- Order short code, Buyer email, Tickets (`N` or `N (M refunded)`), Total, Status (chip: Paid | Partially Refunded | Refunded), Created at, "Refund" button (disabled when no non-refunded tickets remain).

`RefundOrderDialog` (reuses `ConfirmDialog` + `FormField` per existing convention):
- Radio: "Full refund" (default) | "Partial refund"
- If partial: checkbox list of non-refunded tickets (each `{tierName} — €{price}`). Running total displayed.
- Reason dropdown (4 enum values).
- Confirm: `"Refund €{amount}"`, `dangerous=true`.
- `Idempotency-Key` generated at dialog mount, included via `IDEMPOTENT_ENDPOINTS` allowlist in `apiFetch`.
- On success: toast `"Refund initiated — buyer will be notified once confirmed by bank (typically 5–10 business days)"`, close dialog, invalidate `['events', eventId, 'orders']` query.
- On error: toast with `ApiError.message`, dialog stays open.

Hand-written types added to `src/shared/api/types.ts`:

```ts
export interface OrderRow {
  id: string;
  shortCode: string;
  email: string;
  totalMinor: number;
  currency: string;
  ticketCount: number;
  refundedTicketCount: number;
  status: 'paid' | 'partially_refunded' | 'refunded';
  createdAt: string;
}

export interface Refund {
  id: string;
  orderId: string;
  amountMinor: number;
  currency: string;
  status: 'requested' | 'pending' | 'succeeded' | 'failed' | 'canceled';
  reason: RefundReason;
  ticketIds: string[];
  createdAt: string;
  failureMessage?: string;
}

export type RefundReason = 'requested_by_customer' | 'duplicate' | 'fraudulent' | 'other';
```

Endpoints used by the UI:
- `GET  /api/v1/events/{eventId}/orders` → `Paginated<OrderRow>` (new endpoint — implementation plan includes building it; mirrors existing `PayoutsTab`-style listing)
- `GET  /api/v1/orders/{orderId}/refunds` → `Refund[]`
- `POST /api/v1/orders/{orderId}/refund`  → `Refund`

## Failure modes

| Scenario | Behavior |
|---|---|
| Stripe API down during `refunds.create` | Refund row stays at `REQUESTED`; controller returns 502. Same idempotency key on retry → Stripe returns same Refund, row advances. |
| Process crashes between Stripe call success and DB save of `stripe_refund_id` | Same as above. Deterministic Stripe idempotency key (`refund_<our-id>`) prevents double-refund on retry. |
| Webhook never arrives (Stripe outage) | Refund stuck in `PENDING`. Phase A: manual reconciliation. Phase B: scheduled reconciler polling Stripe for pending refunds older than 24h. |
| Two organizers race-refund the same tickets | UNIQUE on `refund_tickets.ticket_id` rejects loser with 409 `TICKET_ALREADY_REFUNDED`. |
| Refund created in Stripe dashboard (not via our API) | Webhook arrives, `findByStripeRefundId` returns empty, handler logs + skips. |
| Refund failed (`charge.refund.updated` status=failed) | Mark `FAILED`, persist `failure_code`/`failure_message`. Inventory NOT released. Publish `RefundFailedEvent` — Phase A: log only. |
| Redeemed ticket | Blocked at API layer (`TICKET_REDEEMED`). Organizer handles manually via Stripe dashboard. |
| Late webhook after terminal state | `refund.getStatus().isTerminal()` check no-ops. |
| Duplicate webhook delivery | `WebhookEventDedupService.tryRecord` no-ops the replay. |

## Testing strategy

Conventions: JUnit 5, H2 + Flyway, MockMvc-style controller tests, Mockito for `StripeClient`, real HMAC signatures for webhook tests (existing pattern from `StripeWebhookServiceTest`).

**Unit / service**:
- `RefundServiceTest` — happy path, idempotency replay, all validation error branches, app-fee proportional math, full vs partial branching, cross-org 404.
- `StripeRefundServiceTest` — verify `RefundCreateParams` fields, idempotency key passed through, partial-refund triggers `ApplicationFee.Refund.create`.

**Webhook integration**:
- `StripeWebhookServiceRefundTest` — synthetic `charge.refund.updated` payloads with HMAC. Cases: PENDING→SUCCEEDED, PENDING→FAILED, replay (dedup), late event after terminal, refund not in DB.

**Inventory integration**:
- `RefundInventoryReleaseIT` — full Stripe-mocked flow: simulate purchase → simulate refund webhook → assert `tier.sold` decremented + ticket state flipped + reservations untouched + email event published.

**Controller**:
- `RefundControllerTest` — missing `Idempotency-Key` → 400, cross-org → 404, redeemed ticket → 409.

**Webapp**:
- Vitest + RTL component test for `RefundOrderDialog` — form validation, mutation invocation, error path.

## Out of scope / follow-ups

- Reconciliation poller for `PENDING` refunds older than 24h (Phase B).
- Importing dashboard-initiated refunds (Phase B).
- Organizer alert email on `FAILED` refunds (Phase B — currently logs only).
- Goodwill partial-amount refunds on a single ticket (product decision; structurally clean to add later as `amountMinor` alongside `ticketIds`).
- Refunding redeemed tickets via override flag (intentionally blocked Phase A).
- Top-level `/orders` page in the dashboard.

## Required precursors

These must land before (or alongside) the refund feature itself; the design depends on them.

**P1 — Snapshot `applicationFeeMinor` on `orders` table.** Today, `Order` stores `totalMinor` but not the application fee that was deducted at checkout. We need it as the source of truth for `appFeeRefundMinor` computation. Two options: (a) add `application_fee_minor` column to `orders` and populate it in `PaidCheckoutService.issuePaidOrder` from `PaymentIntent.applicationFeeAmount`; (b) retrieve from Stripe `Charge` on each refund. **Recommend (a)** — avoids a Stripe round-trip per refund and survives Stripe outages. Add the column in the same V28 migration.

**P2 — Snapshot `priceMinor` on `tickets` table.** Today, `Ticket` snapshots `tierName` but not the price. The tier price can change between purchase and refund, so we need price-at-purchase to compute `refundAmountMinor` faithfully. Add `price_minor` column to `tickets`. Populate it at issuance time in `PaidCheckoutService` / `FreeCheckoutService`. Backfill existing rows from current `tier.price_minor` (acceptable approximation; no current Phase A orders to support refunds for yet). Same V28 migration.

**P3 — `GET /api/v1/events/{eventId}/orders` listing endpoint.** Required by the new "Orders" tab. Returns `Paginated<OrderRow>`. Org-scoped via `principal.orgId == event.orgId`. Mirrors the existing payouts listing pattern.

## Implementation order

Mirrors goal's suggested order, plus precursors:

1. V28 migration: `refunds`, `refund_tickets`, `orders.application_fee_minor`, `tickets.price_minor`. Backfill `tickets.price_minor` from current tier prices.
2. Entities + repositories (`Refund`, `RefundTicket`, repos; `Ticket.priceMinor`; `Order.applicationFeeMinor`).
3. `RefundService.createRefund` + `StripeRefundService` + `RefundController` POST + `GET /orders/{id}/refunds` + new `GET /events/{eventId}/orders` listing.
4. Webhook handler (`onChargeRefundUpdated`) + inventory release + `RefundConfirmedEvent` / `RefundFailedEvent`.
5. `RefundConfirmationEmailer` (`@Async` listener) + email template.
6. Webapp: types + `ordersApi.ts` + `EventOrdersTab` + `RefundOrderDialog` + tab wiring on `EventDetailPage`.
7. Stripe Dashboard: subscribe the webhook endpoint to `charge.refund.updated`. End-to-end test in Stripe test mode + a live test refund.

## Open questions

1. **Stripe SDK API version pin** — currently unpinned. Recommend pinning to the current Java SDK default at impl time (e.g., `2024-12-18.acacia`) in `StripeConfig`. Tiny scope but warrants a yes/no before doing it.
2. **Block on FAILED refunds: log only vs. organizer email vs. Sentry warning** — spec says log only for Phase A. Confirm that's acceptable given live-money launch (organizers may not notice failures otherwise).
