# Stripe test → live cutover — operator runbook

**Read this paragraph before scheduling anything.** Production has run on `sk_test_` since
day one, so every Stripe id in the database is a test-mode object. The reset below clears
them, and from the moment it commits **every paid checkout in production answers `404`
until its organizer re-onboards and its tiers are re-saved.** That is the intended state —
the alternative is a `502` at the Stripe call — but it is a full paid-sales outage whose
length is set by how fast organizers re-onboard, not by how fast the SQL runs. Free tiers
keep selling throughout. Announce it, pick an hour, and do not start without §0 and §1 —
§0 (deploying this build under the current test key) happens days or hours earlier, outside
the window, and the window cannot start until it is done.

Artefacts:

| file | what it is |
|---|---|
| `scripts/stripe-live-cutover-preflight.sql` | read-only report; safe against prod |
| `scripts/stripe-live-cutover.sql` | the reset; pure SQL, idempotent |
| `scripts/stripe-live-cutover-postcheck.sql` | every row must be `0` |

`StripeLiveCutoverScriptTest` runs the reset file itself on an isolated H2 and asserts each
transition, the `reserved` re-derive, idempotency, the untouched controls and the all-zero
post-check. A green suite says the script is correct; it says **nothing** about the live
key's scopes, the webhook endpoints, or which mode the deployed key is actually read as —
those are proven only by §9.

---

## 0. Ship the build first — BEFORE the window

The pre-flight, the reset and the post-check all read and write columns that only exist
once this branch is deployed: `organizations.stripe_livemode` (V129) and `test_mode` on
`orders`, `payout_runs` and `disputes` (V130). Run them against today's production and
every statement touching those columns errors.

So: merge this branch and let Railway deploy it **under the current `sk_test_` key**. That
deploy changes no behaviour — `stripe_livemode` stays `NULL` for every existing org and the
guard always allows `NULL`, the `test_mode` defaults are `FALSE`, and nothing flips a flag
until the reset runs. Then confirm the columns landed:

```
psql "$DATABASE_URL" -c '\d organizations' | grep stripe_livemode
psql "$DATABASE_URL" -c '\d orders'        | grep test_mode
psql "$DATABASE_URL" -c '\d payout_runs'   | grep test_mode
psql "$DATABASE_URL" -c '\d disputes'      | grep test_mode
```

Four lines back, or stop — Flyway has not run yet and the window must not start.

## 1. Announce

Tell organizers, before the window: Stripe onboarding must be redone, and the platform
cannot take money for their paid tiers between the freeze and their re-onboarding. Give
them the re-onboarding link and the hour — and ask them **not to press "Connect Stripe"
until the window is over**. An account created between the reset and the key swap is
minted by the old key, and the organizer has to re-onboard a second time (the mode guard
replaces it for them, but they pay for it in another round of Stripe forms).

## 2. Freeze

**There is no checkout kill-switch in this codebase.** The only gates on
`POST /api/v1/public/events/{id}/checkout` are per-org readiness and per-tier eligibility.
The honest options are:

- **(a) accept the window** — roughly ten minutes of DB work plus however long
  re-onboarding takes; or
- **(b) unpublish the affected events** for the duration.

Either way the buyer sees a leak-safe `404` for a paid tier, never a `500`
(`StripeCheckoutService.reserveAndBuildMetadata` returns `ApiException.notFound("Event")`
when the tier has no `stripe_price_id`, when the org has no account, and when readiness is
false). Free tiers never touch Stripe and keep working.

Organizer onboarding is not frozen by anything in the code — repeat the §1 request that
nobody presses "Connect Stripe" until §8 is done.

## 3. Pre-flight

```
psql "$DATABASE_URL" -f scripts/stripe-live-cutover-preflight.sql | tee preflight-$(date +%F).txt
```

Save the output. Two sections can say stop:

- **§7 refunds in `PENDING`/`REQUESTED`** — reported, never changed. Flipping them in SQL
  would skip the two side effects `RefundService` performs in the same transaction
  (deleting the `refund_tickets` claim and sending the failure email) and leave a
  `UNIQUE(ticket_id)` row that `409`s every future refund of that ticket. Decide per row.
- **§8 open disputes**, with the count of revoked tickets on each linked order. The reset
  closes the disputes; it **cannot** restore those tickets — `DisputeIngestService.restoreTickets`
  has per-ticket logic (`redeemed_at`, already-refunded tickets, other open disputes on the
  order) with no honest SQL equivalent. **Expected count: 0.** If it is not 0, restore each
  ticket by hand and record what you did before continuing.

§2 is the post-cutover worklist (which tiers must be re-saved). §4 lists the tiers whose
`reserved` the script re-derives; every one of them ends at 0, and a non-zero `drift`
column is counter drift that predates the cutover, not stock being taken away.

## 4. Back up, then run the reset

```
pg_dump "$DATABASE_URL" -t organizations -t ticket_tiers -t ticket_reservations \
        -t payout_runs -t refunds -t disputes -t orders > cutover-backup-$(date +%F).sql

psql "$DATABASE_URL" -v ON_ERROR_STOP=1 --single-transaction \
     -f scripts/stripe-live-cutover.sql
```

The transaction comes from `--single-transaction`, not from a `BEGIN` in the file. What it
writes, in order:

| § | table | change |
|---|---|---|
| 1 | `ticket_tiers` | `stripe_product_id`, `stripe_price_id` → `NULL` |
| 2 | `organizations` | account id, connect state, payouts/details/manual flags, both requirements arrays, disabled reason, status timestamp, `stripe_livemode` → never-connected |
| 3 | `ticket_reservations` | every `HELD` row → `RELEASED` / `TEST_MODE_CUTOVER` |
| 4 | `ticket_tiers.reserved` | re-derived from the remaining `HELD` rows, i.e. 0 (**after** §3) |
| 5 | `payout_runs` | `planned`/`submitted`/`retrying` → `blocked` / `TEST_MODE_CUTOVER` |
| 6 | `refunds` | unrecovered platform-funded debt closed with `recovery_reversal_id = 'TEST_MODE_CUTOVER'` |
| 7 | `disputes` | `open` → `withdrawn_reinstated` |
| 8 | `orders`, `payout_runs`, `disputes` | `test_mode = TRUE` for every existing row |

`orders`, `tickets`, `settlements`, `processed_webhook_events` and `PENDING`/`REQUESTED`
refunds are deliberately kept.

**§8 is the money guard.** The rows are kept, so without the flag a test-era event would
re-enter `EventRepository.findPayoutCandidates` once its org re-onboards live and Track B
is enabled, and its fake gross would be paid out of the organizer's real connected balance.
Every input to the per-event net is filtered to `test_mode = false`: gross and application
fee (`orders`), the refunds netted off them, the disputed face value withheld
(`disputes`), and the already-triggered amount subtracted (`payout_runs`) — a test-era
payout moved nothing out of a live balance, so counting it would short the first live
payout. Both candidate queries apply the same exclusion; an event whose orders are ALL
test-mode is not a candidate at all. Nothing else — organizer revenue, analytics, tickets,
the door — reads the flag. Rows created after the key swap are stamped at creation from the
running key's prefix (checkout services, `PostEventPayoutService`, `DisputeIngestService`).

Three payout reads are deliberately **not** filtered, because filtering them would loosen a
guard rather than tighten one: the org-level open-dispute freeze, the one-payout-in-flight
check, and `maxAttemptByEventId` (a higher attempt number only makes the next idempotency key
more unique). §5/§7 of the reset leave the first two with no test-era row to match anyway, and
a spurious "wait" is the safe direction.

**A parked run blocks its event only if it is live money.** `blocked` + a reason other than
`NO_BANK_ACCOUNT` excludes the event from the nightly sweep forever
(`existsBlockedNeedingAHuman`), and that query **is** filtered to `test_mode = false`: §5 parks
every non-terminal test-era run, so an event that mixes test-era orders with live sales would
otherwise be withheld from its organizer forever by a run that moved no real money. A run
parked under the live key still needs a human and is only cleared with another SQL edit.

## 5. Post-checks

```
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/stripe-live-cutover-postcheck.sql
```

**Every row must be `0`.** Run it here, immediately after the reset commits — this run is
the authoritative one. Re-run it after §8 as a regression check, with one caveat: a free
order taken between the swap and the re-run is genuinely live, so
`orders_not_marked_test_mode` (and, once real money moves, the `payout_runs` and `disputes`
counterparts) may legitimately read `1` or `2` at that point. Confirm by `created_at`
before dismissing it; a non-zero count on the §5 run is a real failure.

## 6. Create three NEW live webhook endpoints — before touching any secret

Test-mode endpoints do not carry over. Leave them in the test dashboard — they cost
nothing and a future staging environment reuses them.

**This comes before §7 because the endpoints are what mint the three `whsec_`.** Setting
`STRIPE_SECRET_KEY` on its own redeploys the API immediately (§8), and it would come back
up running a live key against three test-mode signing secrets: every live webhook fails
signature verification, so nothing is fulfilled, no hold is released and the Connect mirror
never moves. Create all three endpoints in the **live** dashboard first and collect their
signing secrets; do not save a single Railway variable until you have all four values in
hand.

- **Endpoint A — "Your account"** → `POST /api/v1/stripe/webhook/v1`, secret
  `STRIPE_WEBHOOK_SECRET_V1`:
  `payment_intent.succeeded`, `payment_intent.processing`, `payment_intent.payment_failed`,
  `payment_intent.canceled`, `checkout.session.expired`,
  `checkout.session.async_payment_failed`, `checkout.session.async_payment_succeeded`,
  `refund.updated`, `refund.failed`, `charge.refund.updated`, `transfer.created`,
  `transfer.reversed`, `charge.refunded`, `charge.dispute.created`, `charge.dispute.closed`,
  `charge.dispute.funds_withdrawn`, `charge.dispute.funds_reinstated`.
  **Do not** subscribe `checkout.session.completed` — fulfilment is driven by the
  PaymentIntent, which is what proves money moved.
- **Endpoint B — "Connected accounts"** → the **same** `/webhook/v1` URL, secret
  `STRIPE_WEBHOOK_SECRET_CONNECT`: `payout.created`, `payout.paid`, `payout.failed`.
- **Endpoint C — V2** → `POST /api/v1/stripe/webhook/v2`, secret `STRIPE_WEBHOOK_SECRET_V2`:
  `v2.core.account[requirements].updated`,
  `v2.core.account[configuration.recipient].capability_status_updated`,
  `v2.core.account[configuration.recipient].updated`,
  `v2.core.account[future_requirements].updated`, `v2.core.account.updated`.
  The bracket notation is literal — a dot-notation subscription matches nothing.

## 7. Swap the four Railway secrets — in ONE update

`STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET_V1`, `STRIPE_WEBHOOK_SECRET_CONNECT`,
`STRIPE_WEBHOOK_SECRET_V2` — all four edited together and saved **once**. Railway redeploys
on every variable change, so saving them one at a time boots one or more intermediate
builds with a live key and stale test secrets. Paste all four, then apply.

A **restricted** `rk_live_` key is recommended. The scopes below are the complete set,
derived by reading every `stripeClient.<x>()` call site in `src/main/java` — anything
missing here is a silent production failure at the first call, not a startup error.

| call site | scope |
|---|---|
| `checkout().sessions().create` (`StripeCheckoutService`), `checkout().sessions().list` (`PaidCheckoutService`) | Checkout Sessions **write** |
| `paymentIntents().create/retrieve` (`StripePaymentIntentService`), `.list` (`PaidFulfilmentReconciler`), `.cancel` (`ReservationSweeper`) | PaymentIntents **write** |
| `charges().retrieve` (`PaidCheckoutService`, `SettlementIngestService`, `PostEventPayoutService`) | Charges **read** |
| `products().create/update` (`StripeProductService`) | Products **write** |
| `prices().retrieve` (`StripeProductService`) | Prices **write** — the Product create mints the Price via `default_price_data` |
| `coupons().create/delete` (`StripeCheckoutService`) | Coupons **write** |
| `refunds().create` (`StripeRefundService`) | Refunds **write** |
| `balance().retrieve` (`PostEventPayoutService`) | Balance **read** |
| `payouts().create/retrieve` (`PostEventPayoutService`) | Payouts **write** |
| `transfers().reversals().create` (`PostEventPayoutService`) | Transfers **write** |
| `balanceSettings().update` (`StripePayoutScheduleService`) | Balance settings **write** |
| `accounts().retrieve` + `expand=external_accounts` (`PostEventPayoutService`, `PayoutService`) | Connect: Accounts **read**, External accounts **read** |
| `accounts().loginLinks().create` (`PayoutService`) | Connect: Login links **write** |
| `accountSessions().create` (`StripeConnectService`) | Connect: Account sessions **write** |
| `v2().core().accounts().create` (`StripeConnectService`) / `.retrieve` (`StripeConnectStatusMirror`) | v2 Core Accounts **write** |
| `v2().core().accountLinks().create` (`StripeConnectService`) | v2 Core Account links **write** |
| `v2().core().events().retrieve` (`StripeWebhookService`) | **v2 Core Events read** |

Two things that are easy to miss:

- **v2 Core Events read.** Without it every v2 thin event `502`s
  (`StripeWebhookService` fetches the full event by id) and the Connect mirror is dark —
  no org ever reaches `ACTIVE`.
- The key must be usable **on connected accounts** (`Stripe-Account` header): every
  payout, balance and external-account call sets it.

## 8. Redeploy — it already started

There is nothing to click. A Railway environment-variable change redeploys the service by
itself (≈3 min), so applying §7 IS the redeploy; §0 already proved the migrations are in.
Wait for the deploy to go healthy, confirm the boot log's `STRIPE MODE: live` line
(`StripeConfig` logs it at WARN on every start, naming the mode it detected from the
trimmed key prefix — `test` here means the key was not pasted as you think it was, and every order
taken from now on would be stamped as test money), then re-run §5.

## 9. Smoke test on a staff org

Connect → onboard → publish a €1 event → buy it → verify the `payment_intent.succeeded`
fulfilment, the ticket email and the QR → refund it → verify `refund.updated` lands. This
is what proves the restricted key's scopes and all three endpoints' signatures. Nothing in
the test suite can stand in for it.

**Then prove the money is stamped LIVE.** The mode flag is written from
`StripeProperties.isLiveKey()`; if that ever read the key wrongly, every euro of live
revenue would be filed as test money, silently excluded from the payout net, and no other
surface would complain. Take the `pi_` from the smoke purchase and check the row:

```
psql "$DATABASE_URL" -c "SELECT id, test_mode FROM orders WHERE stripe_payment_intent_id = '<pi_...>'"
```

`test_mode` must be **`f`**. If it is `t`, stop: re-check §8's `STRIPE MODE:` line and the
key itself before letting any more sales in — and note that the orders already taken are
wrongly flagged and need fixing by hand.

The same flag exists on `payout_runs` and `disputes`, written the same way from the same
method — there is nothing to buy that creates them, so check them when they first appear:

```
psql "$DATABASE_URL" -c "SELECT id, event_id, test_mode FROM payout_runs WHERE created_at > now() - interval '1 day'"
psql "$DATABASE_URL" -c "SELECT id, stripe_dispute_id, test_mode FROM disputes  WHERE created_at > now() - interval '1 day'"
```

Both must read `f` for anything created after §7. (`payout_runs` stays empty while §11's
flag is `false`; a dispute only appears if a real buyer charges back.)

## 10. Organizers re-onboard

`GET /api/v1/orgs/{orgId}/stripe/status` now answers `NOT_STARTED`, which the dashboard
already renders as the "connect Stripe" CTA. Then, **per active event**, an organizer (or
ops) must re-save each tier from §2 of the pre-flight: clearing `stripe_price_id` does NOT
self-heal on the next checkout — `StripeProductService.syncTier` is called only on a tier
write and on the promo-only checkout path. Until the tier is re-saved it cannot sell.

### The livemode guard, from here on

`organizations.stripe_livemode` (V129) records which Stripe mode minted the stored
`acct_`: it is stamped `true`/`false` at account creation from the running key's prefix
(`sk_live_`/`rk_live_` ⇒ live, on the trimmed key). `StripeConnectService.getStatusLive` —
the checkout readiness read — refuses an org whose recorded mode contradicts the running
key, logging an ERROR and reporting the org as not connected, so the buyer gets the same
leak-safe `404` rather than a sale into an account that cannot receive a cent. `getStatus`
refuses it the same way, so the dashboard shows the "connect Stripe" CTA instead of an
ACTIVE badge on an unreadable account. **`NULL` means "unknown" and is always allowed**:
V129 backfills nothing and the reset sets the column back to `NULL`, so no existing org can
be bricked by it.

A mismatched org is **recoverable without SQL**: `getOrCreateAccount` treats an account from
the other mode as no account at all — it mints a new one under the running key, overwrites
`stripe_account_id`, resets the Connect mirror to never-connected and stamps the new mode,
logging a WARN with both ids. That is the path an organizer who ignored §1 and pressed
"Connect Stripe" during the window walks out on; the abandoned test account is left in the
test dashboard.

## 11. `STRIPE_PAYOUT_SCHEDULE_MANUAL` stays `false`

The cutover does not change the Track B gate. `docs/STRIPE_PAYOUTS_SETUP.md` §7 still
requires legal sign-off, retention awareness and live `payout.*` delivery before manual
payouts are switched on; the capability spike is done (§7 of that doc records it). With the
flag false, `PayoutScheduleBackfillSweeper`, `PostEventPayoutSweeper`, `PayoutRetentionMonitor`
and `StripePayoutScheduleService.ensureManual` all return at their first line, so the
`payout_runs` work in §5 of the reset is precautionary.

One consequence to plan for: the test-mode checklist in `STRIPE_PAYOUTS_SETUP.md` §6 now
needs a **staging deploy with its own `sk_test_` key** — production's key is live, and
nothing test-mode can be exercised against it again.
