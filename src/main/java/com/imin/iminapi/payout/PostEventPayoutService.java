package com.imin.iminapi.payout;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.settlement.SettlementRepository;
import com.imin.iminapi.settlement.SettlementStatus;
import com.imin.iminapi.stripe.StripeConnectState;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Balance;
import com.stripe.model.Payout;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountRetrieveParams;
import com.stripe.param.BalanceRetrieveParams;
import com.stripe.param.PayoutCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Track B (manual payouts) Phase 2 — the per-event money move. One public method,
 * {@link #payOneEvent(UUID)}, runs in its OWN
 * {@code @Transactional(propagation = REQUIRES_NEW)} so one event failing can never
 * roll back the rest of the {@link PostEventPayoutSweeper} batch. The sweeper calls
 * it through the Spring proxy (a separate bean) so the {@code REQUIRES_NEW} boundary
 * is real — a private self-call would silently drop the advice.
 *
 * <p>Per-event flow (plan §4.2 step 3 — order is load-bearing):
 * <ol>
 *   <li><b>step 0 — double-pay guard (DB, not Stripe), FIRST.</b> A connected
 *       balance is one shared pool across all of an org's events, so AT MOST ONE
 *       in-flight payout per org per tick. Skip the event entirely if any
 *       {@code payout_runs} row for this {@code stripe_account_id} is already
 *       {@code PLANNED}/{@code SUBMITTED} — re-checked here even though the
 *       candidate query already filtered, because a candidate snapshot can go stale
 *       between scan and commit.</li>
 *   <li><b>step 1 — dispute/hold guard.</b> Skip if the org has an open dispute on a
 *       backing TRANSFER row ({@code object_type='transfer' AND status='failed'}); a
 *       FAILED payout row is a bank-routing failure, NOT a dispute, so it must not
 *       block. Also skip if the account is no longer {@code ACTIVE}.</li>
 *   <li><b>step 2 — per-event net (the ceiling).</b> Reuse the
 *       {@code EventOverviewService} derivation: {@code gross − refunds − net app
 *       fee}. <b>Fee EXCLUDED</b> (§4.4): imin's application fee sits on the platform
 *       balance and is subtracted out here, so the computed ceiling is the
 *       organizer's net.</li>
 *   <li><b>step 3 — live available balance</b> ON the connected account, matched to
 *       the event currency (Stripe reports lowercase; the event stores uppercase).</li>
 *   <li><b>step 4 — clamp</b> {@code payoutMinor = min(net, available)}; skip if
 *       {@code <= 0} (funds not yet available — rolls to the next tick).</li>
 *   <li><b>step 5 — write the {@code payout_runs} row FIRST</b> (insert-or-find on
 *       the deterministic idempotency key) BEFORE any Stripe call. The
 *       {@code UNIQUE(idempotency_key)} is the pre-call guard that makes concurrent
 *       replicas converge on one row.</li>
 *   <li><b>step 6 — create the Payout idempotently</b> reusing
 *       {@code run.getIdempotencyKey()} so a crash between SUBMITTED-write and
 *       Stripe-ack replays to the SAME {@code po_} (Stripe idempotency replay), then
 *       record the {@code po_} and move to {@code SUBMITTED}.</li>
 *   <li><b>step 7 — failure handling.</b> ANY {@code StripeException} from the create
 *       marks the run {@code FAILED} with the Stripe code as the reason — the create was
 *       rejected so no {@code po_} exists. {@code balance_insufficient} is FAILED too (not
 *       left PLANNED): a PLANNED row counts as in-flight for both the org-level double-pay
 *       guard and the per-event candidate query, so leaving it PLANNED would permanently
 *       freeze the org and the event; FAILED lets the event re-candidate next tick with a
 *       bumped {@code attempt} (fresh idempotency key → clean retry).</li>
 * </ol>
 *
 * <p><b>Fee-retention invariant (§4.4):</b> the Payout is created ON the connected
 * account ({@code Stripe-Account: acct_org}), which draws ONLY from the organizer's
 * net — imin's fee lives on a different (platform) Stripe account and structurally
 * cannot be paid out. The net math (fee excluded) and the available-balance clamp
 * are the two independent safeguards.
 */
@Service
public class PostEventPayoutService {

    private static final Logger log = LoggerFactory.getLogger(PostEventPayoutService.class);

    /** In-flight statuses for the org-level one-payout-per-tick guard. */
    private static final List<PayoutRunStatus> IN_FLIGHT =
            List.of(PayoutRunStatus.PLANNED, PayoutRunStatus.SUBMITTED);

    private final StripeClient stripeClient;
    private final StripeProperties props;
    private final EventRepository events;
    private final OrganizationRepository orgs;
    private final PayoutRunRepository payoutRuns;
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final SettlementRepository settlements;

    public PostEventPayoutService(StripeClient stripeClient,
                                  StripeProperties props,
                                  EventRepository events,
                                  OrganizationRepository orgs,
                                  PayoutRunRepository payoutRuns,
                                  OrderRepository orders,
                                  RefundRepository refunds,
                                  SettlementRepository settlements) {
        this.stripeClient = stripeClient;
        this.props = props;
        this.events = events;
        this.orgs = orgs;
        this.payoutRuns = payoutRuns;
        this.orders = orders;
        this.refunds = refunds;
        this.settlements = settlements;
    }

    /**
     * Pay out a single event's available net to the organizer's connected account,
     * idempotently. Runs in its own transaction so a failure here never rolls back
     * the batch. No-op (returns silently) on any guard.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void payOneEvent(UUID eventId) {
        // Master kill-switch — defensive even though the sweeper already gates.
        if (!props.isPayoutScheduleManual()) return;

        Event event = events.findById(eventId).orElse(null);
        if (event == null) return;

        Organization org = orgs.findById(event.getOrgId()).orElse(null);
        if (org == null
                || !org.isStripePayoutsEnabled()
                || !org.isStripePayoutScheduleManual()
                || org.getStripeConnectState() != StripeConnectState.ACTIVE) {
            return;
        }
        String acct = org.getStripeAccountId();
        if (acct == null || acct.isBlank()) return;

        // ── step 0 — DOUBLE-PAY GUARD (DB, not Stripe), re-checked in-tx, FIRST ──
        // One shared balance pool per org → at most ONE in-flight payout per org per
        // tick. The first event to process for an org claims the slot (its PLANNED
        // row, step 5); every other event for the same acct is skipped this tick and
        // rolls forward once that run reconciles out of PLANNED/SUBMITTED.
        if (payoutRuns.existsByStripeAccountIdAndStatusIn(acct, IN_FLIGHT)) {
            log.info("[payout] skip event {} org {} — another payout already in flight for acct {} this tick",
                    eventId, org.getId(), acct);
            return;
        }

        // ── step 1 — dispute / hold guard ──
        // Open dispute lands on the backing TRANSFER row (object_type='transfer',
        // status='failed'). A FAILED *payout* row is a bank-routing failure, NOT a
        // dispute — it must not block future payouts, so it is excluded by construction.
        if (settlements.countOpenTransferDisputes(org.getId(), SettlementStatus.FAILED) > 0) {
            log.info("[payout] skip event {} org {} — open dispute on a transfer row; rolling to next tick",
                    eventId, org.getId());
            return;
        }

        // ── step 2 — per-event net (the ceiling). Fee EXCLUDED (§4.4). ──
        // Mirrors EventOverviewService: gross − refunds − max(0, appFee − appFeeRefunded).
        long gross = orders.sumTotalMinorByEventId(eventId);
        long refunded = refunds.sumSucceededRefundMinorByEventId(eventId);
        long appFee = orders.sumApplicationFeeMinorByEventId(eventId);
        long appFeeRefunded = refunds.sumSucceededRefundApplicationFeeMinorByEventId(eventId);
        long netAppFee = Math.max(0L, appFee - appFeeRefunded);
        long perEventNetMinor = Math.max(0L, Math.max(0L, gross - refunded) - netAppFee);
        if (perEventNetMinor <= 0L) {
            log.info("[payout] skip event {} org {} — computed net is {} (nothing to pay)",
                    eventId, org.getId(), perEventNetMinor);
            return;
        }

        // ── step 2b — payout-destination guard: the account must have a bank attached ──
        // Payout.create below targets the account's DEFAULT external account (no
        // destination param); with none attached it would fail. Recipient onboarding
        // normally collects one — skip + flag (don't even create a run) so the event
        // retries once the organizer adds a payout bank account.
        if (!hasExternalBankAccount(acct)) {
            log.warn("[payout] skip event {} org {} — connected acct {} has NO external bank account; "
                    + "organizer must add a payout bank account before this event can be paid out",
                    eventId, org.getId(), acct);
            return;
        }

        // ── step 3 — live AVAILABLE balance ON the connected account, by currency ──
        // event.currency is UPPERCASE ('EUR'); Stripe balance/payout currency is
        // lowercase ('eur'). Match the available bucket on the lowercase form.
        String cur = event.getCurrency().toLowerCase(Locale.ROOT);
        long availableMinor;
        try {
            RequestOptions onAcct = RequestOptions.builder().setStripeAccount(acct).build();
            Balance bal = stripeClient.balance().retrieve(BalanceRetrieveParams.builder().build(), onAcct);
            availableMinor = bal.getAvailable() == null ? 0L : bal.getAvailable().stream()
                    .filter(a -> cur.equals(a.getCurrency()))
                    .mapToLong(a -> a.getAmount() == null ? 0L : a.getAmount())
                    .findFirst()
                    .orElse(0L);
        } catch (StripeException e) {
            // Could not read the balance — leave everything untouched and retry next tick.
            log.warn("[payout] balance read failed for event {} org {} acct {} — {} (rolling to next tick)",
                    eventId, org.getId(), acct, e.getCode());
            return;
        }

        // ── step 4 — clamp ──
        long payoutMinor = Math.min(perEventNetMinor, availableMinor);
        if (payoutMinor <= 0L) {
            log.info("[payout] skip event {} org {} — net {} but available {} ({}); rolling to next tick",
                    eventId, org.getId(), perEventNetMinor, availableMinor, cur);
            return;
        }

        // ── step 5 — write payout_runs row FIRST (insert-or-find on the deterministic key) ──
        // attempt 1 normally; a fresh attempt (new key) is used ONLY after a FAILED run.
        int attempt = nextAttempt(eventId);
        String idem = "evt:" + eventId + ":attempt:" + attempt;
        PayoutRun run = payoutRuns.findByIdempotencyKey(idem).orElseGet(() -> {
            PayoutRun r = new PayoutRun();
            r.setOrgId(org.getId());
            r.setEventId(eventId);
            r.setStripeAccountId(acct);
            r.setAmountMinor(payoutMinor);
            r.setCurrency(cur);
            r.setStatus(PayoutRunStatus.PLANNED);
            r.setAttempt(attempt);
            r.setIdempotencyKey(idem);
            // UNIQUE(idempotency_key) makes concurrent replicas converge on one row;
            // a UNIQUE violation rolls THIS REQUIRES_NEW tx back and the event simply
            // retries on the next tick (expected, not an error).
            return payoutRuns.save(r);
        });

        // If a prior crashed run already reached SUBMITTED, the candidate/step-0 guards
        // would have excluded it — but guard defensively against re-creating a payout.
        if (run.getStatus() == PayoutRunStatus.SUBMITTED || run.getStatus() == PayoutRunStatus.PAID) {
            return;
        }

        // ── step 6 — create the Payout idempotently (crash-window safe) ──
        // Reusing run.getIdempotencyKey() means a re-issue after a crash between the
        // Stripe ack and the SUBMITTED commit replays to the SAME po_ rather than
        // creating a second payout. NEVER derive the key from anything per-retry random.
        try {
            Payout po = stripeClient.payouts().create(
                    PayoutCreateParams.builder()
                            .setAmount(payoutMinor)
                            .setCurrency(cur)
                            .setDescription("imin event payout " + eventId)
                            .putMetadata("event_id", eventId.toString())
                            .putMetadata("org_id", org.getId().toString())
                            .build(),
                    RequestOptions.builder()
                            .setStripeAccount(acct)
                            .setIdempotencyKey(run.getIdempotencyKey())
                            .build());
            run.setStripePayoutId(po.getId());
            run.setStatus(PayoutRunStatus.SUBMITTED);
            run.setSubmittedAt(Instant.now());
            payoutRuns.save(run);
            log.info("[payout] SUBMITTED event {} org {} acct {} amount={} {} po={}",
                    eventId, org.getId(), acct, payoutMinor, cur, po.getId());
        } catch (StripeException e) {
            // ── step 7 — failure handling ──
            // Every create-rejection path marks the run FAILED. Stripe rejected the create,
            // so NO po_ was minted (the request never produced a payout object) — it is safe
            // to fail and let the event re-candidate next tick with a bumped `attempt` (fresh
            // idempotency key → clean retry). balance_insufficient must NOT stay PLANNED:
            // PLANNED counts as in-flight for BOTH the org-level double-pay guard (which would
            // then freeze every sibling event for the org) and the per-event candidate query
            // (which would freeze this event), permanently. FAILED unblocks both.
            String code = e.getCode();
            run.setStatus(PayoutRunStatus.FAILED);
            run.setFailureReason(code != null ? code : e.getMessage());
            payoutRuns.save(run);
            if ("balance_insufficient".equals(code)) {
                log.warn("[payout] balance_insufficient for event {} org {} acct {} — marking FAILED, re-candidates next tick",
                        eventId, org.getId(), acct);
            } else {
                log.error("[payout] FAILED event {} org {} acct {} amount={} {} — {}",
                        eventId, org.getId(), acct, payoutMinor, cur, code, e);
            }
        }
    }

    /**
     * The next attempt number for an event: 1 when there are no runs, else one more
     * than the highest attempt. Only ever called when no PLANNED/SUBMITTED/PAID run
     * exists for the event (candidate query + step 0 guarantee that), so any existing
     * runs are FAILED and a fresh attempt mints a fresh idempotency key.
     */
    private int nextAttempt(UUID eventId) {
        return payoutRuns.maxAttemptByEventId(eventId) + 1;
    }

    /**
     * True iff the connected account has at least one external (bank/card) account
     * attached — the destination for the no-destination {@link Payout}. Recipient
     * onboarding normally collects one. A Stripe error degrades to FALSE (skip this
     * tick) rather than risk firing a payout at a possibly-bankless account.
     */
    private boolean hasExternalBankAccount(String acct) {
        try {
            RequestOptions onAcct = RequestOptions.builder().setStripeAccount(acct).build();
            Account a = stripeClient.accounts().retrieve(acct,
                    AccountRetrieveParams.builder().addExpand("external_accounts").build(), onAcct);
            return a.getExternalAccounts() != null
                    && a.getExternalAccounts().getData() != null
                    && !a.getExternalAccounts().getData().isEmpty();
        } catch (StripeException e) {
            log.warn("[payout] external-account check failed for acct {} — {} (skipping this tick)", acct, e.getCode());
            return false;
        }
    }
}
