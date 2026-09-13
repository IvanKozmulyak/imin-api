# Restore the organizer's fee share on refunds (`refund_application_fee=true`)
Slug `stripe-refund-fee-share` · Mode: fix · Notion: card not supplied to this agent

## Goal and scope
`StripeRefundService.create` sends `refund_application_fee=false` on every path since 14f2ed7 (2026-09-11); on the
`reverse_transfer=true` path that keeps the platform fee and takes it out of the organizer. Derive the flag from
`reverseTransfer`: `true` → `true`, `false` (platform-funded fallback) → stays `false`. One parameter, one caller
(`RefundService` lines 181 and 236 — the only injection of this service in `src/main`), plus tests and the two refund
specs whose rationale was edited to justify the removal. No migration, no DTO, no endpoint.

Money identity — destination charge `G=1149`, `application_fee_amount F=149`, full refund `A=G`; the connected
balance transaction at sale is payment 1149, fee 149, **net 1000**:
- **Path A, `reverse_transfer=true` + flag `true`**: platform pays the buyer 1149, the reversal pulls 1149 off the
  connected balance, the proportional fee refund returns 149 to it ⇒ connected −1000, platform −149 (its own fee),
  buyer whole. **Today (flag false)**: connected −1149, platform ±0 and keeps 149 ⇒ organizer −149, platform +149 —
  exactly the 2026-09-13 sandbox ledger (`payment_refund −1149`, `application_fee.amount_refunded = 0`).
- **Path B, `reverse_transfer=false` + flag `false`** (`balance_insufficient` fallback): the platform funds all 1149,
  the connected account is untouched, and at the sweep `PostEventPayoutService.recoverPlatformFundedRefunds` reverses
  `amountMinor − applicationFeeRefundMinor = 1000` off the charge's destination transfer ⇒ same end state as path A.
  Flag `true` here would hand the connected account a further 149 the 1000 reversal never claws back (platform −298,
  organizer −851). Hence `false`, and that method is not touched.
- **Books**: `Refund.applicationFeeRefundMinor = 149` persists on both paths, bookkeeping only; payout net `gross − refunds − max(0, appFee − appFeeRefunded)` lands on 0 for a fully refunded event on both. Partial example (order 2298, fee 298, refund one ticket 1149, share 149): books say 1000 payable, but with the flag `false` the connected balance holds 851 → `PostEventPayoutService` clamps the payout or hits `balance_insufficient`; with `true` it holds 1000 and matches.

SDK check — stripe-java **32.1.0** (`~/.m2/.../stripe-java-32.1.0-sources.jar`, `com/stripe/param/RefundCreateParams.java`), `Builder.setRefundApplicationFee(Boolean)`: "If a full charge refund is given, the full application fee will be refunded. Otherwise, the application fee will be refunded in an amount proportional to the amount of the charge refunded." The current javadoc's "all-or-nothing flag would refund the entire fee on a partial refund" is wrong.

## Repos in ship order
| key | base | worktree | verification command |
|---|---|---|---|
| `api` | `master` (@ 6a14f9d) | `/Users/ivan/imin/imin-api/.claude/worktrees/stripe-refund-fee-share` | `./mvnw test` |

## Affected files (per repo)
**imin-api** — 4 edits + this plan:
- `src/main/java/com/imin/iminapi/stripe/StripeRefundService.java` — `.setRefundApplicationFee(reverseTransfer)`;
  rewrite the class javadoc and the `@param appFeeRefundMinor` / `@param reverseTransfer` lines, which state the
  opposite money model today. Keep the single-call design and the log line.
- `src/test/java/com/imin/iminapi/stripe/StripeRefundServiceTest.java` — see Test impact.
- `docs/superpowers/specs/2026-05-25-stripe-refunds-design.md` — decision **#2** (line 26) and **#8** (line 32,
  "never sent to Stripe" no longer holds for the flag), 2–3 lines, citing the connected ledger.
- `docs/superpowers/specs/2026-05-25-stripe-refunds-testing.md` line 29 — that test row still describes the deleted
  two-call design; restate it as the per-path flag assertions.
- Checked, no edit: `CLAUDE.md` (Stripe section covers webhooks/Connect state only — `grep -n "refund_application_fee\|reverse_transfer" CLAUDE.md` is empty), `.env.example`, and the shipped `docs/superpowers/plans/2026-09-10-stripe-readiness-p0-p1.md` (history).

## Ordered steps
1. Baseline `./mvnw test` on the untouched worktree; record it. A gate already red there is its own card.
2. Add the red repro tests (`reverseTransferRefundAlsoRefundsTheApplicationFee`, `platformFundedRefundKeepsTheFee`);
   `./mvnw test -Dtest=StripeRefundServiceTest` must fail on `expected TRUE, was FALSE`.
3. Change the flag in `StripeRefundService.create`; rewrite the javadoc to the two-path identity above.
4. Rewrite/retire the sibling assertions per Test impact.
5. Correct spec decisions #2 and #8 and the testing-spec test-table row.
6. Re-read `PostEventPayoutService.recoverPlatformFundedRefunds` (lines 609–678): confirm it still reverses
   `amountMinor − applicationFeeRefundMinor`. No change there.
7. Full `./mvnw test`; `git diff --stat` shows only the files above.

## Verification commands
- `cd /Users/ivan/imin/imin-api/.claude/worktrees/stripe-refund-fee-share && ./mvnw test`
- While iterating: `./mvnw test -Dtest=StripeRefundServiceTest`, `-Dtest=RefundServiceTest`,
  `-Dtest=PostEventPayoutServiceTest`, `-Dtest=RefundPlatformFundedTest`

## Test impact
Branches of the changed logic = the two values of `reverseTransfer`. File
`src/test/java/com/imin/iminapi/stripe/StripeRefundServiceTest.java`:
- `reverseTransferRefundAlsoRefundsTheApplicationFee` (new, red first) — `reverseTransfer=true` ⇒
  `params.getReverseTransfer() == TRUE` **and** `params.getRefundApplicationFee() == TRUE`.
- `platformFundedRefundKeepsTheFee` (renamed from `platformFundedRefundSetsReverseTransferFalse`) —
  `reverseTransfer=false` ⇒ both flags `FALSE`; keep the existing "must not pull from the short connected balance"
  reason and add one naming the 1000-reversal at the sweep.
- `fullRefundDoesNotCreateApplicationFeeRefund` → rename `refundIssuesExactlyOneStripeCall`: keep
  `verifyNoInteractions(feeRefundSvc, appFeeSvc, chargeSvc)` (one call still, the fee now moves via the flag) and
  drop the P0-3 javadoc above it.
- `refundStillSetsReverseTransferTrueAndRefundApplicationFeeFalse` — delete; its assertion *is* the regression.
- Unchanged, must stay green: `create_passesAmountReasonReverseTransferAndIdempotencyKey`, `create_otherReason_omitsStripeReason`; `RefundServiceTest` (asserts only the positional `reverseTransfer` via `eq(true)`/`eq(false)`; signature unchanged, so no edit); `RefundPlatformFundedTest` (schema-level `@DataJpaTest`, no flag assertions); `PostEventPayoutServiceTest`.

## Live-test
After the Railway deploy is live, redo the 2026-09-13 ledger check in the Stripe sandbox:
1. Buy one €1 ticket on a connected test org (destination charge with `application_fee_amount`).
2. Organizer-initiated full refund via `POST /api/v1/orders/{id}/refund`.
3. Assert on Stripe: the connected refund balance transaction **nets to −(G − F)**, the organizer's original net (a `payment_refund −1149` carrying a −149 fee line is the same thing); `GET /v1/application_fees/{fee}` shows `amount_refunded == F`, not 0; the buyer's refund is `G`.
4. Record the refund id, the connected balance-transaction id and `amount_refunded` below.

## Contract impact
None. No controller, DTO, schema, enum or error code changes — one Stripe request parameter plus tests and docs.
`/v3/api-docs.yaml` is unchanged, so no `api:sync` and no webapp/public follow-up. Confirm at review with
`git diff --name-only` (nothing under `controller/`, `dto/`, `db/migration/`).

## i18n impact
None — no UI strings and no email templates touched (`refund-confirmed.*` already ships EN/ES/FR/UK, untouched).

## Blast radius
Live-money path, but narrow: one parameter, one Stripe call, one caller. Payouts get *more* truthful — `PostEventPayoutService` already withholds `appFee − appFeeRefunded`, so today's books assume a fee refund Stripe never made; the fix removes that divergence and the connected-balance shortfall it causes on partial refunds. No stored row changes meaning, nothing historical is rewritten, no migration, no webhook contract. Refunds already `PENDING` at deploy are unaffected — the parameter is fixed at creation.

## Risks
- **Window 2026-09-11 16:37 UTC → this fix**: prod runs `sk_test` (live cutover scripted, not executed — `scripts/stripe-live-cutover*.sql`, `docs/STRIPE_LIVE_CUTOVER.md`, `organizations.stripe_livemode` V129), so every refund taken with the wrong flag is **test-mode money: nothing to reconcile**. Before ship, confirm no live-mode refund exists in the window (`refunds` joined to `orders.test_mode = false`); if one does, reconcile it by hand via `application_fees/{fee}/refunds` — this fix is forward-only.
- **Partial-refund rounding**: Stripe computes its own proportional fee refund while we persist `round(fee × A / G)` clamped to the unrefunded remainder, so the two can differ by ≤1 minor unit per partial. `applicationFeeRefundMinor` estimates Stripe's number, it does not mirror it — do not later assert equality.
- Path B stays correct only while `recoverPlatformFundedRefunds` reverses `amount − applicationFeeRefundMinor`;
  step 6 is the guard.
- Size: 2 code/test files + 2 docs. No split needed.

## Definition of done
- `setRefundApplicationFee(true)` when `reverseTransfer` is true, `false` otherwise; javadoc states both paths.
- The repro test was seen red before the fix and green after; full `./mvnw test` green from the worktree root.
- Spec decisions #2/#8 and the testing-spec row carry the corrected rationale, citing the connected ledger.
- `git diff --name-only` lists only "Affected files"; no migration, no contract change.
- Live-test performed after deploy, numbers pasted below.

## Live-test evidence

## Review rounds

- round 1 → APPROVED (1 MEDIUM: stale two-call Java sketch in the design spec, fixed post-review; zero-application-fee edge gated post-review; 3 LOW noted: clamp can under-record fee refunded by cents, ≤1 minor-unit rounding drift on partials, a test name that overstates its assertion)
