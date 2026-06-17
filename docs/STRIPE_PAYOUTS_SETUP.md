# Stripe Payouts Setup — Option B (Operator Runbook)

Destination charges + **manual** payouts. The platform (imin) gates the bank payout and triggers it post-event.
This is a **configuration** runbook (Dashboard + API). App code is out of scope — see `docs/decisions/` and `CLAUDE.md`.

> Run everything in **test mode first** (§6). Use a **restricted/secret key** with Connect + Balance + Payout write scopes.

---

## 1. Prerequisites — confirm before touching anything

Per connected account (`acct_...`), verify:

- [ ] Account is a **v2 Accounts API recipient** config with capability `stripe_balance.stripe_transfers` = `active`
      (NOT classic Express/Custom/Standard, NOT `card_payments`, NOT v1 `transfers`).
- [ ] Losses collector / fees collector = `application` → **platform may control payouts**.
- [ ] Organizer has attached an **external bank account** (onboarding collects it as `EVENTUALLY_DUE`).
- [ ] Payouts enabled (mirrored locally: `organizations.stripe_payouts_enabled = true`, `stripe_connect_state = ACTIVE`).

Check capability + bank + payout-eligibility:

```bash
curl https://api.stripe.com/v2/core/accounts/acct_XXX?include=configuration.recipient,identity \
  -H "Authorization: Bearer $STRIPE_SECRET_KEY" \
  -H "Stripe-Version: 2025-03-31.preview"
# confirm: configuration.recipient.capabilities.stripe_balance.stripe_transfers.status == "active"

curl https://api.stripe.com/v1/balance \
  -u "$STRIPE_SECRET_KEY:" -H "Stripe-Account: acct_XXX"
# confirms the account has a balance surface (default schedule = automatic until §2)
```

---

## 2. Set manual payout schedule (the core change)

**Accounts v2 CANNOT manage payout settings.** Use the account-scoped **Balance Settings API** (v1), with the
`Stripe-Account` header. Same call for new and existing accounts. Run once per account, **after** `stripe_transfers`
goes `active`.

```bash
curl https://api.stripe.com/v1/balance_settings \
  -u "$STRIPE_SECRET_KEY:" \
  -H "Stripe-Account: acct_XXX" \
  -d "payments[payouts][schedule][interval]=manual"
```

Verify:

```bash
curl https://api.stripe.com/v1/balance_settings \
  -u "$STRIPE_SECRET_KEY:" -H "Stripe-Account: acct_XXX"
# expect: payments.payouts.schedule.interval == "manual"
```

**New accounts:** the app sets this automatically on the recipient→ACTIVE transition (and a backfill sweep covers
existing accounts). If running manually for a one-off, do it per account as above.

After `manual` is set: Stripe **stops** auto-paying that account's bank. imin's scheduled job creates the bank Payout
post-event via `POST /v1/payouts` + `Stripe-Account: acct_XXX` (app code, not this runbook).

---

## 3. Lock down the org-controlled schedule

So organizers can't flip themselves back to automatic / change cadence:

1. Dashboard → **Connect → Settings → Payouts** (payout schedule controls for connected accounts).
2. Turn **off** "Allow connected accounts to manage their payout schedule" (express dashboard).
3. For full lockdown on express-dashboard accounts → **contact Stripe support** to disable the payout-schedule UI.

> **The `balance_settings` API call (§2, `interval=manual`) is the AUTHORITATIVE lock — not this toggle.** The Dashboard toggle above may **not be present** for every setup. Verify it exists; if it does, turn it off. Either way, the per-account manual interval set in §2 is what actually stops auto-payouts. Do **not** rely on the toggle alone.

---

## 4. Webhooks

`imin-api/CLAUDE.md` is the source of truth. In **Stripe Workbench → Webhooks**
(`dashboard.stripe.com/workbench/webhooks`), the **"Events from"** scope (`Your account`
vs `Connected accounts`) is chosen **when you create an endpoint** and is NOT a toggle on an
existing one. `payout.*` fire on the **connected** account, so they need a *Connected accounts*
endpoint; `transfer.*` / `charge.*` are platform events. The V1 handler now verifies against
**both** `STRIPE_WEBHOOK_SECRET_V1` **and** `STRIPE_WEBHOOK_SECRET_CONNECT`, so two endpoints
can share the one `/webhook/v1` URL.

**Endpoint A — existing "Your account" (`STRIPE_WEBHOOK_SECRET_V1`), URL `…/api/v1/stripe/webhook/v1`.**
Keep its current events; ADD:
- `transfer.created`, `transfer.reversed`
- `charge.refunded`
- `charge.dispute.created`, `charge.dispute.closed`, `charge.dispute.funds_withdrawn`, `charge.dispute.funds_reinstated`

**Endpoint B — NEW, "Events from" = Connected accounts, SAME URL `…/api/v1/stripe/webhook/v1`.**
Subscribe ONLY:
- `payout.created`, `payout.paid`, `payout.failed`

Copy Endpoint B's signing secret into env `STRIPE_WEBHOOK_SECRET_CONNECT` (see §5), then redeploy.
Until that secret is set, `payout.*` events fail signature verification and payout-arrival stays dark.

**Endpoint C — existing V2** (`POST /api/v1/stripe/webhook/v2`, `STRIPE_WEBHOOK_SECRET_V2`) — Connect
mirror thin-events, no change. See `CLAUDE.md`.

> Verify after: in Workbench, "Send test event" on Endpoint B for `payout.paid` returns 2xx (signature
> OK). A real connected `payout.paid` then flips its `payout_runs` row to PAID. A fake/sample account id
> is logged-and-skipped (no matching org) — that's expected; it only proves the signature path.

---

## 5. Required env / secrets

| Var | Value |
|---|---|
| `STRIPE_SECRET_KEY` | `sk_live_...` (test: `sk_test_...`) — Connect + Balance + Payout write scopes |
| `STRIPE_WEBHOOK_SECRET_V1` | `whsec_...` from Endpoint A (Your account, `/webhook/v1`) |
| `STRIPE_WEBHOOK_SECRET_CONNECT` | `whsec_...` from Endpoint B (Connected accounts, same `/webhook/v1` URL) — required for `payout.*` |
| `STRIPE_WEBHOOK_SECRET_V2` | `whsec_...` from the V2 endpoint |
| payout buffer (app config) | days after `event_end` before payout trigger; default **3 days**, resolved **Europe/Amsterdam** |

---

## 6. Test-mode verification checklist

Run end-to-end in **test mode** before live:

1. [ ] Onboard a test recipient account → confirm `stripe_transfers` = `active` + bank attached (§1).
2. [ ] Set manual schedule (§2) → re-`GET` balance_settings → `interval == "manual"`.
3. [ ] Run a **destination charge** (public checkout) with card `4242 4242 4242 4242`.
4. [ ] Confirm split: organizer share on **`acct_XXX` balance**, `application_fee_amount` on **platform** balance
       (`GET /v1/balance` with and without `Stripe-Account`).
5. [ ] Refund pre-payout with `reverse_transfer=true` → funds claw back from org balance; net corrects.
6. [ ] Wait for funds to move `pending → available` (~2 business days; only **available** pays out).
7. [ ] Trigger Payout (`POST /v1/payouts` + `Stripe-Account: acct_XXX`, idempotency key) → confirm `payout.paid` +
       bank arrival.
8. Test cards: `4242 4242 4242 4242` success · `4000 0025 0000 3155` 3DS · `4000 0000 0000 9995` decline ·
   SEPA `AT32 1904 3002 3547 3204`.

---

## 7. Go-live gate

- [ ] **Capability spike passed**: manual-schedule + acting-as-account Payout round-trip confirmed on a real
      v2-recipient (`stripe_balance.stripe_transfers`-only) test account.
- [ ] **Legal sign-off**: under destination charges **imin is merchant of record** and owns dispute/chargeback
      liability — VAT / consumer-protection / safeguarding accepted.
- [ ] **Retention-deadline awareness**: once manual, funds must be disbursed within Stripe's retention window
      (~90 days most countries; US ~2y; Thailand ~10d). The post-event job must release before the deadline.
- [ ] Webhooks (§4) live + receiving connected-account `payout.*` in prod before trusting the settlements read-model.

---

### Sources
- Manual payout schedule: https://docs.stripe.com/connect/manage-payout-schedule
- Balance settings API: https://docs.stripe.com/api/balance_settings
- Create payout: https://docs.stripe.com/api/payouts/create
- Platform controls: https://docs.stripe.com/connect/platform-controls-for-stripe-dashboard-accounts
- Destination charges: https://docs.stripe.com/connect/destination-charges
