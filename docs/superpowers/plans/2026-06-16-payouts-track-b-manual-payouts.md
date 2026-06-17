# Plan — Payouts Track B: manual payouts, event-date-triggered bank Payout (Option B)

**Date:** 2026-06-16
**Project:** `imin-api` (Java 17 · Spring Boot 4 · Flyway · Lombok · stripe-java 32.1.0)
**Design intent:** [`/Users/ivan/imin/stripe-payouts-config.md`](../../../../stripe-payouts-config.md) (Option B)
**Status:** spec → STEP 0 spike gated. Build nothing past Phase 0 until the spike passes.

---

## 1. Summary + how it differs from today

Today imin runs **destination charges** on the platform account (`StripeCheckoutService.setPaymentIntentData` → `application_fee_amount` + `transfer_data.destination`, no `transfer_group`, no `on_behalf_of`), so each organizer's net lands on **their** connected-account balance at charge time and imin keeps only its fee. But imin sets **no payout schedule anywhere** — grep finds zero `balance_settings` / schedule / `Payout.create` code — so the organizer's connected account is on Stripe's **default automatic** schedule and Stripe already auto-pays their bank. Option B changes exactly two things: (a) flip each payout-eligible connected account to a **manual** payout schedule via the account-scoped **Balance Settings API** (`stripeClient.balanceSettings().update(...)` with `Stripe-Account` header, `payments.payouts.schedule.interval = MANUAL`), so funds sit in the organizer's balance instead of auto-disbursing; and (b) add a new **scheduled post-event job** that, at `event.endsAt + buffer`, creates a **Payout** on the connected account (`stripeClient.payouts().create(...)` with the `Stripe-Account` header) for that event's available net, recorded in a new `payout_runs` table written **before** the Stripe call so retries/replica-overlap never double-pay. Checkout gains one line (`transfer_group = eventId`); refund-before-payout already works unchanged. **This does not change merchant-of-record:** under destination charges imin remains the business of record and owns dispute liability (losses-collector = APPLICATION) — that is the residual legal sign-off, not an engineering change.

### Corrections to `stripe-payouts-config.md` (the doc's idealized assumptions vs. the v2-recipient reality)

The doc was written before the account config was verified. The following lines are **stale** and the implementation MUST follow the corrected reality, not the doc:

| Doc line | Doc says | Reality (code-grounded) | Plan follows |
|---|---|---|---|
| §22 L24 | "Account type: Express or Custom — not Standard" | These are **v2 Accounts-API RECIPIENT-configuration** accounts with `dashboard=EXPRESS` (`StripeConnectService.buildCreateParams`, L184/L190-209), **not** a classic v1 account *type*. Platform-controlled payouts are available because losses-collector = APPLICATION. | "v2 recipient config, express dashboard, platform-controlled" |
| §22 L25 | "Capabilities: `card_payments` + `transfers` active" | Account requests **ONLY** `stripe_balance.stripe_transfers` (L195-205). No `card_payments`, no v1 `transfers`. `card_payments` is unnecessary — charges run on the **platform** account via destination charges. | only `stripe_balance.stripe_transfers` |
| §22 L26 | "set `settings.payouts.schedule.interval=manual` via the **Accounts v1 API**" | Correct mechanism is the **account-scoped Balance Settings API**: `BalanceSettingsService.update(params, RequestOptions.setStripeAccount(acct))` with `payments.payouts.schedule.interval = MANUAL`. The legacy v1 `Account.update settings.payouts.schedule` path has no recipient-config equivalent here. | `balanceSettings().update(...)` |
| §29 L36 | optional `on_behalf_of = acct_organizer` | **Not viable.** `on_behalf_of` requires a payments capability (`card_payments`) the recipient accounts deliberately do NOT have; adding it forces a MERCHANT config + `card_payments` onboarding (contradicts `createOnboardingLink` L262-271, which attaches RECIPIENT only). | **DROP** `on_behalf_of` for v1 |
| §29 L34 | charge adds `transfer_group=<eventId>` | Not done today; not required for destination charges, but **cheap and useful** for reconciliation. | ADD it (Phase 3) |
| §14 L18 | "Funds … are **held** (payout schedule = manual)" | **Not yet true** — accounts are on the default automatic schedule. Phase 1 makes it true. | Phase 1 + backfill |

---

## 2. STEP 0 — Pre-build test-mode SPIKE (GATING)

**The single load-bearing unknown:** does `BalanceSettingsService.update(... interval=MANUAL)` scoped by the `Stripe-Account` header apply cleanly to a **v2 RECIPIENT** account that has ONLY `stripe_balance.stripe_transfers` (no `card_payments`), and does a subsequent `Payout.create` acting as that account then succeed against its **available** balance? The SDK surface exists (verified in `stripe-java-32.1.0.jar`: `StripeClient.balanceSettings()`, `.payouts()`, `.balance()`; `BalanceSettingsUpdateParams…Schedule.Interval.MANUAL`; `PayoutCreateParams`) and Stripe docs (verified 2026-06-16) say a platform that owns loss liability MAY set manual payouts via `balance_settings` regardless of how the account was minted — but **no code path exercises this combination today**, so it is unprovable without a test-mode round-trip. **Build nothing past this step until it passes.**

### 0.1 Spike harness

Run against `sk_test_…`. Either a throwaway `@SpringBootTest`-free `main()` using the existing `StripeClient` bean wiring, or `stripe` CLI + a tiny Java scratch class. Use a **real onboarded** test recipient account (one that has reached `stripe_transfers.status == active`, i.e. an org showing `stripeConnectState=ACTIVE` in dev) — a not-yet-active account is the wrong subject (manual schedule on a non-payouts-eligible account may be premature; see Phase 1 timing).

### 0.2 Exact calls to prove

> **Builder-path caveat:** the nested Balance Settings builder chain below (`Payments → Payouts → Schedule → Interval.MANUAL`) is the **exact builder class path TBC at spike compile** — only `…Schedule.Interval.MANUAL` (the enum constant) and the service methods `balanceSettings().update(...)` / `payouts().create(...)` / `RequestOptions.setStripeAccount(...)` are **jar-verified** in `stripe-java-32.1.0`. The full nested builder class names (`BalanceSettingsUpdateParams.Payments.Payouts.Schedule…`) are **not** yet verified and must be confirmed when the spike compiles against the jar; adjust if the real nesting differs.

```java
String acct = "acct_TEST_ACTIVE_RECIPIENT";
RequestOptions onAcct = RequestOptions.builder().setStripeAccount(acct).build();

// (A) Flip to manual — the crux call.
BalanceSettingsUpdateParams manual = BalanceSettingsUpdateParams.builder()
    .setPayments(BalanceSettingsUpdateParams.Payments.builder()
        .setPayouts(BalanceSettingsUpdateParams.Payments.Payouts.builder()
            .setSchedule(BalanceSettingsUpdateParams.Payments.Payouts.Schedule.builder()
                .setInterval(BalanceSettingsUpdateParams.Payments.Payouts.Schedule.Interval.MANUAL)
                .build())
            .build())
        .build())
    .build();
BalanceSettings bs = stripeClient.balanceSettings().update(manual, onAcct);
// ASSERT: bs.getPayments().getPayouts().getSchedule().getInterval() == "manual"  (no exception)

// (B) Read available balance ON the account.
Balance bal = stripeClient.balance().retrieve(BalanceRetrieveParams.builder().build(), onAcct);
// pick getAvailable() entry whose currency == event currency; that amount is the ceiling.

// (C) Create a manual Payout ON the account (after a real test charge has gone available).
PayoutCreateParams po = PayoutCreateParams.builder()
    .setAmount(availableMinor)            // <= available
    .setCurrency("eur")
    .setDescription("imin spike payout")
    .build();
RequestOptions poOpts = RequestOptions.builder()
    .setStripeAccount(acct)
    .setIdempotencyKey("spike-" + acct + "-1")
    .build();
Payout payout = stripeClient.payouts().create(po, poOpts);
// ASSERT: payout.getId() starts po_, status in {pending,in_transit,paid}, amount == requested.
```

### 0.3 Pass criteria
1. (A) returns 200 and the interval reads back `manual` — no `card_payments`/capability error.
2. (B) returns an available balance for the account currency.
3. (C) returns a `po_` id; a deliberate over-amount returns Stripe `balance_insufficient` (proves the available clamp is the right guard, not a silent overdraw).
4. In Stripe test dashboard, force the payout to `paid` (`stripe payouts` test helpers) and confirm a `payout.paid` event fires **on the connected account**.

### 0.4 Fallback if the spike FAILS

If `balance_settings` rejects the recipient account, or `Payout.create` can't act as it:
- **Fallback B1 — transfer-timing control (Option A-lite).** Switch checkout from destination charges to **separate charges + transfers**: charge on the platform, hold funds on the **platform** balance, and run the post-event job as `Transfer.create(destination=acct, transfer_group=eventId)` at `endsAt + buffer`. imin then custodies organizer funds until release (heavier safeguarding exposure — flag to legal) but needs no manual schedule and no acting-as-account payout. The post-event job, `payout_runs` record, and reconciliation skeleton from Phases 2/6 are reused almost verbatim (Transfer instead of Payout).
- **Fallback B2 — Stripe support manual lockdown.** Request manual payout control via Stripe support for the recipient configuration, then retry (A)/(C). Slower; only if B1's custody is unacceptable.

Record the spike outcome inline in this file under a `## STEP 0 result` heading before proceeding.

---

## 3. Phase 1 — Set accounts to manual payouts (+ backfill)

### 3.1 Config (`StripeProperties`, `imin.stripe.*`)

Add to `StripeProperties` (bind env in `application.yaml`):

| Field | Env | Default | Use |
|---|---|---|---|
| `payoutScheduleManual` | `STRIPE_PAYOUT_SCHEDULE_MANUAL` | `true` | master kill-switch for the whole Phase 1/2 behavior — when `false`, never flip schedule, never run the post-event job (accounts stay on Stripe auto, today's behavior). |
| `payoutBufferDays` | `STRIPE_PAYOUT_BUFFER_DAYS` | `3` | days after `event.endsAt` before the payout job fires. |
| `payoutZone` | `STRIPE_PAYOUT_ZONE` | `Europe/Amsterdam` | timezone for resolving `payout_at` (event has its own `timezone` column, default `"UTC"`; resolve the buffer in this zone for the business deadline, not the event's local zone). |

### 3.2 The Balance Settings call — new `StripePayoutScheduleService`

New class `com.imin.iminapi.stripe.StripePayoutScheduleService`. Single responsibility: set a connected account to manual, idempotently.

**Transaction boundary (mandatory):** `ensureManual` runs in its **own** `@Transactional(propagation = Propagation.REQUIRES_NEW)` — it must not enlist in the caller's `syncFromStripe` transaction, so a Stripe failure here can never roll back the mirror's just-saved projection. Inside it: **call Stripe FIRST** (`balanceSettings().update(...)`), and only **on success** set `stripe_payout_schedule_manual = true` and `orgs.save(org)`. The `catch (StripeException)` block logs **WARN and returns** — it MUST NEVER write the flag on failure, so the backfill/next-active-transition retries the account.

```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
void ensureManual(Organization org) {
    if (!props.isPayoutScheduleManual()) return;
    if (org.isStripePayoutScheduleManual()) return;          // already done (new column, §3.4)
    String acct = org.getStripeAccountId();
    RequestOptions onAcct = RequestOptions.builder().setStripeAccount(acct).build();
    try {
        stripeClient.balanceSettings().update(<MANUAL params from 0.2>, onAcct);   // Stripe FIRST
        org.setStripePayoutScheduleManual(true);                                   // flag only on SUCCESS
        orgs.save(org);
        log.info("[payout-schedule] set MANUAL for org={} acct={}", org.getId(), acct);
    } catch (StripeException e) {
        log.warn("[payout-schedule] failed to set MANUAL for org={} acct={} — will retry", org.getId(), acct, e);
        // return WITHOUT writing the flag → backfill / next ACTIVE transition retries
    }
}
```
- **Idempotent twice over:** guarded by the new `stripe_payout_schedule_manual` boolean column AND the call itself is naturally idempotent (setting manual on an already-manual account is a no-op `update`).
- **Swallows upstream errors like the mirror does** — on `StripeException`, log WARN and leave the column `false` so the backfill/next-active-transition retries. Never throw into the onboarding flow, and **never** write the flag on a failed call.

### 3.3 Timing — where it is invoked (decision: on the ACTIVE transition, NOT at create)

Do **NOT** put the call in `buildCreateParams` — the v2 `Recipient` builder has no schedule node (jar-verified), and an account that isn't payouts-active yet shouldn't have a schedule forced on it.

Invoke `ensureManual(org)` from **`StripeConnectStatusMirror.applyTo`'s persistence path** at the moment `stripe_transfers.status` flips to `active` (i.e. `payoutsEnabled` true and the org's previous `stripePayoutsEnabled` was false, OR `stripe_payout_schedule_manual` still false). Concretely: `applyTo(...)` is `static` and a **pure projection** — it must stay **Stripe-free** (its unit tests construct no Stripe client), so the side-effect MUST NOT go in `applyTo`. Wire it in **`StripeConnectStatusMirror.syncFromStripe`** right **after** `orgs.save(org)` (L68) — `if (org.isStripePayoutsEnabled() && !org.isStripePayoutScheduleManual()) payoutScheduleService.ensureManual(org);`. This lands exactly once, idempotently, the moment payouts are enabled, on both the webhook fast path and the `StripeConnectStatusSweeper` backstop (both go through `syncFromStripe`). Inject `StripePayoutScheduleService` into `StripeConnectStatusMirror` as a **REQUIRED constructor dependency** — the mirror has a single 2-arg constructor today and there are **no** nullable/optional legacy constructors here to mimic; the sweeper also calls `syncFromStripe`, so the bean must be in scope on every path. Bump it to a 3-arg constructor and update all wiring. Unit tests that don't exercise the schedule flip MUST supply a **no-op/stub** `StripePayoutScheduleService` (a Mockito mock or a tiny fake whose `ensureManual` does nothing) rather than relying on a nullable field.

### 3.4 Persistence — `organizations.stripe_payout_schedule_manual`

New Flyway migration **`V44__org_payout_schedule_manual.sql`** (next after V43):

```sql
ALTER TABLE organizations
  ADD COLUMN stripe_payout_schedule_manual BOOLEAN NOT NULL DEFAULT FALSE;
```
Map on `Organization` as `@Column(name = "stripe_payout_schedule_manual", nullable = false) private boolean stripePayoutScheduleManual = false;` (mirror the existing `stripePayoutsEnabled` field shape, L77-78). This column gates both the onboarding hook and the backfill, and is the **auditable guarantee** the Phase 2 payout job trusts before moving money.

### 3.5 Backfill of existing accounts (one-shot, idempotent)

Every account onboarded before this ships is still on auto. Add a `@Scheduled` + `@SchedulerLock` sweeper modeled on `StripeConnectStatusSweeper` (same batch-over-repository-query shape):

New `com.imin.iminapi.stripe.PayoutScheduleBackfillSweeper`:
- Query (new `OrganizationRepository` method): orgs where `stripeAccountId IS NOT NULL AND stripePayoutsEnabled = true AND stripePayoutScheduleManual = false`, paged `BATCH_SIZE = 100`.
- For each: `payoutScheduleService.ensureManual(org)` (isolated `try/catch` per row like the connect sweeper L75-87 so one bad acct can't kill the batch).
- `@Scheduled(fixedDelay = 600_000, initialDelay = 120_000)`; `@SchedulerLock(name = "PayoutScheduleBackfillSweeper.sweep", lockAtLeastFor="PT30S", lockAtMostFor="PT9M")`.
- **Self-terminating:** when the candidate query returns empty (steady state — all eligible accounts flipped, new ones handled by §3.3), the tick is one indexed query and returns immediately. No separate teardown needed; it stays as a cheap safety net for accounts that go active while the backfill is mid-flight.
- **It is the only backstop for a missed capability webhook on a newly-ACTIVE account.** `StripeConnectStatusSweeper` **excludes already-ACTIVE accounts** from re-sync (it only re-reconciles non-terminal orgs), so if the `v2.core.account…capability_status_updated` webhook is missed for an account that has already reached ACTIVE, `syncFromStripe` (and therefore the §3.3 hook) never re-fires for it. This **permanent** backfill sweeper — not the status sweeper — is then the **only** thing that sets that account to manual. This is why the backfill sweeper must stay running indefinitely, not be torn down after the one-shot migration.

---

## 4. Phase 2 — Scheduled post-event Payout job (the money move)

### 4.0 Money-safety invariant — at most ONE in-flight payout per org per tick (HARD RULE)

A connected balance is a **single shared pool** across all of an org's events. The batch processes up to N events with `REQUIRES_NEW` per event, so two events for the **same org** in one tick would each read the **same** Stripe available balance independently and each create a payout — together summing to **more than available** → **double-pay**. This is the single most important correctness rule in Phase 2:

> **Before reading the balance or creating a payout, the job MUST skip the org entirely for this tick if any `payout_runs` row for that `stripe_account_id` is already `PLANNED` or `SUBMITTED`.** This is a **DB check, not a Stripe check**. At most one in-flight payout per org per tick.

This is enforced **both** in the candidate query (§4.2 step 2, coarse) **and** re-checked inside the per-event `REQUIRES_NEW` transaction (§4.2 step 3.0, authoritative). It is NOT a soft "clamp to remainder" — the second event of an org does not get a smaller payout this tick, it gets **no payout this tick** and rolls forward. Combined with the per-event idempotent insert (§4.1) this is what makes concurrent ticks / multiple replicas converge on at most one payout per org per tick.

### 4.1 New `payout_runs` record (mandatory — written BEFORE the Stripe call)

**Why not reuse `settlements`:** the settlements table is a **post-hoc Stripe status mirror** — a row only appears AFTER Stripe emits `payout.created` (`SettlementIngestService.ingestPayout`), so it gives **no pre-call guard** against double-paying, carries no idempotency key, no event scope at trigger time, and no in-flight state. A trigger ledger is required.

New Flyway **`V45__payout_runs.sql`**:

```sql
CREATE TABLE payout_runs (
  id                  UUID PRIMARY KEY,
  org_id              UUID NOT NULL REFERENCES organizations(id),
  event_id            UUID NOT NULL REFERENCES events(id),
  amount_minor        BIGINT NOT NULL,
  currency            VARCHAR(8) NOT NULL,
  -- PLANNED -> SUBMITTED -> PAID | FAILED (VARCHAR per V28/V43 convention, no native enum).
  status              VARCHAR(24) NOT NULL,
  -- Deterministic; reused on Payout.create so a re-run never double-pays.
  idempotency_key     VARCHAR(128) NOT NULL UNIQUE,
  stripe_payout_id    VARCHAR(64) UNIQUE,            -- po_..., set on SUBMITTED
  stripe_account_id   VARCHAR(64) NOT NULL,
  scheduled_at        TIMESTAMP WITH TIME ZONE NOT NULL,
  submitted_at        TIMESTAMP WITH TIME ZONE,
  paid_at             TIMESTAMP WITH TIME ZONE,
  failure_reason      TEXT,
  attempt             INT NOT NULL DEFAULT 1,
  created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  -- One in-flight/successful run per event; a retry after FAILED uses a new attempt
  -- (idempotency_key carries the attempt, so the UNIQUE(idempotency_key) is the real guard).
  CONSTRAINT payout_runs_event_active_uniq UNIQUE (event_id, attempt)
);
CREATE INDEX payout_runs_status_idx ON payout_runs (status);
```
- `idempotency_key = "evt:" + eventId + ":attempt:" + attempt` (deterministic). Passed to `Payout.create` via `RequestOptions.setIdempotencyKey(...)` so a crash between SUBMITTED-write and Stripe-ack, or a replica overlap, replays to the **same** Stripe payout.
- JPA entity `com.imin.iminapi.payout.PayoutRun` + `PayoutRunRepository` (package alongside `settlement/`). Status enum `PayoutRunStatus { PLANNED, SUBMITTED, PAID, FAILED }`.

### 4.2 The job — `PostEventPayoutSweeper`

`com.imin.iminapi.payout.PostEventPayoutSweeper`, `@Scheduled` + `@SchedulerLock` (mirrors `StripeConnectStatusSweeper` / `ReservationSweeper`):

```
// ONCE-PER-DAY POLL. The job does NOT arm a per-event timer for "exactly +3 days".
// It runs once a day, asks "which events are now past endsAt+buffer and still unpaid?",
// and pays those. payout_at has day granularity, so daily is the right cadence:
// idempotency (§4.1) makes a re-run harmless, and a missed day self-heals — candidates
// are recomputed from scratch each tick, so a still-due event is simply caught the next day.
@Scheduled(cron = "0 0 3 * * *", zone = "Europe/Amsterdam")   // 03:00 daily
@SchedulerLock(name="PostEventPayoutSweeper.sweep", lockAtLeastFor="PT1M", lockAtMostFor="PT30M")
```

Per tick:
1. **Skip entirely** if `!props.isPayoutScheduleManual()`.
2. **Candidate query** (new `EventRepository` method): events where
   - `endsAt IS NOT NULL AND endsAt < (now - bufferDays resolved in payoutZone)`,
   - the org is **payout-eligible**: `stripePayoutsEnabled = true AND stripePayoutScheduleManual = true` (join org by `event.orgId`),
   - **no successful/in-flight `payout_runs` row** for the **event** (`NOT EXISTS payout_runs WHERE event_id = e.id AND status IN (PLANNED,SUBMITTED,PAID)`),
   - **HARD RULE — at most one in-flight payout per org per tick (double-pay guard, §4.0):** **no in-flight `payout_runs` row for the same `stripe_account_id`** (`NOT EXISTS payout_runs WHERE stripe_account_id = <org.acct> AND status IN (PLANNED,SUBMITTED)`). This is the **org-level** guard and is what prevents two events of the same org both reading the same available balance in one tick.
   - paged `BATCH_SIZE = 50`.

   The candidate query is a coarse filter; the authoritative double-pay guard is re-checked **inside** the per-event transaction at step 3.0 below (a candidate-query snapshot can go stale between the scan and the per-event commit).
3. For each candidate event, in its **own `@Transactional(propagation = REQUIRES_NEW)` unit** (one event failing must not roll back the batch — use a per-event service method so the boundary is real):
   0. **Double-pay guard (DB, not Stripe) — re-check inside the transaction, FIRST, before reading any balance or creating any payout:** SKIP this org for this tick if **any** `payout_runs` row for this `stripe_account_id` is already `PLANNED` or `SUBMITTED`. At most **one in-flight payout per org per tick** — full stop. This is a **DB check**, not a Stripe balance check. Two events for the **same org** in one tick would otherwise each read the same Stripe available balance and each create a payout summing to **more than available** → double-pay. The first event to process for an org claims the in-flight slot (its `PLANNED` row, step 5); every other event for that same `stripe_account_id` is skipped this tick and naturally rolls to a later tick once the first run reconciles out of PLANNED/SUBMITTED.
   1. **Dispute / hold guard:** skip (leave for next tick, log) if any open dispute affects the org. **Query precisely:** `settlements WHERE org_id = X AND object_type = 'transfer' AND status = 'failed'` — dispute annotations from `ingestDispute` land on the **transfer** row (the backing destination-charge transfer), not on payout rows. **Do NOT treat a `FAILED` *payout* row as a dispute:** a failed `payout` settlement means a **bank-routing failure** (e.g. closed external account), which must NOT block future payouts. (Optionally a live `Dispute.list` scoped to the account if more precision is needed.) Also skip if `org.stripeConnectState != ACTIVE` (just-disabled account).
   2. **Compute per-event net** (the ceiling), reusing `EventOverviewService`'s derivation (do NOT duplicate the SQL — extract the net calc into a shared method or call repos directly):
      `perEventNetMinor = max(0, orders.sumTotalMinorByEventId(eventId) - refunds.sumSucceededRefundMinorByEventId(eventId) - max(0, orders.sumApplicationFeeMinorByEventId(eventId) - refunds.sumSucceededRefundApplicationFeeMinorByEventId(eventId)))`.
      **Caveat (carry forward from `EventOverviewService` L86-88):** this figure is **gross of Stripe processing fees** and unaware of availability lag — it is NOT what Stripe will let you pay out.
   3. **Read live AVAILABLE balance** on the connected account: `stripeClient.balance().retrieve(BalanceRetrieveParams.builder().build(), RequestOptions.setStripeAccount(acct))`; take the `getAvailable()` entry matching `event.currency` (lowercased). This is **available, not pending** — late/SEPA sales still `pending` are excluded by construction.
   4. **Clamp:** `payoutMinor = min(perEventNetMinor, availableMinor)`. If `payoutMinor <= 0` → skip this tick (funds not yet available; rolls naturally to the next trigger — doc §53 "Availability lag", §55).
   5. **Write `payout_runs` row FIRST — insert-or-find on the deterministic idempotency key, BEFORE the Stripe call.** Concretely: compute the deterministic `idempotency_key` (`"evt:" + eventId + ":attempt:" + attempt`), then either (a) **find by `idempotency_key` first** and reuse the existing row, or (b) **`INSERT ... ON CONFLICT (idempotency_key) DO NOTHING` then re-`SELECT`**, with `status=PLANNED`, `amount_minor=payoutMinor`, `stripe_account_id`. This is what makes **concurrent replicas converge on one row**: the `UNIQUE(idempotency_key)` constraint guarantees a single winner. If a plain `INSERT` is used instead and it hits a **UNIQUE violation**, the `REQUIRES_NEW` transaction **rolls back** and the event simply **retries on the next tick** (document this — it is expected, not an error). Commit nothing irreversible to Stripe yet — the row + its `UNIQUE(idempotency_key)` is the pre-call guard.
   6. **Create the Payout** (idempotently — crash-window safe). A crash **between** `Payout.create` returning and the DB commit of `SUBMITTED` would, on the next tick, re-enter this step with the same `PLANNED` run. **Before** creating a new payout for a `PLANNED` run, first **check whether a Stripe payout already exists for this idempotency key** — either `stripeClient.payouts().retrieve(...)`/`list(...)` scoped to the account filtering on the key, or simply rely on Stripe's idempotency replay (re-issuing `Payout.create` with the **same** `RequestOptions.setIdempotencyKey(run.idempotencyKey())` returns the **same** `po_` rather than a second payout). If a payout already exists, move the run **straight to `SUBMITTED`** (record its `stripe_payout_id`) instead of creating a second one. Then: `stripeClient.payouts().create(PayoutCreateParams.builder().setAmount(payoutMinor).setCurrency(cur).setDescription("imin event payout " + eventId).build(), RequestOptions.builder().setStripeAccount(acct).setIdempotencyKey(run.idempotencyKey()).build())`. On success: set `stripe_payout_id`, `status=SUBMITTED`, `submitted_at=now`.
   7. **Failure handling:** on `balance_insufficient` (a race where availability dropped) → leave `PLANNED`/mark `FAILED` and roll to next tick (clamp re-reads next time). On other `StripeException` → `status=FAILED`, `failure_reason=e.getCode()`, alert/flag (log ERROR; surface in an ops view). **Stripe does not auto-retry payouts** — the next tick re-evaluates with a **new attempt** number (so a fresh idempotency key) only if the prior run is FAILED.
4. The `payout.created/paid/failed` webhooks (Phase 6) flip the run to PAID/FAILED and close the loop.

### 4.3 Multi-event commingle note

A connected balance is a single pool across all the org's events — per-event payouts draw from the **same** available balance. **Within a single tick**, the §4.0 hard rule (at most one in-flight payout per org per tick) is what prevents two events of the same org from both reading the shared balance and over-paying — the second event gets **no** payout this tick and rolls forward; it does **not** clamp to a remainder. **Across ticks**, each event's payout is still clamped `min(perEventNet, accountAvailable)` and "skip if `<=0`, roll to next tick", so a payout is **not** strictly "this event's money" — it's "up to this event's net, capped by what's available now, and only when no other payout for the org is in flight." Document this in the ops view.

### 4.4 Fee-retention invariant — imin's cut never pays out (HARD RULE)

imin's `application_fee_amount` is retained on **imin's platform balance** at charge time — that is the destination-charge mechanic (`StripeCheckoutService` sets `application_fee_amount` + `transfer_data.destination`, so only the organizer's **net** lands on the **organizer's connected** balance; the fee never enters it). The Phase-2 payout is created **on the connected account** (`Stripe-Account: acct_org`), so it draws **only** from the organizer's net and **structurally cannot include imin's fee** — there is no code path by which a connected-account payout reaches imin's platform balance.

Two independent safeguards make this auditable:
1. **Source of funds:** the payout reads `Balance.getAvailable()` **on the connected account**, which already excludes imin's fee (the fee sits on the platform balance — a *different* Stripe account). Even if the amount arithmetic were wrong, the available-balance clamp (§4.2 step 4) caps the payout at the organizer's net.
2. **Amount math:** `perEventNetMinor` (§4.2 step 2) explicitly subtracts the net application fee (`app_fee − app_fee_refunds`), so the computed ceiling equals the organizer's net and agrees with (1).

**Reconcile (ops + test):** after each payout, imin's platform-balance fee for the event is unchanged, and `payout.amount ≤ connectedAvailableAtPayout`. Any payout whose amount exceeds `gross − fee − refunds` for the event is a bug → alert. (Stripe's own processing fee is deducted from imin's platform balance, i.e. out of imin's fee — never from the organizer's net; unchanged from today, `EventOverviewService` L86-88.)

---

## 5. Phase 3 — Checkout + refund deltas

### 5.1 Checkout: add `transfer_group` (one line)

In `StripeCheckoutService.setPaymentIntentData(...)` (currently L304-310: `setApplicationFeeAmount` + `transfer_data.destination` + `putAllMetadata`), add:
```java
.setTransferGroup(eventId.toString())
```
Nothing else changes. **Do NOT add `on_behalf_of`** (needs `card_payments` the recipient accounts lack — see §1 correction). `event_id` is already in the PI metadata, so the read-model can still attribute by destination + metadata; `transfer_group` is a belt-and-suspenders grouping for reconciliation and future per-event transfer queries.

### 5.2 Refund BEFORE payout — already correct, no change

`StripeRefundService.create` already issues `RefundCreateParams.setReverseTransfer(true).setRefundApplicationFee(false)` + a separate proportional `ApplicationFeeRefund` (L55-91). Under Option B, a refund issued before the event's `payout_runs` row exists naturally claws the organizer's share back from their balance and nets out — the later payout releases the corrected net. **No code change.** One guard to add: the refund path should be allowed only while no `payout_runs` row for the event is `SUBMITTED`/`PAID` **or** must use the after-payout path below; decide policy in §5.3.

### 5.3 Refund AFTER payout — new path (spec)

Today refund-after-payout hard-fails: `RefundService` catches Stripe `balance_insufficient` and throws **409 `ORDER_NOT_REFUNDABLE`** with `stripeCode=balance_insufficient` (`RefundService.java:175-185`). That happens because `reverse_transfer` can't pull funds that already left the organizer's balance via payout. Two viable policies — **pick one explicitly with the money owner**:

- **Policy A (recommended for MVP) — block + queue.** Keep the 409, but make it actionable: if a `payout_runs` row for the event is `SUBMITTED`/`PAID`, return a distinct error (`ORDER_REFUND_AFTER_PAYOUT`) telling ops to reclaim out-of-band. Simplest; no negative balances.
- **Policy B — force clawback.** Switch the refund to refund-the-charge + **reverse the transfer with `debit_negative_balances=true`** so the clawback debits the organizer's (possibly already-paid-out) balance, driving it negative; Stripe recovers from the next inbound funds or the external account. This is the doc's §49-50 path. Implement by adding the transfer-reversal call with `debit_negative_balances` and removing the hard 409 for this case. Higher risk (negative balances, organizer relations); requires the reserve in §5.4.

### 5.4 Disputes — platform-balance debit + reserve

With destination charges and losses-collector = APPLICATION, a chargeback **auto-debits imin's platform balance** for the disputed amount + fee (`SettlementIngestService.ingestDispute` already annotates the read-model FAILED while funds are at risk). To recover, reverse the backing transfer from the organizer's balance (same `debit_negative_balances` consideration as 5.3 Policy B). **Keep a platform reserve** sized to expected dispute exposure so the auto-debit never bounces. The Phase 2 job already **skips events with an open dispute on the org** (§4.2.3.1) so funds aren't paid out from under an active dispute.

---

## 6. Reconciliation — tie each triggered payout to a real `po_`/arrival_date

The Track A settlements read-model + `payout.*` webhooks already mirror real Stripe payouts; Phase 2's `payout_runs` is the **trigger ledger**. Close the loop by linking them:

1. **Confirm the webhook subscription is live.** Per `imin-api/CLAUDE.md`, the V1 endpoint must have `payout.created/paid/failed` subscribed **and "Listen to events on Connected accounts" enabled** — `PayoutService`'s own javadoc (L49-55) warns these may NOT be subscribed in prod yet. **This is a prerequisite for Phase 2 going live**; without it `payout_runs` rows never advance past SUBMITTED.
2. **Extend `SettlementIngestService.ingestPayout`** (or a small adjacent step in `StripeWebhookService`'s V1 dispatch) to, after upserting the settlement row, **find the `payout_runs` row by `stripe_payout_id == payout.getId()`** and flip it: `payout.paid` → `PAID` + `paid_at` (use Stripe `arrival_date`); `payout.failed` → `FAILED` + `failure_reason`. Inherit the same `@Transactional(propagation = MANDATORY)` + dedup gate the ingest already runs in.
3. **Restore per-event attribution.** Payout settlement rows have `event_ids = null` today (`SettlementIngestService.java:121-123` — payouts carry no metadata). The `payout_runs.event_id` link is what restores it: when matching the `po_` to a run, copy the run's `event_id` into the settlement row's `event_ids` so `/payouts` history labels the event. **Wiring note:** `ingestPayout(payout, connectedAccount)` has **no parameter for event ids**, and the current upsert only writes `event_ids` when **non-null**, so passing nothing leaves it null. Add an **optional `String eventIds` param** to `ingestPayout` (or do it via a **separate update call** on the settlement row after the run match) so the run's `event_id` actually lands on the settlement row — do not assume the existing signature backfills it.
4. **Ops view (optional, MVP-deferrable):** a `GET /api/v1/payouts/runs` (org-scoped) listing `payout_runs` with their reconciled `po_`/status — lets ops see PLANNED/SUBMITTED stuck rows and FAILED runs needing a retry.

**Money-path invariant:** every `Payout.create` uses a deterministic idempotency key from its `payout_runs` row, and every run is reconciled back to a real `po_` via webhook before it is considered settled. A run with no matching `po_` after N ticks is an alert.

---

## 7. Edge cases, risks, minimum-viable cut

### Edge cases
- **Availability lag** (doc §55): charge funds go `pending` → `available` over ~2 business days. Sales the night of the event aren't `available` at `endsAt + buffer`; the `min(perEventNet, available)` clamp + roll-to-next-tick handles this — the remainder pays out on a later tick. Buffer default 3 days covers most EU card lag; tune `STRIPE_PAYOUT_BUFFER_DAYS` if a country lags longer.
- **SEPA / async methods** (doc §56): count funds only once `available` (never `pending`). Because the clamp reads available balance, a still-pending SEPA charge is excluded automatically; a failed async charge after a payout would create a negative — caught by the same `debit_negative_balances`/reserve policy as refunds.
- **Retention deadline ⇒ stranded-funds SLA (Phase-2 SHIP REQUIREMENT, not deferred — see §7 minimum-viable cut item 5):** once an account is manual, Stripe requires funds be disbursed within a retention window (~90 days most countries; US 2y; Thailand 10d). **Critically, the Phase-1 backfill flips balances to manual on deploy, which STARTS this clock immediately** for every existing eligible org — so the monitor must be **live before the backfill runs in prod**, not added later. If an event's funds never become payable (org disabled, dispute frozen), they can strand. **Concrete bound:** add a `payout_runs`/org monitor that alerts when an org has `available > 0` and `now - oldest_charge > 75 days` (a ~15-day margin under the common 90-day window) so ops force a payout before the deadline. Do NOT let the buffer + roll-forward silently exceed the window.
- **Currency:** the account default currency is country-derived (`buildCreateParams` has no explicit currency); events carry their own `currency`. Always match the `Balance.getAvailable()` entry to `event.currency` and pass that currency to `Payout.create`. A multi-currency org has separate available buckets per currency — clamp per currency.
- **Multi-event commingle:** see §4.3 — shared balance pool, clamp + roll handles it; payouts are "up to event net, capped by available."
- **Event with no `endsAt`:** `endsAt IS NULL` events never become candidates (query requires it). Decide product rule for open-ended events (likely: require `endsAt` to enable payouts, or fall back to `startsAt`).

### Risks
- **Spike is gating** — if §2 fails, the whole approach changes to a Fallback (§0.4); do not pre-build Phases 1-2 against an unproven crux.
- **Webhook subscription not live in prod** (§6.1) — Phase 2 silently strands SUBMITTED runs without it. Verify first.
- **Negative balances** from Policy B refunds/disputes — organizer-relations + reserve risk; needs money-owner sign-off.
- **Manual schedule is hard to reverse per-account at scale** — if Option B is abandoned, a reverse-backfill (set interval back to `daily`) is needed; the `stripe_payout_schedule_manual` column makes that auditable.
- **Legal/merchant-of-record** unchanged but still the residual sign-off (doc §60-63).

### Minimum-viable cut (ship order)
1. **STEP 0 spike** (gating).
2. **Phase 1**: `V44` column + `StripePayoutScheduleService` + ACTIVE-transition hook + backfill sweeper. (Accounts go manual; funds now held. No payouts yet — verify in dashboard that balances accumulate.)
3. **Webhook subscription** verified live (§6.1).
4. **Phase 2**: `V45 payout_runs` + `PostEventPayoutSweeper` with the per-org one-in-flight rule (§4.0) + idempotent insert (§4.1) + clamp. (Money moves.)
5. **Retention-deadline monitor (SHIP REQUIREMENT, not deferred):** the Phase-1 backfill flips balances to manual **on deploy**, which **starts Stripe's retention clock immediately** (~90d most countries, Thailand 10d, US 2y). An alert for orgs holding funds past the bound (§7: `available > 0` and `now - oldest_charge > 75 days`) **MUST be live before the Phase-1 backfill runs in prod**, so funds can never strand past Stripe's window. Ship it with Phase 2.
6. **Phase 3.1** (`transfer_group`) — trivial, ship with Phase 2.
7. **Phase 6** reconciliation link in `ingestPayout`.
8. **Defer:** refund-after-payout Policy B (§5.3 — start with Policy A block), `/payouts/runs` ops view.

---

## 8. Test plan (test-mode, concrete — mirrors doc §66-68)

**Unit (H2, Stripe mocked):**
- `StripePayoutScheduleService.ensureManual`: no-op when `stripePayoutScheduleManual=true`; no-op when `payoutScheduleManual=false`; calls `balanceSettings().update` once and sets the column when eligible; swallows `StripeException` leaving the column false.
- `StripeConnectStatusMirror.syncFromStripe`: calls `ensureManual` exactly once on the active transition; not called when payouts not enabled; mirror unit tests still construct with the nullable dependency absent.
- `PostEventPayoutSweeper` (pure parts extracted): candidate filter respects buffer/timezone, eligibility, and the `NOT EXISTS payout_runs` guard; `payoutMinor = min(perEventNet, available)`; `<=0` ⇒ skip; idempotency key is deterministic per (event, attempt).
- `PayoutScheduleBackfillSweeper`: selects only `payoutsEnabled && !scheduleManual` orgs; per-row failure isolated.
- Net computation matches `EventOverviewService` derivation on shared fixtures (gross − refunds − net app fee).

**Integration / test-mode (sk_test, Stripe CLI) — the doc's happy path made concrete:**
1. Onboard a test recipient account → reach `stripe_transfers.active` → assert `stripe_payout_schedule_manual` flips true and dashboard shows **manual** schedule.
2. Run a destination charge (`4242 4242 4242 4242`) for an event → assert `transfer_group=eventId` on the PI, organizer balance rises by net, imin keeps the fee.
3. Wait for funds `available` (or use `stripe` test-clock/balance helpers) → run `PostEventPayoutSweeper` with the event's `endsAt` in the past → assert a `payout_runs` row PLANNED→SUBMITTED with a `po_` id, amount = `min(net, available)`, **and assert imin's platform-balance fee for the event is untouched by the payout** (fee-retention invariant §4.4 — the `po_` amount excludes `application_fee_amount`).
4. Replay the tick / simulate a replica → assert **no second payout** (same idempotency key → same `po_`; `NOT EXISTS` guard holds).
5. Drive the payout to `paid` (`stripe payouts` test helper) → assert `payout.paid` webhook flips the run to `PAID` with `paid_at` and copies `event_id` into the settlement row's `event_ids`.
6. **Refund before payout:** issue a refund (`reverse_transfer`) before step 3 → assert organizer balance drops, fee refunded proportionally, and the later payout amount reflects the corrected net.
7. **Refund after payout:** issue a refund after step 5 → assert Policy A returns `ORDER_REFUND_AFTER_PAYOUT` 409 (or, if Policy B, the transfer reversal with `debit_negative_balances` drives a negative and recovers).
8. **Decline / 3DS / SEPA cards** per doc §68: `4000000000009995` decline (no funds, no payout), `4000002500003155` 3DS, SEPA `AT321904300235473204` (stays `pending`, excluded from the available clamp until it clears).
9. **Over-amount guard:** force a payout request > available → assert Stripe `balance_insufficient`, run marked FAILED, next tick re-clamps.
