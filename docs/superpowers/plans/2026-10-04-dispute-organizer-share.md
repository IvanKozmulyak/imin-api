# A lost dispute costs the organizer only their ticket share (api)
dispute-organizer-share · Subagent · Notion: card id not provided (see OPEN_QUESTIONS)

## Goal and scope

Ivan's policy (approved 2026-10-04): on a LOST dispute the organizer bears **only the ticket share**, i.e. what was transferred to them (order total − imin application fee). imin bears its own booking fee and Stripe's dispute fee.

**What the code does today (origin/master 4b189b54), verified:**
- Payout net, `PostEventPayoutService.java:229-239`: `max(0, max(0, gross − refunded) − max(0, appFee − appFeeRefunded) − disputedMinor)`. `disputedMinor` = `DisputeRepository.sumOpenOrLostLiveMinorByEventId` (`DisputeRepository.java:116-123,189-191`) = Σ `Dispute.amountMinor`, which is Stripe `dispute.amount` (`DisputeIngestService.java:135`). Stripe's own doc for that field (stripe-java 32.1.0 `com/stripe/model/Dispute.java:34-37`): "Disputed amount. Usually the amount of the charge, but it can differ (... only part of the order is disputed)". The amount includes the booking fee, and the fee is also inside `appFee`, so it is subtracted **twice**. The organizer is underpaid by each disputed order's `application_fee_minor`.
- The same double count is in `EventOverviewService.java:89-103` (`revenueAfterFeesMinor`) and `DashboardRevenue.java:35-55` (`net`, "same expression as the payout").
- Nothing moves money on a dispute. `DisputeIngestService` only revokes and restores tickets (`:144-196`), and `grep reversals().create` finds only the refund recovery (`PostEventPayoutService.java:655`). Under destination charges Stripe debits the platform for the dispute, so the withheld share stays in the connected balance forever and imin never recovers it.
- **Transfer amount = full charge, not total − fee.** `StripeRefundService.java:16-22`: a destination charge of G with `application_fee_amount = F` credits the account G, and the fee is a separate debit. The refund recovery reverses `amount − applicationFeeRefundMinor` (`PostEventPayoutService.java:633`). The dispute recovery reverses the same thing: total − fee.
- The comment at `Dispute.java:57` ("FACE VALUE … dispute fee is not in here") is wrong about meaning. So are `DisputeStatus.java:9-20`, `PostEventPayoutService.java:59-67,234-236` and `DisputeIngestService.java:179-183`.
- The email line "If the dispute is lost — the disputed amount is not paid out" (`dispute-opened*.txt:13`, `*.html:83`) claims the full amount is withheld.

**Decisions:**
1. **The organizer's share of a disputed order** (per order, over that order's OPEN/LOST disputes; T = `order.total_minor`, F = `order.application_fee_minor`, R/FR = SUCCEEDED refund amount/fee share of the order, D = Σ dispute `amount_minor`):
   - `remainingGross = max(0, T − R)`, `remainingFee = max(0, F − FR)`
   - `grossWithheld = min(max(0, D), remainingGross)`: what comes off gross-based figures
   - `feeShare = T > 0 ? min(remainingFee, Math.round((double) F × grossWithheld / T)) : 0`: same rounding as `RefundService.computeAppFeeRefundMinor` (`RefundService.java:535-540`)
   - `organizerShare = max(0, min(grossWithheld − feeShare, remainingGross − remainingFee))`
   - A dispute with no order (`order_id` null) keeps today's conservative behaviour: gross withheld = organizer share = D. In production, `event_id` is only ever set together with `order_id` (`DisputeIngestService.java:123-126`, `DisputeRepository.attachToOrder`), so this branch exists for safety only.
   - Partial disputes take the fee off pro rata. A dispute larger than what is left after refunds is capped at the organizer's remaining stake, and imin absorbs the rest (OPEN_QUESTIONS 2).
2. **One helper:** `DisputeShare` (pure) + `DisputeWithholding` (queries). Used by payout, Overview, DashboardRevenue and the gross-based readouts (Sales, EventSalesTotals, org home Now card), which now subtract `grossWithheld` (capped) instead of the raw sum.
3. **On LOST:** reverse the organizer share from the destination transfer in the nightly payout sweep, org-wide and inside each payout. One idempotency key per dispute (`dispute:<uuid>:reversal`), a lookup for an existing reversal before creating one, a marker committed in its own `REQUIRES_NEW` transaction, and insert/update-protected columns. Payouts stay frozen while any dispute is OPEN (unchanged).
4. **If a recovered dispute later turns WON / WITHDRAWN_REINSTATED** (Stripe documents lost as final; unverified this session): transfer the recovered amount back (the restore-the-prior-state rule), skipped while another OPEN/LOST dispute remains on the order.
5. **Already paid out:** the reversal draws on the connected balance. If Stripe refuses with `balance_insufficient`, the debt stays open, the next tick retries, and every payout of the org's other events holds that amount back (`reserve`) so imin's money is not paid out.
6. **Inquiries (`warning_needs_response` / `warning_under_review`)** keep mapping to OPEN (`DisputeStatus.java:37-45`). They freeze payouts and revoke tickets as today. An inquiry never becomes LOST (an escalation creates a new dispute), so it never triggers a reversal. Unchanged here; follow-up proposed (OPEN_QUESTIONS 5).

Out of scope: inquiry and `prevented` mapping, the webapp UI, the platform-funded refund reserve (same gap, follow-up).

## Repos in ship order

1. **imin-api** (`master`), worktree `/Users/ivan/imin/imin-api/.claude/worktrees/dispute-organizer-share`. Ship **Part A**, then **Part B** as two units (see Risks). Both are API-only.
2. imin-webapp: **no code change**. `/ship-imin` runs `npm run api:sync` after the Part A deploy because the `@Schema` descriptions change (Contract impact). The sync commit is description-only.

## Affected files (per repo)

Paths are relative to the api worktree. `m/` = `src/main/java/com/imin/iminapi/`, `t/` = `src/test/java/com/imin/iminapi/`, `r/` = `src/main/resources/`.

### Part A: formula everywhere (no migration, no Stripe call)

| # | File | Change |
|---|---|---|
| A1 | `m/dispute/DisputeShare.java` (new) | `public record DisputeShare(long grossWithheldMinor, long feeShareMinor, long organizerShareMinor)`, with `static DisputeShare of(long totalMinor, long feeMinor, long refundedMinor, long feeRefundedMinor, long disputedMinor)` (decision 1 formula) and `static DisputeShare unattributed(long disputedMinor)` → `(D, 0, D)`. |
| A2 | `m/dispute/DisputeOrderRow.java` (new) | `public record DisputeOrderRow(UUID eventId, UUID orderId, Long totalMinor, Long feeMinor, Long disputedMinor)` for JPQL constructor expressions (Long because of the left join). |
| A3 | `m/refund/RefundOrderSums.java` (new) | `public record RefundOrderSums(UUID orderId, Long refundedMinor, Long feeRefundedMinor)`. |
| A4 | `m/dispute/DisputeRepository.java` | Add `withholdingRowsByEventIds(eventIds, statuses)` (left join Order, all modes), `liveWithholdingRowsByEventId(eventId, statuses)` (`d.testMode = false`) and `withholdingRowsByOrgOrderWindow(orgId, since, until, statuses)` (inner join, window on `o.createdAt`). Delete the now-unused `sumMinorByEventIdAndStatusIn`, `sumMinorByEventIdsAndStatusIn`, `sumLiveMinorByEventIdAndStatusIn`, `sumMinorByOrgOrderWindowAndStatusIn` and their defaults `sumOpenOrLostMinorByEventId(s)`, `sumOpenOrLostMinorByOrgOrderWindow`, `sumOpenOrLostLiveMinorByEventId`. Update the javadoc of `countOpenByOrgId` (":146-147 face value"). |
| A5 | `m/refund/RefundRepository.java` | Add `sumSucceededAmountAndFeeByOrderIds(Collection<UUID> orderIds)` → `List<RefundOrderSums>` (SUCCEEDED only, grouped by order). |
| A6 | `m/dispute/DisputeWithholding.java` | Constructor adds `RefundRepository`. `withheldMinor` / `withheldMinorByEvent` → Σ `grossWithheld`. New `organizerShareMinor(eventId)` (all modes), `organizerShareLiveMinor(eventId)` (payout) and `organizerShareMinorByOrgWindow(orgId, since, until)`. Private `splits(rows)` does one refunds query per call. Rewrite the class javadoc. |
| A7 | `m/payout/PostEventPayoutService.java` | Inject `DisputeWithholding`. Step 2 uses `organizerShareLiveMinor(eventId)` in place of `disputes.sumOpenOrLostLiveMinorByEventId`. Rewrite the javadoc at `:59-67` and the comment and log at `:234-243` ("organizer share … withheld"). |
| A8 | `m/service/event/EventOverviewService.java` | `revenueAfterFeesMinor = max(0, max(0, gross − refunded) − netAppFee − disputeWithholding.organizerShareMinor(id))`. `revenueMinor` and `disputedMinor` keep using `withheldMinor` (now capped). |
| A9 | `m/service/dashboard/DashboardRevenue.java` | Replace the `DisputeRepository` dependency with `DisputeWithholding`. `forOrgWindow` → `organizerShareMinorByOrgWindow`, `netForEvent` → `organizerShareMinor`. The `net(...)` signature stays (5th arg renamed `disputedShare`). Update the class and method javadoc. |
| A10 | `m/dispute/Dispute.java` | `:57` comment → "Stripe `dispute.amount`: the disputed part of the charge, booking fee included; Stripe's dispute fee is not." |
| A11 | `m/dispute/DisputeStatus.java` | `:9-12,20` → LOST "permanently withholds the organizer's share of the order". |
| A12 | `m/dispute/DisputeIngestService.java` | Comment and log at `:179-183`: "the organizer's share comes off the event's net". No logic change in A. |
| A13 | `m/dto/dashboard/DashboardResponse.java` | `@Schema` of `Cycle.revenueMinor`: "Orders placed in the window, test mode included: totals less their succeeded refunds, the unrefunded booking fee and the organizer share of open or lost chargebacks (the chargeback less its booking fee). Minor units, never negative." `LastEventMetrics.avgTicketMinor` / `Business.totalRevenueMinor` refer to it (unchanged text). |
| A14 | `docs/superpowers/API_CONTRACT.md` | `:420` same wording as A13. |
| A15–A22 | `r/email-templates/dispute-opened{,.es,.fr,.uk}.{txt,html}` (8 files) | The "lost" line only; text in the i18n impact section (Part A wording). |
| — | `m/service/dashboard/DashboardService.java` | None: calls `disputeWithholding.withheldMinor` (`:127-131`), which now returns the capped gross figure; the "gross less refunds less chargebacks" semantics hold. |
| — | `m/service/event/SalesDashboardService.java` | None: `:76` gross basis via `withheldMinor`. |
| — | `m/service/event/EventSalesTotals.java` | None: `:50` gross basis via `withheldMinorByEvent`. |
| — | `m/controller/order/EventOrdersController.java` | None: `:156-165` shows the per-order dispute amount (a fact about the chargeback), not a withholding. |

Tests, Part A:

| # | File | Change |
|---|---|---|
| TA1 | `t/dispute/DisputeShareTest.java` (new) | Pure branches + worked examples (Test impact). |
| TA2 | `t/dispute/DisputeWithholdingScenarios.java` (new, abstract) | Query scenarios shared by H2 and PG. |
| TA3 | `t/dispute/DisputeWithholdingH2Test.java` (new) | `@SpringBootTest extends TA2`. |
| TA4 | `t/dispute/DisputeWithholdingPostgresTest.java` (new) | `@Testcontainers(disabledWithoutDocker = true)` + `postgres:17-alpine` + `@ServiceConnection`, same pattern as `t/audienceplan/controller/AudiencePlanListPostgresTest.java:23-35`. |
| TA5 | `t/payout/PostEventPayoutServiceTest.java` | `closed_lost_dispute_no_longer_blocks_but_reduces_the_net` (`:548-574`): expected **5_200 → 5_400** (order 8000/800, dispute 2000: gross withheld 2000, fee round(800×2000/8000)=200, share 1800; 7200 − 1800). Add `mixed_event_lost_11_49_dispute_withholds_only_the_ticket_share` → 3_000. |
| TA6 | `t/payout/PayoutTestModeExclusionTest.java` | No edit, expectations unchanged. Reads the `dispute(e, …)` fixture (`:467-478`), which has **no `order_id`**, so it takes the `unattributed` branch (share = D): `netStillWithholdsALiveDispute` stays **3_500**, `netIgnoresATestEraDispute` stays **8_500**. |
| TA7 | `t/service/event/EventOverviewServiceTest.java` | Add `after_fees_counts_a_disputed_orders_fee_once`. Existing tests read disputes on fee-0 orders (`newOrder` sets no fee) with D ≤ T, so the values are unchanged: 3047/3047/1149, 2298, 1149, 4196. |
| TA8 | `t/service/dashboard/DashboardRevenueTest.java` | `worked_example_window_…` and `event_net_uses_the_same_formula…`: **2_502 → 4_000** (worked example in Test impact). Comment at `:101`. Other tests unchanged (4_000, 3_000). |
| TA9 | `t/service/dashboard/DashboardRevenueNetTest.java` | Worked example → `net(12_045, 2_149, 1_045, 149, 5_000) == 4_000` with the comment "5000 = organizer shares 1000 + 4000". Other cases unchanged. |
| TA10 | `t/service/event/EventSalesTotalsTest.java` | Construct `new DisputeWithholding(disputes, tickets, refunds)`. Mock `disputes.withholdingRowsByEventIds(ids, STATUSES)` (and `refunds.sumSucceededAmountAndFeeByOrderIds`) in place of `sumOpenOrLostMinorByEventIds`. Same expected figures (row b: order 1000 total, fee 0, D 1000 → 1000; row a in the clamp test: order total 500, D 500). `verify(..., times(1))` on both new calls. |
| TA11 | `t/service/event/EventServiceSalesFiguresTest.java` | `:71` constructor gets `refunds`. |
| TA12 | `t/stripe/SettlementIngestServiceTest.java` | `:271` → autowire `DisputeWithholding` and assert `withheldMinor(event.getId())` is zero. |
| — | `t/service/event/SalesDashboardServiceTest.java` | No edit. Reads a dispute fixture (`:251-271`) on a fee-0 order with D = T = 1149, so 10_000 is unchanged. |
| — | `t/service/dashboard/DashboardServiceTest.java` | No edit. Mocks `DisputeWithholding.withheldMinor`, whose API is unchanged. |
| — | `t/email/OrganizerEmailLocaleVariantsTest.java` | No edit. Placeholders for `dispute-opened` (`:42-46`) are unchanged. |
| — | `t/dispute/DisputeNotifierTest.java` | No edit. A grep for the old line ("is not paid out") finds no hit in tests. |

### Part B: recover on LOST

| # | File | Change |
|---|---|---|
| B1 | `r/db/migration/V172__dispute_recovery.sql` (new; number = next above max on origin/master at ship, currently V171) | 5 nullable columns on `disputes` (Ordered steps B1). No CHECK constraints (V128 avoids unnamed checks). |
| B2 | `m/dispute/Dispute.java` | 5 fields, each `@Column(..., insertable = false, updatable = false)`. Written only by B3's bulk updates. |
| B3 | `m/dispute/DisputeRepository.java` | `findUnrecoveredLostByOrgId`, `findRecoveredToReturnByOrgId`, `findOrgIdsOwingDisputeMoney`, `sumLostAmountByOrderId`, `sumRecoveredUnreturnedByOrderId`, `markRecovered`, `markReturned`, `unrecoveredLostRowsByOrgExcludingEvent` (JPQL in Ordered steps). |
| B4 | `m/dispute/DisputeWithholding.java` | `long owedOnOrder(Order o)` and `long unrecoveredLostShareLiveMinorByOrg(UUID orgId, UUID excludeEventId)`. |
| B5 | `m/payout/DisputeRecoveryMarker.java` (new) | `@Transactional(REQUIRES_NEW)` `markRecovered(id, amount, reversalId)` and `markReturned(id, transferId)`, modelled on `RefundRecoveryMarker.java:44-57`. |
| B6 | `m/payout/PostEventPayoutService.java` | `recoverLostDisputes(org)`, `returnRecoveredDisputes(org)`, wired into step 0b and `recoverForOrg`; reserve after step 3; class javadoc. |
| B7 | `m/payout/PostEventPayoutSweeper.java` | Step B org ids = refund-owing ∪ `disputes.findOrgIdsOwingDisputeMoney(...)`; rename the private method to `recoverOwedMoney`; javadoc. |
| B8 | `m/dispute/DisputeIngestService.java` | On the transition out of LOST for a row with `recoveredAt != null`, log WARN "recovered share will be transferred back by the payout sweep". LOST log: "reversed from the connected balance by the next payout sweep". No other logic. |
| B9–B16 | `r/email-templates/dispute-opened{,.es,.fr,.uk}.{txt,html}` (8 files) | Append the "taken back" clause (i18n impact). |
| B17 | `CLAUDE.md` (api) | One paragraph under "Stripe Connect": dispute share formula, LOST reversal in the 03:00 payout sweep (same `STRIPE_PAYOUT_SCHEDULE_MANUAL` switch and ShedLock `PostEventPayoutSweeper.sweep`), return transfer, reserve, the `disputes` recovery columns. |
| — | `m/payout/RefundRecoveryMarker.java` | None. Separate bean for refunds; the pattern is copied, not shared. |
| — | `m/stripe/SettlementIngestService.java` | None. Our reversal fires `transfer.reversed` → `ingestTransfer` marks the settlement row REVERSED (`:99-102`), which replaces the dispute's FAILED annotation. The read-model gates nothing. A return transfer fires `transfer.created` → a new PENDING row, which is accurate. |
| — | `m/dispute/DisputeAttributionSweeper.java` | None. `attachToOrder` (`DisputeRepository.java:61-73`) does not touch the new columns, and an orphan LOST dispute is picked up by recovery on the next tick after it is attached. |
| — | `m/refund/RefundService.java` | None. The refund gate `hasOpenOrLostByOrderId` (`:115`) is unchanged. |
| — | `m/stripe/StripeWebhookService.java` | None. It only dispatches to ingest. |

Tests, Part B:

| # | File | Change |
|---|---|---|
| TB1 | `t/payout/PostEventPayoutDisputeRecoveryTest.java` (new) | `@SpringBootTest(properties = "imin.stripe.secret-key=sk_live_dummy_for_tests")`: its own context, never mutating the shared `StripeProperties`. `@MockitoBean StripeClient` over a fake `StripeResponseGetter` (pattern `PostEventPayoutServiceTest.java:84-232`) that dispatches on `req.getMethod()` + path. |
| TB2 | `t/dispute/DisputeRecoveryColumnsTest.java` (new) | Writers-of-same-row, both orderings; conditional updates. |
| TB3 | `t/dispute/DisputeRecoveryQueriesPostgresTest.java` (new) | The new B3 queries and bulk updates on PG 17. |
| TB4 | `t/payout/PostEventPayoutSweeperTest.java` | Add `sweep_recovers_a_lost_dispute_for_an_org_with_no_candidate_event`. The existing `wireRecoveryStripeCalls` (`:383`) is extended for GET transfer, list reversals (method-aware). Existing tests seed no disputes, so they are unchanged. |
| — | `t/payout/PostEventPayoutServiceTest.java` | No further edit in B. It runs on `sk_test_dummy` (`src/test/resources/application.yaml:67`), so recovery only looks at `test_mode = true` disputes. Its LOST fixtures are `test_mode = false` (Dispute default), so no new Stripe path is hit and the reserve excludes the same event. Values stay as set in Part A. |

## Ordered steps

### Part A

**A0 — baseline.** In the worktree: `docker info` (must answer), then `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test` on the untouched base. Record `Tests run / Failures / Skipped`. Any red or any skipped Testcontainers test is its own card and must not be blamed on this diff.

**A1 — `DisputeShare`.**
```java
public static DisputeShare of(long totalMinor, long feeMinor, long refundedMinor,
                              long feeRefundedMinor, long disputedMinor) {
    long remainingGross = Math.max(0L, totalMinor - refundedMinor);
    long remainingFee = Math.max(0L, feeMinor - feeRefundedMinor);
    long gross = Math.min(Math.max(0L, disputedMinor), remainingGross);
    long fee = totalMinor <= 0 ? 0L
            : Math.min(remainingFee, Math.round((double) feeMinor * gross / totalMinor));
    long share = Math.max(0L, Math.min(gross - fee, remainingGross - remainingFee));
    return new DisputeShare(gross, fee, share);
}
```
Comment, 2 lines: "What a chargeback takes off this order: the gross part caps at what was not refunded, and the organizer's part leaves imin's booking fee with imin."

**A2 — records** `DisputeOrderRow`, `RefundOrderSums` (no arrays).

**A3 — repository queries** (JPQL, ad-hoc entity join; one row per order):
```
select new com.imin.iminapi.dispute.DisputeOrderRow(d.eventId, d.orderId, o.totalMinor, o.applicationFeeMinor, sum(d.amountMinor))
  from Dispute d left join com.imin.iminapi.model.Order o on o.id = d.orderId
 where d.eventId in :eventIds and d.status in :statuses
 group by d.eventId, d.orderId, o.totalMinor, o.applicationFeeMinor
```
- `liveWithholdingRowsByEventId`: same, with `d.eventId = :eventId and d.testMode = false`.
- `withholdingRowsByOrgOrderWindow`: `join` (inner) with `d.orgId = :orgId and o.orgId = :orgId and o.createdAt >= :since and o.createdAt < :until and d.status in :statuses`. Same population as the deleted `sumMinorByOrgOrderWindowAndStatusIn` (`DisputeRepository.java:129-141`).
- Refunds: `select new com.imin.iminapi.refund.RefundOrderSums(r.orderId, sum(r.amountMinor), sum(r.applicationFeeRefundMinor)) from Refund r where r.orderId in :orderIds and r.status = com.imin.iminapi.refund.RefundStatus.SUCCEEDED group by r.orderId`.
- No nullable String parameter anywhere (H2 vs PG `lower(bytea)` trap).
- Delete the old sums listed in A4 of Affected files.

**A4 — `DisputeWithholding`.** Private helper `Map<UUID, DisputeShare> perEvent(List<DisputeOrderRow> rows)`:
- Collect the non-null order ids. When the set is non-empty (Hibernate cannot bind an empty IN), call `refunds.sumSucceededAmountAndFeeByOrderIds` **once**.
- Per row: `orderId == null` → `DisputeShare.unattributed(D)`, else `DisputeShare.of(T, F, R, FR, D)`.
- Sum per event into `(grossWithheld, organizerShare)`.

Public methods per the Affected files table; `STATUSES` is unchanged. An event with no rows is absent from the batch map, as today.

**A5 — payout.** In `payOneEvent` step 2, replace `:234-243` with `long disputedShareMinor = disputeWithholding.organizerShareLiveMinor(eventId);` and the same `perEventNetMinor` expression with `disputedShareMinor`. Log: "{} of the organizer's share of open/lost disputes withheld from the net". Comment: "A chargeback takes the organizer's ticket share off the net; its booking fee is already in netAppFee and stays imin's loss."

**A6 — EventOverview** as in the table. **A7 — DashboardRevenue** as in the table.

**A8 — comments and schema** (A10–A14), **email line** (A15–A22, i18n impact Part A text).

**A9 — tests TA1–TA12**, then the guard proofs (Test impact), then the full gate (Verification commands).

### Part B (after Part A is in prod)

**B1 — migration** `V172__dispute_recovery.sql` (5 columns):
```sql
-- What the payout sweep pulled back from the connected account for a LOST dispute, and any transfer back if it later turned won.
ALTER TABLE disputes ADD COLUMN recovered_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE disputes ADD COLUMN recovered_minor BIGINT;
ALTER TABLE disputes ADD COLUMN recovery_reversal_id VARCHAR(64);
ALTER TABLE disputes ADD COLUMN returned_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE disputes ADD COLUMN return_transfer_id VARCHAR(64);
```
No index: the table holds 3 rows in prod (caller finding), and the queries filter on `org_id` / `status` already covered by `disputes_org_status_idx` (V128).

**B2 — entity** fields `recoveredAt`, `recoveredMinor` (Long), `recoveryReversalId`, `returnedAt`, `returnTransferId`, all `insertable = false, updatable = false`. Comment: "Written only by DisputeRepository.markRecovered/markReturned; a full-entity save from dispute ingest must not revert them."

**B3 — repository** (`lost = DisputeStatus.LOST`, `back = List.of(WON, WITHDRAWN_REINSTATED)`, passed as params because of the converter):
- `findUnrecoveredLostByOrgId(orgId, lost, testMode)`: `d.orgId = :orgId and d.status = :lost and d.recoveredAt is null and d.orderId is not null and d.testMode = :testMode order by d.createdAt`.
- `findRecoveredToReturnByOrgId(orgId, back, testMode)`: `d.status in :back and d.recoveredAt is not null and d.recoveredMinor > 0 and d.returnedAt is null and d.testMode = :testMode order by d.createdAt`.
- `findOrgIdsOwingDisputeMoney(lost, back, testMode)`: `select distinct d.orgId … where d.testMode = :testMode and ((d.status = :lost and d.recoveredAt is null and d.orderId is not null) or (d.status in :back and d.recoveredMinor > 0 and d.returnedAt is null))`.
- `sumLostAmountByOrderId(orderId, lost)`, `sumRecoveredUnreturnedByOrderId(orderId)` (`recoveredAt is not null and returnedAt is null`, coalesce 0).
- `@Modifying(clearAutomatically = true, flushAutomatically = true) markRecovered(id, amount, reversalId, now)`: `set recoveredAt = :now, recoveredMinor = :amount, recoveryReversalId = :rid, updatedAt = :now where id = :id and recoveredAt is null`.
- `markReturned(id, transferId, now)`: `… where id = :id and recoveredAt is not null and returnedAt is null`.
- `unrecoveredLostRowsByOrgExcludingEvent(orgId, lost, eventId)` → `DisputeOrderRow`, inner join Order, `d.testMode = false and d.recoveredAt is null and d.eventId <> :eventId`.
- Precedent for a bulk update on an `updatable = false` column: `EventRepository.updateRadarMuted` (api CLAUDE.md, radar paragraph).

**B4 — `DisputeWithholding`:**
- `owedOnOrder(Order o)` = `max(0, DisputeShare.of(T, F, R, FR, sumLostAmountByOrderId(o.id)).organizerShareMinor() − sumRecoveredUnreturnedByOrderId(o.id))`. LOST disputes only: an OPEN sibling is not owed yet.
- `unrecoveredLostShareLiveMinorByOrg(orgId, excludeEventId)` = Σ organizer share over B3's rows (refunds as in A4).

**B5 — `DisputeRecoveryMarker`.** Same shape as `RefundRecoveryMarker`. Log at ERROR when the conditional update hits 0 rows and the row has no marker (the row vanished). An already-marked row is a silent return (another run's marker is the claim).

**B6 — `PostEventPayoutService`:**

(a) **`recoverLostDisputes(Organization org)`** runs right after `recoverPlatformFundedRefunds(org)` in step 0b (`:190`) and in `recoverForOrg` (`:443`), so refunds reverse first on a shared transfer. `boolean testMode = !props.isLiveKey();` (only the running mode's transfers are reachable). Per dispute in `findUnrecoveredLostByOrgId(org.id, LOST, testMode)`, in this order; every `continue` leaves the debt open unless noted:
1. Load the order. Missing → ERROR, `continue`.
2. `!order.currency.equalsIgnoreCase(d.currency)` → ERROR "never reverse across currencies", `continue`.
3. `owed = disputeWithholding.owedOnOrder(order)`. If `owed <= 0` → `commitDisputeMarker(d, 0, null)` (recovered, with nothing to take), `continue`.
4. `d.stripeChargeId` blank → ERROR with the amount, `continue`.
5. `try {`
   - `transferId = stripeClient.charges().retrieve(chargeId).getTransfer()`. Blank → ERROR, `continue`.
   - **Lookup before create:** `stripeClient.transfers().reversals().list(transferId, TransferReversalListParams.builder().setLimit(100L).build())`. A reversal with `metadata.dispute_id == d.id` → `commitDisputeMarker(d, its amount, its id)`, `continue`. This closes the gap left when Stripe drops the idempotency key after ~24h.
   - `Transfer tr = stripeClient.transfers().retrieve(transferId)`; `remaining = nz(tr.getAmount()) − nz(tr.getAmountReversed())`. Stripe allows "only up to the unreversed amount remaining" (`TransferReversalCreateParams.java:18-21`).
   - `amount = Math.min(owed, remaining)`. If `amount <= 0` → ERROR "nothing left on transfer", `commitDisputeMarker(d, 0, null)`, `continue`. If `amount < owed` → ERROR naming the unrecoverable residue. This is a cap, not a split: the debt is never reversed in two pieces.
   - `TransferReversal rv = stripeClient.transfers().reversals().create(transferId, TransferReversalCreateParams.builder().setAmount(amount).putMetadata("dispute_id", d.getId().toString()).putMetadata("stripe_dispute_id", d.getStripeDisputeId()).build(), RequestOptions.builder().setIdempotencyKey("dispute:" + d.getId() + ":reversal").build())`. Signature: `TransferReversalService.create(String id, TransferReversalCreateParams params, RequestOptions options)`, stripe-java 32.1.0 `TransferReversalService.java:122-124`. No `refund_application_fee`: the fee is imin's.
   - `commitDisputeMarker(d, amount, rv.getId())`.
6. `} catch (StripeException e)` → `balance_insufficient`: WARN "debt stays open; payouts of this org's other events hold it back". Anything else: ERROR with the throwable.
7. `commitDisputeMarker` wraps the marker call in `try/catch (RuntimeException)` and logs ERROR "MONEY MOVED, MARKER MISSING — reversal {} for dispute {}". It never rethrows (cleanup-in-catch rule). The next tick's lookup adopts the reversal.

(b) **`returnRecoveredDisputes(Organization org)`** runs right after (a) on both paths. Per row in `findRecoveredToReturnByOrgId(org.id, back, testMode)`:
- `disputes.countOtherOpenOrLostByOrderId(orderId, d.id) > 0` → INFO "kept: another dispute on the order still withholds", `continue` (restoring-access rule).
- Blank `org.stripeAccountId` → ERROR, `continue`.
- Lookup before create: `stripeClient.transfers().list(TransferListParams.builder().setDestination(acct).setCreated(recoveredAt epoch − 60).setLimit(100L).build())`; a match on `metadata.dispute_return_id == d.id` → adopt it.
- Else `stripeClient.transfers().create(TransferCreateParams.builder().setAmount(d.getRecoveredMinor()).setCurrency(d.getCurrency()).setDestination(acct).putMetadata("dispute_return_id", d.getId().toString()).putMetadata("event_id", String.valueOf(d.getEventId())).build(), RequestOptions.builder().setIdempotencyKey("dispute:" + d.getId() + ":return").build())`. Signature `TransferService.create(TransferCreateParams, RequestOptions)` (`TransferService.java:75`); builder setters verified at `TransferCreateParams.java:151,161,177`.
- Commit `markReturned` through the same kind of wrapped call. `balance_insufficient` (platform balance) → WARN; anything else → ERROR.

(c) **Reserve**, in `payOneEvent` right after step 3 (`:279-284`):
```java
long reserved = disputeWithholding.unrecoveredLostShareLiveMinorByOrg(org.getId(), eventId);
if (reserved > 0L) { availableMinor = Math.max(0L, availableMinor - reserved); log.warn(...); }
```
Comment: "A lost-dispute debt that could not be reversed yet is imin's money in this balance; this event's own share is already out of its net." Live rows only, same filter as the net (V130).

(d) The Stripe call in `payOneEvent` is the only `payouts().create` (grep: `PostEventPayoutService.java:359`), so (c) guards every path that moves money out of a connected balance.

**B7 — sweeper.** Step B: `Set<UUID> owing = new LinkedHashSet<>(refunds.findOrgIdsWithUnrecoveredPlatformFunded()); owing.addAll(disputes.findOrgIdsOwingDisputeMoney(LOST, back, !props.isLiveKey()));`, then `payoutService.recoverForOrg(orgId)` per org (already `REQUIRES_NEW`, already try/catch). Inject `DisputeRepository`. This covers the zero-candidate case.

**B8 — ingest log** (Affected files B8). **B9–B16 email clause.** **B17 CLAUDE.md.**

**B9 — tests TB1–TB4**, guard proofs, full gate.

**Webhook and payout timing covered by B6 (all paths reach recovery through B6a):**

| Order of events | Result |
|---|---|
| created → closed(lost) → 03:00 sweep | Step B org pass reverses the share, then candidates pay a net that excludes it |
| event paid out → created → lost | Org pass reverses from the remaining balance. If refused, the reserve holds the debt back from other events and the next tick retries |
| closed(lost) delivered before created | `isStale` (`DisputeIngestService.java:362-368`) keeps LOST; recovery is unchanged |
| dispute before its order exists | Orphan (`order_id` null) is skipped until `attachToOrder` / the attribution sweep, then recovered next tick |
| lost → won (Stripe says final) | B6b returns `recovered_minor` once no sibling withholds |
| sweep runs while another dispute on the org is OPEN | Recovery and return still run (they are not payouts); payouts stay frozen by `countOpenByOrgId` (`:218`) |

## Verification commands

All in `/Users/ivan/imin/imin-api/.claude/worktrees/dispute-organizer-share`:
1. `docker info` must answer. A run with skipped Testcontainers tests counts as red.
2. Targeted (comma-separated; judge by its own `Tests run:` line). Part A: `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=DisputeShareTest,DisputeWithholdingH2Test,DisputeWithholdingPostgresTest,PostEventPayoutServiceTest,PayoutTestModeExclusionTest,EventOverviewServiceTest,DashboardRevenueTest,DashboardRevenueNetTest,EventSalesTotalsTest,EventServiceSalesFiguresTest,SettlementIngestServiceTest,SalesDashboardServiceTest,DashboardServiceTest,OrganizerEmailLocaleVariantsTest`. Part B adds `PostEventPayoutDisputeRecoveryTest,DisputeRecoveryColumnsTest,DisputeRecoveryQueriesPostgresTest,PostEventPayoutSweeperTest`.
3. The gate: `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test`, re-run after every rebase. After renumbering a migration, run `./mvnw clean` first.
4. `git grep -n -i "face value" -- src/main` → zero hits outside history. Also `grep -rni "disputed amount is not paid out\|el importe disputado no se paga\|le montant contesté n'est pas versé\|оскаржену суму не буде виплачено" src/main/resources` → zero hits (the HTML French uses entities, so also grep `contest&eacute; n&rsquo;est pas`).

## Test impact

**Branches → tests (one each, minimal setup).**

`DisputeShareTest`: all values in minor units. The €11.49 order is a €10.00 ticket + 5% (50) + €0.99 (99) = 149 fee, from `application.yaml:172-175` (`application-fee-bps: 500`, `application-fee-fixed-minor: 99`).

| Branch | Input (T, F, R, FR, D) | Expected (grossWithheld, feeShare, organizerShare) |
|---|---|---|
| worked A: full dispute, €11.49 | 1149, 149, 0, 0, 1149 | 1149, 149, 1000 |
| partial dispute, round result | 2298, 298, 0, 0, 1149 | 1149, 149, 1000 |
| partial dispute, rounding (298×500/2298 = 64.84 → 65) | 2298, 298, 0, 0, 500 | 500, 65, 435 |
| worked D: refund then full-charge dispute | 2298, 298, 1149, 149, 2298 | 1149, 149, 1000 |
| fee 0 | 1149, 0, 0, 0, 1149 | 1149, 0, 1149 |
| fee clamp (proportional fee 149 > remaining fee 49, i.e. 149 − 100) | 1149, 149, 0, 100, 1149 | 1149, 49, 1000 (cap 1149 − 49 = 1100 does not bind) |
| stake cap: refund 1000 without fee refund | 5349, 349, 1000, 0, 5349 | 4349, 284, 4000 (349×4349/5349 = 283.75 → 284; 4065 capped at 4349 − 349) |
| refunds ≥ total | 1149, 149, 1149, 149, 1149 | 0, 0, 0 |
| total 0 | 0, 0, 0, 0, 500 | 0, 0, 0 |
| unattributed | `unattributed(700)` | 700, 0, 700 |

(10 rows.)

**`DisputeWithholdingScenarios`** (H2 + PG). Each test seeds its own org and event, deletes committed rows in `@AfterEach`, and dates fixtures relative to now:
- worked B, mixed event: o1 1149/149 LOST 1149, o2 1149/149, o3 2298/298. `organizerShareMinor` = 1000; `withheldMinor` = 1149.
- worked D in a mixed event: o2 1149/149 + o3 2298/298 refunded 1149 (fee 149) then LOST 2298 → share 1000, gross withheld 1149. Proves `min(D, T − R)`.
- live filter: one live LOST (1149/149) + one test-mode LOST on another order (1149/149) → `organizerShareLiveMinor` = 1000, `organizerShareMinor` = 2000.
- status filter: WON + WITHDRAWN_REINSTATED on fixtures created **before** an OPEN one → only the OPEN one counts.
- orderless dispute with an `event_id` → share = D (conservative).
- two disputes on one order (OPEN 1149 + LOST 1149, order 2298/298) → gross withheld 2298, share 2000.
- window: an order at `since` counted, at `until` not; another org's order not counted.
- batch: an event without disputes is absent from `withheldMinorByEvent`; one refunds query per call (`@SpyBean RefundRepository`, `times(1)`).

**Worked example for `DashboardRevenueTest` (TA8)**, orders A 4398/398, B 1149/149 refunded 1149 (fee 149), C 1149/149 LOST 1149, D 5349/349 test-mode, refund 1000 (fee 0), OPEN 5349:
- C share = 1000; D share = 4000 (stake-cap row above)
- 12045 − 2149 = 9896; − (1045 − 149) = 9000; − 5000 = **4000**
- check: only A's stake is left, 4398 − 398 = 4000

**`PostEventPayoutServiceTest` (TA5)**, `mixed_event_lost_11_49_dispute_withholds_only_the_ticket_share`:
- orders 1149/149 (LOST 1149), 1149/149, 2298/298; available 50_000
- payout amount **3_000** (4596 − 596 − 1000); the old formula paid 2_851

**`EventOverviewServiceTest` (TA7)**, two orders 1149/149, one with LOST 1149:
- `revenueMinor` 1149, `revenueAfterFeesMinor` **1000** (2298 − 298 − 1000), `disputedMinor` 1149
- the old formula gave 851

**`PostEventPayoutDisputeRecoveryTest` (TB1).** Live-key context; the fake asserts method, path, amount, metadata and idempotency key.

| Branch | Setup | Assertion |
|---|---|---|
| r1 nothing owed | no disputes | zero Stripe calls besides the payout path |
| r2 currency mismatch | dispute `usd`, order `EUR` | no reversal; debt open |
| r3 owed ≤ 0 | order fully refunded before LOST | no Stripe call; `recovered_minor` = 0 |
| r4 no charge id | LOST without `stripe_charge_id` | no reversal; debt open |
| r5 charge without transfer | fake charge `transfer: null` | no reversal; debt open |
| r6 existing reversal adopted | fake list returns `metadata.dispute_id` = id | zero `POST /reversals`; marker = listed id and amount |
| r7 transfer fully reversed | amount 1149, amount_reversed 1149 | no create; marker amount 0 |
| r8 cap | amount 1149, amount_reversed 500, owed 1000 | reversal amount **649** |
| r9 worked A | 1149/149 LOST, transfer 1149 | `POST /v1/transfers/tr_x/reversals` amount **1000**, key `dispute:<id>:reversal`, metadata `dispute_id`; marker set |
| r9b worked D | 2298/298, refund 1149 (fee 149), LOST 2298, transfer 2298 with 1149 reversed | reversal amount **1000** |
| r9c platform-funded refund then dispute | same order with the refund `platform_funded`, transfer 2298 unreversed | two reversals in one tick, refund first (1000, key `refund:<id>:reversal`), then dispute (1000) |
| r10 balance_insufficient | fake refuses | no marker; the payout of another event of the org is reduced by the reserve |
| r11 other Stripe error | fake throws 500 | no marker; no exception escapes `payOneEvent` |
| r12 marker fails | `@MockitoSpyBean DisputeRecoveryMarker` throws | no exception; the next call adopts via the list (no second create) |
| r13 mode filter | test-mode LOST under the live key | no Stripe call |
| r14 status/orderless | OPEN, WON and orderless LOST created **before** the target | only the target is reversed |
| r15 never twice | call `recoverForOrg` twice | reversal count 1 |
| r16 rollback | runtime crash after step 0b (`accounts` path throws `IllegalStateException`) | the marker survives; the next tick does not reverse again |
| t1 return | recovered 1000, then status WON, no sibling | `POST /v1/transfers` destination = acct, amount 1000, currency eur, key `dispute:<id>:return`; `returned_at` set |
| t2 sibling withholds | another LOST on the same order | no transfer |
| t3 already returned | `returned_at` set | no transfer |
| t5 adopt return | list returns `metadata.dispute_return_id` | no create; marker set |
| t6 platform short | create refused `balance_insufficient` | `returned_at` null |
| t7 no acct | blank `stripe_account_id` | no transfer |
| v1 reserve | event X LOST unrecovered (refused), event Y due, available 5000, Y net 3000 | payout 3000 − ? : `min(3000, 5000 − 1000)` = **3000**; with available 3500 → **2500** |
| v2 own event not double-reserved | LOST on Y itself, refused | payout = Y net (share already out), not net − share again |
| v3 recovered not reserved | X recovered | available untouched |
| v4 test-mode not reserved | X test-mode LOST | available untouched |

(27 rows.)

**`DisputeRecoveryColumnsTest` (TB2).**
- (1) `markRecovered`, then `DisputeIngestService.ingest` saves the entity: columns stay.
- (2) ingest loads the row, `markRecovered` commits, then ingest saves: columns stay.
- (3) a second `markRecovered` returns 0.
- (4) `markReturned` on an unrecovered row returns 0.

**`DisputeRecoveryQueriesPostgresTest` (TB3).** Every B3 query and both bulk updates, including the `or`/`in` org-id query and the `<> :eventId` reserve query, on PG 17.

**`PostEventPayoutSweeperTest` (TB4).** An org with a LOST dispute and no candidate event. The tick asserts a positive `POST /reversals` interaction. Expire the shedlock `lock_until` before the call (ShedLock rule).

**Guard proofs.** Remove each line once, confirm red, restore. Record in the PR body.

| Removed line | Test that must go red |
|---|---|
| `Math.min(..., remainingGross)` in `of` | worked D (DisputeShareTest + scenarios) |
| `Math.min(remainingFee, ...)` | fee clamp |
| `min(gross − fee, remainingGross − remainingFee)` | stake cap / DashboardRevenueTest 4000 |
| `d.testMode = false` in `liveWithholdingRowsByEventId` | live filter |
| `d.recoveredAt is null` in `findUnrecoveredLostByOrgId` | r15 |
| `d.testMode = :testMode` | r13 |
| `d.status = :lost` | r14 |
| lookup before create | r12 (create count 2) |
| transfer-remaining cap | r8 |
| `countOtherOpenOrLostByOrderId` check in return | t2 |
| `d.eventId <> :eventId` in reserve | v2 |
| `insertable/updatable = false` | TB2 (2) |
| `REQUIRES_NEW` on `DisputeRecoveryMarker` | r16 |
| dispute owers in the sweeper union | TB4 |

## Live-test

API only; `/live-test api`. Production has no live dispute (caller finding: 3 disputes, all test mode, `withdrawn_reinstated`), so prod can only be checked passively. The Stripe behaviours the code depends on are checked in the **sandbox** with the Stripe CLI under a test key; prod is never written:
1. Destination charge on a test connected account with card `4000000000000259`; `charge.dispute.created` arrives.
2. Check `charge.transfer` = full charge and `transfer.amount_reversed` = 0 after the dispute (Stripe does not auto-reverse).
3. `stripe disputes update du_… -d "evidence[uncategorized_text]=losing_evidence" -d submit=true` → status `lost`.
4. Reverse 1000 with metadata `dispute_id` and key `dispute:test:reversal`; replay the same request → same `trr_`.
5. `GET /v1/transfers/tr_…/reversals` shows the metadata.
6. Reverse more than what remains on the transfer → record the error code.
7. Reverse more than the connected balance → record whether Stripe refuses (`balance_insufficient`) or lets the balance go negative.

Write each `id` and answer into "Live-test evidence". If step 7 shows a negative balance, the reserve branch is still correct but less likely to be needed; record it.

Local run (optional, sk_test, `STRIPE_PAYOUT_SCHEDULE_MANUAL=true`) is limited: the sweep is cron-only (`PostEventPayoutSweeper.java:71`), so the code path itself is proven by TB1/TB4, not by a local trigger.

After each deploy:
- `curl -s https://imin-api-production.up.railway.app/v3/api-docs.yaml | grep -c "organizer share of open or lost chargebacks"` ≥ 1 (Part A).
- `railway` read-only psql `\d disputes` shows the 5 new columns (Part B).
- `select status, test_mode, count(*) from disputes group by 1, 2` → expect no `lost` + `false` row.
- The next 03:00 Europe/Amsterdam sweep log shows no dispute recovery line and no ERROR.

## Contract impact

- No path, field or type change. `@Schema` **description** change on `DashboardResponse.Cycle.revenueMinor` (and the same wording in `API_CONTRACT.md:420`).
- Prod OpenAPI marker: `organizer share of open or lost chargebacks`.
- The numbers change meaning without changing shape: `metrics.revenueAfterFeesMinor`, `cycle.revenueMinor`, `business.totalRevenueMinor`, `lastEvent.metrics.avgTicketMinor`, the Now-card `revenueMinor` / `metrics.revenueMinor` / `disputedMinor` (capped at the unrefunded total), and Sales `tiles.netRevenueMinor`. All are higher or equal than before for the same data.
- webapp: no `src/shared/api/types.ts` edit (no type change). `npm run api:sync` must run in `/ship-imin` after the API deploys, or webapp's `api:check` goes red on the description diff. `api:check` runs in /ship after api deploys.
- `/api/v1/public` is untouched, so `PUBLIC_PAGE_API.md` does not move.

## i18n impact

No webapp/public/fan-app string changes. The organizer email `dispute-opened` exists in EN/ES/FR/UK; every locale changes in the same commit. The `.html` files keep each file's existing entity style (FR uses `&eacute;`/`&rsquo;`, all use `&mdash;`), and only the text after `</strong> &mdash; ` changes.

| Locale | Part A line (txt) | Part B adds before " If it is won…" |
|---|---|---|
| EN | `- If the dispute is lost — your share of the disputed amount (what you received for those tickets, not imin's service fee) is not paid out. If it is won, it goes back.` | `…is not paid out, or is taken back from your Stripe balance if it was already paid out.` |
| ES | `- Si se pierde la disputa — tu parte del importe disputado (lo que recibiste por esas entradas, sin la comisión de servicio de imin) no se paga. Si se gana, vuelve.` | `…no se paga, o se descuenta de tu saldo de Stripe si ya se había pagado.` |
| FR | `- Si le litige est perdu — votre part du montant contesté (ce que vous avez reçu pour ces billets, hors frais de service imin) n'est pas versée. S'il est gagné, il revient.` | `…n'est pas versée, ou est reprise sur votre solde Stripe si elle a déjà été versée.` |
| UK | `- Якщо оскарження програно — вашу частку оскарженої суми (те, що ви отримали за ці квитки, без сервісного збору imin) не буде виплачено. Якщо виграно — повернеться.` | `…не буде виплачено, а якщо її вже виплачено, її буде списано з вашого балансу Stripe.` |

(4 locales × 2 files × 2 parts.) "service fee" matches the organizer dashboard's term (webapp `copy.ts:158` "Net of service fee, …"). ES/FR/UK drafts need Ivan's language review.

**Copy ledger.** Every string this plan adds or reuses that rests on a changed figure.

| String (where) | Field(s) behind it (file:line) | Meaning, scope, range | Rendered next to |
|---|---|---|---|
| "After fees · {X}" (webapp `copy.ts:550`, es `:563`, fr `:553`, uk `:566`) | `metrics.revenueAfterFeesMinor`, `EventOverviewService.java` (replaces `:99-103`) | Per event, all modes: `max(0, gross − succeeded refunds − unrefunded booking fee − Σ organizer share of OPEN/LOST disputes)`. Exact minor units, ≥ 0, ≤ `revenueMinor` (proof: `revenueMinor − after = netAppFee − Σ feeShare ≥ 0`, because each `feeShare ≤` that order's remaining fee) | Under the "Revenue" big stat value `revenueMinor` (`EventOverviewTab.tsx:146-158`) |
| "{X} withheld in disputes" (webapp `copy.ts:559` + 3 locales) | `metrics.disputedMinor` = `DisputeWithholding.withheldMinor` (`EventOverviewService.java:89`) | Per event, all modes: Σ per order `min(Σ dispute amount, total − refunded)`, OPEN (held) + LOST (gone). Equals exactly what `revenueMinor` subtracts | Same sub-block, shown only when > 0 |
| "Revenue" value (webapp `copy.ts:549`) | `metrics.revenueMinor`, `EventOverviewService.java:93` | Gross − refunds − the line above; the booking fee stays in (gross basis) | The two lines above |
| "Net of service fee, refunds and open or lost chargebacks" (webapp `copy.ts:158`) | `business.totalRevenueMinor` / `cycle.revenueMinor` via `DashboardRevenue.forOrgWindow` (`:40-46`) | Window, all modes: `gross − refunds − unrefunded fee − Σ share` ≡ `gross − refunds − fee of non-charged-back orders − Σ grossWithheld`, i.e. chargebacks subtracted in full and the service fee once. The string is now true where it double-counted before | "Total revenue" value (`DashboardPage.tsx:375-378`) |
| Sales "Net revenue" (webapp `copy.events.sales.netRevenue`) | `tiles.netRevenueMinor`, `SalesDashboardService.java:76` | Per event, all modes: gross − refunds − `withheldMinor` (capped) | Sales tiles |
| Email "DISPUTED AMOUNT {{amountFormatted}} — on hold while the dispute is open" (reused, unchanged) | `Dispute.amountMinor` = Stripe `dispute.amount` (`DisputeIngestService.java:135`); `DisputeNotifier.java:101` | The disputed part of the charge, booking fee included. "On hold" = org payouts frozen while OPEN (`PostEventPayoutService.java:218`) | Email body block |
| Email "your share of the disputed amount (what you received for those tickets, not imin's service fee) is not paid out" (Part A) | `DisputeShare.organizerShareMinor`, payout net step 2 | `grossWithheld − round(F × grossWithheld / T)` capped at the remaining stake; F = PI `application_fee_amount` (`PaidCheckoutService.java:217`) | "If the dispute is lost" bullet |
| Email "…or is taken back from your Stripe balance if it was already paid out" (Part B) | `recoverLostDisputes` → `transfers().reversals().create` (B6a) | The same share, reversed from the destination transfer at the 03:00 sweep after LOST | Same bullet |
| OpenAPI `Cycle.revenueMinor` description (A13) | `DashboardRevenue.forOrgWindow` | As row 4 | Swagger / api:sync |

(9 rows.)

## Blast radius

**Money (payouts):** `PostEventPayoutService.payOneEvent` (net, reserve, recovery), `recoverForOrg`, and `PostEventPayoutSweeper.sweep`. That is every path that creates a Payout (`:359`, the only `payouts().create`) or reverses a transfer (`:655` refunds, plus the new disputes path). All are serialised by ShedLock `PostEventPayoutSweeper.sweep` and gated by `STRIPE_PAYOUT_SCHEDULE_MANUAL` (true in prod). There is no admin endpoint (`grep payOneEvent|recoverForOrg` outside the payout package: none).

**Effect in prod today:** none numerically. Every figure reads OPEN/LOST disputes, and the 3 prod disputes are `withdrawn_reinstated` (caller finding; re-checked post-deploy with read-only SQL).

**Shared module:** `DisputeWithholding` / `DisputeRepository` are read by `EventOverviewService`, `SalesDashboardService`, `EventSalesTotals`, `DashboardService`, `DashboardRevenue` and `EventOrdersController` (statuses only). All are listed under Affected files with their change, or with one line on why none is needed.

**Webhooks:** `DisputeIngestService` (log only), `SettlementIngestService` (our reversal and return produce `transfer.reversed`/`transfer.created`; read-model only), `DisputeAttributionSweeper` (no change; orphans are recovered after attach).

**Writers of the `disputes` row:**
- `DisputeIngestService.ingest` full `save` (`:142`)
- `DisputeRepository.attachToOrder` bulk update (`:61-73`)
- new `markRecovered` / `markReturned` bulk updates

There is no `@Version` or `@DynamicUpdate`, so the new columns are `insertable = false, updatable = false`. TB2 tests both orderings.

**Migration:** additive, nullable columns. Old code running against the new schema is unaffected; rollback = leave the columns.

**Refunds:** the refund gate is unchanged. Refund recovery runs before dispute recovery on the same transfer (r9c).

**Contract:** description-only (Contract impact).

## Risks

- **Size → split.** About 25 non-test files in Part A (8 of them email templates) and 18 in Part B, so this is two separable concerns. **Proposal:** ship Part A (formula + copy, no Stripe call, no migration) as one /do-task + /ship-imin, then Part B (migration + reversal + return + reserve) as a second. Part A alone is strictly better than today: the organizer stops losing the fee twice, and the share stays stranded exactly as today.
- **Unverified Stripe facts:**
  - "lost is final": no Stripe doc read this session; the return path exists in case it is not.
  - Whether a reversal larger than the connected balance fails or goes negative: settled in sandbox live-test step 7. The code handles both.
  - The over-cap error code: live-test step 6.
- **Reserve can stall sibling events.** While a debt cannot be reversed, other events of the org pay less and sit at PARTIAL until the balance covers it. This is the same shape as the existing platform-funded refund recovery. It is correct (the organizer owes it) but visible to them; OPEN_QUESTIONS 4.
- **Refund-then-full-dispute:** imin absorbs the second payout to the buyer beyond the organizer's remaining stake (policy reading; OPEN_QUESTIONS 2).
- **Unattributed LOST disputes** (no order) are never recovered: ERROR log only. Today none exist with an `event_id`.
- **Idempotency key expiry (~24h)** is covered by the lookup before create (reversals list by `dispute_id` metadata, transfers list by `dispute_return_id`). A transfer with >100 reversals would page past the list; this is not realistic.
- **Migration number collision:** `SPRING_FLYWAY_OUT_OF_ORDER=true`. Take max+1 on origin/master at ship time and run `./mvnw clean` after any renumber.
- **Rule conflicts noted, not fixed here:**
  - Stripe's documented `prevented` status maps to OPEN (`DisputeStatus.java:37-45`), against the API repo's rule that unrecognised states must not block money.
  - Inquiries freeze payouts and revoke tickets.
  - Follow-up card proposed for both.
- **Rounding:** our fee share and Stripe's own proportion can differ by 1 minor unit; the transfer-remaining cap keeps a reversal within what Stripe allows.

## Definition of done

- Part A merged: payout, Overview, dashboard and gross readouts all use `DisputeWithholding`. Every worked example (A €11.49 → 1000; B mixed → 3000; partial 500 → 435; D refund-then-dispute → 1000; dashboard window → 4000) is pinned by a test. Every guard proof was recorded red→green. The full `./mvnw test` is green with zero skipped Testcontainers tests on a fresh run after rebase. The prod OpenAPI shows the marker. webapp `api:sync` is committed in /ship.
- Part B merged: V172 applied in prod. Recovery, return and reserve are covered by TB1–TB4. The sandbox probe results are recorded below. The first prod 03:00 sweep after deploy logs no ERROR.
- No comment longer than 2 lines and no ticket or milestone id in comments. Email EN/ES/FR/UK updated in the same commit. api `CLAUDE.md` paragraph added.

## Decisions (main session)
- Ship as TWO units, in order: Part A (formula fix everywhere, no migration, no Stripe change), then Part B (V172, transfer reversal on LOST). This task implements PART A ONLY; Part B gets its own worker and review after A ships.
- OQ2 (refund then full-charge dispute): accepted. The organizer pays only what it still holds from that order; imin absorbs the rest. This is consistent with Ivan's policy (the organizer bears only its ticket share).
- OQ3 (LOST later turns WON): for Part B, transfer the share back automatically once no other open or lost dispute remains on the order.
- OQ4 (hold unrecoverable debts from other payouts, which may then show PARTIAL): accepted for Part B.
- OQ5: inquiries/`prevented` mapping goes to a separate follow-up card, out of scope here.
- OQ6: Part B ships only after a Stripe test-mode check of reversals larger than the connected balance (the main session arranges it).
- OQ7: after the Part A api deploy, run webapp `npm run api:sync` as its own tiny ship.

## Live-test evidence

## Review rounds

### Part A implementation (2026-10-05)

Guard proofs, each run in a scratch copy of the worktree (`scratchpad/mut`), one mutation at a time, file restored byte-exact from the worktree after each run (`filecmp` checked):

| Mutation | Tests run with it | Result |
|---|---|---|
| M1 drop `min(…, remainingGross)` in `DisputeShare.of` | DisputeShareTest, DisputeWithholdingH2Test | red: 6 failures incl. `full_charge_dispute_after_a_refund…` in both classes |
| M2 drop `min(remainingFee, …)` | DisputeShareTest | red: `fee_share_is_capped_at_the_fee_not_yet_refunded` |
| M3 drop the stake cap `min(gross − fee, remainingGross − remainingFee)` | DisputeShareTest, DashboardRevenueTest | red: `share_is_capped_at_the_organizers_remaining_stake`, both DashboardRevenueTest 4_000 tests |
| M4 drop `d.testMode = false` in `liveWithholdingRowsByEventId` | DisputeWithholdingH2Test | red: `the_payout_figure_leaves_out_test_mode_disputes` |
| M5 drop `r.status = SUCCEEDED` in `sumSucceededAmountAndFeeByOrderIds` | DisputeWithholdingH2Test | red: `a_refund_that_has_not_succeeded_does_not_cap_the_dispute` |
| M6 neutralise `o.createdAt < :until` in the window query | DisputeWithholdingH2Test | red: `the_window_counts_orders_from_since_up_to_but_not_including_until` |
| M7 neutralise `d.status in :statuses` in `withholdingRowsByEventIds` | DisputeWithholdingH2Test | red: `won_and_reinstated_disputes_withhold_nothing` |

Plan correction: the "fee clamp" row of the DisputeShareTest table (1149, 149, 0, 100, 1149) expects share 1000, but the plan's own formula gives 1100 (gross 1149, fee min(49, 149) = 49, share min(1149 − 49, 1149 − 49) = 1100). The test pins 1100; with 1000 the M2 guard proof could not go red.

### Review-fix round 1 (2026-10-05)

- `Cycle.revenueMinor` @Schema and API_CONTRACT.md: parenthetical now "(what the organizer still holds from each disputed order, booking fee excluded)"; marker substring kept.
- FR email won clause: "S'il est gagné, elle vous revient." (txt and html).
- New scenario `disputes_beyond_the_orders_remaining_gross_are_capped_per_order` (1149/149, OPEN 1149 + LOST 1149 → withheld 1149, share 1000). Red proof M8, grouping by `d.id` in `withholdingRowsByEventIds` (scratch copy): `expected: 1149L but was: 2298L`.
- New `DisputeWithholdingTest.a_dispute_whose_order_is_missing_withholds_its_whole_amount` (stubbed row, order id set, total null → 700/700); the FK on `disputes.order_id` keeps such a row out of the DB. Red proof M9, branch reduced to `orderId() == null`: NullPointerException on `totalMinor()`.
- CLAUDE.md and STRIPE_LIVE_CUTOVER.md: "disputed face value" → "organizer share of open/lost disputes"; DisputeWithholding split paragraph trimmed to 2 lines.
- Branch fast-forwarded to origin/master b340bd2a with `git merge --ff-only` (no branch commits, no file overlap, no stash).
