# Dispute attribution converges regardless of webhook order

Slug `dispute-before-order-race` · Mode: plan (implement after the plan gate) · Notion: no tracker access from this agent — card left to the main session.

## Goal and scope

A `charge.dispute.created` that lands before `payment_intent.succeeded` writes a `disputes` row with `order_id = NULL` (`DisputeIngestService.ingest`, `resolveOrgId` falling back to the charge's transfer destination) and revokes nothing — `revokeTickets(null)` returns 0. Nothing ever re-runs, so the buyer keeps a live QR on a charged-back order. Prod 2026-09-13: dispute delivered 20:13:14, order `a5dc9f70` written 20:13:20.

In scope: late attachment at order creation, a bounded sweep for every other ordering (and for the orphan already in prod), truthful organizer copy, correct `test_mode` on late attachment. Out of scope: the dispute state machine, `settlements`, payouts math.

**Design — (d) + (b); (c) rejected.** (d) converges in seconds on the observed race and is free: both order-writing paths (`StripeWebhookService.onPaymentIntentSucceeded` and `PaidFulfilmentReconciler`) funnel through `PaidCheckoutService.issuePaidOrder`, so one call site covers both. (b) is ~30 lines and one DB query per 5 min; it covers a dispute ingested while the charge was unreadable and retro-fixes the prod orphan without hand SQL. (c) is rejected on facts: a throw rolls the `WebhookEventDedupService` INSERT back together with the settlements annotation (`handleV1Transactional` is one REQUIRED transaction); Stripe's retry backoff is minutes-to-hours, not seconds, so the ticket stays live meanwhile; and when the charge genuinely is not ours it 500s until Stripe gives up and **no** dispute row is ever written — losing the org-level payout freeze (`countOpenByOrgId`) that the unattributed row provides today.

## Repos in ship order

| # | repo | dir | base | check command | deploy on push |
|---|---|---|---|---|---|
| 1 | api | `imin-api` | `master` | `./mvnw test` | Railway ≈3 min |

No FE repo: nothing here crosses the `/api/v1` contract.

## Affected files (per repo)

`imin-api`, worktree `/Users/ivan/imin/imin-api/.claude/worktrees/dispute-before-order-race` — 14 files:

- `src/main/java/com/imin/iminapi/dispute/DisputeRepository.java` — orphan finders + conditional attach UPDATE
- `src/main/java/com/imin/iminapi/dispute/DisputeIngestService.java` — new `attachOrphansForOrder` / `attachOrphan`
- `src/main/java/com/imin/iminapi/dispute/DisputeAttributionSweeper.java` — **new**
- `src/main/java/com/imin/iminapi/dispute/DisputeNotifier.java` — truthful consequence copy
- `src/main/java/com/imin/iminapi/service/ticket/PaidCheckoutService.java` — one call after the ticket loop
- `src/main/resources/db/migration/V131__disputes_payment_intent_id_index.sql` — **new**
- `src/main/resources/email-templates/dispute-opened{,.es,.fr,.uk}.{html,txt}` — 8 files
- tests: `dispute/DisputeIngestServiceTest.java`, `dispute/DisputeNotifierTest.java`, `dispute/DisputeAttributionSweeperTest.java` (**new**), `service/ticket/PaidCheckoutServiceTest.java`

## Ordered steps

1. **`DisputeRepository`** — add `List<Dispute> findByStripePaymentIntentIdAndOrderIdIsNull(String pi)`; `List<Dispute> findByOrderIdIsNullAndStripePaymentIntentIdIsNotNullAndCreatedAtAfter(Instant cutoff, Pageable p)` (bounded, `PageRequest.of(0, 200)`); and `@Modifying(clearAutomatically = false, flushAutomatically = true) @Query("update Dispute d set d.orderId = :orderId, d.eventId = :eventId, d.orgId = :orgId, d.testMode = :testMode, d.updatedAt = :now where d.id = :id and d.orderId is null") int attachToOrder(...)` — `clearAutomatically = false` because this runs inside paid fulfilment and clearing would detach the order, tickets, tier and reservation that transaction still owns. The `and d.orderId is null` is the race guard between steps 3 and 4. `updatedAt` is passed explicitly (`@PreUpdate` does not fire on a bulk update), and the caller must not `save(row)` afterwards — the bulk UPDATE bypasses the persistence context and the in-memory entity is stale.
2. **`DisputeIngestService`** — add `@Transactional(propagation = MANDATORY) public int attachOrphansForOrder(Order order)` and `public boolean attachOrphan(Dispute row, Order order)`, both funnelling into one private `attach`:
   - no-op when `order.getStripePaymentIntentId()` is null/blank; 0 rows updated ⇒ another path won ⇒ skip silently.
   - `attachToOrder(row.getId(), order.getId(), order.getEventId(), order.isTestMode(), Times.nowMicros())` — **`test_mode` from the ORDER**, not `stripeProps.isLiveKey()`: the order records whether the money was real, and a post-cutover sweep attaching a test-era orphan under a live key would otherwise withhold real face value via `sumOpenOrLostLiveMinorByEventId`.
   - on a win, revoke through the existing private `revokeTickets(order, disputeId)` when the row's status is `OPEN` **or** `LOST` (a late-attached LOST dispute was never revoked at OPEN and the money is gone); `WON` / `WITHDRAWN_REINSTATED` revoke nothing.
   - **publishes no `DisputeOpenedEvent`** — `ingest` already published it on the transition into OPEN, so the organizer gets exactly one alert whichever path attaches. Say so in the javadoc.
3. **`PaidCheckoutService.issuePaidOrder(pi, prepared)`** — inject `DisputeIngestService`; after the `tickets.save(t)` loop and before `publisher.publishEvent(new TicketsIssuedEvent(...))`, call `disputeIngest.attachOrphansForOrder(order)` and log when it attaches. `MANDATORY` holds on both callers: the webhook runs inside `handleV1Transactional`, the reconciler through the `@Transactional` one-arg overload. No bean cycle — `DisputeIngestService` depends on repositories plus `SettlementIngestService` (settlements/org/payout-run repos, `StripeClient`, publisher), none of which reach into `service.ticket`.
4. **`DisputeAttributionSweeper`** (new, `com.imin.iminapi.dispute`) — `@Scheduled(fixedDelay = 300_000, initialDelay = 120_000)` + `@SchedulerLock(name = "DisputeAttributionSweeper.sweep", lockAtLeastFor = "PT30S", lockAtMostFor = "PT5M")` + `@Transactional`; window `Duration.ofDays(3)` on `created_at` (always non-null, unlike `opened_at`); per row `orders.findByStripePaymentIntentId(...)` then `attachOrphan`. Pure DB work, no Stripe call, so it is safe on the four-thread shared scheduler pool. One summary log line, only when something attached.
5. **`V131__disputes_payment_intent_id_index.sql`** — `CREATE INDEX disputes_pi_idx ON disputes (stripe_payment_intent_id);`. Plain, not partial: tests run Flyway on H2, which has no partial indexes. Keeps step 3 an index probe on the checkout hot path as the table grows.
6. **`DisputeNotifier`** — inject `TicketRepository`; build a `consequenceLine` localized in Java via `EmailLocale.choose(locale, en, es, fr, uk)`, exactly as `subject(...)` already is, with three branches: (a) `orderId == null` — could not be matched to an order yet, and the tickets on it are revoked automatically the moment we match it; (b) `orderId != null` and revoked count > 0 — the N tickets on that order are revoked and will not open the door; (c) `orderId != null` and count 0 — those tickets were already refunded, nothing left to revoke. Count via `tickets.findByOrderId(orderId)` filtered on `Ticket.STATE_REVOKED` at send time. Use the same sentence for the in-app `Notification` body, which today asserts revocation unconditionally. ASCII apostrophes only (HTML values are escaped).
7. **Templates ×8** — replace the revocation sentence with `{{consequenceLine}}` in the HTML preheader and subhead and in the `.txt` second paragraph, for en/es/fr/uk. `EmailTemplateRenderer` **throws** on a placeholder with no value, so every render path must supply it. Same pass: reword the two event-scoped claims ("withheld from this event's payable balance") to org-scoped ones — an unattributed dispute has no event and withholds from no event net; only the org-wide freeze applies.

## Verification commands

```
cd /Users/ivan/imin/imin-api/.claude/worktrees/dispute-before-order-race
./mvnw test                                            # the gate
./mvnw test -Dtest='DisputeIngestServiceTest,DisputeNotifierTest,DisputeAttributionSweeperTest'
./mvnw test -Dtest='PaidCheckoutServiceTest,PaidCheckoutDuplicateKeyTest,SettlementIngestServiceTest'
```
Run `./mvnw test` once on untouched `origin/master` first — a gate already red there is its own card.

## Test impact

- `src/test/java/com/imin/iminapi/service/ticket/PaidCheckoutServiceTest.java` — **repro, red on current code**: `disputeArrivingBeforeTheOrderIsAttachedWhenTheOrderIsCreated`. Seed an orphan OPEN `Dispute` (`order_id` null, `stripe_payment_intent_id = pi_x`), call `service.issuePaidOrder(piFor("pi_x"))`, assert the row now carries `orderId` + `eventId`, every ticket is `revoked`, and `isTestMode()` equals the order's.
- `src/test/java/com/imin/iminapi/dispute/DisputeIngestServiceTest.java` — one Mockito test per attach branch, no async: revokes on OPEN; revokes on LOST; does not revoke on WON; stamps `test_mode` from the order and not from a live running key; a 0-row conditional update revokes nothing; `verify(publisher, never()).publishEvent(any(DisputeOpenedEvent.class))` on the attach path.
- `src/test/java/com/imin/iminapi/dispute/DisputeAttributionSweeperTest.java` (new) — attaches an orphan whose order now exists; a second run changes nothing; skips a row with no PI id; skips a row older than the 3-day window.
- `src/test/java/com/imin/iminapi/dispute/DisputeNotifierTest.java` — the three copy branches assert the exact sentence, plus one non-EN locale per branch proving `EmailLocale.choose` picked the localized string (the renderer falls back to EN silently, so "it rendered" proves nothing). Existing `unattributedDisputeEmailsWithoutAnInAppRow` also asserts wording (a).
- `SettlementIngestServiceTest.disputeCreatedWithNoResolvableOrderStillPersistsTheRow` stays green — an orphan still writes its row and still blocks the org.

## Live-test

Prod, Stripe sandbox, after the Railway deploy is live:
1. Buy one ticket on a test event via `app.imin.wtf`, confirming the PaymentIntent with `pm_card_createDispute` so `charge.dispute.created` is delivered **before** `payment_intent.succeeded` (the ordering observed at 20:13:14 vs 20:13:20 on 2026-09-13).
2. Within 60 s: the order exists, the `disputes` row carries `order_id`/`event_id`, the ticket reads `revoked`, and `/order/<token>` shows no usable QR.
3. Exactly one chargeback email reaches the org contact inbox, with branch-(a) copy.
4. The pre-existing prod orphan (order `a5dc9f70`) is attached by the sweep within 5 min of boot and its ticket flips to `revoked`.

## Contract impact

**None — confirmed.** `DisputeRepository` is `@RepositoryRestResource(exported = false)`; no `Dispute` type appears under `controller/` or `dto/`; no endpoint, request or response schema moves. Nothing to look for in the prod OpenAPI, no `src/shared/api/types.ts` edit, no `PUBLIC_PAGE_API.md` change.

## i18n impact

Organizer email + in-app copy change, so four locales move in this task: `src/main/resources/email-templates/dispute-opened.{html,txt}`, `dispute-opened.es.{html,txt}`, `dispute-opened.fr.{html,txt}`, `dispute-opened.uk.{html,txt}`, plus the EN/ES/FR/UK sentences themselves in `DisputeNotifier` via `EmailLocale.choose`. Subject unchanged. No webapp/public/fan-app strings, so no `check:i18n` here.

## Blast radius

- **Money path.** Step 3 adds one indexed lookup plus a conditional UPDATE inside the `payment_intent.succeeded` transaction, which already holds `SELECT … FOR UPDATE` on the tier. No Stripe call is added inside that lock.
- **Payouts.** `countOpenByOrgId` ignores `order_id`, so the org freeze is unchanged — it already worked for orphans. What changes is per-event: an attached OPEN/LOST dispute now subtracts its face value in `PostEventPayoutService` step 2 (`sumOpenOrLostLiveMinorByEventId`). Re-stamping `test_mode` from the order can move a row into or out of that live sum.
- **Flyway V131** — index only, no data. `SPRING_FLYWAY_OUT_OF_ORDER=true` is permanent on Railway.
- **Emails** — every organizer chargeback email and in-app row changes wording in four languages.

## Risks

- A revoked order still gets the buyer issuance email: `TicketIssuanceEmailer` reads `findByOrderIdOrderByCreatedAtAsc` with no state filter, so the buyer receives a QR the door already rejects and the wallet 409s on. **Separable concern — follow-up card** ("suppress the issuance email when every ticket on the order is revoked"); folding it in would put a new branch on the buyer email path inside a dispute fix. `MetaCapiOutboxWriter.writeForOrder` firing a purchase for a disputed order belongs on the same card.
- A dispute whose charge was unreadable at ingest carries neither PI nor charge id — no path can attach it. Both log it; resolving it needs a Stripe read and is deliberately out of scope.
- Any JPA query in step 3 auto-flushes the pending Order/Ticket inserts, so a duplicate-PI race raises its `DataIntegrityViolationException` at the attach call rather than at commit — same transaction, same rollback, same Stripe retry, but re-run `PaidCheckoutDuplicateKeyTest` deliberately.
- 14 files, one concern, one repo: no split proposed.

## Definition of done

- `./mvnw test` green in the worktree; the repro test is demonstrably red without the step-3 call.
- Dispute-before-order attaches, revokes and notifies exactly once; dispute-after-order is byte-identical.
- The sweep attaches an orphan and is idempotent; late attachment takes `test_mode` from the order.
- Organizer copy names only consequences that happened, in EN/ES/FR/UK; no template renders a `{{`.
- `PostEventPayoutService` sees the attached dispute as OPEN for the org and withholds its face value.

## Live-test evidence

## Review rounds

- round 1 → FIX_REQUIRED (1 HIGH: ingest back-fills order_id on a later delivery without revoking, row then invisible to both attach paths; 1 MEDIUM: clearAutomatically=true detaches the fulfilment persistence context; 4 LOW)
- round 2 → APPROVED (all round-1 items verified fixed; 3 LOW: org_id symmetry on the attachedNow path [unreachable by construction, fixed post-review for symmetry], overclaiming comment in issuePaidOrder [fixed], alert never becomes truthful after late attachment [accepted, follow-up card with the issuance-email suppression])
