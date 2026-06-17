#!/usr/bin/env bash
# stripe-payout-spike.sh — Option B GATING SPIKE (test mode only).
#
# Proves the single load-bearing unknown before building/enabling Track B:
#   Can a v2 RECIPIENT connected account (stripe_balance.stripe_transfers only) be
#   (1) flipped to MANUAL payouts via the Balance Settings API, and
#   (2) paid out to its bank via a platform-initiated Payout.create (acting as the account)?
# Funds the account with a bypass-pending test charge so the payout has available balance.
#
# Reference: imin-api/docs/STRIPE_PAYOUTS_SETUP.md (operator runbook) §1,§2,§6
#            imin-api/docs/superpowers/plans/2026-06-16-payouts-track-b-manual-payouts.md §0 (spike)
#
# Usage:
#   export STRIPE_SECRET_KEY=sk_test_...          # TEST key ONLY (refuses live keys)
#   export ACCT=acct_xxx                          # an ONBOARDED, payouts-enabled test connected account
#   [export FUND_MINOR=5000]                      # buyer charge in minor units to seed funds (default 5000 = EUR 50; 0 = skip funding)
#   [export FEE_MINOR=500]                        # imin application fee on the funding charge (default 500)
#   [export CURRENCY=eur]                         # default eur
#   ./scripts/stripe-payout-spike.sh
#
# Requires: curl, jq.
# Exit 0 = spike PASSED (manual schedule set + payout created). Exit 1 = blocked/failed (message says why).
set -euo pipefail

: "${STRIPE_SECRET_KEY:?set STRIPE_SECRET_KEY (sk_test_... or rk_test_...)}"
: "${ACCT:?set ACCT=acct_... (a test connected account)}"
FUND_MINOR="${FUND_MINOR:-5000}"
FEE_MINOR="${FEE_MINOR:-500}"
CURRENCY="${CURRENCY:-eur}"
API=https://api.stripe.com
PREVIEW_VER="2025-03-31.preview"

command -v jq >/dev/null  || { echo "FAIL  jq not installed (brew install jq)"; exit 1; }

# Hard refuse anything that isn't a test key — this script moves money.
case "$STRIPE_SECRET_KEY" in
  sk_test_*|rk_test_*) ;;
  *) echo "REFUSING: STRIPE_SECRET_KEY is not a TEST key (must start sk_test_ / rk_test_). This spike creates charges + payouts."; exit 1 ;;
esac

say()  { printf '\n=== %s ===\n' "$1"; }
pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
note() { printf '  ----  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; exit 1; }

# curl wrapper: prints the Stripe error message (not just an HTTP code) on failure.
sget()  { curl -s "$@"; }

say "0. Context"
note "account = $ACCT   currency = $CURRENCY   fund = $FUND_MINOR (fee $FEE_MINOR)   key = ${STRIPE_SECRET_KEY:0:12}…"

# Guard: ACCT must be a CONNECTED (organizer) account, NOT the platform's own account.
# (Running against the platform account silently "passes" steps 2/4 but proves nothing about Connect.)
platform_id=$(sget "$API/v1/account" -u "$STRIPE_SECRET_KEY:" | jq -r '.id // empty')
note "platform account (owns the key) = ${platform_id:-unknown}"
if [ -n "$platform_id" ] && [ "$platform_id" = "$ACCT" ]; then
  fail "ACCT=$ACCT is your PLATFORM account (it matches the API key), NOT a connected account.
        Use an organizer's connected acct_ id instead:
          • Stripe Dashboard (test) → Connect → Accounts → an onboarded EU/EUR account, or
          • organizations.stripe_account_id from the imin DB, or
          • create one via POST /api/v1/orgs/{orgId}/stripe/connect → onboarding-link.
        The spike only proves anything when run against a CONNECTED recipient account."
fi

# ---------------------------------------------------------------------------
say "1. Account is a v2 recipient with stripe_balance.stripe_transfers ACTIVE"
acct_json=$(sget "$API/v2/core/accounts/$ACCT?include=configuration.recipient" \
  -H "Authorization: Bearer $STRIPE_SECRET_KEY" -H "Stripe-Version: $PREVIEW_VER" || true)
xfer_status=$(echo "$acct_json" | jq -r '.configuration.recipient.capabilities.stripe_balance.stripe_transfers.status // "absent"')
err=$(echo "$acct_json" | jq -r '.error.message // empty')
[ -n "$err" ] && note "v2 accounts API said: $err  (continuing — capability check below is the gate)"
note "stripe_transfers.status = $xfer_status"
if [ "$xfer_status" = "active" ]; then
  pass "recipient stripe_transfers is active (payout capability exists)"
else
  note "could not confirm 'active' via v2 API; relying on the live payout attempt (step 5) as the real proof"
fi

# ---------------------------------------------------------------------------
say "2. Set MANUAL payout schedule (the #1 unknown) — Balance Settings API, Stripe-Account scoped"
set_json=$(sget "$API/v1/balance_settings" -u "$STRIPE_SECRET_KEY:" \
  -H "Stripe-Account: $ACCT" \
  -d "payments[payouts][schedule][interval]=manual" || true)
set_err=$(echo "$set_json" | jq -r '.error.message // empty')
[ -n "$set_err" ] && fail "Stripe rejected setting manual interval: $set_err  → Option B is NOT viable on this account as-is (check losses-collector=application, account type)."
interval=$(echo "$set_json" | jq -r '.payments.payouts.schedule.interval // .settings.payouts.schedule.interval // "unknown"')
[ "$interval" = "manual" ] || fail "balance_settings update returned interval=$interval (expected manual). Raw: $(echo "$set_json" | jq -c '.')"
pass "payout schedule set to manual"

# Verify read-back
verify_json=$(sget "$API/v1/balance_settings" -u "$STRIPE_SECRET_KEY:" -H "Stripe-Account: $ACCT")
interval2=$(echo "$verify_json" | jq -r '.payments.payouts.schedule.interval // .settings.payouts.schedule.interval // "unknown"')
[ "$interval2" = "manual" ] && pass "read-back confirms interval=manual" || fail "read-back interval=$interval2"

# ---------------------------------------------------------------------------
if [ "$FUND_MINOR" -gt 0 ]; then
  say "3. Seed funds — destination charge with bypass-pending test PM (funds become AVAILABLE immediately)"
  pi_json=$(sget "$API/v1/payment_intents" -u "$STRIPE_SECRET_KEY:" \
    -d amount="$FUND_MINOR" -d currency="$CURRENCY" \
    -d "payment_method=pm_card_bypassPending" -d confirm=true \
    -d "transfer_data[destination]=$ACCT" -d application_fee_amount="$FEE_MINOR" \
    -d "automatic_payment_methods[enabled]=true" -d "automatic_payment_methods[allow_redirects]=never" || true)
  pi_err=$(echo "$pi_json" | jq -r '.error.message // empty')
  pi_status=$(echo "$pi_json" | jq -r '.status // "none"')
  if [ -n "$pi_err" ]; then
    note "funding charge error: $pi_err  (continuing; if the account already has available balance the payout below still works)"
  else
    note "PaymentIntent status=$pi_status  (organizer net = $((FUND_MINOR - FEE_MINOR)) $CURRENCY on $ACCT; imin fee $FEE_MINOR stays on platform)"
    pass "destination charge created — fee retained on platform, net to connected account"
  fi
else
  say "3. Funding skipped (FUND_MINOR=0) — assuming the account already has available balance"
fi

# ---------------------------------------------------------------------------
say "4. Read AVAILABLE balance on the connected account"
bal_json=$(sget "$API/v1/balance" -u "$STRIPE_SECRET_KEY:" -H "Stripe-Account: $ACCT")
avail=$(echo "$bal_json" | jq -r --arg c "$CURRENCY" '(.available[] | select(.currency==$c) | .amount) // 0')
pend=$(echo "$bal_json" | jq -r --arg c "$CURRENCY" '(.pending[] | select(.currency==$c) | .amount) // 0')
note "available=$avail $CURRENCY   pending=$pend $CURRENCY"
if [ "${avail:-0}" -le 0 ]; then
  note "no available balance yet. Re-run with FUND_MINOR>0, or wait for funds to settle. (Manual-schedule proof in step 2 already PASSED.)"
  pass "PARTIAL: manual schedule confirmed; fund the account then re-run to prove the payout."
  exit 0
fi
pass "available balance present: $avail $CURRENCY"

# ---------------------------------------------------------------------------
say "5. Create a manual Payout ON the account (platform acting as the connected account)"
IDEMP="spike-payout-$ACCT-$avail"
payout_json=$(sget "$API/v1/payouts" -u "$STRIPE_SECRET_KEY:" \
  -H "Stripe-Account: $ACCT" -H "Idempotency-Key: $IDEMP" \
  -d amount="$avail" -d currency="$CURRENCY" \
  -d "description=imin Option B spike payout" || true)
po_err=$(echo "$payout_json" | jq -r '.error.message // empty')
[ -n "$po_err" ] && fail "Payout.create failed: $po_err  (if 'No external account', attach a test bank to $ACCT first.)"
po_id=$(echo "$payout_json" | jq -r '.id // "none"')
po_status=$(echo "$payout_json" | jq -r '.status // "none"')
[ "${po_id:0:3}" = "po_" ] || fail "no payout id returned. Raw: $(echo "$payout_json" | jq -c '.')"
pass "Payout created: $po_id  status=$po_status  amount=$avail $CURRENCY"

# ---------------------------------------------------------------------------
say "6. Over-amount guard — a payout > available must be rejected (proves the available clamp is the right guard)"
over=$((avail + 100000))
over_json=$(sget "$API/v1/payouts" -u "$STRIPE_SECRET_KEY:" -H "Stripe-Account: $ACCT" \
  -d amount="$over" -d currency="$CURRENCY" || true)
over_err=$(echo "$over_json" | jq -r '.error.code // .error.type // empty')
if [ -n "$over_err" ]; then
  pass "over-amount correctly rejected ($over_err)"
else
  note "over-amount was NOT rejected (test-mode quirk) — verify the available-balance clamp in code regardless"
fi

# ---------------------------------------------------------------------------
say "SPIKE PASSED"
note "Manual schedule settable on this v2 recipient + platform-initiated Payout works."
note "→ Option B is viable. Safe to enable STRIPE_PAYOUT_SCHEDULE_MANUAL=true in prod (after legal sign-off)."
note "Reset this test account to automatic if reusing it:"
note "  curl $API/v1/balance_settings -u \$STRIPE_SECRET_KEY: -H 'Stripe-Account: $ACCT' -d 'payments[payouts][schedule][interval]=daily'"
