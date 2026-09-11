# Stripe readiness — P0 + P1 remediation

Slug `stripe-readiness-p0-p1` · Mode: single repo, 6 sequential phases in ONE worktree · Notion: card link not supplied to the planner (see OPEN_QUESTIONS)

## Goal and scope

Fix the four P0 and thirteen P1 findings from the 2026-09-10 Stripe readiness audit in `imin-api`, on branch `stripe-readiness-p0-p1` off `origin/master @ 3d88ec6`.

In scope (all re-verified against the worktree while planning):

| id | one line | verified at |
|---|---|---|
| P0-1 | `payment_intent.payment_failed` releases a hold that is still retryable → later `succeeded` logs `[OVERSOLD]` and the poller already said FAILED | `stripe/StripeWebhookService.java:441-455`, `service/event/InventoryService.java:215-238`, `service/ticket/CheckoutStatusService.java:83-87` |
| P0-2 | no role guard on the three Connect POSTs or on minting the Express login link | `stripe/StripeConnectController.java:27,38,43`, `stripe/StripeConnectService.java:418`, `service/payout/PayoutService.java:189-190` vs the ADMIN guard at `controller/payout/PayoutController.java:70-76` |
| P0-3 | refund double-credits the application fee (proportional transfer reversal **plus** an explicit ApplicationFeeRefund) | `stripe/StripeRefundService.java:55-59,74-91` |
| P0-4 | a dispute only flips a settlements row: tickets stay valid, organizer is never told, the loss is never recovered, and the row blocks every future payout forever | `stripe/SettlementIngestService.java:213-261`, `settlement/SettlementRepository.java:110-117`, `payout/PostEventPayoutService.java:183-187` |
| P1-5 | async payment methods (SEPA/iDEAL/Klarna) settle in days against a 30-minute hold | `service/event/ReservationSweeper.java:63-86,107-115`, `stripe/StripeWebhookService.java:199-257` |
| P1-6 | fulfilment never verifies the paid amount/currency against what we priced | `stripe/StripeCheckoutService.java:377-404` (metadata build), `service/ticket/PaidCheckoutService.java:174-181` |
| P1-7 | a Connect-scoped duplicate of a fulfilment event is accepted and fulfilled | `stripe/StripeWebhookService.java:155-173,199-257` |
| P1-8 | hosted checkout charges the stored (possibly stale) Stripe Price | `stripe/StripeCheckoutService.java:362-366`, `stripe/StripeProductService.java:44-56` |
| P1-9 | `organizations.stripe_account_id` is not unique and create is not serialized | `stripe/StripeConnectService.java:97-128`, `db/migration/V18__stripe_connect.sql:11`, `model/Organization.java:81` |
| P1-10 | RESTRICTED orgs can sell but are excluded from payouts | `stripe/StripeCheckoutService.java:616` vs `repository/EventRepository.java:420-436` + `payout/PostEventPayoutService.java:156-165` |
| P1-11 | a partial Stripe account response persists `payoutsEnabled=false` and bricks checkout | `stripe/StripeConnectStatusMirror.java:87-93,123,157-162` |
| P1-12 | "no bank account attached" is a bare WARN that repeats nightly and reaches nobody | `payout/PostEventPayoutService.java:208-214,465-476` |
| P1-13 | `payout.failed` re-attempts nightly forever; the `payout_arrived` preference sends nothing | `payout/PostEventPayoutService.java:346-353,453-456`, `db/migration/V8__notification_preferences.sql:9`, `service/me/NotificationPrefsService.java:40` |
| P1-14 | refund after payout is a hard `409 ORDER_NOT_REFUNDABLE` | `refund/RefundService.java:190-199` |
| P1-15 | a dashboard-initiated refund is a silent no-op | `refund/RefundService.java:261-266` |
| P1-16 | a Stripe timeout mid-refund maps to a non-retryable 422 and orphans the Stripe-side refund | `refund/RefundService.java:180-210` |
| P1-17 | `.env.example` names a webhook variable the app does not read; prod default for the organizer return URL is localhost; stale dot-notation v2 event names in comments | `.env.example:38`, `src/main/resources/application.yaml:158-159,174`, `stripe/StripeWebhookController.java:33-34` |

Out of scope: anything requiring a live Stripe call to prove (no CLI, no test key available here); the FE side of any of this; enabling `IMIN_REMINDERS_ENABLED` or other unrelated flags; a payments-side re-architecture (separate charges and transfers).

**This is far more than ~15 files, so it ships as six phases, implemented SEQUENTIALLY by separate workers in this ONE worktree.** Phases A, C, D1, D2 all touch `StripeWebhookService.java`; parallel worktrees would collide on it. Every phase must leave `./mvnw test` green on its own so a phase can ship alone.

| phase | findings | approx. files (main + test + resources) |
|---|---|---|
| A — checkout / inventory / webhook | P0-1, P1-5, P1-6, P1-7, P1-8 | 13 |
| B — connect / auth / mirror | P0-2, P1-9, P1-10, P1-11 | 12 |
| C — refunds | P0-3, P1-14, P1-15, P1-16 | 12 |
| D1 — disputes | P0-4 (a)(c)(d) | 14 |
| D2 — payout operability + notifications | P0-4 (b) reuse, P1-12, P1-13 | 15 (8 of them email templates) |
| E — config / docs | P1-17 + CLAUDE.md webhook list | 5 |

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| `api` | `master` | `/Users/ivan/imin/imin-api/.claude/worktrees/stripe-readiness-p0-p1` | `./mvnw test` (run from the worktree root) |

No other repo ships in this task. `webapp`/`public`/`gate`/`fan-app` are untouched — see **Contract impact**.

## Affected files (per repo)

All paths are relative to `/Users/ivan/imin/imin-api/.claude/worktrees/stripe-readiness-p0-p1`.

### Phase A — checkout / inventory / webhook (P0-1, P1-5, P1-6, P1-7, P1-8)

main:
- `src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java` — P0-1 release rule, P1-5 `payment_intent.processing` + `payment_intent.canceled` + `checkout.session.async_payment_succeeded|failed`, P1-6 amount gate call, P1-7 scope gate
- `src/main/java/com/imin/iminapi/stripe/CheckoutAmountVerifier.java` — **new**. Pure recompute of the expected total from PI metadata (`tier_id`, `qty`, `promo_id`) + the event currency; no Stripe call, no writes
- `src/main/java/com/imin/iminapi/service/event/InventoryService.java` — new `markAsyncProcessing(UUID, Instant)`; `releaseReservation` unchanged
- `src/main/java/com/imin/iminapi/model/TicketReservation.java` — `asyncProcessingAt` field
- `src/main/java/com/imin/iminapi/repository/TicketReservationRepository.java` — conditional `markAsyncProcessing` update (status still HELD)
- `src/main/java/com/imin/iminapi/stripe/StripeCheckoutService.java` — P1-8 inline `price_data` line item
- `src/main/java/com/imin/iminapi/stripe/StripeProperties.java` — `asyncPaymentHoldDays` (default 7)
- `src/main/resources/application.yaml` — `imin.stripe.async-payment-hold-days: ${STRIPE_ASYNC_PAYMENT_HOLD_DAYS:7}`
- `src/main/resources/db/migration/V125__reservation_async_processing.sql` — **new**, additive: `ALTER TABLE ticket_reservations ADD COLUMN async_processing_at TIMESTAMP WITH TIME ZONE NULL;`

test:
- `src/test/java/com/imin/iminapi/stripe/StripeWebhookServiceTest.java` — modified (two existing tests change meaning, see **Test impact**)
- `src/test/java/com/imin/iminapi/stripe/CheckoutAmountVerifierTest.java` — new
- `src/test/java/com/imin/iminapi/stripe/StripeCheckoutServiceTest.java` — modified (P1-8)
- `src/test/java/com/imin/iminapi/service/event/InventoryServiceTest.java` — modified (`markAsyncProcessing`)

### Phase B — connect / auth / mirror (P0-2, P1-9, P1-10, P1-11)

main:
- `src/main/java/com/imin/iminapi/stripe/StripeConnectController.java` — ADMIN guard on `/connect`, `/account-session`, `/onboarding-link`
- `src/main/java/com/imin/iminapi/service/payout/PayoutService.java` — `dashboardUrl` only for ADMIN+
- `src/main/java/com/imin/iminapi/stripe/StripeConnectService.java` — `findByIdForUpdate` re-read before the Stripe create
- `src/main/resources/db/migration/V126__unique_stripe_account_id.sql` — **new**, additive unique index
- `src/main/java/com/imin/iminapi/stripe/StripeConnectStatusMirror.java` — P1-11 bail-out when the recipient configuration is absent
- `src/main/java/com/imin/iminapi/repository/EventRepository.java` — P1-10 payout-candidate predicate
- `src/main/java/com/imin/iminapi/payout/PostEventPayoutService.java` — P1-10 in-transaction predicate (must match the query exactly)

test:
- `src/test/java/com/imin/iminapi/stripe/StripeConnectControllerTest.java` — new
- `src/test/java/com/imin/iminapi/service/payout/PayoutServiceStatusRoleTest.java` — new
- `src/test/java/com/imin/iminapi/stripe/StripeConnectServiceTest.java` — modified (lock + create)
- `src/test/java/com/imin/iminapi/stripe/StripeConnectStatusMirrorTest.java` — modified (P1-11)
- `src/test/java/com/imin/iminapi/payout/PostEventPayoutServiceTest.java` — modified (P1-10)

### Phase C — refunds (P0-3, P1-14, P1-15, P1-16)

main:
- `src/main/java/com/imin/iminapi/stripe/StripeRefundService.java` — delete the ApplicationFeeRefund call + the charge retrieve; add an optional `reverseTransfer` argument for P1-14
- `src/main/java/com/imin/iminapi/refund/RefundService.java` — P1-14 second attempt + platform-funded flag; P1-15 back-resolution; P1-16 status mapping
- `src/main/java/com/imin/iminapi/refund/Refund.java` — `platformFunded` boolean
- `src/main/java/com/imin/iminapi/refund/RefundRepository.java` — `sumPlatformFundedByOrgId` (org-level debt)
- `src/main/resources/db/migration/V127__refund_platform_funded.sql` — **new**, additive: `ALTER TABLE refunds ADD COLUMN platform_funded BOOLEAN NOT NULL DEFAULT FALSE;`
- `src/main/java/com/imin/iminapi/payout/PostEventPayoutService.java` — subtract the org's platform-funded refunds from the payable amount
- `docs/superpowers/specs/2026-05-25-stripe-refunds-design.md` — rewrite decisions **#2** (line 26) and **#8** (line 32) in 1–2 lines each

test:
- `src/test/java/com/imin/iminapi/stripe/StripeRefundServiceTest.java` — modified (two existing tests deleted, see **Test impact**)
- `src/test/java/com/imin/iminapi/refund/RefundServiceTest.java` — modified
- `src/test/java/com/imin/iminapi/refund/RefundPlatformFundedTest.java` — new
- `src/test/java/com/imin/iminapi/payout/PostEventPayoutServiceTest.java` — modified (debt subtraction)

### Phase D1 — disputes (P0-4 a, c, d)

main:
- `src/main/resources/db/migration/V128__disputes.sql` — **new** `disputes` table
- `src/main/java/com/imin/iminapi/dispute/Dispute.java`, `DisputeStatus.java`, `DisputeRepository.java` — **new**
- `src/main/java/com/imin/iminapi/dispute/DisputeIngestService.java` — **new**: resolve Order via charge → PI, revoke/restore tickets, upsert the dispute row, publish `DisputeOpenedEvent`
- `src/main/java/com/imin/iminapi/dispute/DisputeOpenedEvent.java` — **new** (consumed in D2)
- `src/main/java/com/imin/iminapi/stripe/SettlementIngestService.java` — keep the read-model annotation; hand the dispute to `DisputeIngestService`
- `src/main/java/com/imin/iminapi/stripe/StripeWebhookService.java` — dispatch `charge.dispute.*` to both
- `src/main/java/com/imin/iminapi/payout/PostEventPayoutService.java` — guard on `DisputeRepository.countOpenByOrgId`; subtract lost/withdrawn disputes from the event net
- `src/main/java/com/imin/iminapi/settlement/SettlementRepository.java` — deprecate/remove `countOpenTransferDisputes` once the guard moves

test:
- `src/test/java/com/imin/iminapi/stripe/SettlementIngestServiceTest.java` — new (repro test lives here)
- `src/test/java/com/imin/iminapi/dispute/DisputeIngestServiceTest.java` — new
- `src/test/java/com/imin/iminapi/stripe/SettlementIngestWebhookTest.java` — modified
- `src/test/java/com/imin/iminapi/payout/PostEventPayoutServiceTest.java` — modified

### Phase D2 — payout operability + notifications (P0-4 b, P1-12, P1-13)

main:
- `src/main/java/com/imin/iminapi/payout/PayoutRunStatus.java` — `BLOCKED`
- `src/main/java/com/imin/iminapi/payout/PostEventPayoutService.java` — split Stripe-error vs no-bank-account; attempt cap; BLOCKED run + `failure_reason`
- `src/main/java/com/imin/iminapi/stripe/StripeProperties.java` — `payoutMaxAttempts` (default 3)
- `src/main/resources/application.yaml` — `payout-max-attempts: ${STRIPE_PAYOUT_MAX_ATTEMPTS:3}`
- `src/main/java/com/imin/iminapi/payout/OrganizerPayoutNotifier.java` — **new**: in-app `Notification` + email, modelled on `service/event/SalesMilestoneNotifier.java`
- `src/main/java/com/imin/iminapi/dispute/DisputeNotifier.java` — **new**: same pattern, on `DisputeOpenedEvent`
- `src/main/java/com/imin/iminapi/stripe/SettlementIngestService.java` — `payout.paid` → "payout arrived" notification gated on `payout_arrived`
- `src/main/resources/email-templates/payout-blocked.{html,txt}` + `.es`/`.fr`/`.uk` variants — **8 new files**
- `src/main/resources/email-templates/dispute-opened.{html,txt}` + `.es`/`.fr`/`.uk` variants — **8 new files**

test:
- `src/test/java/com/imin/iminapi/payout/OrganizerPayoutNotifierTest.java` — new
- `src/test/java/com/imin/iminapi/dispute/DisputeNotifierTest.java` — new
- `src/test/java/com/imin/iminapi/payout/PostEventPayoutServiceTest.java` — modified
- `src/test/java/com/imin/iminapi/email/OrganizerEmailLocaleVariantsTest.java` — new, mirroring `BuyerEmailLocaleVariantsTest`

### Phase E — config / docs (P1-17)

- `.env.example` — replace `STRIPE_WEBHOOK_SECRET` with `STRIPE_WEBHOOK_SECRET_V1` / `_V2` / `_CONNECT`
- `src/main/resources/application.yaml` — line 174 default `http://localhost:5173` → `https://dashboard.imin.wtf`; fix the stale dot-notation v2 event names in the comment at 158-159
- `src/main/resources/application-dev.yaml` — add `imin.stripe.return-url-base: http://localhost:5173`
- `src/main/java/com/imin/iminapi/stripe/StripeWebhookController.java` — bracket-notation v2 names in the class javadoc (33-34)
- `CLAUDE.md` — Stripe Connect section: webhook subscription list (add `payment_intent.processing`, `payment_intent.canceled`, `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`), the new env vars, and the corrected `STRIPE_RETURN_URL_BASE` default

### Shipped delta (post-review, 2026-09-11)

Files in the shipped diff that the tables above did not name, all added by the review-fix rounds or as per-locale template variants: `dispute/DisputeStatusConverter.java`, `payout/RefundRecoveryMarker.java`, `payout/PayoutArrivedEvent.java`, `payout/PayoutBlockedEvent.java`, `payout/PayoutBlockReason.java`, `payout/PayoutRunRepository.java`, `payout/PostEventPayoutSweeper.java`, `repository/OrganizationRepository.java`, `service/ticket/PaidFulfilmentReconciler.java`, `service/ticket/CheckoutStatusService.java`, the ES/FR/UK variants of `dispute-opened`, `payout-blocked`, `payout-arrived` (html+txt), and tests `PostEventPayoutSweeperTest`, `PaidFulfilmentReconcilerTest`, `FreeCheckoutIdempotencyTest`, `StripeFixtures`, `StripeWebhookServiceConnectTest`, `OrganizationRepositoryLockTest`, `SettlementIngestPayoutNotifyTest`. V129 was folded into V127; shipped migrations are V125–V128.

## Ordered steps

### Phase A — checkout / inventory / webhook

1. **P1-8 first** (it changes what Stripe charges, which P1-6 then verifies). In `StripeCheckoutService` replace the ticket line item at :362-366 with inline `price_data{currency, unit_amount = tier.getPriceMinor(), product = tier.getStripeProductId()}`. Keep `product` so the one-shot coupon's `applies_to.products` scoping at :555 still binds. When `getStripeProductId()` is null, fall back to `product_data{name = tier.getName()}` and accept that a promo then cannot be product-scoped — log a WARN naming the tier. Leave the `stripePriceId == null` gate in `reserveAndBuildMetadata` (:604-610) alone: it is the "product sync never ran" signal, not the price source.
2. **P1-6.** Add `CheckoutAmountVerifier` with one method: given the PI metadata, the `Event` and the `TicketTier`, recompute `expected = netTotal + fee` where `netTotal = max(0, priceMinor*qty − discount)` and `fee = QuoteService.computeFee(priceMinor*qty, qty, bps, fixedMinor)` — i.e. the exact identity `StripePaymentIntentService:193` uses (`p.netTotalMinor() + p.applicationFee()`) and the hosted line items sum to. Reuse `StripeCheckoutService.computeDiscount` for the promo (make it package-visible/static rather than duplicating the formula). Return a result carrying `expectedMinor`, `expectedCurrency` and a match flag.
3. In `onPaymentIntentSucceeded`, call the verifier **before** `prepareIssuance`. On mismatch: log `ERROR "[AMOUNT_MISMATCH] paymentIntentId={} reservationId={} eventId={} tierId={} qty={} expected={} {} actual={} {}"`, do **not** confirm the reservation, do **not** issue, do **not** increment the promo, and return normally so the event is ACKed and Stripe stops retrying. No auto-refund. Skip the check (and log INFO) when `tier_id`/`qty` metadata is missing — those are the pre-V27 legacy events the handler already tolerates.
4. **P1-7.** Add a private `isPlatformScoped(event)` = `event.getAccount() == null`. Gate the *money and fulfilment* cases only — `payment_intent.*`, `checkout.session.*`, `refund.updated`, `refund.failed`, `charge.refund.updated` — on it, and gate `payout.*` on `event.getAccount() != null`. Mismatch ⇒ `log.warn("[stripe-webhook] v1 wrong-scope type={} eventId={} account={} — ignoring")` and return (still ACK 200, dedup row already written). **Deliberate deviation from the finding:** `transfer.*`, `charge.refunded` and `charge.dispute.*` are NOT gated. They are designed to accept both scopes — `SettlementIngestService.ingestTransfer/ingestChargeRefunded/ingestDispute` all take `event.getAccount()` as the org-resolution fallback, `retrieveCharge` (:353-373) retries on the connected account, and `SettlementIngestWebhookTest#fullRefund_onTheConnectedAccountCopy_stillResolvesViaSourceTransfer` pins that behaviour. Gating them would delete working ingestion. Record this in the phase's commit message.
5. **P1-5 schema.** Write `V125__reservation_async_processing.sql`: one additive nullable `TIMESTAMP WITH TIME ZONE` column. **Do not add a `PROCESSING` value to `ReservationStatus`** — `V27__ticket_reservations_and_shedlock.sql:29` pins `status` with an *unnamed* inline `CHECK (status IN ('HELD','CONFIRMED','RELEASED'))`, and an unnamed check gets a different auto-generated name in PostgreSQL (`ticket_reservations_status_check`) than in H2, so no portable `ALTER TABLE … DROP CONSTRAINT` exists. There is no precedent in this repo for dropping a check constraint. The column carries the state instead: `async_processing_at IS NOT NULL` **and** `status = 'HELD'` *is* the PROCESSING state.
6. Add `TicketReservation.asyncProcessingAt` + a conditional repository update `update TicketReservation r set r.asyncProcessingAt = :at, r.expiresAt = :newExpiry where r.id = :id and r.status = HELD and r.asyncProcessingAt is null` (returns rows-affected, so a redelivery is a no-op) and `InventoryService.markAsyncProcessing(reservationId, newExpiry)` wrapping it. No tier lock is needed: `reserved` does not move.
7. Handle `payment_intent.processing` in the dispatcher → `markAsyncProcessing(reservationId, clock.instant().plus(Duration.ofDays(props.getAsyncPaymentHoldDays())))`. `ReservationSweeper.findHeldExpiredBefore` then skips the row for free — no sweeper change, and `cancelIfNativeIntent` is never reached for a `processing` PI it cannot cancel anyway.
8. **P0-1.** Rewrite `onPaymentIntentFailed`: release **only** when the reservation exists and `asyncProcessingAt != null` (an async method that definitively failed days later — terminal). Otherwise log `INFO "[stripe-webhook] payment_intent.payment_failed {} reservation {} left HELD — the PaymentIntent is still retryable"` and return. Card declines and 3DS failures now drain via `checkout.session.expired` (hosted) or the `ReservationSweeper` (native + hosted backstop), both of which already exist and are the documented source of truth.
9. Add a `payment_intent.canceled` case → `releaseReservation(reservationId, "WEBHOOK_CANCELED")`. This is the deterministic terminal signal the release used to steal from `payment_failed`; it also covers the `ReservationSweeper.cancelIfNativeIntent` path, whose `paymentIntents().cancel` now round-trips back to us. `release_reason` is `VARCHAR(32)` (V27:39) so `WEBHOOK_CANCELED` fits.
10. Add `checkout.session.async_payment_failed` → same terminal release, and `checkout.session.async_payment_succeeded` → an INFO no-op (fulfilment stays on `payment_intent.succeeded`, exactly as `checkout.session.completed` is handled at :252).
11. `CheckoutStatusService` needs no change: an async hold stays `HELD`, so the poller answers `PENDING`, which is the truth. Confirm by reading `terminalFailure` (:83-87) — it keys on `RELEASED` only.

### Phase B — connect / auth / mirror

1. **P0-2.** In `StripeConnectController`, add `RoleGuard.requireAtLeast(p, UserRole.ADMIN, "…")` as the first statement of `connect`, `accountSession` and `onboardingLink`, with actions `"connect a Stripe account"`, `"start Stripe onboarding"`, `"start Stripe onboarding"`. Leave `GET /status` at MEMBER. Guard in the controller, not in `StripeConnectService`: `PayoutService.connect` also calls `getOrCreateAccount` and `createOnboardingLink`, and `PayoutController:70-76` already guards that entry — a service-level guard would be a duplicate, and moving it there risks changing the `PayoutService.status` read path.
2. In `PayoutService.status` (:186-193) mint the login link only when `RoleGuard.isAtLeast(principal, UserRole.ADMIN)` is also true. `stripeConnected` and `accountLast4` stay MEMBER-visible; `dashboardUrl` is null for a MEMBER. The `PayoutsStatusResponse` shape does not change — `dashboardUrl` is already nullable by construction (it is null whenever the account is not ready).
3. **P1-9 migration.** Before writing it, run the duplicate check in **Verification commands** below against prod. Then `V126__unique_stripe_account_id.sql`: `CREATE UNIQUE INDEX uq_organizations_stripe_account ON organizations (stripe_account_id);` — a **plain** unique index, **not** a partial one. Both PostgreSQL and H2 treat NULLs as distinct in a unique index, which is exactly why `V27:48-52` and `V93:14-18` chose plain unique indexes over partial ones ("No partial index — H2 backs the test suite and does not support one"). Leave `ix_org_stripe` (V18:11) in place — dropping it is not additive and the duplicate index costs nothing on this table.
4. **P1-9 lock.** In `getOrCreateAccount`, after `loadOwnedOrg`, re-read via the existing `OrganizationRepository.findByIdForUpdate` (already present at :78-79, native `SELECT … FOR UPDATE` precisely because `@Lock(PESSIMISTIC_WRITE)` emits H2-incompatible SQL) and re-check `getStripeAccountId()` on the locked row before calling Stripe. The method is already `@Transactional`, so the lock spans the Stripe call.
5. **P1-11.** In `StripeConnectStatusMirror.syncFromStripe`, before `applyTo`, bail when the recipient configuration is unreadable: if `readTransferStatus(account) == null` **and** `account.getConfiguration() == null || account.getConfiguration().getRecipient() == null`, log `WARN "[stripe-mirror] account {} came back without configuration.recipient — leaving the mirror untouched"` and return without saving. Do the check outside `applyTo` — `applyTo` is documented as a pure Stripe-free projection and `StripeConnectStatusMirrorTest` drives it directly. A genuinely absent *capability* under a present `recipient` still projects normally (that is a real "not requested yet" state).
6. **P1-10.** Change the payout-eligibility predicate in **both** places, identically: `EventRepository`'s candidate query (:420-436) and `PostEventPayoutService.payOneEvent` (:156-165). Replace `stripeConnectState = ACTIVE` with `stripeConnectState <> DISABLED`, keeping `stripePayoutsEnabled = true` and `stripePayoutScheduleManual = true`. That makes payout eligibility identical to the checkout gate at `StripeCheckoutService:616` (`readyToReceivePayments` = transfers capability active = `stripePayoutsEnabled`). Add a 2-line comment on the query naming the invariant "sell ⇒ payable".

### Phase C — refunds

1. **P0-3.** In `StripeRefundService.create`, delete the whole `if (appFeeRefundMinor > 0)` block (:74-91) and the now-unused `Charge`/`ApplicationFeeRefundCreateParams` imports. Keep `setReverseTransfer(true)` and `setRefundApplicationFee(false)`. Keep the `appFeeRefundMinor` parameter — it is still logged and still persisted by the caller.
   The money identity, for the commit message: on a destination charge of `G` with `application_fee_amount = F`, the platform holds `F` and the connected account holds `G − F`. A refund of `A` with `reverse_transfer=true` debits `A` from the platform and reverses `A·(G−F)/G` back to it, so the platform's remaining fee is `F·(1 − A/G)` and the organizer bears its own proportional share — buyer, platform and organizer are all made whole with **one** call. The extra `ApplicationFeeRefund` moved a further `F·A/G` from the platform to the connected account, i.e. platform −fee / organizer +fee on every refunded ticket.
2. **Keep** `RefundService.computeAppFeeRefundMinor` and the `applicationFeeRefundMinor` column. Its meaning is now "the platform fee share attributable to this refund", which is exactly what `PostEventPayoutService:190-198` needs: `netAppFee = max(0, appFee − appFeeRefunded)`. Prove the payout math still lands on 0 for a fully refunded event with a test (`gross = refunded`, `appFee = appFeeRefunded` ⇒ `perEventNetMinor = 0`).
3. Update `docs/superpowers/specs/2026-05-25-stripe-refunds-design.md` decisions #2 (line 26) and #8 (line 32): the proportional transfer reversal is the whole mechanism; the explicit `ApplicationFee.Refund` call is removed because on a destination charge it credits the connected account a second time. Two lines, no ticket reference.
4. **P1-16.** In the `catch (StripeException e)` at :183, map `e.getStatusCode() == 0` (i.e. `ApiConnectionException` — the same "never reached Stripe / no answer" case `PostEventPayoutService.isDefinitiveRejection` already special-cases at :434-441) and `429` to `HttpStatus.BAD_GATEWAY` + `ErrorCode.UPSTREAM_UNAVAILABLE`. Add a 2-line comment stating that this is safe **because** `stripeIdempotencyKey` is derived from durable inputs (:365-388), so a client retry replays the identical Stripe request. Prefer an `instanceof ApiConnectionException || instanceof RateLimitException` test over the raw status int, mirroring the payout service.
   Take the **status-mapping only**; do **not** add a scheduled refund reconciler in this task. Rationale: `PaidFulfilmentReconciler` already exists as the pattern, but a refund reconciler needs `stripeClient.refunds().list(...)` paging, a new ShedLock job, and a "recent failed attempt" marker column — that is a phase of its own and P1-15 already materializes any Stripe-side refund we did not create, from the webhook, which is the same repair with no polling. Note it as follow-up work in the commit message.
5. **P1-15.** In `handleWebhookStatusChange`, when `refunds.findByStripeRefundId` is empty, back-resolve: `orders.findByStripePaymentIntentId(stripeRefund.getPaymentIntent())`. Branches, each with its own test:
   - order not found ⇒ current WARN + return (unchanged);
   - `amount == order.totalMinor − refunds.sumActiveAmountByOrderId(orderId)` (the remaining total) ⇒ materialize a `Refund` row (`initiatedByUserId` = a fixed system UUID or nullable — check the `NOT NULL` on `refunds.initiated_by_user_id` in `V28__refunds.sql` first and add a nullable-widening migration only if there is no system user), claim every non-refunded ticket into `refund_tickets`, then run the normal succeeded transition (which decrements `sold` and flips tickets to `refunded` via `releaseInventoryAndMarkTickets`);
   - partial ⇒ materialize the `Refund` row with **no** `refund_tickets` rows, revoke nothing, and `log.error("[UNMAPPED_PARTIAL_REFUND] orderId={} stripeRefundId={} amount={} — money moved, no ticket mapping; ops must reconcile")`.
   The `refund_tickets` write must be `saveAllAndFlush` inside a try/catch on `DataIntegrityViolationException`, exactly like the create path at :161-174 — the unique index is the decision.
6. **P1-14.** Give `StripeRefundService.create` a `boolean reverseTransfer` parameter. In `RefundService`, on `"balance_insufficient"`, retry **once** with `reverseTransfer=false` and a **different** idempotency key (append `":platform"` to the material in `stripeIdempotencyKey` — Stripe rejects a replayed key whose params differ). On success set `refund.platformFunded = true`. If the second attempt also fails, throw the existing `409 ORDER_NOT_REFUNDABLE` unchanged. The retry is a second Stripe call inside the same `@Transactional`; a failure in it must not swallow the original error — wrap the cleanup, per the workspace rule.
7. `V127__refund_platform_funded.sql` (additive, `NOT NULL DEFAULT FALSE`), `Refund.platformFunded`, and `RefundRepository.sumPlatformFundedByOrgId(orgId)` summing `amountMinor` where `platformFunded = true AND status = SUCCEEDED` across the org's orders.
8. In `PostEventPayoutService.payOneEvent`, after `owedMinor` is computed (:240), subtract the org-level platform-funded debt and clamp at zero, before the `payoutMinor` clamp. The debt is org-level, not event-level, deliberately: the refund may be for an event that has already paid out. Log the subtraction. **Do not** persist a "debt settled" marker in this phase — the sum is recomputed each tick and shrinks as `PayoutRun` amounts absorb it; the resulting behaviour (debt suppresses payouts until it is covered) is the intended one, but flag the double-subtraction question in **Risks**.

### Phase D1 — disputes

1. `V128__disputes.sql`: `id UUID PK`, `stripe_dispute_id VARCHAR(64) NOT NULL UNIQUE`, `org_id UUID NOT NULL REFERENCES organizations(id)`, `event_id UUID NULL REFERENCES events(id)`, `order_id UUID NULL REFERENCES orders(id)`, `stripe_charge_id VARCHAR(64)`, `stripe_payment_intent_id VARCHAR(255)`, `amount_minor BIGINT NOT NULL`, `currency VARCHAR(8) NOT NULL`, `status VARCHAR(32) NOT NULL`, `opened_at`, `closed_at`, `last_event_at`, `created_at`, `updated_at`. No inline CHECK on `status` (see the V27 lesson in Phase A step 5). Index on `(org_id, status)`.
2. `DisputeStatus` enum — `OPEN`, `WON`, `LOST`, `WITHDRAWN_REINSTATED` — with a `fromStripe(String)` mapping `needs_response`/`under_review`/`warning_*` ⇒ `OPEN`, `won` ⇒ `WON`, `lost` ⇒ `LOST`. Store lowercase via a converter, matching `SettlementStatus`/`PayoutRunStatus`.
3. `DisputeIngestService.ingest(Dispute, connectedAccount, eventType, eventAt)`, `Propagation.MANDATORY` like `SettlementIngestService`:
   - resolve the `Charge` exactly the way `SettlementIngestService.retrieveCharge` (:353-373) already does — reuse it, do not re-implement; a webhook payload never carries an expanded charge;
   - `order = orders.findByStripePaymentIntentId(charge.getPaymentIntent())`; when there is no order, still persist the dispute row (org from the settlement/transfer resolution) and log — the payout math in step 5 still needs it;
   - upsert on `stripe_dispute_id`, with the same `lastEventAt` staleness guard `SettlementIngestService.isStale` uses;
   - **(a)** on `charge.dispute.created` (and any transition into `OPEN`), set every ticket of the order that is not already `refunded` to `Ticket.STATE_REVOKED`. `revoked` already exists (`model/Ticket.java:52`) and is already rejected at the door (`service/ticket/TicketRedeemService.java:83,97`) and by the wallet (`service/ticket/WalletEligibility.java:62,75`) — **no new state and no ticket migration**. Do not touch `tier.sold`: the sale is contested, not reversed.
   - on `charge.dispute.funds_reinstated` or a `won` close, flip those same tickets back to `issued` (only the ones this dispute revoked — track them by order + a `revoked` state check, and skip any that later became `refunded`);
   - publish `DisputeOpenedEvent(disputeId)` on the OPEN transition only (consumed in D2).
4. `StripeWebhookService.onDispute` calls `settlementIngest.ingestDispute(...)` (read-model, unchanged) **and** `disputeIngest.ingest(...)`. Order matters only for logs.
5. **(c) payout math.** In `PostEventPayoutService`, subtract `disputes.sumOpenOrLostMinorByEventId(eventId)` from `perEventNetMinor` (:190-198). `WON`/`WITHDRAWN_REINSTATED` add back by construction (they are excluded from the sum). Policy: the organizer bears the disputed **face value**; the platform absorbs Stripe's dispute fee, which we never see in this table. Flagged in **Risks** for the plan gate.
6. **(d) the forever-block.** Replace the guard at :183-187 with `disputes.countOpenByOrgId(org.getId()) > 0`, so a **closed** dispute (won or lost) stops blocking — lost is handled by step 5. Remove `SettlementRepository.countOpenTransferDisputes` and its two existing tests (`dispute_guard_skips_on_failed_transfer_row`, `failed_payout_settlement_row_is_not_a_dispute`), replacing them with equivalents against the new repository. Keep `ingestDispute`'s annotation of the transfer row for the Payouts UI — it is a read-model, not a gate any more.

### Phase D2 — payout operability + notifications

1. `PayoutRunStatus.BLOCKED` — "needs a human; not retried automatically". `payout_runs.status` is `VARCHAR(16)` with **no** CHECK constraint (`V46:28`), so no migration. Add `BLOCKED` to neither `IN_FLIGHT` nor `ALREADY_TRIGGERED`; verify by reading both constants before changing them.
2. **P1-12.** Split `hasExternalBankAccount` into a tri-state: `HAS`, `NONE`, `UNKNOWN`. `UNKNOWN` (any `StripeException`) ⇒ `log.error` + skip this tick (transient, no row) — today's `return false` conflates it with `NONE`. `NONE` ⇒ persist a `BLOCKED` `PayoutRun` with `failure_reason = "NO_BANK_ACCOUNT"` **once** (guard on an existing BLOCKED run for the event with that reason, so the nightly sweep does not re-notify) and fire the organizer notification.
3. **P1-13.** Before `nextAttempt` mints a new attempt, cap it: when `payoutRuns.maxAttemptByEventId(eventId) >= props.getPayoutMaxAttempts()`, write/flip the run to `BLOCKED` with the last `failure_reason` and notify, instead of retrying. Property `imin.stripe.payout-max-attempts` default `3`.
4. `OrganizerPayoutNotifier` — copy the shape of `SalesMilestoneNotifier` exactly: `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("ticketEmailExecutor")`, in-app `Notification` row for `event.getCreatedBy()`, email to `org.getContactEmail()`, recipient locale from `users.findById(organizerUserId).map(User::getLocale)`, subject via `EmailLocale.choose(locale, en, es, fr, uk)`, template via `renderer.render(name, locale, values)`, failures logged and swallowed. Gate on `NotificationPreferences.isPayoutArrived()` for the arrival notification; **do not** gate the blocked/failed alerts on a preference — a payout that will never arrive is not a marketing notice.
5. Wire `payout.paid` → "payout arrived" in `SettlementIngestService.ingestPayout` by publishing an event consumed by the same notifier, gated on `payout_arrived` (`V8__notification_preferences.sql:9`, already exposed by `NotificationPrefsService:40` and today sending nothing).
6. `DisputeNotifier` on `DisputeOpenedEvent` — same pattern; in-app kind `dispute.opened`, link `/events/{eventId}`, email `dispute-opened`.
7. Email templates: `payout-blocked` and `dispute-opened`, each as base EN `.html` + `.txt` plus `.es`/`.fr`/`.uk` variants — **8 files each**. Copy the markup of `sales-milestone-80.html` so branding does not drift. Placeholders must be identical across all four locales; `EmailTemplateRenderer` throws on an unresolved `{{placeholder}}` and silently falls back to EN on a missing file, which is exactly the failure `BuyerEmailLocaleVariantsTest` exists to catch — add the organizer equivalent (`OrganizerEmailLocaleVariantsTest`) listing both new templates plus the three `sales-milestone-*` ones.

### Phase E — config / docs

1. `.env.example`: replace line 38's `STRIPE_WEBHOOK_SECRET=` with the three names the app actually binds (`application.yaml:157,161,166`): `STRIPE_WEBHOOK_SECRET_V1=`, `STRIPE_WEBHOOK_SECRET_V2=`, `STRIPE_WEBHOOK_SECRET_CONNECT=` (with a one-line "optional; blank ⇒ payout.* is dark" note on the last). Also add `STRIPE_ASYNC_PAYMENT_HOLD_DAYS` and `STRIPE_PAYOUT_MAX_ATTEMPTS` as commented optionals if Phases A and D2 landed.
2. `application.yaml:174`: `return-url-base: ${STRIPE_RETURN_URL_BASE:https://dashboard.imin.wtf}`, matching `imin.email.app-base-url`'s prod-safe default and the reason recorded in `CLAUDE.md` ("prod-safe, like every other outbound-link base here"). Add `imin.stripe.return-url-base: http://localhost:5173` to `application-dev.yaml` next to the existing `imin.stripe.public-return-url-base` override at :36. Also fix `StripeProperties.java:62`'s Java default for the same reason.
3. `application.yaml:158-159` and `StripeWebhookController.java:33-34`: replace the dot-notation `v2.core.account.requirements.updated` / `v2.core.account.recipient.capability_status_updated` with the bracket forms Stripe actually sends and that `StripeWebhookService.V2_ACCOUNT_STATE_TYPES` (:323-328) lists. `StripeWebhookController`'s V1 list also gains the four new subscriptions from Phase A.
4. `CLAUDE.md` Stripe Connect section: add the new V1 subscriptions, `STRIPE_ASYNC_PAYMENT_HOLD_DAYS` / `STRIPE_PAYOUT_MAX_ATTEMPTS`, the corrected `STRIPE_RETURN_URL_BASE` default, and one line each on the P0-1 release rule and the P0-3 single-call refund.
5. **Note for the user, not a code step:** `.env.local` is untracked, so no agent can fix it. Rename `STRIPE_WEBHOOK_SECRET` → `STRIPE_WEBHOOK_SECRET_V1` there by hand (and add `_V2` / `_CONNECT`) before the next local run.

## Verification commands

```bash
cd /Users/ivan/imin/imin-api/.claude/worktrees/stripe-readiness-p0-p1

# The check command from the workspace CLAUDE.md repo table. Run at the end of EVERY phase.
./mvnw test

# Per-phase focus runs while iterating (never a substitute for the full suite):
./mvnw test -Dtest=StripeWebhookServiceTest            # Phase A
./mvnw test -Dtest=StripeConnectControllerTest         # Phase B
./mvnw test -Dtest=StripeRefundServiceTest             # Phase C
./mvnw test -Dtest=SettlementIngestServiceTest         # Phase D1
./mvnw test -Dtest=OrganizerPayoutNotifierTest         # Phase D2

# Next free migration number (currently 124 — take 125+, never reuse; prod runs SPRING_FLYWAY_OUT_OF_ORDER=true)
ls src/main/resources/db/migration | sed 's/^V\([0-9]*\)__.*/\1/' | sort -n | tail -1
```

**Before Phase B ships**, the user must confirm prod has no duplicate connected-account ids — `V126` is a unique index and a violation fails the Railway boot:

```sql
SELECT stripe_account_id, count(*) FROM organizations
 WHERE stripe_account_id IS NOT NULL GROUP BY 1 HAVING count(*) > 1;
```

(Expected empty: `OrganizationRepository.findByStripeAccountId` returns `Optional`, so a duplicate would already be throwing on every mirror sync.)

Contract impact is `none`, so `api:check` in `imin-webapp` is unaffected and no FE work is queued.

## Test impact

Branches are mapped first; each gets **one** test with the minimal setup that forces it. Harness style matches what is already there: Mockito + a real Stripe-shaped signed JSON payload for `StripeWebhookServiceTest` / `StripeRefundServiceTest` / `StripeConnectServiceTest`; `@SpringBootTest` + H2 + Flyway + a faked `StripeResponseGetter` for `SettlementIngestWebhookTest` / `PostEventPayoutServiceTest`. Never proxy-test a private method through a caller — `CheckoutAmountVerifier` and `DisputeStatus.fromStripe` are extracted precisely so they can be tested directly.

### Reproduction tests (one per P0) — must be RED on current code

| P0 | test class path | method | assertion that goes red today |
|---|---|---|---|
| P0-1 | `src/test/java/com/imin/iminapi/stripe/StripeWebhookServiceTest.java` | `paymentFailedDoesNotReleaseWhileRetryable` | `verify(inventoryService, never()).releaseReservation(eq(reservationId), anyString())` after a `payment_intent.payment_failed` whose reservation has **no** `async_processing_at`. Today `StripeWebhookService:450` calls `releaseReservation(reservationId, "WEBHOOK_FAILED")`, so the `never()` fails. |
| P0-2 | `src/test/java/com/imin/iminapi/stripe/StripeConnectControllerTest.java` (new) | `memberCannotMintOnboardingLink` | `assertThatThrownBy(() -> controller.onboardingLink(memberPrincipal, orgId, null)).isInstanceOf(ApiException.class)` with `status() == 403`, plus `verifyNoInteractions(connectService)`. Today the controller calls straight through and the mock returns a URL. |
| P0-3 | `src/test/java/com/imin/iminapi/stripe/StripeRefundServiceTest.java` | `fullRefundDoesNotCreateApplicationFeeRefund` | `verifyNoInteractions(feeRefundSvc)` (and `verifyNoInteractions(chargeSvc)`) after `create(..., appFeeRefundMinor = 149, ...)`. Today `StripeRefundService:87` calls `applicationFees().refunds().create(...)`. |
| P0-4 | `src/test/java/com/imin/iminapi/stripe/SettlementIngestServiceTest.java` (new) | `disputeCreatedRevokesTickets` | after ingesting `charge.dispute.created` for a charge whose PI belongs to a 2-ticket order, `assertThat(tickets).allMatch(t -> Ticket.STATE_REVOKED.equals(t.getState()))`. Today `ingestDispute` (:213-261) only touches the settlements row and the tickets stay `issued`. |

The orchestrator fills the red output below after running them.

```
TODO: red output for StripeWebhookServiceTest#paymentFailedDoesNotReleaseWhileRetryable
TODO: red output for StripeConnectControllerTest#memberCannotMintOnboardingLink
TODO: red output for StripeRefundServiceTest#fullRefundDoesNotCreateApplicationFeeRefund
TODO: red output for SettlementIngestServiceTest#disputeCreatedRevokesTickets
```

### Phase A

`src/test/java/com/imin/iminapi/stripe/StripeWebhookServiceTest.java` (modified):
- `paymentFailedDoesNotReleaseWhileRetryable` — repro, above.
- `paymentFailedReleasesWhenAsyncProcessing` — the async-terminal branch.
- `paymentIntentCanceledReleasesTheHold` — the new terminal path.
- `paymentIntentProcessingExtendsTheHoldInsteadOfReleasing` — asserts `markAsyncProcessing` with an expiry ~7 days out and no release.
- `asyncPaymentFailedReleasesTheHold` / `asyncPaymentSucceededIsANoOp` — the two `checkout.session.async_payment_*` branches.
- `amountMismatchIssuesNothingAndKeepsTheReservation` — asserts `never()` on `confirmSold`, `issuePaidOrder` and `incrementUsedCount`, and that the call returns normally.
- `currencyMismatchIssuesNothing` — the second half of the same gate.
- `connectScopedPaymentIntentSucceededIsIgnored` — P1-7, event JSON carrying `"account": "acct_x"`.
- `platformScopedPayoutIsIgnored` — the mirror branch (`payout.*` with a null account).
- **Two existing tests change meaning and must be rewritten, not deleted:** `paymentIntentFailed_callsReleaseReservation` (:416) becomes the async variant, and `paymentIntentFailed_replayedTwice_releasesOnce` (:428) becomes `paymentIntentCanceled_replayedTwice_releasesOnce` so the dedup contract stays pinned.

`src/test/java/com/imin/iminapi/stripe/CheckoutAmountVerifierTest.java` (new): exact match; promo-discounted match; wrong amount; wrong currency; missing `tier_id`/`qty` ⇒ "skip, do not fail".

`src/test/java/com/imin/iminapi/stripe/StripeCheckoutServiceTest.java` (modified): `ticketLineItemUsesTheTierPriceNotTheStoredStripePrice` — captures `SessionCreateParams` and asserts `unitAmount == tier.priceMinor` and `product == tier.stripeProductId`; plus `fallsBackToProductDataWhenTheProductIdIsNull`.

`src/test/java/com/imin/iminapi/service/event/InventoryServiceTest.java` (modified): `markAsyncProcessingExtendsExpiryAndIsIdempotent` — second call affects 0 rows; `markAsyncProcessingIsANoOpOnAReleasedRow`.

### Phase B

`StripeConnectControllerTest` (new): `memberCannotMintOnboardingLink` (repro), `memberCannotConnect`, `memberCannotMintAccountSession`, `adminCanMintOnboardingLink`, `memberCanStillReadStatus`.
`PayoutServiceStatusRoleTest` (new): `memberGetsNullDashboardUrl` (asserts `fetchDashboardUrl` is never called and `accountLast4` is still populated), `adminGetsDashboardUrl`.
`StripeConnectServiceTest` (modified): `getOrCreateAccountLocksTheOrgBeforeCallingStripe` (asserts `findByIdForUpdate` ran and, when the locked row already has an id, `accounts().create` never did).
`StripeConnectStatusMirrorTest` (modified): `syncSkipsTheWriteWhenRecipientConfigurationIsAbsent` — asserts `orgs.save` is never called and the pre-existing `stripePayoutsEnabled=true` survives.
`PostEventPayoutServiceTest` (modified): `restricted_org_with_transfers_active_is_still_paid_out`, `disabled_org_is_not_paid_out`.

### Phase C

`StripeRefundServiceTest` (modified): `fullRefundDoesNotCreateApplicationFeeRefund` (repro), `refundStillSetsReverseTransferTrueAndRefundApplicationFeeFalse`, `platformFundedRefundSetsReverseTransferFalse`. **Delete** `create_withAppFeeRefund_alsoCallsApplicationFeeRefund` (:98) and `create_chargeWithoutApplicationFee_skipsFeeRefund` (:124) — they pin the removed behaviour.
`RefundServiceTest` (modified): `connectionTimeoutMapsToUpstreamUnavailable`, `rateLimitMapsToUpstreamUnavailable`, `balanceInsufficientRetriesPlatformFunded`, `balanceInsufficientTwiceStillThrows409`, `unknownStripeRefundFullAmountMaterializesAndRevokes`, `unknownStripeRefundPartialAmountMaterializesWithoutRevoking`, `unknownStripeRefundWithNoOrderIsStillANoOp`.
`RefundPlatformFundedTest` (new, `@SpringBootTest` + H2): the column persists and `sumPlatformFundedByOrgId` only counts SUCCEEDED platform-funded rows.
`PostEventPayoutServiceTest` (modified): `fully_refunded_event_pays_out_zero` (guards the retained `appFeeRefundMinor` bookkeeping) and `platform_funded_refund_reduces_the_next_payout`.

### Phase D1

`SettlementIngestServiceTest` (new): `disputeCreatedRevokesTickets` (repro), `disputeWonRestoresTickets`, `disputeCreatedWithNoResolvableOrderStillPersistsTheRow`, `refundedTicketIsNotRevokedByADispute`, `staleDisputeEventDoesNotRewriteState`.
`DisputeIngestServiceTest` (new): `fromStripeMapsWarningStatusesToOpen`, `closedLostIsTerminal`.
`SettlementIngestWebhookTest` (modified): `disputeCreatedThenClosedUnblocksPayouts` — the P0-4(d) end-to-end.
`PostEventPayoutServiceTest` (modified): `open_dispute_blocks_the_payout`, `closed_lost_dispute_no_longer_blocks_but_reduces_the_net`, `won_dispute_restores_the_net`.

### Phase D2

`OrganizerPayoutNotifierTest` (new): `noBankAccountNotifiesOnceAndWritesABlockedRun`, `attemptCapParksTheRunAndNotifies`, `payoutArrivedRespectsThePreference`, `payoutBlockedIgnoresThePreference`.
`DisputeNotifierTest` (new): `disputeOpenedWritesInAppAndEmail`, `emailFailureDoesNotThrow`.
`PostEventPayoutServiceTest` (modified): `stripe_error_on_the_bank_check_skips_the_tick_without_writing_a_row` (the P1-12 `UNKNOWN` vs `NONE` split).
`OrganizerEmailLocaleVariantsTest` (new): parameterized over `{payout-blocked, dispute-opened, sales-milestone-50, sales-milestone-80, sales-milestone-100}` × `{es, fr, uk}`, asserting clean substitution and that the rendered body is genuinely not the English file — the same net `BuyerEmailLocaleVariantsTest` provides for buyer mail.

### Phase E

No new tests. `./mvnw test` must stay green; if any test asserts on `STRIPE_RETURN_URL_BASE`'s default, update it in the same commit.

## Live-test

**Needed: no.**

Nothing here can be proven end-to-end from this environment: the Stripe CLI and a test key are unavailable to the implementers, and prod runs `sk_test_…` on Railway with all three webhook secrets set but no way for an agent to trigger a real decline, dispute, SEPA settlement or payout from here. Every fix is designed to be correct by Stripe's documented destination-charge semantics and is pinned by unit tests.

If the user wants a live pass after ship, the surfaces are, in ascending risk: `POST /api/v1/orgs/{orgId}/stripe/onboarding-link` as a MEMBER (expect `403`, no Stripe call) — this one needs no Stripe at all and is the cheapest real check; then a `stripe trigger payment_intent.payment_failed` followed by `payment_intent.succeeded` on the same PI against a local `stripe listen` (expect one ticket, no `[OVERSOLD]`); then a `stripe trigger charge.dispute.created` in test mode (expect revoked tickets + an organizer email); then a test-mode partial refund from the Stripe Dashboard (expect an `[UNMAPPED_PARTIAL_REFUND]` line and a materialized row).

## Contract impact

**none.**

- No new or renamed path; no new or changed response DTO field. `PayoutsStatusResponse.dashboardUrl` is already nullable by construction and only changes *when* it is null.
- `PayoutRunStatus.BLOCKED`, `ReservationStatus`, `DisputeStatus` and `Refund.platformFunded` are all internal — grepping `src/main/java/com/imin/iminapi/controller/` and `service/payout/` for `PayoutRunStatus` and `ReservationStatus` returns nothing, so none of them reaches a serialized shape.
- The new 403s on the Connect POSTs and the 502/503 on a refund timeout use `ErrorCode` values that already exist (`FORBIDDEN` via `ApiException.forbidden`, `UPSTREAM_UNAVAILABLE` at `security/ErrorCode.java:37`) and the unchanged single-error envelope.
- Therefore no `src/shared/api/types.ts` edit in `imin-webapp`, no `PUBLIC_PAGE_API.md` change (nothing under `/api/v1/public` moves), and `npm run api:check` stays green.

If an implementer finds themselves adding a field to a controller DTO, they must stop and escalate — that would turn this into a two-repo task.

## i18n impact

`imin-api` is not one of the four locale-checked frontends, but organizer email **does** ship EN/ES/FR/UK by convention (`EmailTemplateRenderer` locale fallback + `EmailLocale.choose` + the `.es`/`.fr`/`.uk` template variants, and every one of the 20 existing templates has all four).

Phase D2 adds two organizer emails and therefore **16 template files**: `payout-blocked` and `dispute-opened`, each `.html` + `.txt` in EN (no suffix) + `.es` + `.fr` + `.uk`. Both subjects go through `EmailLocale.choose(locale, en, es, fr, uk)` exactly as `SalesMilestoneNotifier.emailSubject` (:162-181) does. Placeholder names must be byte-identical across all four locales — `EmailTemplateRenderer` throws on an unresolved `{{placeholder}}`, and it falls back to English *silently* on a missing file, so a dropped variant would never surface without `OrganizerEmailLocaleVariantsTest`.

No other phase adds a user-visible string. Log messages and `ApiException` messages are EN-only, per existing practice.

## Blast radius

This task touches money, auth and Flyway. Stated explicitly:

- **Money — refunds (Phase C).** P0-3 changes what Stripe does with real funds on every refund from the moment it deploys. Refunds issued *before* the deploy already over-credited the connected account; this fix does not claw those back, and reconciling them is a separate operational decision (**Risks**). P1-14 introduces the platform paying for a refund out of its own balance — a genuinely new money movement — recovered from the org's next payout.
- **Money — payouts (Phases B, C, D1, D2).** P1-10 widens who gets paid (RESTRICTED orgs now qualify). P0-4(c) and P1-14 both *reduce* payable amounts. P0-4(d) unblocks orgs currently frozen by a closed dispute. Track B is **live in prod** (`STRIPE_PAYOUT_SCHEDULE_MANUAL=true`), so all of this is real money on the next nightly sweep, not dormant code.
- **Money — fulfilment (Phase A).** P1-6 can refuse to issue tickets. A false positive is a paid buyer with no ticket. This is why the gate must skip cleanly on missing metadata and why P1-8 lands first: any tier whose stored Stripe Price has drifted from `priceMinor` would otherwise trip the amount check on in-flight sessions. Sessions created before the deploy and paid after it still carry the old Price — see **Risks**.
- **Auth (Phase B).** Three organizer endpoints move from "any member of the org" to ADMIN+. A MEMBER who today can open the Stripe onboarding flow will get a 403 tomorrow. The dashboard may render a button that now fails; the FE is not in scope for this task.
- **Flyway (Phases A, B, C, D1).** Four new migrations, all additive (three `ADD COLUMN`, one `CREATE TABLE`, one `CREATE UNIQUE INDEX`). No existing migration is edited. Prod runs `SPRING_FLYWAY_OUT_OF_ORDER=true` permanently. **`V126` (the unique index) is the only one that can fail a deploy** — run the duplicate query in **Verification commands** first.
- **Shared modules.** `StripeWebhookService`, `PostEventPayoutService` and `SettlementIngestService` are each touched by 2–4 phases, which is the whole reason the phases are sequential in one worktree. `InventoryService.confirmSold`'s `[OVERSOLD]` path is *not* removed — P0-1 makes it rarer, it stays as the late-webhook safety net.
- **Not touched:** ticket issuance emails, the door scanner contract, the public buyer API, the marketing/campaign engine, AI poster generation.

## Risks

Policy decisions the user must confirm at the plan gate (one line each):

1. **Dispute loss allocation.** Default proposed: the organizer bears the disputed **face value** (subtracted from the event's payout net), the platform absorbs Stripe's dispute fee. Confirm or invert.
2. **Retroactive fee over-credit.** P0-3 fixes refunds from the deploy forward; past refunds left the platform short by the fee share and the organizer up by it. Confirm "leave historical refunds alone" vs "produce a reconciliation report".
3. **Platform-funded refunds (P1-14).** Confirm imin is willing to front a refund out of the platform balance when the connected account is short, and to recover it from that org's next payout as an **org-level** debt that can cross events.
4. **7-day async hold.** `STRIPE_ASYNC_PAYMENT_HOLD_DAYS=7` keeps seats out of the pool for up to a week on SEPA/iDEAL/Klarna. Confirm 7, or pick another number.
5. **Card declines no longer free the seat immediately.** After P0-1 a declined card holds its seat until the 30-minute session expiry or the sweeper. On a hot on-sale this measurably reduces available inventory for up to 30 minutes. Confirm this is preferred over the oversell it replaces.
6. **RESTRICTED orgs become payable (P1-10).** Confirm that "can sell" should imply "can be paid" even while requirements are outstanding, rather than tightening the *checkout* gate instead.
7. **MEMBER loses the Stripe onboarding buttons.** Confirm ADMIN+ (not OWNER-only) is the right bar, and that a follow-up `imin-webapp` task to hide the buttons for MEMBER is acceptable rather than required in lockstep.
8. **Amount-mismatch policy.** Confirm "log, hold the reservation, issue nothing, no auto-refund" — a mismatched payment leaves the buyer charged with no ticket until an operator acts.
9. **Payout attempt cap = 3.** Confirm the number and that a capped run parks as `BLOCKED` requiring a human, rather than backing off exponentially.
10. **P1-7 scope narrowed.** `transfer.*`, `charge.refunded` and `charge.dispute.*` are deliberately **not** gated on `event.getAccount() == null`, because the settlements ingest is built to accept both scopes and a passing test pins the connected-account path. Confirm this narrower gate.

Execution risks (no user decision needed, but they shape the phases):

11. **>15 files.** 71 files across six phases; hence the sequential split. Do not let one worker take two phases in one commit.
12. **Migration collision.** Six phases each numbering their own migration; `V125`–`V128` are reserved here in phase order. A worker must re-run the `tail -1` command before creating one.
13. **Existing tests that pin the old behaviour** must be rewritten, not deleted, in the same commit: `StripeWebhookServiceTest:416,428`, `StripeRefundServiceTest:98,124`, `PostEventPayoutServiceTest#dispute_guard_skips_on_failed_transfer_row` and `#failed_payout_settlement_row_is_not_a_dispute`.
14. **In-flight sessions across the P1-8/P1-6 deploy.** A hosted session created before the deploy carries the old Stripe Price; if that Price has drifted from `priceMinor`, the new amount check refuses fulfilment. The 30-minute session TTL bounds the window; the `[AMOUNT_MISMATCH]` log is the signal.
15. **Double-subtraction of platform-funded refund debt (P1-14).** The org-level sum is recomputed each tick and is not marked "settled"; verify with a test that a debt absorbed by one payout does not suppress the next one twice, or add a settled-at marker.
16. **`refunds.initiated_by_user_id` is `NOT NULL`.** P1-15 materializes a refund with no initiating user; read `V28__refunds.sql` first and choose between a fixed system UUID and a nullable-widening migration before writing the code.

## Definition of done

- All four reproduction tests were RED on `3d88ec6` (output pasted into **Test impact**) and are GREEN at the end of their phase.
- `./mvnw test` is green from the worktree root at the end of **every** phase, not just the last — a phase must be shippable alone.
- Every branch listed in **Test impact** has exactly one test; every regression test asserts every statement its fix added.
- No private method is proxy-tested through a caller.
- Four migrations exist, all additive, none editing an earlier file, numbered above `124`, and the prod duplicate-account query returned empty before `V126` ships.
- Both new organizer emails have all four locales for both extensions (16 files) and `OrganizerEmailLocaleVariantsTest` proves each localized body differs from the English one.
- `docs/superpowers/specs/2026-05-25-stripe-refunds-design.md` decisions #2 and #8 match the shipped behaviour.
- `CLAUDE.md`'s Stripe Connect webhook list matches the `switch` in `StripeWebhookService.handleV1Transactional` exactly.
- `.env.example` names only variables `application.yaml` actually binds.
- Contract impact is still `none`: no controller DTO gained or lost a field.
- Comments added are 1–2 lines, reference no ticket; no AI attribution anywhere; no changelog file; every cleanup inside a `catch` is itself wrapped.
- All ten policy decisions in **Risks** were answered by the user before Phase C, D1 or D2 was implemented (Phases A, B and E do not depend on them, except decisions 4, 5, 6, 7, 8 and 10).

## Live-test evidence

## Review rounds

- round 1 → FIX_REQUIRED (1 CRITICAL: reconciler bypasses amount gate; 4 HIGH: warning_closed maps OPEN, platform-funded recovery moves no money, verifier uses live price, findByIdForUpdate returns cached instance; 5 MEDIUM; 5 LOW)
- round 2 → FIX_REQUIRED (1 HIGH: reversal marker written in the payout tx, lost on rollback → possible double reversal after the 24h idempotency window; 5 MEDIUM; 4 LOW; all round-1 items verified fixed)
- round 3 → APPROVED (all round-2 items verified fixed; 3 LOW: sweeper test ShedLock release in @BeforeEach [fixed post-review], recoverForOrg holds a tx across Stripe calls [accepted at current volume], webapp types.ts dashboardUrl non-nullable [follow-up webapp task]). Ship notes: recovery reversal path + amount_off coupon never run against real Stripe — test-mode probe before enabling in prod; V126 needs the prod duplicate-account query to return empty first.
