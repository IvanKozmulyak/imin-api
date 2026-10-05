# Payouts use the amounts Stripe settled (api)
stripe-settlement-currency · Subagent · Notion: (card URL not provided)

## Goal and scope

Ivan, 2026-10-05: "let Stripe handle currency". An event may be priced in any currency. imin never does its own FX. Every amount imin moves (payout, transfer reversal, return transfer) is sized from what Stripe actually settled.

**Facts (cited).**
- The checkout creates a destination charge with `application_fee_amount` + `transfer_data.destination` and no `on_behalf_of` (StripeCheckoutService.java:451-461; StripePaymentIntentService.java:190-198). Stripe transfers the whole charge, converted into the platform settlement currency, then takes the application fee back. Sandbox probe 2026-10-05 on the FR/EUR platform: a USD charge of 1149 with fee 149 produced a 1025 EUR platform balance transaction (rate 0.892011) and a 1025 EUR transfer. Reversals on that transfer are EUR.
- The order keeps only presentment figures: `totalMinor` = `pi.amount`, `currency` = `pi.currency`, `applicationFeeMinor` = `pi.application_fee_amount` (PaidCheckoutService.java:186-189, 217).
- Prod today: all 69 events are EUR.

**Every computation that assumes presentment currency == settlement currency** (current code):

| # | Where | What it assumes | Decision |
|---|---|---|---|
| 1 | PostEventPayoutService.java:261-270 | Event net = Σ`total` − refunds − net fee − dispute share, in presentment minor units | Settlement units, per order |
| 2 | PostEventPayoutService.java:300-309 | Balance bucket matched on `event.currency` (a USD event reads `usd` = 0, so it is never paid) | Read the event's settlement currency |
| 3 | PostEventPayoutService.java:371, 403-404 | `PayoutRun.currency` and `Payout.create` currency = event currency | Settlement currency |
| 4 | PostEventPayoutService.java:636 | BLOCKED no-bank run currency = event currency | Settlement currency |
| 5 | PostEventPayoutService.java:677, 699-706 | Platform-funded refund reversal = `amount − feeRefund` in presentment; refuses when `refund.currency ≠ transfer.currency` | Converted amount; guard compares `order.settlementCurrency` with the transfer currency |
| 6 | PostEventPayoutService.java:789, 831-839 | LOST-dispute reversal = presentment share; refuses when `dispute.currency ≠ transfer.currency` | Converted share; guard compares `order.settlementCurrency` with the transfer currency. The dispute-vs-order presentment guard at :783-788 stays |
| 7 | PostEventPayoutService.java:927 | Return transfer `setCurrency(d.getCurrency())`: a USD dispute would be returned in USD | `order.settlementCurrency` |
| 8 | DisputeWithholding.java:82-102 (`owedOnOrder`, `returnableOnOrder`, `lostShare`) | Presentment share minus `recovered/returned_minor` (which are transfer-currency amounts) | Settlement units |
| 9 | DisputeWithholding.java:108-119 + DisputeRepository.java:278-294 | Hold for other events' debts filtered on `lower(o.currency) = :currency` | Filter on `o.settlementCurrency`, share in settlement units |
| 10 | DisputeWithholding.java:68-71 + DisputeRepository.java:116-126 | `organizerShareLiveMinor` (payout only) in presentment | Settlement units |
| 11 | SettlementIngestService.java:105, 164 | Mirrors `transfer`/`payout` amount + currency exactly as Stripe reports them | Already settlement currency: no change |
| 12 | EventOverviewService.java:91-108, SalesDashboardService.java:92, DashboardRevenue.java:36-56 | Organizer readouts in presentment currency | Stay in presentment currency (what buyers paid). Fix javadocs that claim "what lands in the payout" |
| 13 | RefundService.java:160-166, 194; StripeRefundService | Refund requested in presentment on the charge; Stripe reverses proportionally in transfer currency | Correct as is; javadoc on the recovery amount updated |
| 14 | DisputeNotifier.java:101 | Dispute amount shown in dispute (presentment) currency | Correct: it is what the buyer disputed |

**Options.** I could not fetch Stripe docs in this run (no context7 tool). Reasoning is from the Stripe API reference. URLs: docs.stripe.com/connect/destination-charges, /connect/currencies, /api/refunds/create (`reverse_transfer`, `refund_application_fee`: both proportional), /api/application_fees/object, /api/charges/object (`balance_transaction`, `transfer`, `application_fee`). The transfer fact is backed by the probe. The application-fee currency is UNVERIFIED (live-test L1).

- **(a) Read Stripe's settled amounts. CHOSEN.** Per order, store the transfer currency and amount, plus the application fee's amount in that currency. Each presentment figure on the order converts at that order's own Stripe ratio: `gross_s(x) = round½↑(x·Gs/G)` and `fee_s(y) = round½↑(y·Fs/F)`. This is the same proportional rule Stripe uses for `reverse_transfer` and `refund_application_fee`. It is the identity when Gs = G and Fs = F, which holds for every EUR order today. It needs no FX table, works for zero-decimal currencies (both sides are already minor units), and needs one Stripe read per order, once.
- **(b) Settle in presentment currency.** Not feasible as a small change.
- `on_behalf_of` requires `card_payments` on the connected account. Our v2 recipient accounts have only `stripe_transfers` (StripeCheckoutService.java:458-461; StripeConnectService.java:221-249). It would also move the merchant of record.
- Adding a USD bank account to the FR platform would settle USD charges in USD. But Express accounts with EUR banks would then either convert on transfer or hold USD balances that need per-currency payouts. FX still happens; it just moves. Payouts would need multi-currency runs, and every non-EUR platform settlement currency would need a bank account.

**What stays in presentment currency.** Overview revenue and "After imin fee", Sales dashboard, org home, buyer-facing amounts, refund requests, dispute emails.

**What is in settlement currency.** The payout run, payout emails and in-app notifications (already formatted from `run.currency`). Also the Payouts tab and summary, which come from Stripe `settlements` rows (already the case). The shown payout figure does switch currency for non-EUR events. That is correct, because it is the money that reaches the bank.

**Backfill.** Every existing order with `lower(currency) = 'eur'` gets `settlement = (eur, total_minor, application_fee_minor)`. That holds because a destination charge without `transfer_data.amount` transfers the full EUR total, and EUR on the EUR platform does not convert. Non-EUR rows (test-era only) stay NULL and are stamped by the sweep in their own key mode.

**Out of scope:** fulfilment-time stamping (not needed; the sweep is the only consumer); org home mixing currencies across events (DashboardRevenue.java:43-48; follow-up card); multi-currency payouts per event.

## Repos in ship order

1. `api` (imin-api, base `master`, worktree `/Users/ivan/imin/imin-api/.claude/worktrees/stripe-settlement-currency`).

No webapp, public or fan-app change: no DTO or endpoint changes, and no figure shown by a frontend changes meaning (see copy ledger under i18n impact).

## Affected files (per repo)

**imin-api** (paths under `src/main/java/com/imin/iminapi/` unless shown otherwise)

| # | File | Change |
|---|---|---|
| 1 | `src/main/resources/db/migration/V174__order_settlement_amounts.sql` (new) | Add `settlement_currency VARCHAR(8)`, `settlement_gross_minor BIGINT`, `settlement_fee_minor BIGINT`, all NULL. Backfill EUR rows 1:1 (`lower(currency)`). Add constraint `ck_orders_settlement` (quoted under Ordered steps, step 1). V173 is reserved by the predictor-web-research worktree |
| 2 | `model/Order.java` | Three fields `settlementCurrency`, `settlementGrossMinor` (Long), `settlementFeeMinor` (Long), `insertable = true, updatable = false`. Javadoc: written by the insert (NULL in production) and by `OrderRepository.stampSettlement` only |
| 3 | `repository/OrderRepository.java` | Add `stampSettlement` (conditional `@Modifying` UPDATE, `@Transactional(REQUIRES_NEW)`), `findUnstampedPaidByOrgId(orgId, testMode, Pageable)` → `List<Object[]>{id, stripePaymentIntentId}`, and `settlementRowsByEventId(eventId)` → `List<OrderSettlementRow>`. Delete `sumLiveTotalMinorByEventId` and `sumLiveApplicationFeeMinorByEventId` (no caller left) |
| 4 | `refund/RefundRepository.java` | Delete `sumSucceededLiveRefundMinorByEventId` and `sumSucceededLiveRefundApplicationFeeMinorByEventId` (no caller left; their refund term moves into `settlementRowsByEventId`) |
| 5 | `settlement/SettlementRate.java` (new) | Pure record `(totalMinor, feeMinor, settledGrossMinor, settledFeeMinor)` with `gross(x)`, `fee(y)`, `stake(refunded, feeRefunded)`, `organizerShare(DisputeShare, refunded, feeRefunded)`, `static of(Order)` (throws `IllegalStateException` when unstamped) |
| 6 | `payout/OrderSettlementRow.java` (new) | Record `(orderId, totalMinor, feeMinor, settlementCurrency, settlementGrossMinor, settlementFeeMinor, refundedMinor, feeRefundedMinor)` |
| 7 | `payout/OrderSettlementStamper.java` (new) | `stampOrg(Organization)`: for each unstamped paid order in the running key's mode (≤ 500 per call), one PaymentIntent retrieve with expands, checks, then `orders.stampSettlement` |
| 8 | `payout/PayoutRunRepository.java` | Add `existsByEventIdAndTestModeFalseAndStatusInAndCurrencyNot(UUID, Collection<PayoutRunStatus>, String)` |
| 9 | `payout/PostEventPayoutService.java` | Call the stamper in step 0b of both `payOneEvent` and `recoverForOrg`. Settlement net, currency and guards in steps 2-4. Park with settlement currency. Recovery and return in settlement units and currency. Update class javadoc steps 2-3 |
| 10 | `dispute/DisputeSettlementRow.java` (new) | Record `(eventId, orderId, totalMinor, feeMinor, settlementCurrency, settlementGrossMinor, settlementFeeMinor, disputedMinor)` |
| 11 | `dispute/DisputeRepository.java` | `liveWithholdingRowsByEventId` and `unrecoveredLostRowsByOrgExcludingEvent` select `DisputeSettlementRow`; the latter filters `o.settlementCurrency = :currency`. Add `countLiveUnrecoveredLostOnUnstampedOrdersByOrgId(orgId, lost)` |
| 12 | `dispute/DisputeWithholding.java` | `organizerShareLiveMinor`, `owedOnOrder`, `returnableOnOrder` and `unrecoveredLostShareLiveMinorByOrg` return settlement minor units via `SettlementRate`; javadocs say so. Presentment readouts (`withheldMinor`, `organizerShareMinor`, `…ByOrgWindow`, `…ByEvent`) unchanged |
| 13 | `stripe/StripeRefundService.java` | Javadoc only: the recovery reverses `gross_s(A) − fee_s(F·A/G)` in the transfer currency |
| 14 | `service/dashboard/DashboardRevenue.java` | Javadoc only (:33-35): presentment counterpart of the payout net, not the same expression any more |
| 15 | `dto/event/EventOverviewResponse.java` | Javadoc only (:19-23): `revenueAfterFeesMinor` is in the event currency; the payout is in the settlement currency |
| 16 | `CLAUDE.md` (repo root) | Stripe section: rewrite the currency sentences of the dispute paragraph (:209), replace the "Operator note" with the settlement rule, and add the three columns plus the stamper to the state model |
| 17 | `src/test/java/com/imin/iminapi/settlement/SettlementRateTest.java` (new) | Pure unit tests (see Test impact) |
| 18 | `src/test/java/com/imin/iminapi/payout/OrderSettlementStamperTest.java` (new) | `@SpringBootTest`, real `StripeClient` over a fake `StripeResponseGetter`, H2 |
| 19 | `src/test/java/com/imin/iminapi/migration/OrderSettlementMigrationScenarios.java` (new) | Flyway `target("172")` → seed rows → migrate → assert backfill + CHECK |
| 20 | `src/test/java/com/imin/iminapi/migration/OrderSettlementMigrationH2Test.java` (new) | H2 subclass (pattern: VenueCoordsConstraintH2Test) |
| 21 | `src/test/java/com/imin/iminapi/migration/OrderSettlementMigrationPostgresTest.java` (new) | Testcontainers subclass (`disabledWithoutDocker = true`) |
| 22 | `src/test/java/com/imin/iminapi/payout/PostEventPayoutServiceTest.java` | Fixture `order(..)` (:1162-1175) stamps `eur/total/fee`. New overload `order(e, total, fee, currency, sCur, Gs, Fs)`. Fake gains `/v1/payment_intents/` handling. New cases P1-P9 |
| 23 | `src/test/java/com/imin/iminapi/payout/PostEventPayoutDisputeRecoveryTest.java` | Fixture `order(..)` (:1006-1016) stamps EUR 1:1, with an overload for converted orders. Replace `a_transfer_in_another_currency_than_the_dispute_is_never_reversed` (:543-556) and `a_platform_funded_refund_on_a_converted_transfer_is_never_reversed` (:559-569) with worked C/B. Rework `a_debt_in_another_currency_is_not_held_back` (:936-957) to create the GBP order with `gbp` settlement at insert (today it sets currency after save, which would leave a stale `eur` stamp). New R-cases |
| 24 | `src/test/java/com/imin/iminapi/payout/PostEventPayoutSweeperTest.java` | Fixture `orderOn` (:389-400) stamps EUR 1:1; expected values unchanged |
| 25 | `src/test/java/com/imin/iminapi/payout/PayoutTestModeExclusionTest.java` | Fixture (:453-464) stamps EUR 1:1. Replace the direct sum assertions at :125-130 (deleted queries) with `orders.settlementRowsByEventId` asserting only the live order's row (Gs 10 000, Fs 1 000, refunds 0) |
| 26 | `src/test/java/com/imin/iminapi/dispute/DisputeWithholdingScenarios.java` | Fixture (:248-260) stamps EUR 1:1, so the expected 1000/1000/435/700 at :91/124/137/153 are unchanged. Add one converted-order scenario (runs on H2 and PG) |
| 27 | `src/test/java/com/imin/iminapi/dispute/DisputeRecoveryQueriesPostgresTest.java` | Fixture (:294-305) stamps EUR 1:1. :234-244 creates the GBP order with `gbp` settlement at insert, and asserts `DisputeSettlementRow(other, a, 1149, 149, "eur", 1149, 149, 1149)`. Add a PG case for `settlementRowsByEventId` (left join + group by) |

**No change needed (one line each):**
- `stripe/SettlementIngestService.java`: mirrors Stripe's transfer and payout amount and currency, already settlement (:105, :164).
- `controller/payout/dto/PayoutRowResponse.java`, `service/payout/PayoutService.java`: built from `settlements` rows (PayoutService.java:111-151).
- `payout/OrganizerPayoutNotifier.java`: formats `run.amountMinor/run.currency` (:174), which is now the settlement currency (copy ledger rows 1-3).
- `payout/PostEventPayoutSweeper.java`: only calls `recoverForOrg` (:137) and `payOneEvent` (:111), which both gain the stamper.
- `payout/PayoutRetentionMonitor.java`: a log line in presentment (:84-86), no math.
- `dispute/DisputeShare.java`: presentment split, reused as the input to `SettlementRate`.
- `dispute/DisputeIngestService.java`, `dispute/DisputeNotifier.java`: dispute amount and currency are Stripe's presentment figures.
- `refund/RefundService.java`: the refund is requested on the charge in presentment; Stripe reverses proportionally in the transfer currency.
- `service/ticket/PaidCheckoutService.java`, `stripe/StripeCheckoutService.java`, `stripe/StripePaymentIntentService.java`: fulfilment and checkout untouched; inserts leave the stamp NULL.
- `service/audience/SmsConsentService.java`: full-entity `orders.save` (:66, :85) cannot write the stamp (`updatable = false`). Proven by stamper test S9.
- `service/event/EventOverviewService.java`, `service/event/SalesDashboardService.java`: presentment readouts stay.
- Tests `DisputeShareTest`, `DisputeRepositoryWithholdingTest`, `DisputeRecoveryColumnsTest`, `DisputeIngestServiceTest`, `DisputeAttributionSweeperTest`, `EventSalesTotalsTest`, `EventServiceSalesFiguresTest`, `DashboardServiceTest`, `SettlementIngestServiceTest`: I grepped each for `organizerShareLiveMinor|owedOnOrder|returnableOnOrder|unrecoveredLost|liveWithholdingRows|sumLive|sumSucceededLiveRefund`. None reads a changed method or the new columns.

## Ordered steps

0. **Baseline.** In the worktree run `docker info`, then the full gate (see Verification commands) on the untouched tree. Record the `Tests run:` line. A red baseline gets its own card.

1. **Migration V174** (`V174__order_settlement_amounts.sql`):
 ```sql
 ALTER TABLE orders ADD COLUMN settlement_currency VARCHAR(8);
 ALTER TABLE orders ADD COLUMN settlement_gross_minor BIGINT;
 ALTER TABLE orders ADD COLUMN settlement_fee_minor BIGINT;
 -- EUR on the EUR platform never converts, and a destination charge transfers its whole total.
 UPDATE orders SET settlement_currency = 'eur', settlement_gross_minor = total_minor,
                   settlement_fee_minor = application_fee_minor
  WHERE lower(currency) = 'eur';
 ALTER TABLE orders ADD CONSTRAINT ck_orders_settlement CHECK (
   (settlement_currency IS NULL AND settlement_gross_minor IS NULL AND settlement_fee_minor IS NULL)
   OR (settlement_currency IS NOT NULL AND settlement_gross_minor >= 0 AND settlement_fee_minor >= 0
       AND settlement_fee_minor <= settlement_gross_minor));
 ```
 The stamper rejects any value outside this constraint before writing (step 4), so no write can trip it. If V173 lands on master first, keep V174. If master is beyond V173 at ship time, take the next free number and `./mvnw clean`.

2. **Order entity.** Add the three fields with `@Column(name = …, insertable = true, updatable = false)`. Javadoc (1-2 lines): "What Stripe settled for this order, in the destination transfer's currency. Written only by `OrderRepository.stampSettlement`; NULL until the payout sweep reads it."

3. **SettlementRate** (pure, no Spring):
 - `scale(x, num, den)`: returns 0 when `den <= 0 || x <= 0`. Returns `x` when `num == den`. Otherwise `Math.floorDiv(Math.addExact(Math.multiplyExact(Math.multiplyExact(2L, x), num), den), Math.multiplyExact(2L, den))`. That is half-up, overflow-loud, and the same rounding as `DisputeShare.of`'s `Math.round` for non-negative values.
 - `gross(x) = scale(x, settledGrossMinor, totalMinor)`; `fee(y) = scale(y, settledFeeMinor, feeMinor)`.
 - `stake(refunded, feeRefunded) = max(0, max(0, Gs − gross(refunded)) − max(0, Fs − fee(feeRefunded)))`.
 - `organizerShare(DisputeShare s, refunded, feeRefunded) = max(0, min(gross(s.grossWithheldMinor()) − fee(s.feeShareMinor()), stake(refunded, feeRefunded)))`.
 - `of(Order)` throws `IllegalStateException` when `settlementCurrency == null`.

4. **Stamper** (`OrderSettlementStamper`, `@Component`, not transactional):
 - `orders.findUnstampedPaidByOrgId(orgId, !props.isLiveKey(), PageRequest.of(0, 500))`: JPQL `select o.id, o.stripePaymentIntentId from Order o where o.orgId = :orgId and o.settlementCurrency is null and o.totalMinor > 0 and o.testMode = :testMode and o.stripePaymentIntentId is not null order by o.createdAt`.
 - Per row, call `stripeClient.paymentIntents().retrieve(piId, PaymentIntentRetrieveParams.builder().addExpand("latest_charge.balance_transaction").addExpand("latest_charge.transfer").addExpand("latest_charge.application_fee").build())`. Signature in stripe-java 32.1.0 (`~/.m2/.../stripe-java-32.1.0.jar`, `javap`): `PaymentIntent PaymentIntentService.retrieve(String, PaymentIntentRetrieveParams) throws StripeException`. `PaymentIntent.getLatestChargeObject()`, `Charge.getTransferObject()`, `Charge.getApplicationFeeObject()`, `Charge.getBalanceTransactionObject()`, `Charge.getApplicationFeeAmount(): Long`, `Transfer.getAmount(): Long`, `Transfer.getCurrency(): String`, `ApplicationFee.getAmount()/getCurrency()`, `BalanceTransaction.getCurrency()/getExchangeRate(): BigDecimal`.
 - No stamp, and log ERROR naming the order, when:
   - the latest charge or transfer is null;
   - the balance-transaction currency ≠ the transfer currency;
   - the charge has a nonzero fee and the application fee object is null, or its currency ≠ the transfer currency;
   - `fee > transfer.amount`, or either is negative (the CHECK constraint's domain).
 - When the charge's `application_fee_amount` is null or 0, `Fs = 0`.
 - A `StripeException` logs WARN (code only) and continues with the next row. A `RuntimeException` from the write is caught and logged at ERROR, never rethrown. No money moved, so the next tick re-reads.
 - Write via `orders.stampSettlement(id, transfer.currency.toLowerCase(Locale.ROOT), transfer.amount, fee)`: `@Modifying(flushAutomatically = true, clearAutomatically = true) @Transactional(propagation = REQUIRES_NEW)`, `update Order o set o.settlementCurrency = :c, o.settlementGrossMinor = :g, o.settlementFeeMinor = :f where o.id = :id and o.settlementCurrency is null`. It runs in its own transaction, so it never clears the payout transaction's context and survives a rollback of that transaction. Log the exchange rate at INFO.

5. **Queries.**
 - `OrderRepository.settlementRowsByEventId`:
   ```
   select new com.imin.iminapi.payout.OrderSettlementRow(o.id, o.totalMinor, o.applicationFeeMinor,
           o.settlementCurrency, o.settlementGrossMinor, o.settlementFeeMinor,
           coalesce(sum(r.amountMinor), 0), coalesce(sum(r.applicationFeeRefundMinor), 0))
     from Order o left join com.imin.iminapi.refund.Refund r
            on r.orderId = o.id and r.status = com.imin.iminapi.refund.RefundStatus.SUCCEEDED
    where o.eventId = :eventId and o.testMode = false and o.totalMinor > 0
    group by o.id, o.totalMinor, o.applicationFeeMinor, o.settlementCurrency,
             o.settlementGrossMinor, o.settlementFeeMinor
   ```
   It keeps the V130 rule: test-mode orders and their refunds are out of every term.
 - `DisputeRepository`: the two payout-only queries select `DisputeSettlementRow` (add `o.settlementCurrency, o.settlementGrossMinor, o.settlementFeeMinor` to select and group by). `unrecoveredLostRowsByOrgExcludingEvent` filters `o.settlementCurrency = :currency` instead of `lower(o.currency)`. New `countLiveUnrecoveredLostOnUnstampedOrdersByOrgId`: `select count(d) from Dispute d join Order o on o.id = d.orderId where d.orgId = :orgId and d.status = :lost and d.testMode = false and d.recoveredAt is null and o.settlementCurrency is null`.
 - `PayoutRunRepository`: the derived `existsByEventIdAndTestModeFalseAndStatusInAndCurrencyNot`.
 - Delete the four presentment payout sums listed in Affected files rows 3-4.

6. **DisputeWithholding.**
 - `organizerShareLiveMinor(eventId)`: per `DisputeSettlementRow`, `SettlementRate.organizerShare(DisputeShare.of(...presentment...), refunded, feeRefunded)`, with refunds from the existing `sumSucceededAmountAndFeeByOrderIds`. A row with no order or no stamp counts `disputedMinor` in full (conservative; `payOneEvent` has already refused unstamped orders by then).
 - `owedOnOrder(order) = max(0, lostShare_s − sumHeldByOrderId)`; `returnableOnOrder(order) = max(0, sumHeld − lostShare_s)`, with `lostShare_s = SettlementRate.of(order).organizerShare(DisputeShare.of(total, fee, refunded, feeRefunded, lost), refunded, feeRefunded)`.
 - `unrecoveredLostShareLiveMinorByOrg(orgId, eventId, settlementCurrency)` uses the settlement rows and the same per-order owed.
 - Javadoc for each says "settlement minor units".

7. **PostEventPayoutService** (inject `OrderSettlementStamper`):
 - **Step 0b**, in `payOneEvent` (:221) and `recoverForOrg` (:486): `stamper.stampOrg(org)` first, then the existing recoveries.
 - **`recoverPlatformFundedRefunds`**: load `orders.findById(refund.getOrderId())`. When the order is missing or unstamped, log ERROR and continue (debt stays open). `amount = rate.gross(refund.amount) − rate.fee(refund.applicationFeeRefundMinor)`. The guard becomes `order.getSettlementCurrency().equalsIgnoreCase(trCurrency)`. Key `refund:<id>:reversal` unchanged; the amount is identical for every EUR refund already open.
 - **`recoverLostDisputes`**: keep the dispute-vs-order presentment guard. Add: unstamped order → log ERROR, continue. Transfer guard: `order.getSettlementCurrency()` vs `tr.getCurrency()`. Amounts come from the settlement `owedOnOrder`.
 - **`returnRecoveredDisputes`**: unstamped order → log ERROR, add it to `failedOrders`, continue. Use `.setCurrency(order.getSettlementCurrency())` and log that currency.
 - **Step 1b** (after the open-dispute guard): when `disputes.countLiveUnrecoveredLostOnUnstampedOrdersByOrgId(org, LOST) > 0`, log WARN and return. A debt we cannot size cannot be held back.
 - **Step 2**:
   - `rows = orders.settlementRowsByEventId(eventId)`. Any row with null `settlementCurrency` → WARN "n orders not stamped yet", return.
   - Distinct currencies > 1 → ERROR, return.
   - `cur` = the single currency. With no rows, `net = 0` → existing "nothing to pay" return.
   - `net = max(0, Σ rate(row).stake(row.refunded, row.feeRefunded) − disputeWithholding.organizerShareLiveMinor(eventId))`.
 - **Step 2b**: `parkNoBankAccount(event, org, net, cur)` sets `r.setCurrency(cur)`.
 - **Step 3**: drop the `event.getCurrency()` line (:300); match the balance bucket on `cur`. Step 3b passes `cur`.
 - **Step 4**: before `alreadyTriggered`, `if (payoutRuns.existsByEventIdAndTestModeFalseAndStatusInAndCurrencyNot(eventId, ALREADY_TRIGGERED, cur))` → ERROR, return. Units would not compare.
 - Update the class javadoc steps 2-3: "matched to the event's settlement currency".
 - Comments: 1-2 lines, no ticket or milestone ids.

8. **Docs.** Rows 13-16 of Affected files, in the same edit as the behaviour change.

9. **Tests** as listed under Test impact. For each guard, prove the test goes red without it (table under Test impact).

10. **Full gate.** Run it again after any rebase.

**Worked examples** (sandbox rate 0.892011 from the 2026-10-05 probe; `Fs = 133` is my calculation, UNVERIFIED until L1). Order: 1 ticket, 1000 + fee 149 = **1149 USD**, transfer **Gs = 1025 EUR** (probe), application fee **Fs = 133 EUR** (149 × 0.892011 = 132.91).

| Case | Presentment (USD) | Settlement arithmetic (EUR, half-up) | Expected Stripe call |
|---|---|---|---|
| W1 payout, nothing else | net 1149 − 149 = 1000 | stake = 1025 − 133 = **892** | `Payout.create` amount 892, currency `eur`; run currency `eur`. Today: `usd` bucket = 0, never paid |
| W2 refund half (A = 574, fA = round(149·574/1149) = 74) | net (1149−574) − (149−74) = 500 | gross_s(574) = ⌊(2·574·1025 + 1149)/2298⌋ = 512; fee_s(74) = ⌊(2·74·133 + 149)/298⌋ = 66; stake = 513 − 67 = **446** | Payout 446 `eur`. Platform-funded variant: reversal 446 on the transfer, key `refund:<id>:reversal` |
| W3 dispute LOST in full, no refund | DisputeShare.of(1149,149,0,0,1149) = (1149, 149, 1000) | gross_s(1149) = 1025, fee_s(149) = 133, share = min(892, stake 892) = **892** | Reversal 892 on `tr_`, key `dispute:<id>:reversal:892`; `recovered_minor` 892; event net 892 − 892 = 0 → no payout |
| W4 W3 then WON | returnable = held 892 − lost share 0 | 892 | Platform transfer amount 892, currency **`eur`** (today `usd`), key `dispute:<id>:return:0:892` |
| W5 refund 574/74 then LOST in full | DisputeShare.of(1149,149,574,74,1149) = (575, 75, 500) | gross_s(575) = ⌊1179899/2298⌋ = 513; fee_s(75) = ⌊20099/298⌋ = 67; share = min(446, stake 446) = **446** | Reversal 446 (transfer 1025 with 512 already reversed by the refund leaves 513 ≥ 446) |
| W6 EUR order 1149/149 settled 1149/149 | as before | identity: every figure equals today's | All existing EUR tests unchanged (e.g. worked_A reversal 1000) |

## Verification commands

From the workspace table, row `api`. Run in `/Users/ivan/imin/imin-api/.claude/worktrees/stripe-settlement-currency`:

```bash
docker info >/dev/null && /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test
# targeted, comma-separated (never A+B); judge by its own "Tests run:" line
/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=SettlementRateTest,OrderSettlementStamperTest,OrderSettlementMigrationH2Test,OrderSettlementMigrationPostgresTest,PostEventPayoutServiceTest,PostEventPayoutDisputeRecoveryTest,PostEventPayoutSweeperTest,PayoutTestModeExclusionTest,DisputeWithholdingH2Test,DisputeWithholdingPostgresTest,DisputeRecoveryQueriesPostgresTest
```

Skipped Testcontainers tests count as red even with BUILD SUCCESS. Run `./mvnw clean` if the migration is renumbered.

Prod pre-flight (read-only, main session, `railway` psql; must hold before ship):
- `select lower(currency), test_mode, count(*) from orders group by 1,2;` expects live rows only `eur`.
- `select distinct currency from payout_runs;` expects only `eur`.

## Test impact

**Branch map → tests**

| # | Branch (code) | Test (file → name) | Minimal setup |
|---|---|---|---|
| 1 | `scale` identity (num == den) | SettlementRateTest → `eur_order_converts_to_itself` | (1149,149,1149,149): gross(574) = 574, fee(74) = 74 |
| 2 | `scale` conversion W2 | `usd_order_converts_at_its_own_stripe_ratio` | (1149,149,1025,133): gross 512, fee 66 |
| 3 | half-up at exactly .5 | `exact_half_rounds_up` | (2,0,3,0): gross(1) = 2 |
| 4 | den ≤ 0 (no fee) | `no_fee_converts_to_zero` | (1000,0,892,0): fee(5) = 0 |
| 5 | stake clamp ≥ 0 | `stake_never_negative` | refunded = total: stake 0 |
| 6 | share capped at stake, W5 | `share_after_a_refund_is_capped_at_the_stake` | = 446 |
| 7 | W3 share | `full_lost_dispute_on_a_usd_order_is_892_eur` | = 892 |
| 8 | overflow | `overflow_throws` | Long.MAX inputs → ArithmeticException |
| 9 | `of(Order)` unstamped | `unstamped_order_has_no_rate` | IllegalStateException |
| S1 | stamper happy path + Stripe args | OrderSettlementStamperTest → `stamps_transfer_and_fee_from_one_expanded_retrieve` | Test-mode order (mode = running key), PI fake returns charge{transfer{1025,eur}, application_fee{133,eur}, balance_transaction{eur, 0.892011}}. Assert path `/v1/payment_intents/<pi>`, params `expand` = exactly the three strings, columns `eur/1025/133` |
| S2 | stamped rows skipped | `a_stamped_order_is_never_read_again` | Stamped order created FIRST, unstamped second; exactly one retrieve, for the second |
| S3 | key-mode filter | `an_order_of_the_other_key_mode_is_not_read` | Order with `testMode = isLiveKey()` (opposite); zero retrieves |
| S4 | `totalMinor > 0` filter | `a_free_order_is_not_read` | total 0, PI id set; zero retrieves |
| S5 | no transfer | `a_charge_without_a_transfer_is_left_unstamped` | NULL stamp |
| S6 | fee currency ≠ transfer currency | `a_fee_in_another_currency_is_left_unstamped` | NULL stamp |
| S7 | no application fee | `a_charge_without_a_fee_stamps_zero` | `application_fee_amount` null → Fs 0 |
| S8 | Stripe error isolates the row | `a_failed_read_leaves_that_order_and_stamps_the_next` | First PI 500, second OK |
| S9 | conditional write + `updatable = false` | `a_stamp_is_written_once_and_survives_a_full_save` | `stampSettlement` twice → 1 then 0 rows; then `orders.save(entity)` (SmsConsentService path) keeps the stamp. Reverse order: save, then stamp → stamped |
| S10 | balance-txn currency ≠ transfer currency | `a_balance_transaction_in_another_currency_is_left_unstamped` | NULL stamp |
| M1 | backfill EUR | OrderSettlementMigrationScenarios → `eur_orders_settle_one_to_one` | Rows `eur`, `EUR`, free `eur` (0/0) seeded at V172 → stamped `eur`, total, fee |
| M2 | non-EUR untouched | `non_eur_orders_stay_unstamped` | `usd` row → all NULL |
| M3 | CHECK constraint | `half_a_stamp_is_rejected`, `fee_above_gross_is_rejected`, `negative_is_rejected` | DataIntegrityViolationException |
| P1 | W1 payout in settlement currency | PostEventPayoutServiceTest → `worked_W1_a_usd_event_pays_892_eur` | USD event, order (1149,149,usd → eur,1025,133), balance eur 5000; payout 892 `eur`, run currency `eur` |
| P2 | W2 | `worked_W2_half_refunded_usd_order_pays_446_eur` | + SUCCEEDED refund 574/74 |
| P3 | unstamped → skip | `an_unstamped_order_holds_the_event` | Live order with NULL stamp; zero payouts, zero runs |
| P4 | mixed currency → skip | `two_settlement_currencies_on_one_event_pay_nothing` | Orders stamped eur + gbp |
| P5 | prior run in other currency | `a_triggered_run_in_another_currency_pays_nothing` | PAID run `usd` on the event |
| P6 | no-bank park currency | `no_bank_parks_the_settled_net_in_settlement_currency` | hasBank = false; BLOCKED run 892 `eur` |
| P7 | stamper wired into `payOneEvent` | `the_payout_tick_stamps_the_orgs_orders` | Unstamped order in the running key mode; PI retrieve observed + stamp written (positive interaction) |
| P8 | step 1b | `an_unstamped_lost_debt_elsewhere_holds_the_payout` | Other event: LOST unrecovered dispute on an unstamped live order; zero payouts |
| P9 | EUR regression | all existing PostEventPayoutServiceTest cases | Fixture stamps 1:1; expected values unchanged |
| R1 | W3 | PostEventPayoutDisputeRecoveryTest → `worked_W3_a_lost_usd_order_reverses_892_eur` (replaces :543) | Order usd→eur 1025/133, dispute usd 1149, transfer {1025, eur}; reversal 892, key `…:reversal:892`, `recovered_minor` 892 |
| R2 | W4 return currency | `worked_W4_a_won_usd_dispute_returns_892_in_eur` | W3 then WON; transfer create params contain `currency=eur`, amount 892 |
| R3 | W2 platform-funded | `worked_W2_a_platform_funded_usd_refund_reverses_446_eur` (replaces :559) | Refund 574/74 platformFunded; reversal 446 |
| R4 | W5 | `worked_W5_refund_then_lost_reverses_446_eur` | transfer {1025, 512} |
| R5 | transfer currency ≠ settlement (dispute) | `a_transfer_in_another_currency_than_the_settlement_is_never_reversed` | Stamp eur, fake transfer `gbp`; no reversal |
| R6 | same guard (refund) | `a_platform_funded_refund_on_a_transfer_in_another_currency_is_never_reversed` | Stamp eur, transfer `gbp` |
| R7 | unstamped (dispute + refund) | `an_unstamped_order_is_never_reversed` | NULL stamp, test-mode opposite so the stamper cannot fill it; no reversal |
| R8 | 3b filter is settlement, not presentment | `a_usd_order_settled_in_eur_is_held_back_from_the_eur_payout` | LOST debt on a usd→eur order on another event, reversal refused; payout reduced by 892 |
| R9 | 3b other settlement currency | `a_debt_in_another_currency_is_not_held_back` (reworked :936) | Order gbp→gbp stamped at insert |
| R10 | stamper wired into `recoverForOrg` | `recovery_for_an_org_without_candidates_stamps_first` | Unstamped order in running mode with LOST dispute; PI retrieve then reversal |
| Q1 | settlement rows on PG | DisputeRecoveryQueriesPostgresTest → `settlement_rows_sum_succeeded_refunds_per_live_order` | Live + test order, one SUCCEEDED and one FAILED refund |
| Q2 | lost rows filter on PG | existing :234 case, rewritten | gbp settlement at insert |
| Q3 | live rows test-mode exclusion | PayoutTestModeExclusionTest → `netSumsExcludeTestModeOrders` (rewritten :119-130) | Rows contain only the live order |
| D1 | converted share in withholding | DisputeWithholdingScenarios → `a_usd_order_withholds_its_settled_share` | Expected 892 on H2 + PG |

**Guard proofs** (remove the line once, confirm red, restore):

| Guard | Red test |
|---|---|
| Stamper `testMode = :testMode` | S3 |
| Stamper `settlementCurrency is null` | S2 |
| Stamper `totalMinor > 0` | S4 |
| `stampSettlement … and o.settlementCurrency is null` | S9 |
| `updatable = false` on the three fields | S9 (save after stamp) |
| Step 2 unstamped return | P3 |
| Step 2 distinct-currency return | P4 |
| Step 4 currency-not guard | P5 |
| Step 3 bucket on `cur` (revert to event currency) | P1 |
| Step 1b | P8 |
| Return `setCurrency(order.settlementCurrency)` (revert to `d.getCurrency()`) | R2 |
| Dispute transfer guard on settlement currency | R5 |
| Refund transfer guard on settlement currency | R6 |
| 3b `o.settlementCurrency = :currency` (revert to `lower(o.currency)`) | R8 |
| Stamper call in `payOneEvent` | P7 |
| Stamper call in `recoverForOrg` | R10 |

## Live-test

`/live-test api`:
- **L1 (before ship, sandbox, read-only):** `stripe payment_intents retrieve <probe pi> --expand latest_charge.transfer --expand latest_charge.application_fee --expand latest_charge.balance_transaction`. Record `transfer.amount/currency` (expect 1025 eur), `application_fee.amount/currency` (expected 133 eur, UNVERIFIED) and `balance_transaction.exchange_rate`. If the fee is not in the transfer currency, stop: the stamper refuses every converted order and the plan must change.
- **L2 (before ship, local, sandbox key):** run the app with `STRIPE_PAYOUT_SCHEDULE_MANUAL=true` against the sandbox. Call `OrderSettlementStamper.stampOrg` for the probe org. Easiest path: OrderSettlementStamperTest's pattern with a real sandbox key in an untracked scratch test, never committed. Confirm the probe order stamps `eur/1025/<L1 fee>`.
- **L3 (after deploy, prod, read-only):**
- `select count(*) from orders where lower(currency)='eur' and (settlement_currency is distinct from 'eur' or settlement_gross_minor <> total_minor or settlement_fee_minor <> application_fee_minor);` = 0.
- `select count(*) from orders where settlement_currency is null and total_minor > 0 and test_mode = false;` = 0 (all prod orders are EUR).
- **L4 (after the next 03:00 sweep):** Railway logs show no new ERROR from `OrderSettlementStamper` or the payout path. Any new `payout_runs` row has currency `eur`. Forward invariant: a new live order shows NULL settlement until the first sweep that reaches its org, then is stamped (positive check on the first such order).

## Contract impact

none. No endpoint, DTO field or schema name changes, so there is no OpenAPI marker to wait for. The three columns are internal (Order has no Spring Data REST export of them; orders are not a REST resource). Webapp `src/shared/api/types.ts` and `PUBLIC_PAGE_API.md` are untouched.

## i18n impact

No new or changed UI or email string. Copy ledger for the strings whose figures this plan touches or deliberately leaves alone:

| # | String (file:line) | Field behind it (file:line) | Meaning, scope, range after this change | Rendered next to it |
|---|---|---|---|---|
| 1 | "{amount} is ready to pay out, but your Stripe account has no bank account attached…" (OrganizerPayoutNotifier.java:110-111) + `payout-blocked*.html/txt` `amountFormatted` | `PayoutRun.amountMinor/currency` set in `parkNoBankAccount` (PostEventPayoutService.java:635-636) | The event's computed net, settlement currency, per event, ≥ 1. Exact for unrefunded orders; within 1 minor unit per converted partial refund or dispute | Event name, bank CTA |
| 2 | "{amount} could not be paid out after N attempts…" (OrganizerPayoutNotifier.java:112-114) | `PayoutRun.amountMinor/currency` (PostEventPayoutService.java:369, 371) | The exact amount sent to `Payout.create`, settlement currency, per run | Attempt count, last error |
| 3 | "{amount} has left Stripe for your bank account…" (OrganizerPayoutNotifier.java:142) + `payout-arrived*` | Same as row 2 | Exact paid amount, settlement currency, per run | Event name |
| 4 | webapp "After imin fee · {x}" (copy.ts:622, es :645, fr :634, uk :646) | `revenueAfterFeesMinor` (EventOverviewService.java:102-104) | Presentment (event) currency, per event, all modes, net of refunds, fee and dispute share. Not the payout. Unchanged | `metrics.currency` (event currency) |
| 5 | webapp "{x} after imin fee" (copy.ts:817, es :841, fr :830, uk :851) | Same as row 4 | Same as row 4 | Hero revenue |

Rows 4-5: the strings say "after imin fee", not "payout", so they stay true in the event currency. Only the API javadoc that claimed "what lands in the organizer's payout" is corrected (Affected files row 15).

## Blast radius

- **Money/Stripe.** Every payout, refund recovery, dispute reversal and dispute return goes through the changed code (PostEventPayoutService.java:221, :486, :671-730, :767-956). For EUR orders every amount is identical (SettlementRate identity, W6). New refusal states, each with a signal and a way out:
- order not stamped yet: WARN; re-read next tick;
- stamp refused (no transfer, currency mismatch): ERROR to Sentry; manual reconciliation;
- mixed settlement currencies on one event: ERROR;
- a prior triggered run in another currency: ERROR.
All entry paths are covered: `PostEventPayoutSweeper.sweep` step B → `recoverForOrg`, and step C → `payOneEvent`. No scheduler, reconciler or admin endpoint reaches these state machines another way (`grep payOneEvent|recoverForOrg|transfers().reversals().create|transfers().create|payouts().create` lists only those). `reconcileSubmittedRun` moves no amount.
- **Stripe calls added.** One `GET /v1/payment_intents/{id}` with expands per paid order, once, inside the sweep, ≤ 500 per org per call. No fulfilment-path change.
- **Flyway V174 on `orders`** (a hot table):
- three nullable ADD COLUMNs (metadata-only on PG 17);
- one UPDATE over EUR rows (small table in prod);
- one CHECK. PG validates the CHECK in a full scan holding a lock that blocks writes, which on today's row count is milliseconds.
- Writers of these columns: the migration and `OrderRepository.stampSettlement` only. Existing writers of `orders` rows are `PaidCheckoutService.issuePaidOrder` (insert, :236), `FreeCheckoutService` (insert, :184), `SmsConsentService` (full save, :66, :85), the `EventReminderSender` targeted UPDATEs and `DisputeRepository.attachToOrder` (disputes table). Inserts write NULL. The full save cannot write the stamp (`updatable = false`, test S9 both orderings). The targeted UPDATEs do not name the columns.
- **Shared module.** `DisputeWithholding` is read by Overview, Sales, Dashboard, org home and payout. Only the payout-only methods change units; the presentment methods are byte-identical.
- **Deleted queries:** `OrderRepository.sumLiveTotalMinorByEventId`, `sumLiveApplicationFeeMinorByEventId`, `RefundRepository.sumSucceededLiveRefundMinorByEventId`, `sumSucceededLiveRefundApplicationFeeMinorByEventId`. The only callers were PostEventPayoutService and PayoutTestModeExclusionTest (grep in Affected files).
- **No `/api/v1` contract change; no frontend ship.**

## Risks

1. **Application-fee currency unverified.** If Stripe reports `application_fee` in presentment rather than settlement currency, S6 makes the stamper refuse every converted order. Payouts for non-EUR events then stay held with ERRORs (safe, not wrong money). L1 settles it before ship.
2. **Connected-side conversion.** A non-EUR connected account could convert the EUR transfer into its own currency. The payout would then read the transfer currency's bucket and pay nothing (INFO each night). All current accounts are expected EUR (OPEN_QUESTIONS). A follow-up could compare `Account.default_currency`, which is already retrieved in `externalBankAccounts` (PostEventPayoutService.java:1044-1045).
3. **Rounding.** Converted partial refunds and disputes can differ from Stripe's own proportional amounts by 1 minor unit. That is the same tolerance StripeRefundService already documents. The available-balance clamp keeps a payout from ever exceeding the real balance.
4. **First pass on a large event** makes one Stripe read per order (≤ 500 per call; the rest continue next night).
5. **Migration number.** V173 is reserved by the predictor-web-research worktree; this plan takes V174. Re-check `git ls-tree origin/master` at ship.
6. **Size.** 27 files (16 main incl. docs, 11 tests), more than the ~15 guideline. A possible split:
 - **Part A:** V174, `Order`, `stampSettlement`, `SettlementRate`, stamper wired into step 0b, migration tests and SettlementRateTest. 10 files. Stamps only; nothing reads the columns.
 - **Part B:** every consumer and the remaining test edits.
 I recommend shipping as one. Part A alone already puts a Stripe read into the money sweep, so splitting buys little safety, and Part B's tests are what prove Part A's numbers.
7. **Out-of-scope display bug.** DashboardRevenue.java:43-48 adds up presentment amounts of all the org's events regardless of currency. Separate card.

## Definition of done

- Every Ordered step is done; the full `api` gate is green on the final rebased tree with no skipped Testcontainers tests.
- Each guard in the Guard proofs table was shown red once without its line.
- Worked examples W1-W6 are asserted by tests with the exact figures above.
- CLAUDE.md dispute paragraph and operator note, plus the javadocs in Affected files rows 13-15, describe the settlement rule.
- L1 recorded and consistent with the stamper's checks before ship. Prod pre-flight SQL clean. L3/L4 recorded after deploy.
- Commit message notes the Stripe doc URLs read and the date (2026-10-05). No ticket ids in comments. No AI attribution.

## Decisions (main session)
- Option (a) accepted, shipped as ONE unit (as recommended).
- L1 is run by the worker FIRST, read-only, in Stripe TEST mode: retrieve the 2026-10-05 USD probe charge (ch_3UNEh62I8K3TBNXz1unv2OsT) with the transfer, application_fee and balance_transaction expanded. Use the key in imin-api/.env.local only after checking it starts with sk_test_, and never print it. If the application fee is not in the transfer currency, STOP and report; do not implement.
- OQ3: accepted. An event whose orders settled in more than one currency is skipped with an ERROR.
- OQ2 (non-EUR connected accounts): out of scope. Risk 2 is accepted; a follow-up card is queued.
- OQ4 (org home revenue mixes currencies): follow-up card queued.
- Before ship, re-check that the migration number (V174) is still free.

- L1 RESULT (2026-10-05): application_fee.amount is in PRESENTMENT currency (149 usd); its balance_transaction is 133 eur. CHANGE: the stamper adds the expand `latest_charge.application_fee.balance_transaction`; Fs = application_fee.balance_transaction.amount, and its currency must equal the transfer currency (replaces the fee-object currency check). A charge with no fee stamps Fs = 0. W1–W5 figures stay unchanged (Fs = 133). Test fixtures and argument assertions include the fourth expand.

## Live-test evidence

**L1 (2026-10-05, Stripe TEST mode, read-only, key checked `sk_test_`, never printed). RESULT: STOP. The application fee is NOT in the transfer currency.**

`GET /v1/charges/ch_3UNEh62I8K3TBNXz1unv2OsT?expand[]=transfer&expand[]=application_fee&expand[]=balance_transaction` (livemode false):
- charge: amount 1149 `usd`, `application_fee_amount` 149, `payment_intent` `pi_3UNEh62I8K3TBNXz1kTOEgYa`
- transfer `tr_3UNEh62I8K3TBNXz1NHmIChW`: amount **1025 `eur`**, `amount_reversed` 500 (one reversal `trr_1UNEhC2I8K3TBNXzvHOR7gOU` 500 `eur`, no metadata)
- application_fee `fee_1UNEh92I8KWX5mBeyChNeZwV`: amount **149 `usd`**, `amount_refunded` 0
- charge balance_transaction `txn_3UNEh62I8K3TBNXz1P97KG00`: amount 1025 **`eur`**, `exchange_rate` **0.892011**, Stripe fee 78, net 947

`GET /v1/payment_intents/pi_3UNEh62I8K3TBNXz1kTOEgYa` with `expand[]=latest_charge.balance_transaction|transfer|application_fee` (the stamper's exact call) returns the same: transfer [1025, eur], application_fee [149, usd], balance_transaction [eur, 0.892011].

Extra read for the re-plan: `GET /v1/application_fees/fee_1UNEh92I8KWX5mBeyChNeZwV?expand[]=balance_transaction` → fee balance transaction **133 `eur`**, type `application_fee` (exchange_rate null). Transfer balance transaction: −1025 `eur`.

Consequence: the stamper's check "application fee object null, or its currency ≠ the transfer currency" (step 4) refuses every converted order, so Fs = 133 must come from `application_fee.balance_transaction.amount` (currency `eur`), which needs a fourth expand (`latest_charge.application_fee.balance_transaction`). The estimated Fs = 133 is confirmed by that balance transaction; W1–W5 numbers hold if the plan sources Fs there. Not implemented, per Decisions.

Confirmed read-only: `GET /v1/payment_intents/pi_3UNEh62I8K3TBNXz1kTOEgYa` with `expand[]=latest_charge.balance_transaction`, `expand[]=latest_charge.transfer`, `expand[]=latest_charge.application_fee.balance_transaction` succeeds in a single call and returns fee 149 `usd` → balance transaction 133 `eur` (type `application_fee`), transfer 1025 `eur`.

**L2 (2026-10-06, Stripe TEST mode, read-only, key checked `sk_test_` and `isLiveKey() == false`, never printed).** A scratch Java program outside the repo, never committed, ran the real `OrderSettlementStamper.stampOrg`. It used a real `StripeClient` on the sandbox key and the review-fix build of `target/classes`. `OrderRepository` was a recording proxy that returned the probe order (`pi_3UNEh62I8K3TBNXz1kTOEgYa`, test mode) and captured the write. Result: one `stampSettlement(order, "eur", 1025, 133)`. The stamper logged `stamped eur gross 1025 fee 133 (rate 0.892011)`.

## Review rounds

**Implementation round 1 (2026-10-05/06, implement mode).**

Baseline (git archive of origin/master 531df1ff in scratch, `docker info` ok): `Tests run: 7221, Failures: 0, Errors: 0, Skipped: 3`, BUILD SUCCESS. The 3 skips are not Testcontainers: two H2-only assumption aborts in AudiencePlanInvitationWebTest (its Postgres variant runs them) and one `@Disabled` in SimulatorEvalTest.

Deviations from the plan text:
- `ck_orders_settlement` adds `settlement_gross_minor IS NOT NULL AND settlement_fee_minor IS NOT NULL` to the stamped branch. As quoted, a row with a currency and a NULL fee makes that branch NULL, and a CHECK passes on NULL, so M3 `half_a_stamp_is_rejected` could not hold.
- Per the L1 RESULT decision, the stamper sends four expands (`latest_charge.balance_transaction`, `latest_charge.transfer`, `latest_charge.application_fee`, `latest_charge.application_fee.balance_transaction`). Fs = the fee balance transaction's amount, and its currency must equal the transfer currency. A charge whose fee balance transaction is missing is also left unstamped.
- Extra tests beyond the branch map: stamper `a_fee_without_its_balance_transaction_is_left_unstamped`, `a_fee_above_the_transfer_is_left_unstamped`, `a_full_save_before_the_stamp_does_not_block_it`; migration `a_full_stamp_is_accepted`; SettlementRateTest `a_stamped_order_reads_its_four_figures`.

Guard proofs. Each guard was removed once in a scratch rsync copy of the worktree, its test run alone, and the file restored by `cp` from the worktree and checked with `cmp`. At the end, `diff -rq` showed the scratch `src` identical to the worktree. Every one went red:

| Guard (mutation) | Test | Result |
|---|---|---|
| stamper `o.testMode = :testMode` → always true | S3 `an_order_of_the_other_key_mode_is_not_read` | 1 failure |
| stamper `o.settlementCurrency is null` removed | S2 `a_stamped_order_is_never_read_again` | 1 failure |
| stamper `o.totalMinor > 0` removed | S4 `a_free_order_is_not_read` | 1 failure |
| `stampSettlement … and o.settlementCurrency is null` removed | S9 `a_stamp_is_written_once_and_survives_a_full_save` | expected 0 but was 1 |
| `updatable = false` → `true` on the three fields | S9 | expected "eur" but was null |
| Fs from `application_fee.amount` instead of its balance transaction | S1 `stamps_transfer_and_fee_from_one_expanded_retrieve` | expected 133 but was 149 |
| step 2 unstamped `return` removed | P3 `an_unstamped_order_holds_the_event` | error (NPE on the null currency) |
| step 2 distinct-currency `return` removed | P4 `two_settlement_currencies_on_one_event_pay_nothing` | 1 failure |
| step 4 currency-not `return` removed | P5 `a_triggered_run_in_another_currency_pays_nothing` | 1 failure |
| step 3 bucket reverted to the event currency | P1 `worked_W1_a_usd_event_pays_892_eur` | expected 1 payout but was 0 |
| step 1b `return` removed | P8 `an_unstamped_lost_debt_elsewhere_holds_the_payout` | expected 0 but was 1 |
| return `setCurrency(order.settlementCurrency)` → `d.getCurrency()` | R2 `worked_W4_a_won_usd_dispute_returns_892_in_eur` | currency "usd" |
| dispute transfer guard → `d.getCurrency()` | R5 `a_transfer_in_another_currency_than_the_settlement_is_never_reversed` | 1 failure |
| refund transfer guard → `refund.getCurrency()` | R6 `a_platform_funded_refund_on_a_transfer_in_another_currency_is_never_reversed` | 1 failure |
| 3b filter → `lower(o.currency) = :currency` | R8 `a_usd_order_settled_in_eur_is_held_back_from_the_eur_payout` | [3000] instead of [2608] |
| stamper call in `payOneEvent` removed | P7 `the_payout_tick_stamps_the_orgs_orders` | 1 failure |
| stamper call in `recoverForOrg` removed | R10 `recovery_for_an_org_without_candidates_stamps_first` | 1 failure |
| CHECK `IS NOT NULL` clauses removed | M3 `half_a_stamp_is_rejected` (H2) | expected a throwable |

R5 and R6 were set up so that the old guard passes (dispute or refund currency equals the transfer's `usd`) and only the settlement-currency guard refuses.

Targeted run (comma form, 11 classes): `Tests run: 187, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. The Testcontainers classes (OrderSettlementMigrationPostgresTest 6, DisputeWithholdingPostgresTest 11, DisputeRecoveryQueriesPostgresTest 8) all ran.

Full gate (`docker info` ok, `test-serial.sh ./mvnw test`, worktree on 531df1ff): `Tests run: 7274, Failures: 0, Errors: 0, Skipped: 3`, BUILD SUCCESS. The same 3 non-Testcontainers skips as the baseline. origin/master is now 2c994400 (one commit, a different CLAUDE.md line). V173 and V174 are still free on it. Re-run the gate after the ship rebase.

**Review-fix round 1 (2026-10-06).** Verdict CLEAN with 2 MEDIUMs and 2 LOWs, plus missing branch tests and L2. Applied:
1. `OrderSettlementStamper.stampOrg` never throws. The page query is wrapped (ERROR, nothing stamped this tick), and each order's read, parse and write runs in a `catch (RuntimeException)` that logs ERROR naming the order and moves on. The inner write catch keeps its own message.
2. Stuck orders get a signal. A Stripe read error is an ERROR once the order is older than `STRIPE_PAYOUT_BUFFER_DAYS`, else a WARN. The payout's step 2 and step 1b skips also log ERROR when an unstamped live order (or a LOST dispute's unstamped order) is older than the buffer. Two count queries back this: `OrderRepository.countLiveUnstampedByEventIdCreatedBefore` and `DisputeRepository.countLiveUnrecoveredLostOnUnstampedOrdersByOrgIdCreatedBefore`. CLAUDE.md now has the runbook line: a query to find stuck orders, and a manual stamp UPDATE that writes all three values (conditional on an unstamped row) within `ck_orders_settlement`.
3. New branch tests: a won dispute on an unstamped order returns nothing; a LOST sibling of that order waits ("left for the next pass"); `organizerShareLiveMinor` counts an unstamped order's dispute in full (1149, H2 and PG); the stamper's write-failure catch; a platform-funded refund whose order is missing (`findById` stubbed empty on a spy, since the refunds FK makes it unreachable through the database).
4. The unstamped page is read newest first (`order by o.createdAt desc, o.id desc`), with a ponytail comment naming the ceiling: more than 500 permanently refused orders hide the oldest beyond the page.
5. CLAUDE.md lists the four expands the code sends.
6. L2 recorded under Live-test evidence: eur / 1025 / 133.

Guard proofs, each removed once in a scratch copy and restored byte-exact (`cmp`), with a final `diff -rq` showing the scratch src identical. All went red:

| Guard (mutation) | Test | Result |
|---|---|---|
| per-order `catch (RuntimeException)` removed | `a_runtime_failure_on_one_order_still_stamps_the_next` | threw |
| page-query catch removed | `a_failed_listing_never_throws` | 1 failure |
| write catch narrowed so it misses | `a_failed_write_leaves_that_order_and_stamps_the_next` | outer message instead of "could not write the stamp" |
| stamper: never ERROR | `a_failed_read_is_an_error_only_once_the_order_is_older_than_the_payout_buffer` | 1 failure |
| stamper: always ERROR | same | 1 failure |
| page back to oldest first | `newer_orders_are_read_first` | 1 failure |
| step 2: never ERROR / always ERROR | `an_unstamped_order_older_than_the_payout_buffer_is_an_error` / `…inside_the_payout_buffer_is_a_warning` | 1 failure each |
| step 1b: never ERROR / always ERROR | `an_unsized_lost_debt_older_than_the_payout_buffer_is_an_error` / `…inside_the_payout_buffer_is_a_warning` | 1 failure each |
| return: unstamped block removed | `a_won_dispute_on_an_unstamped_order_returns_nothing` | threw (no settlement stamp) |
| return: `failedOrders.add` removed | `a_lost_sibling_of_an_unstamped_return_waits_for_the_next_pass` | 1 failure |
| withholding: unstamped not counted in full | `a_dispute_on_an_unstamped_order_withholds_its_whole_amount_from_the_payout` (H2) | 1 failure |
| refund recovery: `order == null` check removed | `a_platform_funded_refund_whose_order_is_missing_stays_open` | threw (NPE) |

Targeted run (11 classes): `Tests run: 201, Failures: 0, Errors: 0, Skipped: 0`.
Full gate (`docker info` ok, `test-serial.sh ./mvnw test`): `Tests run: 7288, Failures: 0, Errors: 0, Skipped: 3`, BUILD SUCCESS. The same 3 non-Testcontainers skips as the baseline; every Postgres class ran with 0 skipped.
