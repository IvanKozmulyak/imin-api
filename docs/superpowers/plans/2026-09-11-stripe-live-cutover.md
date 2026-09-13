# Stripe test→live cutover for prod

stripe-live-cutover · plan · Notion: card not supplied (see OPEN_QUESTIONS)

## Goal and scope

Production has run on `sk_test_` since day one. Every Stripe id in the prod DB is a
test-mode object. Flipping `STRIPE_SECRET_KEY` to a live key without clearing them leaves
dangling references that fail in three distinct ways — all confirmed by reading the code in
this worktree (`origin/master` @ `14f2ed7`):

1. **Checkout 502.** `StripeCheckoutService.resolveTicketProductId` returns the stored
   `tier.stripe_product_id` with no validation (`StripeCheckoutService.java:813-814`), and it
   is put straight into the line item's `price_data.product`
   (`StripeCheckoutService.java:375-377`). Under a live key that test `prod_` id does not
   exist, `checkout().sessions().create` (`:484`) throws, and the handler maps it to
   `502 UPSTREAM_UNAVAILABLE` (`:503-504`). Note `StripeProductService.priceHasChanged`
   (`:113-126`) *does* self-heal a missing price by re-creating, but nothing re-validates the
   product id on the checkout path.
2. **The Connect mirror freezes ACTIVE and lies to the money gate.**
   `StripeConnectStatusMirror.syncFromStripe` catches the retrieve failure and `return`s,
   leaving the columns untouched (`StripeConnectStatusMirror.java:66-69`). A live-key 404 on a
   test `acct_` is therefore indistinguishable from a Stripe outage: the org stays whatever it
   was mirrored as. `StripeConnectService.getStatusLive` (`:365-374`) degrades to that stale
   mirror, so `reserveAndBuildMetadata` (`StripeCheckoutService.java:640-643`) passes an org
   that cannot receive a cent. `StripeConnectStatusSweeper` then retries the same 404 every
   5 minutes forever for any ONBOARDING/PENDING_VERIFICATION/RESTRICTED org
   (`StripeConnectStatusSweeper.java:41-44, 68`).
3. **Re-onboarding is impossible.** `getOrCreateAccount` early-returns the stored id
   (`StripeConnectService.java:98-101`) — an org holding a test `acct_` can never mint a live
   one until the column is cleared.

Scope: a one-off, idempotent SQL reset of the org + tier Stripe columns and of the in-flight
rows that would otherwise be swept against Stripe with a stale id; a JUnit test that proves
the script on H2; an operator runbook. Optional (gate decision, §Risks): a `stripe_livemode`
guard so a mode mismatch can never silently recur.

**Explicitly kept** (history, and none of them is polled against Stripe with a stored id):
`orders` (`stripe_payment_intent_id`, `stripe_session_id`), `tickets`, `refunds` rows,
`settlements` (`stripe_object_id`), `disputes` rows, `processed_webhook_events`
(`stripe_event_id` is the dedup key; a live event id cannot collide with a test one), all
marketing/audience data. Deleting orders would orphan `disputes.order_id` (FK, `V128:20`) and
invalidate issued ticket QRs at the door.

**Not affected, verified:** `PaidFulfilmentReconciler` *lists* PaymentIntents from Stripe
rather than reading stored ids (`PaidFulfilmentReconciler.java:76-85`), so it is correct on
day one under a live key. `PayoutScheduleBackfillSweeper`, `PostEventPayoutSweeper`,
`PayoutRetentionMonitor` and `StripePayoutScheduleService.ensureManual` all return at their
first line when `imin.stripe.payout-schedule-manual` is false (default false,
`StripeProperties.java:72`, `application.yaml:185`) — the payout_runs work below is therefore
*precautionary*, and the runbook keeps that flag false through the cutover.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| `api` | `master` | `/Users/ivan/imin/imin-api/.claude/worktrees/stripe-live-cutover` | `./mvnw test` |

No other repo. Nothing in this task changes an endpoint, a DTO or a rendered string.

## Affected files (per repo)

### imin-api (7 files if the guard is included, 5 without)

New:
1. `scripts/stripe-live-cutover-preflight.sql` — read-only report. psql-flavoured (`\echo`,
   `\pset`), matching `scripts/v82-normalization-impact-report.sql`. Never executed by a test.
2. `scripts/stripe-live-cutover.sql` — the reset. **Pure SQL, no psql meta-commands, no
   `BEGIN`/`COMMIT`** (see Ordered steps §2 for why), plain `UPDATE`s only.
3. `scripts/stripe-live-cutover-postcheck.sql` — one `UNION ALL` query returning
   `(check_name, n)`; every row must be `n = 0`. Separate file on purpose: run *after* the
   commit, because `psql --single-transaction` commits at EOF regardless of what a SELECT
   inside it printed.
4. `src/test/java/com/imin/iminapi/stripe/StripeLiveCutoverScriptTest.java`
5. `docs/STRIPE_LIVE_CUTOVER.md` — the runbook.

Plan (this file): `docs/superpowers/plans/2026-09-11-stripe-live-cutover.md`.

Optional guard (second commit, only if the gate is approved):
6. `src/main/resources/db/migration/V129__organizations_stripe_livemode.sql`
7. `src/main/java/com/imin/iminapi/model/Organization.java` (one nullable `Boolean` field),
   `src/main/java/com/imin/iminapi/stripe/StripeProperties.java` (`isLiveKey()`),
   `src/main/java/com/imin/iminapi/stripe/StripeConnectService.java` (stamp on create + refuse
   on mismatch in `getStatusLive`), `src/test/java/com/imin/iminapi/stripe/StripeLivemodeGuardTest.java`,
   `CLAUDE.md` (one bullet in the Stripe Connect section). Six files total; see Risks.

### Amendment (approved at gate) — close the test-era payout hole

Risk 2 is no longer deferred. Test-era orders are kept, so under a live key a test-era event
would re-enter `findPayoutCandidates` and its fake gross would be disbursed from the
organizer's real balance. Added files:

8. `src/main/resources/db/migration/V130__test_mode_flags.sql` — `test_mode BOOLEAN NOT NULL
   DEFAULT FALSE` on `orders`, `payout_runs` and `disputes` (V129 is the livemode guard;
   V128 was the highest on master).
9. `src/main/java/com/imin/iminapi/model/Order.java` (one boolean),
   `repository/OrderRepository.java` + `refund/RefundRepository.java` (live-only sums),
   `payout/PostEventPayoutService.java` (net reads the live sums),
   `repository/EventRepository.java` (both candidate queries),
   `service/ticket/PaidCheckoutService.java` + `service/event/FreeCheckoutService.java`
   (stamp the flag from the running key's mode),
   `src/test/java/com/imin/iminapi/payout/PayoutTestModeExclusionTest.java`.

Also amended: `docs/STRIPE_PAYOUTS_SETUP.md` §7 and
`docs/superpowers/plans/2026-06-16-payouts-track-b-manual-payouts.md` record the capability
spike result.

### Amendment (review rounds 2-3) — mode-mismatch recovery and its tests

Round 1 (HIGH-2) added `StripeConfig.java` (the `STRIPE MODE: live|test` boot banner,
WARN-level as of round 2) and `StripeKeyModeTest.java`. Round 2 (MEDIUM-1/3) added
`repository/OrganizationRepository.java` (`lockAndReadStripeLivemode`, a second scalar
`FOR UPDATE` read used to decide a mode mismatch under lock rather than from the stale
pre-lock entity) and `OrganizationRepositoryLockTest.java`. Round 2 (LOW-1) added the two
missing stamp assertions to the existing `PaidCheckoutServiceTest.java` and
`PostEventPayoutServiceTest.java`, and a `DisputeIngestServiceTest.java` case for
first-sighting stamping. All are covered by the Review rounds log below; no new behaviour
beyond what that log and the GATE 2 summary already named.

## Ordered steps

### 1. Pre-flight report — `scripts/stripe-live-cutover-preflight.sql`

Read-only, safe against prod with a read-only role. Sections, each a labelled count plus the
row list where the list is short enough to eyeball:

- §1 `organizations` with `stripe_account_id IS NOT NULL`, broken down by
  `stripe_connect_state` and `stripe_payouts_enabled`.
- §2 `ticket_tiers` with `stripe_product_id IS NOT NULL OR stripe_price_id IS NOT NULL`, and
  of those, how many belong to an event that is still sellable (`status` published, not
  deleted, `ends_at > now()`) — these are the tiers that must be re-synced first after the
  swap.
- §3 `ticket_reservations` `status = 'HELD'`, split by `async_processing_at IS NULL` (30-min
  card holds, self-draining) vs `IS NOT NULL` (V125 async holds, up to 7 days).
- §4 the per-tier `SUM(qty)` of those HELD rows next to `ticket_tiers.reserved` — the
  decrement §2.3 will apply, shown before it is applied.
- §5 `payout_runs` in `('planned','submitted','retrying')`, with `stripe_payout_id`.
- §6 `refunds` `platform_funded = TRUE AND recovered_at IS NULL` (the org-level debt set read
  by `RefundRepository.findUnrecoveredPlatformFundedByOrgId`).
- §7 `refunds` `status = 'PENDING'` and `'REQUESTED'` — **reported, not changed** (§2.5).
- §8 `disputes` `status = 'open'`, with the count of `revoked` tickets on each linked order.
- §9 KEEP counts, printed so the operator sees the size of what is deliberately untouched:
  `orders`, `tickets`, `settlements`, `processed_webhook_events`.

### 2. The reset — `scripts/stripe-live-cutover.sql`

Run as `psql "$DATABASE_URL" -v ON_ERROR_STOP=1 --single-transaction -f scripts/stripe-live-cutover.sql`.
The transaction comes from the runner, not from a `BEGIN;` in the file: `cleanup-offlist-genres.sql`
already establishes `--single-transaction` as the house invocation, and it keeps the file pure
SQL so the JUnit test can execute the *artefact itself* through `ScriptUtils` instead of a
stripped copy of it. (`BEGIN` is a token in H2 2.4.240's parser but its statement-level
behaviour there is not something this plan is willing to assume on the money path.) The header
comment states the required invocation.

Statement order is load-bearing. 2.3 must run **before** 2.4.

**2.1 `ticket_tiers`** — `stripe_product_id = NULL, stripe_price_id = NULL`
WHERE either is non-null. Idempotent by the WHERE clause.

**2.2 `organizations`** — all nine columns, WHERE `stripe_account_id IS NOT NULL OR
stripe_connect_state <> 'NOT_STARTED' OR stripe_payouts_enabled OR stripe_details_submitted OR
stripe_payout_schedule_manual OR stripe_disabled_reason IS NOT NULL OR
stripe_connect_status_updated_at IS NOT NULL OR stripe_requirements_currently_due <> '[]' OR
stripe_requirements_past_due <> '[]'`:

| column | value | why |
|---|---|---|
| `stripe_account_id` | `NULL` | unblocks `getOrCreateAccount` (`StripeConnectService.java:98-101`); NULLs are distinct under `uq_organizations_stripe_account` (V126) |
| `stripe_connect_state` | `'NOT_STARTED'` | NOT NULL, default `'NOT_STARTED'` (V33) |
| `stripe_payouts_enabled` | `FALSE` | drops the org out of `findPayoutCandidates` and `findPayoutScheduleBackfillCandidates` |
| `stripe_details_submitted` | `FALSE` | sticky flag in `applyTo` (`StripeConnectStatusMirror.java:121-127`); left true it would derive RESTRICTED/ACTIVE instead of ONBOARDING on the first live sync |
| `stripe_payout_schedule_manual` | `FALSE` | the live account has never had its schedule flipped |
| `stripe_requirements_currently_due` | `'[]'` | TEXT NOT NULL DEFAULT `'[]'` (V33); `StringListJsonConverter` parses it |
| `stripe_requirements_past_due` | `'[]'` | same |
| `stripe_disabled_reason` | `NULL` | |
| `stripe_connect_status_updated_at` | `NULL` | NULL means never synced, which makes `shouldRefreshForCheckout` return true on the first read (`StripeConnectService.java:411-413`) |

**2.3 `ticket_tiers.reserved`** — give the seats back *before* the holds are flipped, because
the decrement reads the HELD rows:

```
UPDATE ticket_tiers t
   SET reserved = CASE WHEN t.reserved - h.held < 0 THEN 0 ELSE t.reserved - h.held END
  FROM ...   -- portable form: correlated scalar subquery, NOT an UPDATE ... FROM
```
Write it as a correlated subquery (`SET reserved = CASE WHEN reserved - (SELECT COALESCE(SUM(r.qty),0) FROM ticket_reservations r WHERE r.tier_id = t.id AND r.status = 'HELD') < 0 THEN 0 ELSE reserved - (same subquery) END WHERE EXISTS (...HELD...)`) — `UPDATE ... FROM` is PostgreSQL-only. The
clamp mirrors `InventoryService.releaseReservation:149-155`, which clamps and logs drift
rather than going negative. **This step is the one a hand-written reset would forget**, and
forgetting it leaves every affected tier permanently short of inventory.

**2.4 `ticket_reservations`** — `status = 'RELEASED'`, `released_at = now()`,
`release_reason = 'TEST_MODE_CUTOVER'` WHERE `status = 'HELD'`. Column is `release_reason`
(not `released_reason`), `VARCHAR(32)` (`V27:38`) — the literal is 17 chars. `'RELEASED'` is
permitted by the unnamed CHECK on `status` (`V27:29`). Idempotent: after the first run there
are no HELD rows, so 2.3 and 2.4 are both no-ops. The matching test-mode PaymentIntent is
*not* cancelled — it is unreachable under the live key, and `ReservationSweeper.cancelIfNativeIntent`
already treats a cancel failure as a WARN (`ReservationSweeper.java:107-116`).

**2.5 `payout_runs`** — `status = 'blocked'`, `failure_reason = 'TEST_MODE_CUTOVER'`,
`updated_at = now()` WHERE `status IN ('planned','submitted','retrying')`. Status is stored
lowercase via `PayoutRunStatusConverter` (`PayoutRunStatus.java:48-52`). Those three are
exactly the non-terminal set:

- `submitted` is read by `PayoutRunRepository.findByStatusAndSubmittedAtBefore` and fed to
  `PostEventPayoutService.reconcileSubmittedRun`, which calls
  `payouts().retrieve(po_, Stripe-Account: acct_)` off the **row**, not the org
  (`PostEventPayoutService.java:457-480`) — the one payout path that survives the org reset.
- `planned` and `submitted` are the org-level in-flight guard
  (`PostEventPayoutService.java:117-119, 206`).
- `retrying` is replayed with its original idempotency key
  (`PostEventPayoutService.java:537-545`) — i.e. a real `payouts().create` against a test
  `acct_`.

`blocked` rather than `failed` is deliberate and is the safest available terminal state:
`existsBlockedNeedingAHuman` (`PayoutRunRepository.java:112-120`) permanently excludes the
event from step 0a (`PostEventPayoutService.java:195-199`) for any reason other than
`NO_BANK_ACCOUNT`, and `BLOCKED` is in neither `IN_FLIGHT` nor `ALREADY_TRIGGERED`. `failed`
would let the event re-candidate with a bumped attempt and attempt a **live** payout for
revenue that was test money.

**2.6 `refunds` (platform-funded debt)** — `recovered_at = now()`,
`recovery_reversal_id = 'TEST_MODE_CUTOVER'` WHERE `platform_funded = TRUE AND recovered_at IS
NULL`. `recovery_reversal_id` is `VARCHAR(64)` (`V127:17`). Predicate is a deliberate superset
of `RefundRepository.findUnrecoveredPlatformFundedByOrgId` (which also requires
`status = 'SUCCEEDED'`) so a platform-funded row that is still PENDING cannot join the
recovery set later. Left alone, each of these rows makes
`PostEventPayoutService.recoverPlatformFundedRefunds` call `charges().retrieve(test ch_)`
(`:641`) every night once the org re-onboards — an ERROR line per refund, forever, and a debt
that never clears.

**2.7 `disputes`** — `status = 'withdrawn_reinstated'`, `closed_at = now()`,
`updated_at = now()` WHERE `status = 'open'`. An open dispute has no Stripe polling at all
(`DisputeIngestService` is webhook-only), but it blocks **every** payout for its org
(`disputes.countOpenByOrgId(...) > 0`, `PostEventPayoutService.java:218-222`) and reduces the
event net (`sumOpenOrLostMinorByEventId`, `:234`), and no live `charge.dispute.closed` will
ever arrive for a test `du_`. `withdrawn_reinstated` is the only closed status excluded from
*both* (`DisputeStatus.java:12-15`). **The SQL cannot restore the tickets** that
`DisputeIngestService.revokeTickets` revoked when the dispute opened — `restoreTickets`
(`:205-221`) has per-ticket logic (`redeemed_at`, refunded tickets, other open disputes on the
order) that has no honest SQL equivalent. The pre-flight counts those tickets (§1 §8) and the
runbook tells the operator to restore them by hand. If the count is 0 — which is the expected
answer — the question is moot.

### Amendment (approved at gate) — §2.8 `orders.test_mode`

**2.8 `orders`** — `test_mode = TRUE` WHERE `test_mode = FALSE`. Every order that exists at
cutover time is test-era by definition. The flag is read in exactly three places: the payout
net (`OrderRepository.sumLive*`, `RefundRepository.sumSucceededLive*`, consumed by
`PostEventPayoutService` step 2) and the two candidate queries, where an event whose orders
are ALL test-mode is excluded (`NOT EXISTS test-mode OR EXISTS live`, so a zero-order event
still behaves exactly as before). The unfiltered sums stay untouched: filtering them would
rewrite organizer revenue history. Orders created after the swap are stamped at creation
from `StripeProperties.isLiveKey()`, in both checkout services. `stripe_livemode` is also
reset to `NULL` in §2.2 — with the account gone there is no mode left to describe — and the
post-check covers both new columns.

### Amendment (review round 1) — the flag covers three tables

V130 is now `V130__test_mode_flags.sql` (the unshipped migration was amended in place rather
than followed by a V131) and adds the same `test_mode BOOLEAN NOT NULL DEFAULT FALSE` to
`payout_runs` and `disputes`. Both are stamped at creation from the running key
(`PostEventPayoutService` for a planned or no-bank-parked run, `DisputeIngestService` on a
dispute's first sighting) and both filtered out of the payout net —
`PayoutRunRepository.sumLiveAmountByEventAndStatusIn` and
`DisputeRepository.sumOpenOrLostLiveMinorByEventId`. §2.8 of the reset flags all three
tables; the pre-flight and post-check count all three. The org-level open-dispute freeze and
the one-payout-in-flight guard stay unfiltered on purpose: the reset leaves neither with a
test-era row to match, and a spurious "wait" errs towards not moving money.

**Deliberately NOT changed: `refunds` in `PENDING`/`REQUESTED`.** Nothing polls them; the only
transition source is a `refund.updated`/`refund.failed` webhook that will never arrive for a
test `re_`. Flipping them in SQL would skip the two side effects the Java transition performs
in the same transaction — `refundTickets.deleteByRefundId` and the `RefundFailedEvent` email
(`RefundService.java:328-344`) — leaving a `UNIQUE(ticket_id)` claim behind that 409s every
future refund of that ticket. Their only cost is that `sumActiveAmountByOrderId` keeps
counting them, which blocks re-refunding a test-mode order that cannot be refunded under a
live key anyway. Reported in pre-flight §7, decided by a human.

### 3. Post-checks — `scripts/stripe-live-cutover-postcheck.sql`

One statement, a `UNION ALL` of labelled counts, every row must be `0`:

`orgs_with_account`, `orgs_not_reset` (any of the nine columns off its reset value),
`tiers_with_stripe_ids`, `reservations_held`, `payout_runs_nonterminal`,
`refunds_unrecovered_platform_funded` (using the repository's exact predicate incl.
`status = 'SUCCEEDED'`), `disputes_open`. Plus two **non-zero-expected** rows printed after
them for eyeballing (`orders_kept`, `tickets_kept`) — clearly separated from the must-be-zero
block, or they go in the pre-flight instead. Simpler and preferred: keep the post-check file
strictly must-be-zero, so "every row is 0" is the whole contract and the test can assert it
without a whitelist.

### 4. The test — `StripeLiveCutoverScriptTest`

Runs on an **isolated** H2 so the script's table-wide UPDATEs cannot touch rows other test
classes left in the shared in-memory DB: `@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:cutover;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")`.
A distinct property set is a distinct context-cache key, so Flyway builds a fresh schema for
this class alone. Not `@Transactional` (the script is run on a real connection);
`@Import(TestRateLimitConfig.class)` like the other `@SpringBootTest` classes.

Execution: `ScriptUtils.executeSqlScript(Connection, Resource)` — present in spring-jdbc
7.0.6 (`org.springframework.jdbc.datasource.init.ScriptUtils`, verified in `~/.m2`:
`public static void executeSqlScript(java.sql.Connection, org.springframework.core.io.Resource) throws ScriptException`).
The resource is `new FileSystemResource("scripts/stripe-live-cutover.sql")` — surefire's
working directory is the module root and `pom.xml` does not override it.

### 5. The runbook — `docs/STRIPE_LIVE_CUTOVER.md`

Sequence, in order, each step with its own verification:

1. **Announce** to organizers: onboarding must be redone, and the platform cannot take money
   between the freeze and their re-onboarding.
2. **Freeze.** There is no checkout kill-switch in this codebase — verified: the only gates on
   `POST /api/v1/public/events/{id}/checkout` are per-org readiness and per-tier eligibility.
   The honest options are (a) accept the window, (b) unpublish events for the duration. State
   the window is ~10 minutes of DB work plus however long re-onboarding takes, and that
   checkout answers a leak-safe `404` (not a 500) for every paid tier until an org re-onboards
   — `reserveAndBuildMetadata:626-643` returns `ApiException.notFound("Event")` when the tier
   has no `stripe_price_id`, when the org has no account, and when readiness is false. Free
   tiers keep working throughout (`priceIt` → free branch never touches Stripe).
3. **Pre-flight** (§1). Save the output. Stop and decide if §7 or §8 is non-empty.
4. **Back up** the four tables the script writes (`pg_dump -t`), then run the reset (§2) with
   `-v ON_ERROR_STOP=1 --single-transaction`.
5. **Swap the four Railway secrets.** Recommend a **restricted** `rk_live_` key. Required
   scopes, derived by reading every `stripeClient.<x>()` call site in `src/main/java` (the
   full list — anything missing here is a silent production failure):

   | call site | scope |
   |---|---|
   | `checkout().sessions().create/list` (`StripeCheckoutService:484`, `PaidCheckoutService:275`) | Checkout Sessions **write** |
   | `paymentIntents().create/retrieve/list/cancel` (`StripePaymentIntentService:216,298`, `PaidFulfilmentReconciler:85`, `ReservationSweeper:111`) | PaymentIntents **write** |
   | `charges().retrieve` (`PaidCheckoutService:309`, `SettlementIngestService:381,385`, `PostEventPayoutService:641`) | Charges **read** |
   | `products().create/update` (`StripeProductService:87,105`) | Products **write** |
   | `prices().retrieve` (`StripeProductService:115`) | Prices **write** (the Product create mints a Price via `default_price_data`) |
   | `coupons().create/delete` (`StripeCheckoutService:852,495`) | Coupons **write** |
   | `refunds().create` (`StripeRefundService:71`) | Refunds **write** |
   | `balance().retrieve` (`PostEventPayoutService:270`) | Balance **read** |
   | `payouts().create/retrieve` (`PostEventPayoutService:354,474`) | Payouts **write** |
   | `transfers().reversals().create` (`PostEventPayoutService:648`) | Transfers **write** |
   | `balanceSettings().update` (`StripePayoutScheduleService:82`) | Balance settings **write** |
   | `accounts().retrieve` + `expand=external_accounts` (`PostEventPayoutService:712`, `PayoutService:238`) | Connect: Accounts **read**, External accounts **read** |
   | `accounts().loginLinks().create` (`PayoutService:261`) | Connect: Login links **write** |
   | `accountSessions().create` (`StripeConnectService:181`) | Connect: Account sessions **write** |
   | `v2().core().accounts().create/retrieve` (`StripeConnectService:119`, `StripeConnectStatusMirror:65`) | v2 Core Accounts **write** |
   | `v2().core().accountLinks().create` (`StripeConnectService:300`) | v2 Core Account links **write** |
   | `v2().core().events().retrieve` (`StripeWebhookService:369`) | **v2 Core Events read** — call this out loudly: without it every v2 thin event 502s (`StripeWebhookService:374-380`) and the Connect mirror is dark |

   Plus: the key must be usable **on connected accounts** (`Stripe-Account` header) — every
   payout/balance/external-account call sets it.

6. **Three NEW live webhook endpoints** (test-mode endpoints do not carry over), exact event
   lists from the worktree `CLAUDE.md` cross-checked against `StripeWebhookService`'s switch
   (`:223-296`) and `V2_ACCOUNT_STATE_TYPES` (`:400-405`):
   - Endpoint A — "Your account" → `POST /api/v1/stripe/webhook/v1`, secret
     `STRIPE_WEBHOOK_SECRET_V1`: `payment_intent.succeeded`, `payment_intent.processing`,
     `payment_intent.payment_failed`, `payment_intent.canceled`, `checkout.session.expired`,
     `checkout.session.async_payment_failed`, `checkout.session.async_payment_succeeded`,
     `refund.updated`, `refund.failed`, `charge.refund.updated`, `transfer.created`,
     `transfer.reversed`, `charge.refunded`, `charge.dispute.created`, `charge.dispute.closed`,
     `charge.dispute.funds_withdrawn`, `charge.dispute.funds_reinstated`. **Do not** subscribe
     `checkout.session.completed`.
   - Endpoint B — "Connected accounts" → the **same** `/webhook/v1` URL, secret
     `STRIPE_WEBHOOK_SECRET_CONNECT`: `payout.created`, `payout.paid`, `payout.failed`.
   - Endpoint C — V2 → `POST /api/v1/stripe/webhook/v2`, secret `STRIPE_WEBHOOK_SECRET_V2`:
     `v2.core.account[requirements].updated`,
     `v2.core.account[configuration.recipient].capability_status_updated`,
     `v2.core.account[configuration.recipient].updated`,
     `v2.core.account[future_requirements].updated`, `v2.core.account.updated`
     (bracket notation is literal).
7. **Redeploy** (Railway picks up the env change; ≈3 min).
8. **Post-checks** (§3) — every row 0.
9. **Smoke test on a staff org**: connect → onboard → publish a €1 event → buy → verify the
   `payment_intent.succeeded` fulfilment, the ticket email and the QR → refund it → verify
   `refund.updated` lands. This is also what proves the restricted key's scopes.
10. **Organizers re-onboard.** `GET /stripe/status` now answers `NOT_STARTED`; the dashboard
    already renders that as the "connect Stripe" CTA.
11. **`STRIPE_PAYOUT_SCHEDULE_MANUAL` stays `false`.** Consistent with
    `docs/STRIPE_PAYOUTS_SETUP.md` §7: the go-live gate still requires the capability spike,
    legal sign-off, retention awareness and live `payout.*` delivery. Add one line to the
    runbook noting the §6 test-mode checklist now needs a staging deploy with its own
    `sk_test_` key, because prod's key is live.

## Verification commands

```
cd /Users/ivan/imin/imin-api/.claude/worktrees/stripe-live-cutover && ./mvnw test
```

Run it once on untouched `origin/master` first — a gate already red there is its own card, not
this diff. Nothing else: no contract change, so no `api:sync`/`api:check` anywhere.

## Test impact

**No repro test.** The defect is a *configuration state* of the production database, not a
code path: there is no key-dependent branch in the JVM to red-flag, and a test that asserted
"a test id blows up under a live key" would be asserting Stripe's behaviour through a mock we
wrote. What is testable is the artefact — the script — and that is what the test covers.

New: `src/test/java/com/imin/iminapi/stripe/StripeLiveCutoverScriptTest.java`. One method per
branch of the script, each with the minimal seed that forces that branch. Shared `@BeforeEach`
seeds one row per affected state plus the untouched control rows; each test runs the script
once via `ScriptUtils` and asserts.

| method | branch it forces |
|---|---|
| `clearsTierProductAndPriceIds` | §2.1 — a tier with both ids set ends with both NULL |
| `resetsAllNineOrganizationStripeColumns` | §2.2 — an org with `acct_test`, state `ACTIVE`, payouts/details/manual all true, a non-`[]` requirements array, a disabled reason and a non-null `stripe_connect_status_updated_at` ends at NULL/`NOT_STARTED`/false/false/false/`[]`/`[]`/NULL/NULL. Asserts **every one of the nine**, not just the account id |
| `givesHeldSeatsBackToTheTierBeforeReleasingTheHold` | §2.3 — tier `reserved = 5` with one HELD row of qty 3 ends at `reserved = 2` **and** the row RELEASED |
| `clampsTheReservedDecrementAtZero` | §2.3 drift branch — tier `reserved = 1` with a HELD row of qty 3 ends at `reserved = 0`, never negative |
| `releasesHeldReservationsWithTheCutoverReason` | §2.4 — `status`, `released_at`, `release_reason = 'TEST_MODE_CUTOVER'` |
| `leavesAsyncProcessingHoldsReleasedToo` | §2.4 — a HELD row with `async_processing_at` set and `expires_at` seven days out is still released (the sweeper would not have collected it for a week) |
| `parksEveryNonTerminalPayoutRunBlocked` | §2.5 — one `planned`, one `submitted`, one `retrying` row all end `blocked` / `TEST_MODE_CUTOVER` |
| `leavesTerminalPayoutRunsAlone` | §2.5 negative — `paid`, `partial`, `failed`, and an existing `blocked` row are byte-identical afterwards |
| `closesTheUnrecoveredPlatformFundedDebt` | §2.6 — `recovered_at` set, `recovery_reversal_id = 'TEST_MODE_CUTOVER'`; an already-recovered row keeps its original `recovery_reversal_id` |
| `closesOpenDisputesAsWithdrawnReinstated` | §2.7 — `status`, `closed_at`; a `lost` dispute is untouched |
| `leavesPendingRefundsAlone` | the deliberate non-change — a `PENDING` refund is byte-identical afterwards |
| `leavesNonStripeRowsUntouched` | the control: an org with no Stripe account (all nine already at their reset values), a published event with its `updated_at`, a buyer account row, an `orders` row with its `stripe_payment_intent_id`, a `tickets` row and a `processed_webhook_events` row are all unchanged |
| `isIdempotent` | runs the script **twice** and asserts the second run changes nothing — in particular that `ticket_tiers.reserved` is not decremented a second time |
| `postCheckQueriesAllReturnZero` | executes `scripts/stripe-live-cutover-postcheck.sql` as one statement after the reset and asserts every returned row has `n = 0`; also asserts it returns a **non-empty** result set, so a truncated file cannot pass vacuously |

If the livemode guard is approved, add
`src/test/java/com/imin/iminapi/stripe/StripeLivemodeGuardTest.java`: (a) create stamps
`stripe_livemode` from the running key prefix; (b) `getStatusLive` on an org whose stored mode
differs returns not-ready and logs; (c) `stripe_livemode IS NULL` is allowed (no opinion), so
the guard cannot brick an org the migration has not stamped.

## Live-test

**Needed: yes — and the runbook *is* the live test.** Surface: production, plus a one-org
smoke on `app.imin.wtf` checkout.

Provable on H2 (`./mvnw test`): every row transition above, the `reserved` decrement and its
clamp, idempotency, the untouched-control set, and that the post-check query returns all
zeroes. That is the whole of the script's contract.

Provable only in prod, after the secret swap: that a live `rk_live_` key carries every scope
in the table (a missing scope is an `invalid_request_error` at the first call, not a compile
error); that the three new webhook endpoints verify their signatures; that the v2 thin-event
fetch works (`v2.core.events` read); that a real organizer's re-onboarding reaches `ACTIVE`;
and that a €1 purchase + refund round-trips. None of these can be faked in the test suite, and
per the "test doubles hide config gaps" lesson, a green suite is not evidence about any of them.

## Contract impact

**None.** Confirmed: no controller, DTO, request/response record, `SecurityConfig` route or
OpenAPI-annotated signature is touched. `GET /api/v1/orgs/{orgId}/stripe/status` keeps its
shape and starts returning the `NOT_STARTED` variant it already returns for a never-connected
org (`StripeConnectService.notStarted()`), so `imin-webapp` needs no change and no
`src/shared/api/types.ts` edit. `PUBLIC_PAGE_API.md` is untouched — `/api/v1/public/...` moves
nowhere. No `api:sync`, no `api:check`.

If the optional guard ships, this stays true: the refusal is expressed through the existing
`StatusResult` (not-ready), which the buyer path already collapses to the existing leak-safe
404. No new error code, no new field.

## i18n impact

**None.** No user-visible string is added or changed in any repo. This is *why* the guard is
specified to refuse inside `getStatusLive` rather than raising a new organizer-facing
`409`/`503` with a message: a new organizer-facing message would be an `imin-webapp` string
and would drag EN/ES/FR/UK into an api-only task. If the gate decision is instead to surface a
distinct organizer error, that becomes a second card with the webapp in the repo set and four
locales in the same task.

## Blast radius

- **Money path, directly.** `organizations.stripe_account_id`, `ticket_tiers.stripe_price_id`
  and the connect mirror are the three inputs to the buyer-checkout gate
  (`StripeCheckoutService.reserveAndBuildMetadata:626-643`). After the reset **every paid
  checkout in production answers 404 until its org re-onboards and its tiers re-sync.** That is
  the intended state — the alternative is a 502 at the Stripe call — but it is a full paid-sales
  outage for the duration, and the runbook must say so in the first paragraph. Free tiers are
  unaffected.
- **Tier re-sync.** `StripeProductService.syncTier` is called from exactly two places
  (`TicketTierService.java:247` on tier write, and `StripeCheckoutService:817` on the
  promo-only path). Clearing `stripe_price_id` therefore does **not** self-heal on the next
  checkout: an organizer must save the tier (or ops must touch it) before the tier can sell.
  Call this out in the runbook as an explicit post-cutover step per active event, and have the
  pre-flight §2 list exactly which tiers those are.
- **Inventory.** §2.3 writes `ticket_tiers.reserved`, the counter every availability read uses.
  Getting it wrong oversells or undersells. It is the single most dangerous statement in the
  script and has two dedicated tests.
- **Payouts.** `payout_runs` rows parked `blocked`/`TEST_MODE_CUTOVER` are excluded from the
  nightly sweep **forever** by `existsBlockedNeedingAHuman` — correct for test-era events, and
  irreversible without another SQL edit.
- **Flyway.** No migration in the base deliverable. The optional guard adds `V129` — the next
  free number after V128; `SPRING_FLYWAY_OUT_OF_ORDER=true` is already permanent on Railway.
- **Shared modules.** `Organization` is a hot entity touched by many paths; the guard adds one
  nullable field to it. Nothing else in the base deliverable is shared.
- **Not touched:** auth, marketing/audience, tickets/door, wallet passes, the public feed.

## Risks

Every line below is a decision the user must confirm before implementation starts.

1. **Keep vs purge test-mode orders/tickets.** Recommend **keep**. Purging would orphan
   `disputes.order_id`/`settlements`, destroy the only record of who holds a ticket for an
   upcoming event, and break refund-request history. The plan assumes keep.
2. **Test-era events become live payout candidates later.** With orders kept, an event from the
   test era still has `gross > 0`; once its org re-onboards live *and* Track B is enabled, it
   re-enters `findPayoutCandidates` and its "owed net" is fake money competing for the org's
   real shared balance. The available-balance clamp (`PostEventPayoutService:295-301`) bounds
   the damage to money the org genuinely has, but the ledger would be wrong. Recommend: **do
   not solve it in this script** (it needs an INSERT of a `blocked` run per test-era event,
   which is a different shape of change); instead make it a named pre-condition on the Track B
   go-live gate in `docs/STRIPE_PAYOUTS_SETUP.md` §7. Flagging it because it is the one
   money-correctness hole this plan knowingly leaves open.
3. **OPEN test disputes.** Recommend close to `withdrawn_reinstated` (unblocks org payouts and
   is excluded from the net reduction), and restore any revoked tickets **by hand** — SQL
   cannot reproduce `DisputeIngestService.restoreTickets`. Alternative: leave them OPEN and
   accept that those orgs can never be paid out. Expected count: 0.
4. **PENDING test refunds.** Recommend **leave them** (rationale in §2.5 of Ordered steps).
   Alternative: flip to `CANCELED` *and* delete the matching `refund_tickets` rows in the same
   statement — more moving parts for rows that cost nothing where they are.
5. **Livemode guard now or later.** Recommend **now, in a second commit**, at six files
   (`V129`, `Organization`, `StripeProperties.isLiveKey()`, `StripeConnectService`, one test
   class, one `CLAUDE.md` bullet) with these semantics: stamp `stripe_livemode` at account
   creation from the running key prefix (`sk_live`/`rk_live` ⇒ true); refuse in
   `getStatusLive` only — its sole production caller is the checkout gate
   (`StripeCheckoutService:640`), verified by grep — and **`NULL` means "unknown, allow"**, so
   the migration cannot brick a single existing org. Abort to a follow-up card if it grows past
   six files or needs a second edit on the checkout path.
6. **Purge test-mode webhook endpoints in the Stripe dashboard.** Recommend **no** — test-mode
   endpoints live in the test dashboard, cost nothing, and are what a future staging
   environment would reuse. Disabling them is a one-click, reversible operator choice, not a
   deliverable.
7. **Transaction lives in the runner, not in the file.** `psql --single-transaction` instead of
   `BEGIN;`/`COMMIT;` inside the .sql, so the JUnit test can execute the real artefact through
   `ScriptUtils`. If the user wants the file self-contained, `START TRANSACTION;`/`COMMIT;`
   parses on both engines — but the test then needs an autocommit connection and one more
   thing can go wrong on the money path. Deviation from the brief's "one transaction"; flagged
   rather than assumed.
8. **Three SQL files, not one.** Pre-flight is psql-flavoured (`\echo`) and must stay
   un-executable by the test; post-checks must run *after* the commit to mean anything
   (`--single-transaction` commits at EOF regardless of what a SELECT printed inside it).
9. **Size.** Base deliverable is 6 files (5 + this plan); with the guard, 11. Both are under
   the ~15-file threshold and the guard is separable into its own commit, so no split is
   proposed — but if the gate on #5 is "later", ship the base alone.
10. **Paid-sales outage window.** See Blast radius. Needs an explicit organizer announcement
    and a chosen hour; the plan does not pick one.
11. **The prod DB has never been queried for this task.** Every count in the pre-flight could
    come back 0 (no org ever finished onboarding) or in the hundreds. The script is written to
    be correct either way, but the operator must read the pre-flight before running the reset.

## Definition of done

- [ ] `scripts/stripe-live-cutover-preflight.sql` exists, is read-only (every statement a
      SELECT or a psql meta-command), and covers §1–§10.
- [ ] `scripts/stripe-live-cutover.sql` exists, is pure SQL with no `BEGIN`/`COMMIT` and no
      psql meta-commands, contains the ten `UPDATE`s (§1–§8, incl. the approved `test_mode` flags) in the stated order, and
      carries a header comment with the exact `psql -v ON_ERROR_STOP=1 --single-transaction -f`
      invocation.
- [ ] `scripts/stripe-live-cutover-postcheck.sql` returns one labelled row per check, all of
      which must be 0.
- [ ] `StripeLiveCutoverScriptTest` has one method per row of the Test-impact table, runs on an
      isolated H2 database, executes the real script file, and passes.
- [ ] `docs/STRIPE_LIVE_CUTOVER.md` covers all eleven runbook steps including the full scope
      table, the three endpoints with their literal event lists, and the
      `STRIPE_PAYOUT_SCHEDULE_MANUAL=false` line reconciled with `STRIPE_PAYOUTS_SETUP.md` §7.
- [ ] `./mvnw test` green from the worktree root, and green on untouched `origin/master` first.
- [ ] No file under `src/main/java` changed, unless the guard gate (Risk 5) was approved — in
      which case the guard ships in the same commit as the reset (split abandoned at review round 3) and `CLAUDE.md` documents the column.
- [ ] Decisions 1–6 answered in writing by the user before implementation starts.

## Live-test evidence

## Review rounds

- round 1 → FIX_REQUIRED (2 HIGH: runbook runs the script before the build that adds its columns is deployed; smoke test never proves a live order is stamped test_mode=false. 3 MEDIUM: whsec swap ordered before endpoint creation; dispute + already-triggered sums still count test-era rows; a mode-mismatched org cannot recover. 2 LOW: reserved give-back vs late checkout; test mutates the StripeProperties singleton.)
- round 2 → APPROVED (all round-1 items verified fixed; 2 MEDIUM: mode-mismatch path ignores the locked re-read so a concurrent connect can mint a second live account; STRIPE MODE banner at ERROR pages Sentry on every boot. 4 LOW: two stamp branches untested, runbook §-number, stale V130 filename in plan, existsBlockedNeedingAHuman unfiltered. Fixed post-review before GATE 2.)
- round 3 → APPROVED (delta only; all round-2 items verified fixed. 3 LOW noted, not changed: account-session/onboarding-link lack the mismatch guard [unreachable after the reset nulls id+livemode]; DoD wording updated here; guard ships in the same commit.)
