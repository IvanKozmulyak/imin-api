# Ticket revocation converges for disputes that already carry an order

Slug `dispute-revoke-convergence` · Mode: plan (implement after the plan gate) · Notion: no tracker access from this agent — card left to the main session.

## Goal and scope
`b7ae6d5` closed the revoke gap only for deliveries arriving **after** its deploy. A dispute attributed before it — prod `du_1UFJm12I8K3TBNXzr4uUUx9O` on order `a5dc9f70`, orphaned 2026-09-13 20:13 then back-filled by a 14f2ed7-era `ingest` — is unreachable by every path: `DisputeAttributionSweeper.sweep` and `attachOrphansForOrder` both select `order_id IS NULL`; `attachedNow` needs `wasUnattributed`; a resent webhook lands in `ingest`'s final `else` ("no state change … nothing to do", `DisputeIngestService.java:183`). The order renders `disputed` while its ticket is still `issued` and scannable.
In scope: a second idempotent pass on the existing sweep that revokes for **attributed** withholding disputes, the same revoke on `ingest`'s no-state-change branch, and a finder that makes the pass a no-op query when nothing is live. Out of scope: the dispute state machine, restore, `settlements`, payouts math, any migration, any alert.

## Repos in ship order
| # | repo | dir | base | check command | deploy on push |
|---|---|---|---|---|---|
| 1 | api | `imin-api` | `master` | `./mvnw test` | Railway ≈3 min |

No FE repo: nothing here crosses the `/api/v1` contract.

## Affected files (per repo)
`imin-api`, worktree `/Users/ivan/imin/imin-api/.claude/worktrees/dispute-revoke-convergence` — 6 files: `dispute/DisputeRepository.java` (new finder), `dispute/DisputeIngestService.java` (public `revokeAttributed` + the no-state-change belt), `dispute/DisputeAttributionSweeper.java` (pass 2); tests `dispute/DisputeRepositoryWithholdingTest.java`, `dispute/DisputeIngestServiceTest.java`, `dispute/DisputeAttributionSweeperTest.java`.

## Ordered steps
1. **`DisputeRepository`** — bounded finder whose predicate mirrors `revokeTickets`' skip rule exactly, so a row returns iff the pass would change something:
   ```java
   @Query("""
           select d from Dispute d
            where d.orderId is not null and d.status in :statuses and d.createdAt > :createdAfter
              and exists (select 1 from com.imin.iminapi.model.Ticket t
                           where t.orderId = d.orderId and t.state not in ('refunded', 'revoked'))
           """)
   List<Dispute> findAttributedWithLiveTickets(@Param("statuses") Collection<DisputeStatus> statuses,
                                               @Param("createdAfter") Instant createdAfter, Pageable pageable);
   ```
   `not in ('refunded','revoked')` rather than `in ('issued','redeemed')` on purpose: `Ticket.java:55` documents legacy rows carrying `pre` as a synonym for `issued`, and an enumerating predicate would leave those scannable forever. The cross-package entity reference is fully qualified, as `TicketRepository.countRevokedInDisputedOrders` already does. Statuses come from `DisputeWithholding.STATUSES` (OPEN|LOST) — never a second literal list. No new index: `disputes` is tiny and this runs once per 5 min.
2. **`DisputeIngestService`** — extract the withholding guard into a private `int revokeIfWithholding(Dispute row, Order order)` (null-safe, returns 0 unless `DisputeWithholding.STATUSES.contains(row.getStatus())`) delegating to the existing private `revokeTickets`; expose `@Transactional(propagation = MANDATORY) public int revokeAttributed(Dispute row, Order order)` over it — MANDATORY like every other entry point here, because the sweep and the webhook dispatch both already own a transaction.
3. **Same file, the belt** — replace `ingest`'s final `else` body with `int revoked = revokeIfWithholding(row, order);` plus a log naming the count, keeping the existing "no state change" INFO when it is 0. Call the **private** helper, not `revokeAttributed`: a self-call bypasses the proxy and would only look transactional. Two lines, idempotent, so a manual Stripe resend fixes any row without waiting for a sweep. WON/WITHDRAWN_REINSTATED replays still revoke nothing (the guard rejects them), and no `DisputeOpenedEvent` is published here — one chargeback stays one alert.
4. **`DisputeAttributionSweeper.sweep`** — extract pass 1 into a private method so its `if (orphans.isEmpty()) return;` cannot skip pass 2, then add pass 2: same `cutoff` and `BATCH_SIZE`, `disputes.findAttributedWithLiveTickets(DisputeWithholding.STATUSES, cutoff, PageRequest.of(0, BATCH_SIZE))`, resolve `orders.findById(row.getOrderId())`, call `ingest.revokeAttributed(row, order)`, count the orders where it returned > 0, and `log.warn("[dispute-sweep] revoked tickets on {} attributed dispute(s) …")` only when that count is > 0. No new alert and no `disputes` write — the dispute row is already correct; only ticket state was missing.

## Verification commands
`./mvnw test` — run once on the untouched worktree first; a gate already red there is its own card, not this diff. While iterating: `./mvnw test -Dtest=DisputeAttributionSweeperTest+DisputeIngestServiceTest+DisputeRepositoryWithholdingTest`.

## Test impact
`DisputeAttributionSweeperTest` (reuse the `releaseSweepLock()` ShedLock `@BeforeEach` rewind — `lockAtLeastFor = PT30S` otherwise silently skips every later tick): **`revokesTicketsOnADisputeAlreadyAttachedToItsOrder`** is the repro — attributed OPEN dispute + `issued` ticket ⇒ `revoked`, **red on current code** (no pass 2, ticket stays `issued`); `leavesAWonAttributedDisputesTicketsAlone` (WON + `issued` ⇒ still `issued`); `ignoresAnAttributedDisputeOlderThanTheWindow` (`createdAt` 4 days back ⇒ untouched).
`DisputeRepositoryWithholdingTest` (`@DataJpaTest`, executes the real JPQL — the "no write" branch proven where it is decided): `findsAnAttributedWithholdingDisputeWhoseTicketsAreStillLive` (includes a `pre`-state ticket, which must be found); `skipsAnOrderWhoseTicketsAreAllRevokedOrRefunded` (empty result ⇒ the converged case does no work at all); `skipsAWonAttributedDispute`.
`DisputeIngestServiceTest` (mocks, existing `setUp`): **`aReplayOnAnAttributedOpenDisputeRevokesTheStillLiveTickets`** — second red test, today the `else` branch never calls `saveAll`; `aReplayOnAnAttributedOpenDisputePublishesNoSecondDisputeOpenedEvent` (`verify(publisher, never()).publishEvent(any(DisputeOpenedEvent.class))`); `aReplayOnAWonDisputeRevokesNothing` (`verify(tickets, never()).saveAll(anyList())`).

## Live-test
After the Railway deploy, dispute `du_1UFJm12I8K3TBNXzr4uUUx9O` / order `a5dc9f70`: within ~7 min (5-min `fixedDelay` + 2-min `initialDelay` on a fresh JVM) the order's ticket must read `revoked` on `GET /events/{id}/orders`, the order stays `disputed` with `dispute.status=open`, and Railway logs show one `[dispute-sweep] revoked tickets on 1 attributed dispute(s)` WARN with **no** repeat on the following tick.

## Contract impact
None. No endpoint, schema, `PUBLIC_PAGE_API.md` or `src/shared/api/types.ts` change; `EventOrdersController` already renders `revoked` tickets and the `disputed` row status.

## i18n impact
None. No user-facing string; the organizer email (`dispute-opened.*`, 4 locales) is untouched and no second alert is sent.

## Blast radius
Stripe/money-adjacent and a shared scheduler. (1) The only writer of `revoked` is this service and the only writer of `issued` is fresh issuance in `PaidCheckoutService` (verified by grep), so a re-revoke cannot fight another subsystem, and `restoreTickets` only runs once the status leaves `DisputeWithholding.STATUSES`. (2) Payout math, `sumOpenOrLost*`, the org payout freeze and `tier.sold` are untouched: ticket state only. (3) The sweep shares a 4-thread scheduler pool (`spring.task.scheduling.pool.size`) under `lockAtMostFor = PT5M`; pass 2 adds one bounded query plus at most 200 order/ticket reads, still pure DB, no Stripe call. (4) `DisputeWithholding.STATUSES` is read, not redefined, so Overview/Sales/org-home cannot drift.

## Risks
- **The 3-day window is a deadline.** The prod row's `created_at` is 2026-09-13; ship before 2026-09-16 or it falls out of the sweep and needs a one-off widened run or hand SQL. Raise this at the ship gate.
- Re-revoking every 5 min is correct but loud if an organizer ever hand-restores a ticket under an OPEN dispute; no such path exists today, and the WARN only fires when a row actually changed.
- `@DataJpaTest` executes the new JPQL, but H2 is not Postgres; the `exists` subquery has no nullable-String concat/lower, so the known `lower(bytea)` trap does not apply.
- Six files, one concern, no migration — no split needed.

## Definition of done
Both named repro tests fail on the untouched worktree and pass after the change; `./mvnw test` green on the full suite; `sweep()` runs pass 2 even when pass 1 finds no orphans, and a second tick on a converged row writes nothing (proven by the empty finder); no migration, no new alert, no contract or i18n change in the diff; live-test evidence below filled in from prod after the Railway deploy.

## Live-test evidence

## Review rounds

- round 1 → APPROVED (2 MEDIUM fixed post-review: restore gate OPEN-only vs revoke gate OPEN|LOST flip-flop; no interleaving test for pass-1/pass-2 ordering. 3 LOW: null guard on orderId, log wording, duplicate test.)
