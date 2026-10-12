package com.imin.iminapi.payout;

import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.dispute.DisputeWithholding;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.settlement.SettlementRate;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.ModerationHoldRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.stripe.StripeConnectState;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.Balance;
import com.stripe.model.Payout;
import com.stripe.model.Transfer;
import com.stripe.model.TransferReversal;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountRetrieveParams;
import com.stripe.param.BalanceRetrieveParams;
import com.stripe.param.PayoutCreateParams;
import com.stripe.param.PayoutListParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.TransferListParams;
import com.stripe.param.TransferReversalCreateParams;
import com.stripe.param.TransferReversalListParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
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
 *   <li><b>step 0b — recover platform-funded refunds.</b> Reverse the destination transfer of
 *       every refund imin fronted for this org and has not pulled back yet, BEFORE the balance
 *       is read, so the payout clamps against a balance that is already net of it. Then reverse
 *       the organizer's share of every LOST dispute not recovered yet, and transfer a recovered
 *       share back once its dispute turned won with no other dispute withholding on the order.
 *       The sweeper also runs this org-wide ({@link #recoverForOrg}) for orgs with no candidate event.</li>
 *   <li><b>step 0c — cancelled-event hold.</b> A CANCELLED or deleted event is not paid: buyers may be
 *       owed refunds from that balance. Nothing is written (no run, no attempt spent, no no-bank park);
 *       the status is re-read from the database right before step 5. Once the event is not CANCELLED it
 *       pays normally.</li>
 *   <li><b>step 0u — unknown outcome.</b> While the event has a PLANNED or RETRYING run, an earlier create's
 *       answer was never recorded; {@link #resolveUnknownRun} settles it from Stripe's payout list first, and
 *       this method never re-sends a key.</li>
 *   <li><b>step 0d — moderation hold.</b> An active {@code moderation_holds} row with {@code hold_payouts} on the
 *       org or this event stops a new payout: nothing written, re-checked right before step 5. Recoveries owed to
 *       imin (step 0b) still run, and an unknown outcome is still settled by {@link #resolveUnknownRun}.</li>
 *   <li><b>step 0 — double-pay guard (DB, not Stripe), FIRST.</b> A connected
 *       balance is one shared pool across all of an org's events, so AT MOST ONE
 *       in-flight payout per org per tick. Skip the event entirely if any
 *       {@code payout_runs} row for this {@code stripe_account_id} is already
 *       {@code PLANNED}/{@code SUBMITTED} — re-checked here even though the
 *       candidate query already filtered, because a candidate snapshot can go stale
 *       between scan and commit.</li>
 *   <li><b>step 1 — dispute/hold guard.</b> Skip while the org has any OPEN dispute
 *       in the {@code disputes} registry. A CLOSED dispute never blocks: a win releases
 *       the funds, and a loss is settled by withholding the organizer's share of the order
 *       (the disputed amount less its booking fee) from the event's net in step 2. Also skip
 *       if the account is no longer payable.</li>
 *   <li><b>step 1c — open-refund hold.</b> Skip while a live order of the event has a REQUESTED or
 *       PENDING refund; the event re-candidates once it resolves.</li>
 *   <li><b>step 2 — per-event net (the ceiling), in what Stripe settled.</b> Per live order,
 *       {@code transfer − refunds − net app fee}, each presentment figure converted at the order's
 *       own Stripe ratio ({@code SettlementRate}), less the organizer's share of the event's
 *       open/lost disputes. Every order must be stamped ({@link OrderSettlementStamper}) and all in
 *       one settlement currency, else the event waits. <b>Fee EXCLUDED</b> (§4.4): imin's application
 *       fee sits on the platform balance and is subtracted out here, so the computed ceiling is the
 *       organizer's net.</li>
 *   <li><b>step 3 — live available balance</b> ON the connected account, matched to
 *       the event's settlement currency. Step 3b holds
 *       back the organizer share of live LOST disputes on the org's OTHER events that the
 *       recovery could not reverse yet: that part of the balance is imin's.</li>
 *   <li><b>step 4 — subtract what already moved, then clamp.</b>
 *       {@code owed = net − already triggered (SUBMITTED/PAID/PARTIAL)};
 *       {@code payoutMinor = min(owed, available)}; skip if {@code <= 0} (nothing left,
 *       or funds not yet available — rolls to the next tick). What the clamp leaves
 *       behind is recorded on the run as {@code remaining_minor}, so the settled run
 *       reconciles to {@code PARTIAL} rather than {@code PAID} and the event stays a
 *       candidate for a top-up instead of being silently short forever.</li>
 *   <li><b>step 5 — commit the PLANNED {@code payout_runs} row FIRST</b> through
 *       {@link PayoutRunStore#plan} (its own transaction) BEFORE any Stripe call, keyed
 *       {@code evt:<eventId>:attempt:<n>}. A failed insert stops the event before money moves; a crash
 *       after Stripe accepted the payout leaves the row for the resolver instead of losing it.</li>
 *   <li><b>step 6 — create the Payout</b> from the row alone (amount, currency, description, key),
 *       then commit SUBMITTED with the {@code po_} through the store.</li>
 *   <li><b>step 7 — failure handling, split by whether Stripe DEFINITIVELY refused.</b>
 *       A 4xx rejection (the request reached Stripe and was refused, so no {@code po_}
 *       exists) marks the run {@code FAILED} with the Stripe code as the reason.
 *       {@code balance_insufficient} is FAILED too (not left PLANNED): a PLANNED row
 *       counts as in-flight for both the org-level double-pay guard and the per-event
 *       candidate query, so leaving it PLANNED would permanently freeze the org and the
 *       event; FAILED lets the event re-candidate next tick with a bumped
 *       {@code attempt} (fresh idempotency key → clean retry). An unknown outcome
 *       (connection/read timeout, rate limit, 5xx, 409) commits {@code RETRYING}: Stripe may
 *       already have minted the {@code po_}, so the resolver decides from the payout list.
 *       An {@code idempotency_error} parks the run BLOCKED for a human, never FAILED.
 *       Every outcome commits through {@link PayoutRunStore}, conditional on PLANNED/RETRYING.</li>
 *   <li><b>parking (BLOCKED).</b> Two dead ends stop the nightly retry and tell the organizer
 *       instead of only the log: no external bank account on the connected account (recorded
 *       once, self-healing — the next tick after one is attached pays out), and
 *       {@code STRIPE_PAYOUT_MAX_ATTEMPTS} spent attempts (terminal — step 0a keeps the event
 *       out forever, because a payout that failed N times needs a human, not an N+1st try).
 *       A Stripe error during the bank check is neither: it writes nothing at all. Three parks need a
 *       human and only log an ERROR ("HUMAN NEEDED"), with no organizer notice: an
 *       {@code idempotency_error}, and the resolver's ambiguous match or week-long unreadable list.</li>
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
    /** No dispute id: counts every dispute on the order. */
    private static final UUID NO_DISPUTE = new UUID(0L, 0L);

    private static final List<PayoutRunStatus> IN_FLIGHT =
            List.of(PayoutRunStatus.PLANNED, PayoutRunStatus.SUBMITTED);

    /**
     * Statuses whose amount HAS been handed to Stripe for this event. Subtracted from the
     * event's net so a top-up after a clamped payout can never re-pay what already moved.
     * RETRYING is excluded on purpose: its outcome is unknown, and step 0u keeps the event out
     * until the resolver has adopted or failed it.
     */
    private static final List<PayoutRunStatus> ALREADY_TRIGGERED =
            List.of(PayoutRunStatus.SUBMITTED, PayoutRunStatus.PAID, PayoutRunStatus.PARTIAL);

    /** A create whose Stripe answer was never recorded; only the resolver moves a run out of these. */
    static final List<PayoutRunStatus> UNRESOLVED = List.of(PayoutRunStatus.PLANNED, PayoutRunStatus.RETRYING);

    /** How long an unreadable payout list may keep a run unresolved before a human is asked. */
    private static final Duration OUTCOME_UNKNOWN_AFTER = Duration.ofDays(7);

    /** Listing starts this long before the run was planned, so clock skew with Stripe cannot hide its payout. */
    private static final long LIST_SLACK_SECONDS = 300L;

    private final StripeClient stripeClient;
    private final StripeProperties props;
    private final EventRepository events;
    private final OrganizationRepository orgs;
    private final PayoutRunRepository payoutRuns;
    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final DisputeRepository disputes;
    private final DisputeWithholding disputeWithholding;
    private final RefundRecoveryMarker recoveryMarker;
    private final DisputeRecoveryMarker disputeMarker;
    private final OrderSettlementStamper stamper;
    private final ApplicationEventPublisher publisher;
    private final PayoutRunStore runStore;
    private final ModerationHoldRepository holds;

    public PostEventPayoutService(StripeClient stripeClient,
                                  StripeProperties props,
                                  EventRepository events,
                                  OrganizationRepository orgs,
                                  PayoutRunRepository payoutRuns,
                                  OrderRepository orders,
                                  RefundRepository refunds,
                                  DisputeRepository disputes,
                                  DisputeWithholding disputeWithholding,
                                  RefundRecoveryMarker recoveryMarker,
                                  DisputeRecoveryMarker disputeMarker,
                                  OrderSettlementStamper stamper,
                                  ApplicationEventPublisher publisher,
                                  PayoutRunStore runStore,
                                  ModerationHoldRepository holds) {
        this.stripeClient = stripeClient;
        this.props = props;
        this.events = events;
        this.orgs = orgs;
        this.payoutRuns = payoutRuns;
        this.orders = orders;
        this.refunds = refunds;
        this.disputes = disputes;
        this.disputeWithholding = disputeWithholding;
        this.recoveryMarker = recoveryMarker;
        this.disputeMarker = disputeMarker;
        this.stamper = stamper;
        this.publisher = publisher;
        this.runStore = runStore;
        this.holds = holds;
    }

    /** Bank-statement text: "imin payout · <event name>", name cut so the whole string stays under 250 chars. */
    static String payoutDescription(Event event) {
        String prefix = "imin payout · ";
        String name = event.getName() == null ? "" : event.getName().strip();
        if (name.isEmpty()) return "imin payout";
        int room = 250 - prefix.length();
        return prefix + (name.length() > room ? name.substring(0, room - 1) + "…" : name);
    }

    /**
     * Pay out a single event's available net to the organizer's connected account,
     * idempotently. Runs in its own transaction so a failure here never rolls back the batch, and the
     * no-bank and attempt-cap notices go out only after it commits. The run row and the create's outcome
     * commit on their own through {@link PayoutRunStore}. No-op on any guard.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void payOneEvent(UUID eventId) {
        // Master kill-switch — defensive even though the sweeper already gates.
        if (!props.isPayoutScheduleManual()) return;

        Event event = events.findById(eventId).orElse(null);
        if (event == null) return;

        // Org eligibility must match EventRepository.findPayoutCandidates exactly: "sell ⇒ payable" —
        // the transfers capability is the gate, so RESTRICTED is payable, DISABLED is not. Event status: step 0c.
        Organization org = orgs.findById(event.getOrgId()).orElse(null);
        if (org == null
                || !org.isStripePayoutsEnabled()
                || !org.isStripePayoutScheduleManual()
                || org.getStripeConnectState() == StripeConnectState.DISABLED) {
            return;
        }
        String acct = org.getStripeAccountId();
        if (acct == null || acct.isBlank()) return;

        // ── step 0b — RECOVER PLATFORM-FUNDED REFUNDS (real money, before any balance read) ──
        // Ahead of the guards below so a fully refunded event (net 0) still repays imin.
        // Refunds first: they share the destination transfer with a later chargeback.
        // Every amount below is sized from what Stripe settled, so read that first.
        stamper.stampOrg(org);
        recoverPlatformFundedRefunds(org);
        settleDisputes(org);

        // ── step 0u — an earlier create's outcome is unknown: the resolver settles it before any new attempt ──
        if (payoutRuns.existsByEventIdAndStatusInAndTestMode(eventId, UNRESOLVED, !props.isLiveKey())) {
            log.info("[payout] skip event {} org {} — an earlier payout's outcome is not recorded yet; "
                    + "the sweep resolves it from Stripe first", eventId, org.getId());
            return;
        }

        // ── step 0d — a moderation hold on the org or this event: imin staff froze its payouts ──
        if (holds.payoutsHeld(org.getId(), eventId)) {
            log.info("[payout] hold event {} org {} — moderation hold on payouts; nothing written", eventId, org.getId());
            return;
        }

        // ── step 0c — a CANCELLED (or deleted) event is held: buyers may be owed refunds from this balance ──
        if (held(Optional.ofNullable(event.getDeletedAt() == null ? event.getStatus() : null))) {
            log.warn("[payout] hold event {} org {} — event is cancelled or deleted; its funds stay on the "
                    + "connected balance until a human refunds buyers or restores the event", eventId, org.getId());
            return;
        }

        // ── step 0a — PARKED-RUN GUARD ──
        // A run parked BLOCKED by the attempt cap needs a human and must never re-candidate.
        // NO_BANK_ACCOUNT is excluded: the organizer clears that one themselves.
        if (payoutRuns.existsBlockedNeedingAHuman(eventId, PayoutBlockReason.NO_BANK_ACCOUNT, !props.isLiveKey())) {
            log.info("[payout] skip event {} org {} — a payout run is parked BLOCKED; it needs a human "
                    + "and is never retried automatically", eventId, org.getId());
            return;
        }

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
        // Only an OPEN dispute freezes the org: the balance is one shared pool and those funds
        // may still be clawed back. A CLOSED dispute never blocks — a win releases the money,
        // and a loss is settled by the per-event net reduction in step 2, not by a permanent
        // freeze. The settlements read-model is NOT the gate (a FAILED row there also means a
        // bank-routing failure, which is not a dispute at all).
        if (disputes.countOpenByOrgId(org.getId()) > 0) {
            log.info("[payout] skip event {} org {} — open dispute on the org; rolling to next tick",
                    eventId, org.getId());
            return;
        }

        // ── step 1b — a lost-dispute debt we cannot size cannot be held back ──
        long unsized = disputes.countLiveUnrecoveredLostOnUnstampedOrdersByOrgId(org.getId(), DisputeStatus.LOST);
        if (unsized > 0L) {
            // Past the payout buffer an unstamped order is stuck, not new: that needs a human.
            if (disputes.countLiveUnrecoveredLostOnUnstampedOrdersByOrgIdCreatedBefore(org.getId(),
                    DisputeStatus.LOST, stuckBefore()) > 0L) {
                log.error("[payout] skip event {} org {} — {} lost dispute(s) on orders Stripe has not been read for, "
                        + "some older than the payout buffer; stamp them by hand (CLAUDE.md, settlement rule)",
                        eventId, org.getId(), unsized);
            } else {
                log.warn("[payout] skip event {} org {} — {} lost dispute(s) on orders Stripe has not been read for; "
                        + "rolling to next tick", eventId, org.getId(), unsized);
            }
            return;
        }

        // ── step 1c — an open refund holds the event: its money may still leave the balance ──
        long open = refunds.countLiveOpenByEventId(eventId);
        if (open > 0L) {
            long stale = refunds.countLiveOpenByEventIdCreatedBefore(eventId, stuckBefore());
            if (stale > 0L) {
                log.error("[payout] skip event {} org {} — {} open refund(s), {} older than the payout buffer; "
                        + "resolve them in Stripe", eventId, org.getId(), open, stale);
            } else {
                log.warn("[payout] skip event {} org {} — {} open refund(s) not settled yet; rolling to next tick",
                        eventId, org.getId(), open);
            }
            return;
        }

        // ── step 2 — per-event net (the ceiling), in settlement units. Fee EXCLUDED (§4.4). ──
        // LIVE-mode rows only, on every input (V130): test-era money never reached a real
        // balance, so counting it here would disburse fake revenue out of the organizer's live
        // funds — and a test-era dispute or payout run would shrink a live net for free.
        List<OrderSettlementRow> rows = orders.settlementRowsByEventId(eventId);
        long unstamped = rows.stream().filter(r -> r.settlementCurrency() == null).count();
        if (unstamped > 0L) {
            long stuck = orders.countLiveUnstampedByEventIdCreatedBefore(eventId, stuckBefore());
            if (stuck > 0L) {
                log.error("[payout] skip event {} org {} — {} orders not stamped with what Stripe settled, {} older "
                        + "than the payout buffer; stamp them by hand (CLAUDE.md, settlement rule)",
                        eventId, org.getId(), unstamped, stuck);
            } else {
                log.warn("[payout] skip event {} org {} — {} orders not stamped yet with what Stripe settled; "
                        + "rolling to next tick", eventId, org.getId(), unstamped);
            }
            return;
        }
        Set<String> currencies = new HashSet<>();
        rows.forEach(r -> currencies.add(r.settlementCurrency().toLowerCase(Locale.ROOT)));
        if (currencies.size() > 1) {
            log.error("[payout] skip event {} org {} — its orders settled in more than one currency {}; "
                    + "pay it out by hand", eventId, org.getId(), currencies);
            return;
        }
        String cur = currencies.isEmpty() ? null : currencies.iterator().next();
        long stake = 0L;
        for (OrderSettlementRow r : rows) {
            stake += new SettlementRate(r.totalMinor(), r.feeMinor(), r.settlementGrossMinor(),
                    r.settlementFeeMinor()).stake(r.refundedMinor(), r.feeRefundedMinor());
        }
        // A chargeback takes the organizer's ticket share off the net; its booking fee is already
        // out of the stake and stays imin's loss. A won or reinstated dispute leaves the set again.
        long disputedShareMinor = disputeWithholding.organizerShareLiveMinor(eventId);
        long perEventNetMinor = Math.max(0L, stake - disputedShareMinor);
        if (disputedShareMinor > 0L) {
            log.info("[payout] event {} org {} — {} of the organizer's share of open/lost disputes withheld "
                    + "from the net (now {})", eventId, org.getId(), disputedShareMinor, perEventNetMinor);
        }
        if (perEventNetMinor <= 0L) {
            log.info("[payout] skip event {} org {} — computed net is {} (nothing to pay)",
                    eventId, org.getId(), perEventNetMinor);
            return;
        }

        // ── step 2b — payout-destination guard: the account must have a bank attached ──
        // Payout.create targets the account's DEFAULT external account. "No bank" and "we could
        // not ask Stripe" are different answers: only the first is a fact worth acting on.
        switch (externalBankAccounts(acct)) {
            case UNKNOWN -> {
                log.info("[payout] skip event {} org {} — the external-account check did not answer; "
                        + "nothing written, retrying next tick", eventId, org.getId());
                return;
            }
            case NONE -> {
                parkNoBankAccount(event, org, perEventNetMinor, cur);
                return;
            }
            case HAS -> { /* fall through to the payout */ }
        }

        // ── step 3 — live AVAILABLE balance ON the connected account, in the settlement currency ──
        // Stripe balance/payout currency is lowercase, like the stamp.
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

        // ── step 3b — hold back lost-dispute debts the recovery could not reverse yet ──
        // That debt is imin's money in this balance; this event's own share is already out of its net.
        long reservedMinor = disputeWithholding.unrecoveredLostShareLiveMinorByOrg(org.getId(), eventId, cur);
        if (reservedMinor > 0L) {
            long before = availableMinor;
            availableMinor = Math.max(0L, availableMinor - reservedMinor);
            log.warn("[payout] event {} org {} — {} of unrecovered lost-dispute shares on the org's other "
                    + "events held back from the available balance ({} → {})",
                    eventId, org.getId(), reservedMinor, before, availableMinor);
        }

        // ── step 4 — subtract what already moved for this event, then clamp ──
        // A previous tick may have paid a CLAMPED amount (available balance short of the
        // net). That run carries remaining_minor and reconciles to PARTIAL, which keeps the
        // event a candidate; here we pay only the outstanding difference, never the net again.
        if (payoutRuns.existsByEventIdAndTestModeFalseAndStatusInAndCurrencyNot(eventId, ALREADY_TRIGGERED, cur)) {
            log.error("[payout] skip event {} org {} — an earlier payout of this event went out in another currency "
                    + "than {}; amounts would not compare, reconcile by hand", eventId, org.getId(), cur);
            return;
        }
        long alreadyTriggered = payoutRuns.sumLiveAmountByEventAndStatusIn(eventId, ALREADY_TRIGGERED);
        long owedMinor = Math.max(0L, perEventNetMinor - alreadyTriggered);
        if (owedMinor <= 0L) {
            log.info("[payout] skip event {} org {} — net {} already fully triggered ({})",
                    eventId, org.getId(), perEventNetMinor, alreadyTriggered);
            return;
        }

        long payoutMinor = Math.min(owedMinor, availableMinor);
        if (payoutMinor <= 0L) {
            log.info("[payout] skip event {} org {} — owed {} (net {} − triggered {}) but available {} ({}); "
                            + "rolling to next tick",
                    eventId, org.getId(), owedMinor, perEventNetMinor, alreadyTriggered, availableMinor, cur);
            return;
        }
        // What the clamp leaves unpaid. Recorded on the row so the payout.paid
        // reconciliation lands on PARTIAL (top-up-able) rather than PAID (terminal).
        long remainingMinor = owedMinor - payoutMinor;

        // ── step 4b — re-read the status from the database: the event may have been cancelled since step 0c ──
        if (held(events.findStatusIfNotDeleted(eventId))) {
            log.warn("[payout] hold event {} org {} — event was cancelled or deleted while its payout was "
                    + "computed; nothing written", eventId, org.getId());
            return;
        }
        // A hold placed by staff while this payout was being computed (step 0d) stops it too, before any write.
        if (holds.payoutsHeld(org.getId(), eventId)) {
            log.info("[payout] hold event {} org {} — moderation hold placed while its payout was computed; "
                    + "nothing written", eventId, org.getId());
            return;
        }

        // ── step 5 — commit the PLANNED row FIRST, in its own transaction, before any Stripe call ──
        // attempt 1 normally, one more than the highest after a FAILED run; step 0u rules out an open one.
        OptionalInt next = nextAttempt(eventId);
        if (next.isEmpty()) {
            // Attempt cap reached: park the last run for a human instead of minting attempt N+1.
            payoutRuns.findFirstByEventIdOrderByAttemptDesc(eventId)
                    .ifPresent(last -> parkAtAttemptCap(event, org, last));
            return;
        }
        int attempt = next.getAsInt();
        PayoutRun draft = new PayoutRun();
        draft.setOrgId(org.getId());
        draft.setEventId(eventId);
        draft.setStripeAccountId(acct);
        draft.setAmountMinor(payoutMinor);
        draft.setRemainingMinor(remainingMinor);
        draft.setCurrency(cur);
        draft.setStatus(PayoutRunStatus.PLANNED);
        draft.setAttempt(attempt);
        draft.setIdempotencyKey("evt:" + eventId + ":attempt:" + attempt);
        draft.setStripeDescription(payoutDescription(event));
        // Which Stripe mode is about to be asked for this payout (V130).
        draft.setTestMode(!props.isLiveKey());
        PayoutRun run = runStore.plan(draft);

        // ── steps 6/7 — create the payout from the row and commit its outcome ──
        submit(run);
    }

    /**
     * Create the payout described by {@code run} and commit what Stripe answered. An unchecked error leaves
     * the run PLANNED (committed), so the resolver decides next tick whether the payout exists.
     */
    private void submit(PayoutRun run) {
        try {
            Payout po = stripeClient.payouts().create(createParams(run), RequestOptions.builder()
                    .setStripeAccount(run.getStripeAccountId())
                    .setIdempotencyKey(run.getIdempotencyKey())
                    .build());
            if (!runStore.recordSubmitted(run.getId(), po.getId(), Instant.now())) {
                log.error("[payout] HUMAN NEEDED: Stripe accepted payout po={} for run {} (event {} acct {}) but the "
                                + "run had already left PLANNED/RETRYING; reconcile it by hand",
                        po.getId(), run.getId(), run.getEventId(), run.getStripeAccountId());
                return;
            }
            log.info("[payout] SUBMITTED event {} org {} acct {} amount={} {} po={} attempt={} remaining={}",
                    run.getEventId(), run.getOrgId(), run.getStripeAccountId(), run.getAmountMinor(),
                    run.getCurrency(), po.getId(), run.getAttempt(), run.getRemainingMinor());
            if (run.getRemainingMinor() > 0L) {
                log.warn("[payout] event {} org {} was CLAMPED to the available balance — {} {} of the "
                                + "owed net is still outstanding and will be topped up once the balance "
                                + "covers it (run reconciles to PARTIAL, not PAID)",
                        run.getEventId(), run.getOrgId(), run.getRemainingMinor(), run.getCurrency());
            }
        } catch (IdempotencyException e) {
            // The key was used with other params: a payout may exist. A fresh attempt could pay twice.
            runStore.holdForHuman(run.getId(), PayoutBlockReason.IDEMPOTENCY_MISMATCH);
            log.error("[payout] HUMAN NEEDED: Stripe refused key {} as reused with other params — run {} (event {} "
                            + "org {} acct {}) parked BLOCKED; check the account's payouts before releasing it",
                    run.getIdempotencyKey(), run.getId(), run.getEventId(), run.getOrgId(),
                    run.getStripeAccountId(), e);
        } catch (StripeException e) {
            String code = e.getCode();
            String reason = code != null ? code : e.getMessage();
            if (!isDefinitiveRejection(e)) {
                // Unknown outcome: Stripe may have minted the payout. Never FAILED, which would mint attempt N+1.
                runStore.recordRetrying(run.getId(), reason);
                log.error("[payout] unknown outcome for event {} org {} acct {} amount={} {} key={} — run parked "
                                + "RETRYING; the next sweep resolves it from Stripe's payout list",
                        run.getEventId(), run.getOrgId(), run.getStripeAccountId(), run.getAmountMinor(),
                        run.getCurrency(), run.getIdempotencyKey(), e);
                return;
            }
            // Stripe refused (4xx), so no po_ exists: FAILED frees the event for a fresh attempt next tick.
            runStore.recordFailed(run.getId(), reason);
            if ("balance_insufficient".equals(code)) {
                log.warn("[payout] balance_insufficient for event {} org {} acct {} — marking FAILED, re-candidates next tick",
                        run.getEventId(), run.getOrgId(), run.getStripeAccountId());
            } else {
                log.error("[payout] FAILED event {} org {} acct {} amount={} {} — {}", run.getEventId(),
                        run.getOrgId(), run.getStripeAccountId(), run.getAmountMinor(), run.getCurrency(), code, e);
            }
        }
    }

    /** Built from the row alone, so the wire never carries a recomputed amount under a stored key. */
    private static PayoutCreateParams createParams(PayoutRun run) {
        return PayoutCreateParams.builder()
                .setAmount(run.getAmountMinor())
                .setCurrency(run.getCurrency())
                .setDescription(run.getStripeDescription())
                .putMetadata("event_id", run.getEventId().toString())
                .putMetadata("org_id", run.getOrgId().toString())
                .build();
    }

    /**
     * Settle a run whose create answer was never recorded (PLANNED or RETRYING) from Stripe's payout list,
     * never by re-sending: one unrecorded payout matching event, amount and currency is adopted as SUBMITTED;
     * none means it was never created (FAILED, the next attempt is fresh); two or more, or a list unreadable
     * for over {@link #OUTCOME_UNKNOWN_AFTER}, park it BLOCKED for a human.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resolveUnknownRun(UUID runId) {
        if (!props.isPayoutScheduleManual()) return;
        PayoutRun run = payoutRuns.findById(runId).orElse(null);
        if (run == null || !UNRESOLVED.contains(run.getStatus()) || run.isTestMode() == props.isLiveKey()) return;

        List<Payout> matches = new ArrayList<>();
        try {
            PayoutListParams params = PayoutListParams.builder()
                    .setCreated(PayoutListParams.Created.builder()
                            .setGte(run.getCreatedAt().getEpochSecond() - LIST_SLACK_SECONDS).build())
                    .setLimit(100L)
                    .build();
            RequestOptions onAcct = RequestOptions.builder().setStripeAccount(run.getStripeAccountId()).build();
            for (Payout po : stripeClient.payouts().list(params, onAcct).autoPagingIterable()) {
                if (madeBy(po, run) && !payoutRuns.existsByStripePayoutId(po.getId())) matches.add(po);
            }
        } catch (StripeException e) {
            if (run.getCreatedAt().isBefore(Instant.now().minus(OUTCOME_UNKNOWN_AFTER))) {
                runStore.holdForHuman(run.getId(), PayoutBlockReason.OUTCOME_UNKNOWN);
                log.error("[payout-resolve] HUMAN NEEDED: payouts on acct {} unreadable since run {} (event {}) was "
                                + "planned {} — parked BLOCKED; check Stripe for a payout of {} {}",
                        run.getStripeAccountId(), run.getId(), run.getEventId(), run.getCreatedAt(),
                        run.getAmountMinor(), run.getCurrency(), e);
            } else {
                log.warn("[payout-resolve] could not list payouts on acct {} for run {} — {} (retrying next tick)",
                        run.getStripeAccountId(), run.getId(), e.getCode());
            }
            return;
        }

        if (matches.size() == 1) {
            Payout po = matches.get(0);
            Instant created = po.getCreated() == null ? Instant.now() : Instant.ofEpochSecond(po.getCreated());
            if (runStore.recordSubmitted(run.getId(), po.getId(), created)) {
                log.info("[payout-resolve] run {} (event {}) adopted Stripe payout {} — SUBMITTED",
                        run.getId(), run.getEventId(), po.getId());
            } else {
                log.error("[payout-resolve] HUMAN NEEDED: payout po={} matches run {} (event {}) but the run had "
                        + "already left PLANNED/RETRYING; reconcile it by hand", po.getId(), run.getId(), run.getEventId());
            }
        } else if (matches.size() > 1) {
            runStore.holdForHuman(run.getId(), PayoutBlockReason.AMBIGUOUS_MATCH);
            log.error("[payout-resolve] HUMAN NEEDED: {} unrecorded Stripe payouts match run {} (event {} acct {}) — "
                            + "parked BLOCKED; {}", matches.size(), run.getId(), run.getEventId(),
                    run.getStripeAccountId(), matches.stream().map(Payout::getId).toList());
        } else {
            runStore.recordFailed(run.getId(), PayoutBlockReason.NOT_CREATED);
            log.warn("[payout-resolve] run {} (event {}) has no payout at Stripe — FAILED, the next attempt is fresh",
                    run.getId(), run.getEventId());
        }
    }

    /** Same event, amount and currency as the row; the id is checked against recorded runs by the caller. */
    private static boolean madeBy(Payout po, PayoutRun run) {
        return po.getMetadata() != null
                && run.getEventId().toString().equals(po.getMetadata().get("event_id"))
                && po.getAmount() != null && po.getAmount() == run.getAmountMinor()
                && run.getCurrency().equalsIgnoreCase(po.getCurrency());
    }

    /**
     * Recover this org's platform-funded refunds and lost-dispute shares on their own, and transfer
     * back shares of disputes that turned won, with no event being paid out.
     *
     * <p>{@link #payOneEvent} only reaches step 0b for an org that still has a payout CANDIDATE.
     * An org whose events have all been paid out already has no candidate, so a refund imin
     * fronted for it would never be pulled back. The sweeper calls this for every org that owes
     * one, before the candidate loop.
     *
     * <p>Gated on the connected account id alone, deliberately: the reversal is a PLATFORM call
     * against the destination transfer, so a debt is still recoverable from an org that can no
     * longer be paid out.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recoverForOrg(UUID orgId) {
        if (!props.isPayoutScheduleManual()) return;   // same master kill-switch as the payout
        Organization org = orgs.findById(orgId).orElse(null);
        if (org == null) return;
        if (org.getStripeAccountId() == null || org.getStripeAccountId().isBlank()) return;
        stamper.stampOrg(org);
        recoverPlatformFundedRefunds(org);
        settleDisputes(org);
    }

    /**
     * Re-read one {@code SUBMITTED} run's payout from Stripe and apply the same transition
     * {@code SettlementIngestService.ingestPayout} would, so the trigger ledger closes even
     * when no {@code payout.*} webhook ever arrives.
     *
     * <p>This is not belt-and-braces: a SUBMITTED run blocks EVERY event for its org via the
     * org-level in-flight guard, and its only other exit is a connected-account-scoped
     * {@code payout.paid}/{@code payout.failed} delivery — which is dark whenever the OPTIONAL
     * {@code STRIPE_WEBHOOK_SECRET_CONNECT} is blank, and which Stripe abandons after ~3 days
     * of retries. Without this poll one missed delivery freezes the org's payouts permanently
     * with no signal but a 75-day retention WARN.
     *
     * <p>Own {@code REQUIRES_NEW} transaction so one unreadable payout can't roll back the
     * batch. A payout Stripe still reports as pending/in_transit is LEFT SUBMITTED (it really
     * is in flight); it is logged loudly once it is older than four reconcile windows.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcileSubmittedRun(UUID runId) {
        PayoutRun run = payoutRuns.findById(runId).orElse(null);
        if (run == null || run.getStatus() != PayoutRunStatus.SUBMITTED) return;

        String poId = run.getStripePayoutId();
        if (poId == null || poId.isBlank()) {
            // SUBMITTED is only ever written together with the po_ id, so this is a corrupted
            // row rather than a real in-flight payout. Never guess — it would risk a re-pay.
            log.error("[payout-recon] run {} (event {} org {}) is SUBMITTED with NO stripe_payout_id — "
                            + "cannot reconcile; this row is blocking every payout for acct {}",
                    run.getId(), run.getEventId(), run.getOrgId(), run.getStripeAccountId());
            return;
        }

        Payout po;
        try {
            po = stripeClient.payouts().retrieve(poId,
                    RequestOptions.builder().setStripeAccount(run.getStripeAccountId()).build());
        } catch (StripeException e) {
            log.warn("[payout-recon] could not retrieve payout {} on acct {} — {} (retrying next tick)",
                    poId, run.getStripeAccountId(), e.getCode());
            return;
        }

        String status = po.getStatus();
        if ("paid".equals(status)) {
            // Same rule as the webhook path: a clamped run settles PARTIAL, not PAID, so the
            // event stays eligible for its top-up.
            run.setStatus(run.getRemainingMinor() > 0L ? PayoutRunStatus.PARTIAL : PayoutRunStatus.PAID);
            run.setPaidAt(po.getArrivalDate() == null ? Instant.now() : Instant.ofEpochSecond(po.getArrivalDate()));
            payoutRuns.save(run);
            log.info("[payout-recon] polled payout {} -> run {} {} (event {})",
                    poId, run.getId(), run.getStatus(), run.getEventId());
            // "Your payout is on its way", same as the webhook path — this poll exists precisely
            // for when the connect webhook secret is blank, so it owes the organizer the same
            // notice. Dedup-safe: the SUBMITTED guard above means only the transition gets here.
            publisher.publishEvent(new PayoutArrivedEvent(run.getId()));
        } else if ("failed".equals(status) || "canceled".equals(status)) {
            run.setStatus(PayoutRunStatus.FAILED);
            String reason = po.getFailureCode() != null ? po.getFailureCode() : status;
            run.setFailureReason(reason);
            payoutRuns.save(run);
            log.warn("[payout-recon] polled payout {} -> run {} FAILED ({}) (event {})",
                    poId, run.getId(), reason, run.getEventId());
        } else if (run.getSubmittedAt() != null
                && run.getSubmittedAt().isBefore(Instant.now().minus(
                        Duration.ofHours(4L * Math.max(1, props.getPayoutReconcileAfterHours()))))) {
            log.error("[payout-recon] payout {} for event {} org {} has been {} since {} — every payout "
                            + "for acct {} is blocked until it settles; check the Stripe dashboard",
                    poId, run.getEventId(), run.getOrgId(), status, run.getSubmittedAt(),
                    run.getStripeAccountId());
        }
    }

    /**
     * True only when Stripe DEFINITIVELY refused the create — the request reached Stripe,
     * Stripe answered with a 4xx, and therefore no {@code po_} exists. Everything else
     * (connection/read timeout, rate limit, 5xx, or any error carrying no HTTP status at
     * all) leaves the outcome UNKNOWN and must never advance the attempt counter: the
     * payout may already have been created.
     */
    private static boolean isDefinitiveRejection(StripeException e) {
        if (e instanceof ApiConnectionException) return false;   // never reached Stripe, or no answer
        if (e instanceof RateLimitException) return false;       // retry the same key
        Integer status = e.getStatusCode();
        // 409: a concurrent request with this key is still in flight at Stripe, so the outcome is unknown.
        return status != null && status >= 400 && status < 500 && status != 409;
    }

    /** Held = CANCELLED, or no status because the event is deleted or gone; DRAFT/LIVE/PAST money is earned. */
    static boolean held(Optional<EventStatus> status) {
        return status.map(s -> s == EventStatus.CANCELLED).orElse(true);
    }

    /**
     * The next attempt number for an event, or {@link OptionalInt#empty()} when the event has
     * spent its {@code STRIPE_PAYOUT_MAX_ATTEMPTS} budget and must be parked for a human.
     *
     * <p>1 when there are no runs, else one more than the highest attempt: steps 0 and 0u rule out an open
     * run, so every earlier one is settled and a fresh attempt mints a fresh idempotency key.
     */
    private OptionalInt nextAttempt(UUID eventId) {
        int used = payoutRuns.maxAttemptByEventId(eventId);
        if (used >= Math.max(1, props.getPayoutMaxAttempts())) return OptionalInt.empty();
        return OptionalInt.of(used + 1);
    }

    /**
     * Park the event's last run {@code BLOCKED} after the attempt cap and tell the organizer.
     * Terminal by design: step 0a never lets a run parked this way be picked up again, so a
     * payout that has failed N times stops costing a Stripe call (and a log line) every night.
     */
    private void parkAtAttemptCap(Event event, Organization org, PayoutRun last) {
        last.setStatus(PayoutRunStatus.BLOCKED);
        if (last.getFailureReason() == null || last.getFailureReason().isBlank()) {
            last.setFailureReason(PayoutBlockReason.ATTEMPT_LIMIT);
        }
        payoutRuns.save(last);
        log.error("[payout] event {} org {} hit the {}-attempt cap — run {} parked BLOCKED ({}); it will "
                        + "NOT be retried automatically, the organizer has been notified",
                event.getId(), org.getId(), Math.max(1, props.getPayoutMaxAttempts()),
                last.getId(), last.getFailureReason());
        publisher.publishEvent(new PayoutBlockedEvent(last.getId()));
    }

    /**
     * Record — ONCE — that the connected account has nowhere to pay into, and tell the
     * organizer. Deliberately not terminal: the guard is the existing row, so the nightly
     * sweep neither duplicates it nor re-emails, and the event pays out normally on the first
     * tick after a bank account is attached.
     */
    private void parkNoBankAccount(Event event, Organization org, long netMinor, String currency) {
        UUID eventId = event.getId();
        if (payoutRuns.existsByEventIdAndStatusAndFailureReason(
                eventId, PayoutRunStatus.BLOCKED, PayoutBlockReason.NO_BANK_ACCOUNT)) {
            log.info("[payout] event {} org {} still has no external bank account on acct {} — already "
                    + "parked BLOCKED, not re-notifying", eventId, org.getId(), org.getStripeAccountId());
            return;
        }

        PayoutRun r = new PayoutRun();
        r.setOrgId(org.getId());
        r.setEventId(eventId);
        r.setStripeAccountId(org.getStripeAccountId());
        // The net we could not send — no money moved, and BLOCKED is in neither IN_FLIGHT
        // nor ALREADY_TRIGGERED, so this amount never enters the payout math.
        r.setAmountMinor(netMinor);
        r.setCurrency(currency);
        r.setStatus(PayoutRunStatus.BLOCKED);
        r.setFailureReason(PayoutBlockReason.NO_BANK_ACCOUNT);
        // Same stamp as a real run (V130): the era is the running key's, not the outcome's.
        r.setTestMode(!props.isLiveKey());
        // attempt 0 = "no Stripe payout was ever attempted", so real attempts still start at
        // 1 and the attempt cap is not spent on a block the organizer can clear themselves.
        r.setAttempt(0);
        r.setIdempotencyKey("evt:" + eventId + ":attempt:0");
        PayoutRun saved = payoutRuns.save(r);

        log.warn("[payout] event {} org {} — connected acct {} has NO external bank account; {} {} parked "
                        + "BLOCKED (run {}) and the organizer asked to add a payout bank account",
                eventId, org.getId(), org.getStripeAccountId(), netMinor, r.getCurrency(), saved.getId());
        publisher.publishEvent(new PayoutBlockedEvent(saved.getId()));
    }

    /**
     * Pull back every platform-funded refund of this org that imin has not recovered yet, by
     * reversing the destination transfer of the refunded charge.
     *
     * <p>A refund the connected balance could not cover was paid from the PLATFORM balance
     * ({@code reverse_transfer=false}), which leaves the organizer's share of that sale sitting
     * in their connected balance. Withholding it from the next payout only moved a number: the
     * money stayed there and the debt was marked settled, so imin was permanently short. A
     * transfer reversal is the movement.
     *
     * <p>Amount = {@code gross_s(refund.amountMinor) − fee_s(refund.applicationFeeRefundMinor)}, both
     * converted at the order's own Stripe ratio into the transfer currency: the organizer's share only.
     * The fee share was the platform's money already, so reversing it would take the organizer's side
     * of the fee twice. An order not stamped yet is left for the next tick.
     *
     * <p>Per refund, never all-or-nothing: {@code balance_insufficient} leaves THAT debt open
     * (no partial reversal — a partial would burn the idempotency key at the wrong amount and
     * Stripe rejects the replay) and the payout below proceeds with whatever the balance allows.
     */
    private void recoverPlatformFundedRefunds(Organization org) {
        List<com.imin.iminapi.refund.Refund> owed =
                refunds.findUnrecoveredPlatformFundedByOrgId(org.getId());
        if (owed.isEmpty()) return;

        for (com.imin.iminapi.refund.Refund refund : owed) {
            Order order = orders.findById(refund.getOrderId()).orElse(null);
            if (order == null || order.getSettlementCurrency() == null) {
                log.error("[payout] platform-funded refund {} — order {} is {}; it cannot be sized in the transfer "
                        + "currency, the debt stays open for org {}", refund.getId(), refund.getOrderId(),
                        order == null ? "missing" : "not stamped yet", org.getId());
                continue;
            }
            SettlementRate rate = SettlementRate.of(order);
            long amount = rate.gross(refund.getAmountMinor()) - rate.fee(refund.getApplicationFeeRefundMinor());
            if (amount <= 0L) {
                // The whole refund was the platform's own fee — nothing of the organizer's to pull.
                commitRecoveryMarker(refund, null);
                continue;
            }
            String chargeId = refund.getStripeChargeId();
            if (chargeId == null || chargeId.isBlank()) {
                log.error("[payout] refund {} is platform-funded but carries no charge id — cannot "
                        + "reverse its transfer; {} stays owed by org {}",
                        refund.getId(), amount, org.getId());
                continue;
            }

            try {
                String transferId = stripeClient.charges().retrieve(chargeId).getTransfer();
                if (transferId == null || transferId.isBlank()) {
                    log.error("[payout] charge {} behind platform-funded refund {} has no destination "
                            + "transfer — {} stays owed by org {}",
                            chargeId, refund.getId(), amount, org.getId());
                    continue;
                }
                // The amount is sized in the currency Stripe settled the order in; the transfer must match it.
                String trCurrency = stripeClient.transfers().retrieve(transferId).getCurrency();
                if (!order.getSettlementCurrency().equalsIgnoreCase(trCurrency)) {
                    log.error("[payout] platform-funded refund {} — order {} settled in {} but transfer {} is in {}; "
                            + "never reverse across currencies, {} stays owed by org {}", refund.getId(),
                            order.getId(), order.getSettlementCurrency(), transferId, trCurrency, amount, org.getId());
                    continue;
                }
                com.stripe.model.TransferReversal reversal = stripeClient.transfers().reversals().create(
                        transferId,
                        com.stripe.param.TransferReversalCreateParams.builder()
                                .setAmount(amount)
                                .putMetadata("refund_id", refund.getId().toString())
                                .build(),
                        RequestOptions.builder()
                                .setIdempotencyKey("refund:" + refund.getId() + ":reversal")
                                .build());
                commitRecoveryMarker(refund, reversal.getId());
                log.info("[payout] recovered platform-funded refund {} — reversed {} on transfer {} "
                        + "({}), org {}", refund.getId(), amount, transferId, reversal.getId(), org.getId());
            } catch (StripeException e) {
                if ("balance_insufficient".equals(e.getCode())) {
                    log.warn("[payout] cannot recover platform-funded refund {} yet — org {} transfer "
                            + "balance is short of {}; the debt stays open and the payout continues",
                            refund.getId(), org.getId(), amount);
                } else {
                    log.error("[payout] transfer reversal failed for platform-funded refund {} (org {}, "
                            + "amount {}) — {}", refund.getId(), org.getId(), amount, e.getCode(), e);
                }
            }
        }
    }

    /**
     * Commit the recovery marker through {@link RefundRecoveryMarker}'s own {@code REQUIRES_NEW}
     * transaction, so it survives a rollback of the payout transaction this runs inside — that
     * rollback is an expected outcome here (the {@code payout_runs} UNIQUE violation), and the
     * reversal has already moved money by this point.
     *
     * <p>A marker that will not write is not fatal to the payout, but it IS a reversal with no
     * record: log it loudly with both ids so an operator can reconcile before the next tick,
     * which would otherwise reverse the same debt again.
     */
    private void commitRecoveryMarker(com.imin.iminapi.refund.Refund refund, String reversalId) {
        try {
            recoveryMarker.markRecovered(refund.getId(), reversalId);
        } catch (RuntimeException e) {
            log.error("[payout] MONEY MOVED, MARKER MISSING — reversal {} for platform-funded refund {} "
                    + "succeeded but recovered_at could not be committed; reconcile before the next "
                    + "tick or the debt will be reversed twice", reversalId, refund.getId(), e);
        }
    }

    /**
     * Return what LOST-dispute reversals hold beyond what the orders still owe, then reverse what they owe.
     * Returns go first so an adopted return of an earlier pass is counted before any debt is sized.
     */
    private void settleDisputes(Organization org) {
        // A failure may have moved money without a marker; the order's other disputes wait for the next pass.
        Set<UUID> failedOrders = new HashSet<>();
        returnRecoveredDisputes(org, failedOrders);
        recoverLostDisputes(org, failedOrders);
    }

    /**
     * Reverse each unrecovered LOST dispute's organizer share from its destination transfer: Stripe debits
     * the platform for the chargeback. The key carries the amount; an existing reversal is looked up first.
     */
    private void recoverLostDisputes(Organization org, Set<UUID> failedOrders) {
        // Only the running mode's transfers are reachable.
        boolean testMode = !props.isLiveKey();
        List<Dispute> lost = disputes.findUnrecoveredLostByOrgId(org.getId(), DisputeStatus.LOST, testMode);
        for (Dispute d : lost) {
            if (failedOrders.contains(d.getOrderId())) {
                log.info("[payout] LOST dispute {} left for the next pass — a call on order {} failed in this pass",
                        d.getId(), d.getOrderId());
                continue;
            }
            Order order = orders.findById(d.getOrderId()).orElse(null);
            if (order == null) {
                log.error("[payout] LOST dispute {} names order {}, which does not exist — nothing reversed",
                        d.getId(), d.getOrderId());
                continue;
            }
            if (order.getCurrency() == null || !order.getCurrency().equalsIgnoreCase(d.getCurrency())) {
                log.error("[payout] LOST dispute {} is in {} but order {} is in {} — never reverse across "
                        + "currencies; the debt stays open", d.getId(), d.getCurrency(), order.getId(),
                        order.getCurrency());
                continue;
            }
            if (order.getSettlementCurrency() == null) {
                log.error("[payout] LOST dispute {} — order {} is not stamped yet with what Stripe settled; the debt "
                        + "stays open", d.getId(), order.getId());
                continue;
            }
            long owed = disputeWithholding.owedOnOrder(order);
            // What this row's earlier capped reversals took; owed above already counts it as held.
            long before = disputes.recoveredMinorById(d.getId());
            if (owed <= 0L) {
                // Refunds or an earlier reversal on the order already took the organizer's stake.
                if (!commitDisputeMarker(d, before, 0L, null, true)) failedOrders.add(d.getOrderId());
                continue;
            }
            String chargeId = d.getStripeChargeId();
            if (chargeId == null || chargeId.isBlank()) {
                log.error("[payout] LOST dispute {} carries no charge id — cannot reverse its transfer; {} "
                        + "stays owed by org {}", d.getId(), owed, org.getId());
                continue;
            }
            try {
                String transferId = stripeClient.charges().retrieve(chargeId).getTransfer();
                if (transferId == null || transferId.isBlank()) {
                    log.error("[payout] charge {} behind LOST dispute {} has no destination transfer — {} "
                            + "stays owed by org {}", chargeId, d.getId(), owed, org.getId());
                    continue;
                }
                TransferReversal existing = null;
                for (TransferReversal r : stripeClient.transfers().reversals().list(transferId,
                                TransferReversalListParams.builder().setLimit(100L).build())
                        .autoPagingIterable()) {
                    if (r.getMetadata() != null && d.getId().toString().equals(r.getMetadata().get("dispute_id"))
                            && String.valueOf(before).equals(
                                    r.getMetadata().getOrDefault("dispute_reversed_before", "0"))) {
                        existing = r;
                        break;
                    }
                }
                if (existing != null) {
                    long took = nz(existing.getAmount());
                    if (!commitDisputeMarker(d, before, took, existing.getId(), took >= owed)) {
                        failedOrders.add(d.getOrderId());
                    }
                    log.info("[payout] LOST dispute {} — adopted existing reversal {} ({})",
                            d.getId(), existing.getId(), existing.getAmount());
                    continue;
                }
                Transfer tr = stripeClient.transfers().retrieve(transferId);
                // owed is in the order's settlement currency; the transfer must be in it too.
                if (!order.getSettlementCurrency().equalsIgnoreCase(tr.getCurrency())) {
                    log.error("[payout] LOST dispute {} — order {} settled in {} but transfer {} is in {}; never "
                            + "reverse across currencies, {} stays owed by org {}", d.getId(), order.getId(),
                            order.getSettlementCurrency(), transferId, tr.getCurrency(), owed, org.getId());
                    continue;
                }
                long remaining = nz(tr.getAmount()) - nz(tr.getAmountReversed());
                long amount = Math.min(owed, remaining);
                if (amount <= 0L) {
                    // The debt stays open: it keeps the hold on sibling payouts and blocks returns on the order.
                    log.error("[payout] LOST dispute {} — nothing left on transfer {} to reverse; {} owed by "
                            + "org {} stays open", d.getId(), transferId, owed, org.getId());
                    continue;
                }
                if (amount < owed) {
                    log.error("[payout] LOST dispute {} — transfer {} has only {} left; {} of the {} owed by org {} "
                            + "stays open", d.getId(), transferId, remaining, owed - amount, owed, org.getId());
                }
                TransferReversal rv = stripeClient.transfers().reversals().create(transferId,
                        TransferReversalCreateParams.builder()
                                .setAmount(amount)
                                .putMetadata("dispute_id", d.getId().toString())
                                .putMetadata("stripe_dispute_id", d.getStripeDisputeId())
                                .putMetadata("dispute_reversed_before", String.valueOf(before))
                                .build(),
                        RequestOptions.builder()
                                .setIdempotencyKey("dispute:" + d.getId() + ":reversal:" + amount)
                                .build());
                if (!commitDisputeMarker(d, before, amount, rv.getId(), amount >= owed)) {
                    failedOrders.add(d.getOrderId());
                }
                log.info("[payout] LOST dispute {} — reversed {} on transfer {} ({}), org {}",
                        d.getId(), amount, transferId, rv.getId(), org.getId());
            } catch (StripeException e) {
                failedOrders.add(d.getOrderId());
                if ("balance_insufficient".equals(e.getCode())) {
                    log.warn("[payout] cannot reverse LOST dispute {} yet ({} owed by org {}) — the debt stays "
                            + "open; payouts of this org's other events hold it back", d.getId(), owed, org.getId());
                } else {
                    log.error("[payout] transfer reversal failed for LOST dispute {} (org {}, owed {}) — {}",
                            d.getId(), org.getId(), owed, e.getCode(), e);
                }
            }
        }
    }

    /**
     * Per order with a won or reinstated dispute: give back what its reversals hold beyond the share its
     * LOST disputes owe, from the rows that hold it. Never while a sibling is OPEN or LOST and not reversed.
     */
    private void returnRecoveredDisputes(Organization org, Set<UUID> failedOrders) {
        boolean testMode = !props.isLiveKey();
        Map<UUID, List<Dispute>> byOrder = new LinkedHashMap<>();
        for (Dispute d : disputes.findReturnCandidatesByOrgId(org.getId(),
                List.of(DisputeStatus.WON, DisputeStatus.WITHDRAWN_REINSTATED), testMode)) {
            byOrder.computeIfAbsent(d.getOrderId(), k -> new ArrayList<>()).add(d);
        }
        // Both callers return first when the org has no connected account.
        String acct = org.getStripeAccountId();
        for (Map.Entry<UUID, List<Dispute>> e : byOrder.entrySet()) {
            UUID orderId = e.getKey();
            Order order = orders.findById(orderId).orElse(null);
            if (order == null) continue;
            if (order.getSettlementCurrency() == null) {
                failedOrders.add(orderId);
                log.error("[payout] order {} — return kept: not stamped yet with what Stripe settled", orderId);
                continue;
            }
            try {
                // A return sent in an earlier pass but never recorded counts before anything is sized,
                // including whether there is anything left to return at all.
                adoptUnrecordedReturns(e.getValue(), acct);
            } catch (StripeException ex) {
                failedOrders.add(orderId);
                log.error("[payout] return lookup failed for order {} (org {}) — {}", orderId, org.getId(),
                        ex.getCode(), ex);
                continue;
            } catch (RuntimeException ex) {
                failedOrders.add(orderId);
                log.error("[payout] MONEY MOVED, MARKER MISSING — an earlier return on order {} could not be "
                        + "recorded; the next pass adopts it again", orderId, ex);
                continue;
            }
            long pending = disputes.countOtherOpenOrUnrecoveredLostByOrderId(orderId, NO_DISPUTE,
                    DisputeStatus.OPEN, DisputeStatus.LOST);
            if (pending > 0L) {
                log.info("[payout] order {} — return kept: {} dispute(s) still open or not reversed", orderId, pending);
                continue;
            }
            long returnable = disputeWithholding.returnableOnOrder(order);
            if (returnable <= 0L) continue;
            for (Dispute d : e.getValue()) {
                if (returnable <= 0L) break;
                long before = disputes.returnedMinorById(d.getId());
                long amount = Math.min(returnable, nz(d.getRecoveredMinor()) - before);
                if (amount <= 0L) continue;
                try {
                    Transfer tr = stripeClient.transfers().create(
                            TransferCreateParams.builder()
                                    .setAmount(amount)
                                    .setCurrency(order.getSettlementCurrency())
                                    .setDestination(acct)
                                    .putMetadata("dispute_return_id", d.getId().toString())
                                    .putMetadata("dispute_returned_before", String.valueOf(before))
                                    .putMetadata("event_id", String.valueOf(d.getEventId()))
                                    .build(),
                            RequestOptions.builder()
                                    .setIdempotencyKey("dispute:" + d.getId() + ":return:" + before + ":" + amount)
                                    .build());
                    if (!commitReturnMarker(d, tr.getId(), before, amount)) {
                        failedOrders.add(orderId);
                        break;
                    }
                    returnable -= amount;
                    log.info("[payout] order {} — {} {} of dispute {}'s reversal transferred back to {} ({})",
                            orderId, amount, order.getSettlementCurrency(), d.getId(), acct, tr.getId());
                } catch (StripeException ex) {
                    failedOrders.add(orderId);
                    if ("balance_insufficient".equals(ex.getCode())) {
                        log.warn("[payout] cannot transfer back {} for dispute {} yet — the platform balance is "
                                + "short; retrying next pass", amount, d.getId());
                    } else {
                        log.error("[payout] return transfer failed for dispute {} (org {}, amount {}) — {}",
                                d.getId(), org.getId(), amount, ex.getCode(), ex);
                    }
                    break;
                }
            }
        }
    }

    /** Record every return transfer Stripe holds for these rows that the ledger has not counted yet. */
    private void adoptUnrecordedReturns(List<Dispute> holders, String acct) throws StripeException {
        Map<String, Dispute> byId = new HashMap<>();
        long since = Long.MAX_VALUE;
        for (Dispute d : holders) {
            byId.put(d.getId().toString(), d);
            since = Math.min(since, d.getRecoveredAt().getEpochSecond() - 60L);
        }
        List<Transfer> found = new ArrayList<>();
        for (Transfer t : stripeClient.transfers().list(TransferListParams.builder()
                        .setDestination(acct)
                        .setCreated(TransferListParams.Created.builder().setGte(since).build())
                        .setLimit(100L)
                        .build())
                .autoPagingIterable()) {
            if (t.getMetadata() != null && byId.containsKey(t.getMetadata().get("dispute_return_id"))) found.add(t);
        }
        for (Dispute d : holders) {
            long before = disputes.returnedMinorById(d.getId());
            boolean adopted = true;
            while (adopted) {
                adopted = false;
                for (Transfer t : found) {
                    if (d.getId().toString().equals(t.getMetadata().get("dispute_return_id"))
                            && String.valueOf(before).equals(t.getMetadata().get("dispute_returned_before"))) {
                        disputeMarker.markReturned(d.getId(), t.getId(), before, nz(t.getAmount()));
                        log.info("[payout] dispute {} — adopted return transfer {} ({})", d.getId(), t.getId(),
                                t.getAmount());
                        before += nz(t.getAmount());
                        adopted = true;
                        break;
                    }
                }
            }
        }
    }

    /** {@link #commitRecoveryMarker} for a dispute: its own transaction, never rethrown; false when it failed. */
    private boolean commitDisputeMarker(Dispute d, long before, long amountMinor, String reversalId,
                                        boolean complete) {
        try {
            disputeMarker.markRecovered(d.getId(), before, amountMinor, reversalId, complete);
            return true;
        } catch (RuntimeException e) {
            log.error("[payout] MONEY MOVED, MARKER MISSING — reversal {} ({}) for dispute {} succeeded but "
                    + "recovered_at could not be committed; the next pass adopts it by metadata",
                    reversalId, amountMinor, d.getId(), e);
            return false;
        }
    }

    private boolean commitReturnMarker(Dispute d, String transferId, long before, long amountMinor) {
        try {
            disputeMarker.markReturned(d.getId(), transferId, before, amountMinor);
            return true;
        } catch (RuntimeException e) {
            log.error("[payout] MONEY MOVED, MARKER MISSING — return transfer {} ({}) for dispute {} succeeded but "
                    + "returned_minor could not be committed; the next pass adopts it by metadata",
                    transferId, amountMinor, d.getId(), e);
            return false;
        }
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    /** An order created before this and still unstamped has outlived the payout buffer. */
    private Instant stuckBefore() {
        return Instant.now().minus(Duration.ofDays(Math.max(0, props.getPayoutBufferDays())));
    }

    /** What Stripe says about the connected account's payout destination. */
    private enum BankAccounts {
        /** At least one external (bank/card) account is attached. */
        HAS,
        /** Stripe answered, and the account has none. A fact about the account. */
        NONE,
        /** Stripe did not answer. Not a fact about anything — never act on it. */
        UNKNOWN
    }

    /**
     * Whether the connected account has an external account attached — the destination for
     * the no-destination {@link Payout}. A Stripe failure returns {@link BankAccounts#UNKNOWN},
     * NOT "none": conflating them turned every timeout into a permanent-looking organizer
     * alert while still skipping the payout.
     */
    private BankAccounts externalBankAccounts(String acct) {
        try {
            RequestOptions onAcct = RequestOptions.builder().setStripeAccount(acct).build();
            Account a = stripeClient.accounts().retrieve(acct,
                    AccountRetrieveParams.builder().addExpand("external_accounts").build(), onAcct);
            boolean has = a.getExternalAccounts() != null
                    && a.getExternalAccounts().getData() != null
                    && !a.getExternalAccounts().getData().isEmpty();
            return has ? BankAccounts.HAS : BankAccounts.NONE;
        } catch (StripeException e) {
            log.error("[payout] external-account check failed for acct {} — {}; treating as UNKNOWN "
                    + "(no run written, no organizer alert)", acct, e.getCode(), e);
            return BankAccounts.UNKNOWN;
        }
    }
}
