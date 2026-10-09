# Payout never includes money committed to an open refund

## Goal

The post-event payout (`PostEventPayoutService.payOneEvent`) sizes its net from
`OrderRepository.settlementRowsByEventId` (`OrderRepository.java:87-98`), which joins only
`RefundStatus.SUCCEEDED` refunds (`:93`). A refund still `REQUESTED` (two-phase attempt, Stripe
outcome unknown, `RefundAttemptStore.open` / `RefundAttemptReconciler`) or `PENDING` (Stripe
accepted, not settled; written by `RefundAttemptStore.java:65` and `StripeWebhookService.java:734`)
is invisible to the net, so the event can be paid out in full and the refund then lands on a
drained connected balance: `balance_insufficient` → `platform_funded`, imin fronts it and claws it
back by transfer reversal in a later tick (`PostEventPayoutService.recoverPlatformFundedRefunds`,
`:720-787`), driving the connected balance negative.

Goal: no payout is created for an event while any of its live orders carries a REQUESTED or
PENDING refund. The amount an organizer is eventually paid is unchanged in every outcome; only the
day it is paid moves.

## Options and decision

**(a) Subtract REQUESTED+PENDING from the net.** Pays the organizer less now; if the refund later
FAILS/CANCELS the difference must be paid in a later payout. That later payout does not exist:
`EventRepository.findPayoutCandidates` (`EventRepository.java:606-629`) drops an event for good once
it has a `PAID` run (`:619-623`), and a run reconciles to `PAID` whenever `remaining_minor = 0`
(`PostEventPayoutService.java:583`, and the webhook path). The released money would sit on the
connected balance with no automatic payout (manual schedule). Making it work means booking the open
refund into `remaining_minor` so the run settles `PARTIAL`, which changes what PARTIAL/remaining
mean for the clamp, the retention monitor and the organizer emails. Rejected: it changes what an
organizer is paid in the FAILED case.

**(b) Hold the event's payout while it has an open refund.** Skip the tick before anything is
written (no run row, no idempotency key spent); the event is still a candidate next tick because
nothing excluded it. When the refund resolves, the existing net applies unchanged: SUCCEEDED is
subtracted as today, FAILED/CANCELED never was. Cost: a delay. Cap/alert: an open refund cannot be
auto-released (paying it out is the bug), so the cap is an alert, the same WARN-then-ERROR rule the
unstamped-order hold uses (`PostEventPayoutService.java:286-296`): WARN while every open refund is
younger than `STRIPE_PAYOUT_BUFFER_DAYS` (3, `StripeProperties.java:74`), ERROR once one is older.
REQUESTED rows already self-resolve through the reconciler (`[REFUND_UNRESOLVED]` ERROR from attempt
12, `RefundAttemptReconciler.java:59`); the 75-day `PayoutRetentionMonitor` is the outer backstop.

**(c) Combination.** No benefit over (b) once (a) is rejected.

**Decision: (b).** It fits the brief without other payout behaviour changes.

Scope details:
- **Event level, live orders only.** Same scope as the net: `o.eventId = :eventId and o.testMode =
  false` (V130). A test-era refund the live reconciler never picks up (`findUnresolvedAttempts`
  filters by the running mode, `RefundRepository.java` `findUnresolvedAttempts`) must not hold a
  mixed event's live sales forever — the same reason `existsBlockedNeedingAHuman` ignores test runs.
- **A RETRYING replay is not held.** A RETRYING run is a payout whose creation may already have
  happened; the next tick replays the same key and amount (`PostEventPayoutService.java:434-441`,
  `nextAttempt` `:634-642`). Holding it changes nothing about the amount (fixed on the row) and only
  delays resolving an unknown-outcome payout past Stripe's idempotency-key retention.
- **Placement:** after step 1b, before step 2 (net). So the recoveries in step 0b still run, and no
  no-bank BLOCKED row (`parkNoBankAccount`) is written with a net that includes the open refund.

## Writers / readers audit

Writers of `refunds.status` (who moves a row into or out of REQUESTED/PENDING):
- `RefundAttemptStore.open` (REQUESTED insert), `RefundRepository.recordOutcome` (REQUESTED → Stripe
  status), `recordRefusal` (REQUESTED → FAILED), `updateStatusIfCurrent` (webhook transitions),
  `StripeWebhookService.java:734` (`refund.updated`/`refund.failed`), `RefundAttemptReconciler`.
  None changes; the hold only reads.

Readers of the payout net / candidates:
- `OrderRepository.settlementRowsByEventId` (`:87-98`) — net; unchanged.
- `EventRepository.findPayoutCandidates` — unchanged; a held event stays a candidate.
- `PayoutRetentionMonitor` — unchanged; a long hold surfaces there at 75 days.
- `SettlementIngestService` — read-model only, moves no money; unaffected.
- `DisputeWithholding`, `RefundRecoveryMarker`, `DisputeRecoveryMarker` — unaffected; recoveries
  run in step 0b before the hold.
- Idempotency: the hold returns before step 5, so no `payout_runs` row and no `evt:<id>:attempt:<n>`
  key is written.

New readers: `RefundRepository.countLiveOpenByEventId(eventId)` and
`countLiveOpenByEventIdCreatedBefore(eventId, before)` (the buffer cutoff, for the ERROR split).

## Steps

1. Tests first (below), run them against the unfixed code, record red.
2. `RefundRepository`: add `countLiveOpenByEventId` and `countLiveOpenByEventIdCreatedBefore`
   (REQUESTED/PENDING, live orders of the event; the second adds `r.createdAt < :before`).
3. `PostEventPayoutService.payOneEvent`: new step 1c after step 1b — unless the event has a RETRYING
   run, count open refunds; if > 0 log (WARN, or ERROR when any is older than the payout buffer) and
   return.
4. Class Javadoc: one line for step 1c. `OrderSettlementRow` doc unchanged.
5. `imin-api/CLAUDE.md` § Refund attempts: replace "A REQUESTED refund is not yet in the payout net
   (only SUCCEEDED is), as with PENDING." with the hold rule.
6. Run targeted tests.

## Tests (all in `PostEventPayoutServiceTest`, `@IminIntegrationTest`, shared Stripe fake)

1. `an_open_refund_holds_the_payout` — parameterized over {REQUESTED, PENDING} × {inside buffer →
   WARN, older than buffer → ERROR}: no payout request, no run row, the hold logged at that level.
   Red on unfixed code: a 9_000 payout is created.
2. `a_refund_that_later_fails_releases_the_full_net` — tick 1 with a REQUESTED refund pays nothing;
   the row turns FAILED; tick 2 pays the full 9_000. Red on unfixed code: tick 1 pays.
3. `an_open_refund_on_a_test_mode_order_does_not_hold_the_live_payout` — guard for the `testMode`
   filter; proved red by removing the filter once.
4. `an_open_refund_does_not_delay_replaying_a_retrying_run` — guard for the RETRYING exemption;
   proved red by removing the exemption once.
5. No-refund case: covered by the existing `payout_amount_excludes_application_fee` (net 8_500 with no
   refunds), which runs in the targeted suite; not duplicated.

Time: the hold's age split uses `Instant.now()` like `stuckBefore()` (`:1093-1095`), so fixtures are
placed relative to `Instant.now()` as the existing buffer tests do; `MutableClock` would not reach it.

## Risks

- **Payout delay.** Any event with an open refund waits. REQUESTED resolves within minutes via the
  reconciler; PENDING resolves on Stripe's webhook (card refunds usually seconds; some methods days).
- **Stuck PENDING.** There is no poller for a PENDING refund whose webhook was missed; such a row
  holds the event indefinitely with a nightly ERROR. That is the intended alert; the follow-up is a
  stale-PENDING poller (out of plan).
- **Pre-V179 REQUESTED rows** (no `stripe_attempt_at`) are never reconciled; one on a live order
  would hold its event with a nightly ERROR. Check prod before deploy (below).
- **Org pool.** The hold is per event, like the net. An event already PAID that gets a new refund is
  not covered (pre-existing behaviour, platform-funded + recovery path).

## Prod rollout check

Before shipping, against prod (read-only):

```sql
select r.id, r.status, r.created_at, r.stripe_attempt_at, o.event_id, o.org_id
  from refunds r join orders o on o.id = r.order_id
 where r.status in ('REQUESTED','PENDING') and o.test_mode = false
 order by r.created_at;
```

Every row listed holds its event's next payout. Rows older than a few days need a human (resolve in
Stripe, or mark FAILED if Stripe has no refund) before or right after deploy. After deploy, watch the
03:00 Europe/Amsterdam tick log for `open refund` WARN/ERROR lines.

## Review fix: candidate starvation

`PostEventPayoutSweeper` read only the first 50 candidates by `endsAt` (`PostEventPayoutSweeper.java:103`).
Held, net-0, disputed or unstamped events write no run and keep their place, so 50 of them starved every
newer event, and the open-refund hold adds to that set. The sweep now pages by keyset `(endsAt, id)`
through `EventRepository.findPayoutCandidatesAfter` (`findPayoutCandidates` is its first page), at most
20 pages (1000 events) per tick, with a WARN when more remain. Test:
`a_full_page_of_skipped_events_does_not_starve_a_payable_one_in_the_same_tick` (51 skipped events ahead of
one payable one); red on the single-page sweep.
