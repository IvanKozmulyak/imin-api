# Stripe Refunds — Testing Guide

How to verify the refund flow end-to-end. Two paths: **automated tests** (fast, covers logic and contracts) and **live Stripe test mode** (verifies the real Connect handshake, webhooks, money movement, and emails).

---

## 1. Automated tests (5 minutes)

These run against H2 with Stripe mocked — no Stripe account needed.

### Backend

```bash
cd imin-api

# Run only the refund-related tests (fast)
./mvnw test -Dtest='RefundServiceTest,RefundControllerTest,RefundConfirmationEmailerTest,StripeRefundServiceTest,StripeWebhookServiceTest,PaidCheckoutServiceTest'

# Or the full suite — refund work is part of 547 tests now
./mvnw test
```

**Expected:** `BUILD SUCCESS`, 0 failures.

**What each test covers:**
| Test class | What it asserts |
|---|---|
| `RefundServiceTest` (9) | Idempotency-key replay returns existing row, cross-org → 404, free order → 409 `ORDER_NOT_REFUNDABLE`, redeemed ticket → 409 `TICKET_REDEEMED`, already-refunded → 409 `TICKET_ALREADY_REFUNDED`, proportional fee math (599 × 5000/10000 → 300), happy path persists with Stripe IDs |
| `StripeRefundServiceTest` (4) | `reverse_transfer=true`, `refund_application_fee=false`, idempotency key passed to Stripe SDK, separate `ApplicationFee.Refund.create()` call when `appFeeRefundMinor > 0`, OTHER reason omits Stripe enum |
| `StripeWebhookServiceTest` (3 new) | `charge.refund.updated` with status=succeeded calls `handleWebhookStatusChange(SUCCEEDED)`, status=failed passes `failure_reason`, replay of same event id is deduped (handler invoked exactly once) |
| `RefundControllerTest` (4) | Missing `Idempotency-Key` header → 400, cross-org → 404, happy path returns 202 with `RefundResponse`, empty `ticketIds` rejected by bean validation |
| `RefundConfirmationEmailerTest` (3) | Builds email from refund + order + event + organization, missing refund skips, missing order email skips |
| `PaidCheckoutServiceTest` (2 new) | Order snapshots `applicationFeeMinor` from `PaymentIntent.applicationFeeAmount`, each Ticket snapshots `priceMinor` from current tier |

### Webapp

```bash
cd imin-webapp
npm run typecheck
```

**Expected:** Clean (`tsc --noEmit` exits 0).

**Note:** There's no test runner configured in the webapp project. Type-checking is the automated gate.

If `npm run build` fails with `Cannot find native binding ... rolldown-binding.darwin-universal.node`, that's a known npm bug (npm/cli#4828) unrelated to refunds — fix by `rm -rf node_modules package-lock.json && npm i`.

---

## 2. Live Stripe test-mode end-to-end (30 minutes)

This is the acceptance gate — confirms real money movement on a Connect account, webhook delivery, and buyer email.

### 2.1. One-time setup

**Stripe Dashboard (test mode):**
1. Go to **Developers → Webhooks** and find the existing `https://imin-api-production.up.railway.app/api/v1/stripe/webhook/v1` endpoint (or your local `stripe listen` tunnel).
2. Click **"+ Add events"** and subscribe to **`charge.refund.updated`**.
3. (Already subscribed: `payment_intent.succeeded`, `payment_intent.payment_failed`, `checkout.session.expired`.)
4. Save.

**Local Stripe CLI** (if testing locally — skip if you'll use Railway):
```bash
stripe listen --forward-to http://localhost:8085/api/v1/stripe/webhook/v1
# copy the printed whsec_… into STRIPE_WEBHOOK_SECRET_V1
```

**Env vars to confirm:**
- `STRIPE_SECRET_KEY=sk_test_…`
- `STRIPE_WEBHOOK_SECRET_V1=whsec_…`
- `RESEND_API_KEY=re_…` (optional — without it the email step logs a warning but webhook still completes)
- `IMIN_EMAIL_FROM_ADDRESS=` a verified Resend sender

**Boot the backend + frontend:**
```bash
# Terminal 1: Postgres
cd imin-api && docker compose up -d

# Terminal 2: backend
cd imin-api && ./mvnw spring-boot:run

# Terminal 3: dashboard
cd imin-webapp && npm run dev
```

### 2.2. Seed test data — buy a paid ticket

You need an order to refund. Cheapest path:

1. **Sign up + verify** an organizer at http://localhost:5173/auth/signup → check Resend inbox for the verify code, enter it.
2. **Onboard Stripe Connect** — go to Settings → Payouts → connect Stripe. Use the test-mode hosted/embedded onboarding. For test mode you can skip verification with `success_test_account` filler data.
3. **Create an event** with a paid ticket tier (e.g. €25.00). Publish it.
4. **Buy 2 tickets** as a buyer. Open the public link (`http://localhost:3000/e/{id}`) in incognito → checkout. Use Stripe test card `4242 4242 4242 4242`, any future expiry, any CVC.
5. Wait ~5 seconds for the webhook to land → buyer receives the issuance email; organizer dashboard shows the order.

### 2.3. Issue a full refund

1. As the organizer, open **Events → [your event] → Orders** tab.
2. The order row shows `2 tickets`, status `Paid`, with a **Refund** button. Click it.
3. Dialog opens with `Full refund (2 tickets)` selected and `Reason: Requested by customer` defaulted.
4. The confirm button reads **"Refund 50.00 EUR"** (or whatever your total was).
5. Click **Refund**. The mutation fires `POST /orders/{id}/refund` with an `Idempotency-Key` header.

**Expected immediately:**
- Toast: *"Refund initiated. The buyer will be notified once it's confirmed by their bank (typically 5–10 business days)."*
- Dialog closes; the row updates to status `Refunded` (or `Partially refunded` if partial).

**Expected within ~5 seconds (webhook lands):**
- Backend log: `[stripe-webhook] charge.refund.updated refundId=re_… status=succeeded mapped=SUCCEEDED`
- Backend log: `[refund-webhook] refund {uuid} SUCCEEDED — inventory released, email queued`
- Backend log: `[refund] released 2 ticket(s) across 1 tier(s) for refund {uuid}`
- Backend log: `[refund-email] sent for refund {uuid} to buyer@example.com`
- Buyer receives the refund confirmation email (check Resend "Sent" log if no real inbox).
- Ticket `state` flips to `refunded` in DB; tier `sold` decrements by 2.

**Stripe dashboard verification:**
- Refund appears under Payments → Refunds with status `Succeeded`.
- Connected account balance is reduced by `refund amount − app fee proportion`.
- Application Fee Refund visible on the original charge.

### 2.4. Issue a partial refund

1. Buy another order (2 tickets again).
2. Open Refund dialog → select **Partial refund** → check exactly **one** ticket.
3. Confirm shows **"Refund 25.00 EUR"**.
4. Click Refund.

**Expected:**
- App fee refund is proportional: e.g. order total 50.00 with fee 2.49 → partial refund of 25.00 returns ~1.25 in fee.
- The other ticket stays `issued` and can still be redeemed.
- Order status now `Partially refunded` in the dashboard.
- Refund button still active (you can refund the remaining ticket later).

### 2.5. Idempotency / double-click safety

1. Open Refund dialog (don't close after one submit — but our dialog auto-closes on success, so for this test you'd need to refresh and retry).
2. Better way: use `curl` directly with the same `Idempotency-Key` twice:

```bash
TOKEN=$(localStorage value for imin.token from browser devtools)
ORDER_ID=…
TICKET_ID=…

curl -X POST http://localhost:8085/api/v1/orders/$ORDER_ID/refund \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: test-double-1" \
  -d '{"ticketIds":["'$TICKET_ID'"],"reason":"OTHER"}'

# Re-run the SAME command. Expected: 200 with the SAME refund id in the body.
# NO duplicate row in DB, NO duplicate Stripe Refund.
```

### 2.6. Error paths — quick smoke

| Scenario | How to trigger | Expected |
|---|---|---|
| **Missing idempotency key** | `curl … POST …/refund` *without* `Idempotency-Key` header | 400 `MISSING_IDEMPOTENCY_KEY` |
| **Cross-org access** | Use one org's token to refund another org's order | 404 `NOT_FOUND` (leak-safe, not 403) |
| **Free order** | Create a free-ticket event, buy a ticket, then POST refund | 409 `ORDER_NOT_REFUNDABLE` |
| **Already-refunded ticket** | Refund a ticket twice (in two separate requests) | 2nd → 409 `TICKET_ALREADY_REFUNDED` |
| **Redeemed ticket** | Use the QR check-in to redeem a ticket, then try to refund it | 409 `TICKET_REDEEMED` |
| **Empty ticketIds** | POST `{"ticketIds": [], "reason": "OTHER"}` | 400 (bean validation) |

### 2.7. Webhook replay safety

Trigger the same `charge.refund.updated` event twice (in the Stripe dashboard → Webhooks → endpoint → recent events → click an event → **"Resend event"**).

**Expected:** Second delivery is skipped by `WebhookEventDedupService` (look for log `[stripe-webhook] v1 dedup-hit eventId={} type=charge.refund.updated`). Inventory is not double-released; email is not sent twice.

### 2.8. Failed-refund path (rare — requires Stripe test trigger)

Stripe test mode rarely fails refunds organically. To force a failure:

```bash
# Use Stripe's "trigger" command for a known failure scenario
stripe trigger charge.refund.updated --override charge.refund.updated:status=failed
```

(Or manually edit the test refund via the Stripe API to status `failed`.)

**Expected:**
- Backend log: `[refund-webhook] refund {uuid} FAILED code=… message=…`
- Refund row has `status=FAILED`, `failure_code`, `failure_message` populated.
- Inventory is **not** released.
- No email is sent.
- (Phase A) Organizer alert email is **not** sent — log only. Phase B follow-up.

---

## 3. What's NOT covered yet (intentional, out of Phase A scope)

- **Reconciliation poller** for refunds stuck in `PENDING` beyond 24h. Manual reconciliation only.
- **Importing dashboard-initiated refunds** — webhook for a refund created in Stripe Dashboard outside our API logs + skips.
- **Organizer alert on FAILED** — log-only for Phase A.
- **Goodwill partial-amount refunds on a single ticket** (e.g. "refund $10 of a $50 ticket").
- **Refunding redeemed tickets via organizer override** — hard blocked.

---

## 4. Quick smoke before merging to prod

Run, in order:
```bash
cd imin-api && ./mvnw test          # full backend suite
cd imin-webapp && npm run typecheck # webapp gate
```

Both green = the contract and logic are intact. The live Stripe test (§2) is recommended once before the very first prod release, then optional for subsequent changes that don't touch the Stripe wire (e.g. UI tweaks).
