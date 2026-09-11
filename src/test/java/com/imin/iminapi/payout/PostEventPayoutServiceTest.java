package com.imin.iminapi.payout;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.settlement.Settlement;
import com.imin.iminapi.settlement.SettlementObjectType;
import com.imin.iminapi.settlement.SettlementRepository;
import com.imin.iminapi.settlement.SettlementStatus;
import com.imin.iminapi.stripe.StripeConnectState;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Balance;
import com.stripe.model.Payout;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiRequest;
import com.stripe.net.ApiResource;
import com.stripe.net.StripeResponseGetter;
import com.stripe.service.BalanceService;
import com.stripe.service.PayoutService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Money-path tests for the Track B Phase 2 per-event payout unit
 * ({@link PostEventPayoutService}) against H2 + Flyway, with the Stripe HTTP layer
 * faked at the {@link StripeResponseGetter} seam (the {@code BalanceService} /
 * {@code PayoutService} are {@code final}, so a real {@link StripeClient} over a
 * mocked response-getter is the only clean way to drive {@code balance().retrieve}
 * and {@code payouts().create}).
 *
 * <p>Covers the mandatory cases:
 * <ul>
 *   <li>(a) double-pay guard — one payout per org per tick across two events;</li>
 *   <li>(b) idempotency re-run — a second {@code payOneEvent} mints no 2nd payout;</li>
 *   <li>(c) clamp to available — {@code payout.amount = min(net, available)};</li>
 *   <li>(d) dispute guard — an OPEN dispute skips the event, a closed one does not;</li>
 *   <li>(e) FEE EXCLUDED — payout amount equals net, not gross.</li>
 * </ul>
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
@RecordApplicationEvents
class PostEventPayoutServiceTest {

    /** Test-controllable Stripe backend: balance to report + payouts captured. */
    static class FakeStripe {
        final AtomicReference<Long> availableMinor = new AtomicReference<>(0L);
        final AtomicInteger payoutCount = new AtomicInteger(0);
        final AtomicReference<Long> lastPayoutAmount = new AtomicReference<>(null);
        final AtomicReference<String> lastPayoutId = new AtomicReference<>(null);
        final AtomicReference<String> lastIdempotencyKey = new AtomicReference<>(null);
        /** When set, payouts().create throws balance_insufficient (race simulation). */
        volatile boolean failBalanceInsufficient = false;
        /** When set, payouts().create throws a TRANSPORT error (socket/read timeout). */
        volatile boolean failApiConnection = false;
        /** When false, accounts().retrieve reports NO external bank account. */
        volatile boolean hasBank = true;
        /** When set, accounts().retrieve fails at the transport level (outcome UNKNOWN). */
        volatile boolean failAccountRetrieve = false;
        /** Transfer reversals (platform-funded refund recovery) seen this run. */
        final AtomicInteger reversalCount = new AtomicInteger(0);
        final AtomicReference<Long> lastReversalAmount = new AtomicReference<>(null);
        final AtomicReference<String> lastReversalKey = new AtomicReference<>(null);
        final AtomicReference<String> lastReversedTransfer = new AtomicReference<>(null);
        /** When set, the reversal is refused: the connected balance cannot cover it. */
        volatile boolean failReversalBalanceInsufficient = false;
        /** When set, the external-account check dies with a RUNTIME error, rolling the payout tx back. */
        volatile boolean crashAfterRecovery = false;
        /** Status the reconciliation poll (GET /v1/payouts/{id}) reports back. */
        volatile String retrievedStatus = "paid";
        volatile String retrievedFailureCode = null;

        void reset() {
            availableMinor.set(0L);
            payoutCount.set(0);
            lastPayoutAmount.set(null);
            lastPayoutId.set(null);
            lastIdempotencyKey.set(null);
            failBalanceInsufficient = false;
            failApiConnection = false;
            hasBank = true;
            failAccountRetrieve = false;
            reversalCount.set(0);
            lastReversalAmount.set(null);
            lastReversalKey.set(null);
            lastReversedTransfer.set(null);
            failReversalBalanceInsufficient = false;
            crashAfterRecovery = false;
            retrievedStatus = "paid";
            retrievedFailureCode = null;
        }

        @SuppressWarnings("unchecked")
        <T extends StripeObject> T handle(ApiRequest req) throws Exception {
            String path = req.getPath();
            if (path != null && path.startsWith("/v1/balance")) {
                String json = """
                    { "object": "balance",
                      "available": [ { "amount": %d, "currency": "eur" } ],
                      "pending": [] }
                    """.formatted(availableMinor.get());
                return (T) ApiResource.GSON.fromJson(json, Balance.class);
            }
            // GET /v1/payouts/{id} — the reconciliation poll. Distinguished from the
            // create (POST /v1/payouts) by the id segment in the path.
            if (path != null && path.startsWith("/v1/payouts/")) {
                String json = """
                    { "object": "payout", "id": "%s", "amount": %d, "currency": "eur",
                      "status": "%s", "arrival_date": %d, "failure_code": %s }
                    """.formatted(path.substring("/v1/payouts/".length()),
                        lastPayoutAmount.get() == null ? 0L : lastPayoutAmount.get(),
                        retrievedStatus,
                        java.time.Instant.now().getEpochSecond(),
                        retrievedFailureCode == null ? "null" : "\"" + retrievedFailureCode + "\"");
                return (T) ApiResource.GSON.fromJson(json, Payout.class);
            }
            if (path != null && path.startsWith("/v1/payouts")) {
                if (req.getOptions() != null) {
                    lastIdempotencyKey.set(req.getOptions().getIdempotencyKey());
                }
                if (failApiConnection) {
                    // The read timed out. Stripe MAY have created the payout — we never saw
                    // the response. ApiConnectionException extends StripeException, so the
                    // service's single catch used to record this as FAILED.
                    throw new ApiConnectionException("IOException during API request: read timed out");
                }
                if (failBalanceInsufficient) {
                    // InvalidRequestException(message, param, requestId, code, statusCode, cause)
                    // — getCode() reads the 4th arg.
                    throw new InvalidRequestException(
                            "Insufficient funds", "amount", null, "balance_insufficient", 400, null);
                }
                java.util.Map<String, Object> params = req.getParams();
                Object amt = params == null ? null : params.get("amount");
                long amount = amt == null ? 0L : ((Number) amt).longValue();
                String poId = "po_test_" + payoutCount.incrementAndGet();
                lastPayoutAmount.set(amount);
                lastPayoutId.set(poId);
                String json = """
                    { "object": "payout", "id": "%s", "amount": %d, "currency": "eur", "status": "pending" }
                    """.formatted(poId, amount);
                return (T) ApiResource.GSON.fromJson(json, Payout.class);
            }
            // POST /v1/transfers/{id}/reversals — platform-funded refund recovery.
            if (path != null && path.startsWith("/v1/transfers/")) {
                if (req.getOptions() != null) lastReversalKey.set(req.getOptions().getIdempotencyKey());
                if (failReversalBalanceInsufficient) {
                    throw new InvalidRequestException("Insufficient funds in the transfer balance",
                            "amount", null, "balance_insufficient", 400, null);
                }
                java.util.Map<String, Object> params = req.getParams();
                Object amt = params == null ? null : params.get("amount");
                lastReversalAmount.set(amt == null ? 0L : ((Number) amt).longValue());
                lastReversedTransfer.set(path.substring("/v1/transfers/".length())
                        .replace("/reversals", ""));
                String json = """
                    { "object": "transfer_reversal", "id": "trr_test_%d", "amount": %d,
                      "currency": "eur" }
                    """.formatted(reversalCount.incrementAndGet(), lastReversalAmount.get());
                return (T) ApiResource.GSON.fromJson(json, com.stripe.model.TransferReversal.class);
            }
            // GET /v1/charges/{id} — the charge behind a platform-funded refund, read for its
            // destination transfer.
            if (path != null && path.startsWith("/v1/charges/")) {
                String json = """
                    { "object": "charge", "id": "%s", "transfer": "tr_test_1" }
                    """.formatted(path.substring("/v1/charges/".length()));
                return (T) ApiResource.GSON.fromJson(json, com.stripe.model.Charge.class);
            }
            if (path != null && path.startsWith("/v1/accounts")) {
                if (crashAfterRecovery) {
                    // Unchecked, so it escapes payOneEvent and rolls its REQUIRES_NEW tx back —
                    // the same rollback the payout_runs UNIQUE violation produces in production.
                    throw new IllegalStateException("simulated crash after the recovery reversal");
                }
                if (failAccountRetrieve) {
                    throw new ApiConnectionException("IOException during API request: read timed out");
                }
                String data = hasBank
                        ? "{ \"object\": \"bank_account\", \"id\": \"ba_test\", \"last4\": \"4242\", \"currency\": \"eur\" }"
                        : "";
                String json = "{ \"object\": \"account\", \"id\": \"acct_test\", "
                        + "\"external_accounts\": { \"object\": \"list\", \"data\": [ " + data + " ] } }";
                return (T) ApiResource.GSON.fromJson(json, com.stripe.model.Account.class);
            }
            throw new IllegalStateException("unexpected Stripe path in test: " + path);
        }
    }

    @Autowired PostEventPayoutService service;
    @Autowired StripeProperties props;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired SettlementRepository settlements;
    @Autowired DisputeRepository disputes;
    @Autowired PayoutRunRepository payoutRuns;
    @Autowired UserRepository users;
    /** Records what the service published — the organizer notification is async, the event is not. */
    @Autowired ApplicationEvents published;

    /**
     * The {@code BalanceService}/{@code PayoutService} accessors are {@code final},
     * so we mock the {@link StripeClient} itself and stub {@code balance()} /
     * {@code payouts()} to return REAL services wired over a mock
     * {@link StripeResponseGetter} — which the {@link FakeStripe} backend answers by
     * request path. This is the same seam the integration suite uses.
     */
    @MockitoBean StripeClient stripeClient;

    private final FakeStripe fake = new FakeStripe();
    private Organization org;

    @BeforeEach
    void setUp() {
        wipe();
        fake.reset();
        props.setPayoutScheduleManual(true);   // enable the money path for these tests

        StripeResponseGetter rg = mock(StripeResponseGetter.class);
        try {
            // The fluent services route through the default request(ApiRequest, Type)
            // overload, so stub THAT (not the 7-arg abstract one) and read path/params
            // off the ApiRequest.
            when(rg.request(any(ApiRequest.class), any(Type.class)))
                    .thenAnswer((InvocationOnMock inv) -> fake.handle(inv.getArgument(0)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(stripeClient.balance()).thenReturn(new BalanceService(rg));
        when(stripeClient.payouts()).thenReturn(new PayoutService(rg));
        when(stripeClient.accounts()).thenReturn(new com.stripe.service.AccountService(rg));
        when(stripeClient.charges()).thenReturn(new com.stripe.service.ChargeService(rg));
        when(stripeClient.transfers()).thenReturn(new com.stripe.service.TransferService(rg));

        org = newEligibleOrg();
    }

    @AfterEach
    void tearDown() {
        props.setPayoutScheduleManual(false);
        wipe();
    }

    private void wipe() {
        // disputes first: the rows FK to orders/events/organizations.
        disputes.deleteAll();
        // refunds carry order ids, so they go before the orders below.
        refunds.deleteAll();
        payoutRuns.deleteAll();
        settlements.deleteAll();
        orders.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    // ── (e) FEE EXCLUDED — payout == net, not gross ────────────────────────────────
    @Test
    void payout_amount_excludes_application_fee() {
        Event e = newEndedEvent(org);
        // gross 10_000, app fee 1_500, no refunds → net 8_500.
        order(e, 10_000, 1_500);
        fake.availableMinor.set(50_000L);   // plenty available

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isEqualTo(1);
        assertThat(fake.lastPayoutAmount.get())
                .as("payout draws the organizer NET (gross - fee), never the gross")
                .isEqualTo(8_500L);
        PayoutRun run = payoutRuns.findByEventId(e.getId()).get(0);
        assertThat(run.getStatus()).isEqualTo(PayoutRunStatus.SUBMITTED);
        assertThat(run.getAmountMinor()).isEqualTo(8_500L);
        assertThat(run.getStripePayoutId()).isEqualTo("po_test_1");
        assertThat(run.getIdempotencyKey()).isEqualTo("evt:" + e.getId() + ":attempt:1");
    }

    // ── (c) clamp to available ─────────────────────────────────────────────────────
    @Test
    void payout_clamps_to_available_balance() {
        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);            // net 9_000
        fake.availableMinor.set(4_000L);    // only 4_000 available

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isEqualTo(1);
        assertThat(fake.lastPayoutAmount.get())
                .as("min(net=9000, available=4000)")
                .isEqualTo(4_000L);
        PayoutRun clamped = payoutRuns.findByEventId(e.getId()).get(0);
        assertThat(clamped.getAmountMinor()).isEqualTo(4_000L);
        assertThat(clamped.getRemainingMinor())
                .as("the clamp left 5_000 of the organizer's net unpaid — record it so the run "
                        + "settles PARTIAL and the event stays toppable-up")
                .isEqualTo(5_000L);
    }

    // ── stripe-3 — a clamped payout must be topped up, never silently written off ──
    @Test
    void clamped_payout_settles_partial_and_the_next_sweep_pays_the_remainder() {
        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);            // net 9_000
        fake.availableMinor.set(4_000L);    // balance short: only 4_000 can move today

        // ── tick 1: pay what the balance allows.
        service.payOneEvent(e.getId());
        assertThat(fake.lastPayoutAmount.get()).isEqualTo(4_000L);

        // The payout.paid webhook reconciles a clamped run to PARTIAL (asserted end-to-end in
        // SettlementIngestWebhookTest); apply that same transition here.
        PayoutRun first = payoutRuns.findByEventId(e.getId()).get(0);
        assertThat(first.getRemainingMinor()).isEqualTo(5_000L);
        first.setStatus(PayoutRunStatus.PARTIAL);
        payoutRuns.save(first);

        // ── tick 2: the balance has caught up. The event must re-candidate and receive
        // EXACTLY the 5_000 remainder — not the full 9_000 net again, and not nothing.
        fake.availableMinor.set(50_000L);
        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isEqualTo(2);
        assertThat(fake.lastPayoutAmount.get())
                .as("net 9_000 − already triggered 4_000 = 5_000 owed")
                .isEqualTo(5_000L);

        List<PayoutRun> runs = payoutRuns.findByEventId(e.getId());
        assertThat(runs).hasSize(2);
        PayoutRun topUp = runs.stream().filter(r -> r.getAttempt() == 2).findFirst().orElseThrow();
        assertThat(topUp.getAmountMinor()).isEqualTo(5_000L);
        assertThat(topUp.getRemainingMinor())
                .as("the top-up covers the rest, so this run settles PAID")
                .isZero();
        assertThat(runs.stream().mapToLong(PayoutRun::getAmountMinor).sum())
                .as("the organizer is paid their whole net across the two runs — never more")
                .isEqualTo(9_000L);

        // ── tick 3: nothing is owed any more, so no third payout is created.
        topUp.setStatus(PayoutRunStatus.PAID);
        payoutRuns.save(topUp);
        service.payOneEvent(e.getId());
        assertThat(fake.payoutCount.get())
                .as("a fully-paid event never pays again")
                .isEqualTo(2);
    }

    @Test
    void skips_when_nothing_available() {
        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);
        fake.availableMinor.set(0L);

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isZero();
        assertThat(payoutRuns.findByEventId(e.getId())).isEmpty();
    }

    // ── eligibility: "sell ⇒ payable" — the transfers capability is the gate ───────
    /**
     * A RESTRICTED org whose transfers capability is still active can take money at
     * checkout, so its ended events must be payable too — the in-tx predicate has to
     * match the candidate query exactly or the sweeper would hand the service events
     * it silently drops.
     */
    @Test
    void restricted_org_with_transfers_active_is_still_paid_out() {
        org.setStripeConnectState(StripeConnectState.RESTRICTED);
        orgs.save(org);
        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);            // net 9_000
        fake.availableMinor.set(50_000L);

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isEqualTo(1);
        assertThat(fake.lastPayoutAmount.get()).isEqualTo(9_000L);
    }

    @Test
    void disabled_org_is_not_paid_out() {
        org.setStripeConnectState(StripeConnectState.DISABLED);
        orgs.save(org);
        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);
        fake.availableMinor.set(50_000L);

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isZero();
        assertThat(payoutRuns.findByEventId(e.getId())).isEmpty();
    }

    @Test
    void org_without_the_transfers_capability_is_not_paid_out() {
        org.setStripePayoutsEnabled(false);
        orgs.save(org);
        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);
        fake.availableMinor.set(50_000L);

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isZero();
        assertThat(payoutRuns.findByEventId(e.getId())).isEmpty();
    }

    // ── (a) double-pay guard — one payout per org per tick ─────────────────────────
    @Test
    void double_pay_guard_one_payout_per_org_per_tick() {
        Event e1 = newEndedEvent(org);
        Event e2 = newEndedEvent(org);   // SAME org / acct
        order(e1, 5_000, 500);           // net 4_500
        order(e2, 7_000, 700);           // net 6_300
        fake.availableMinor.set(100_000L);

        service.payOneEvent(e1.getId());
        service.payOneEvent(e2.getId());   // must be skipped — acct already in flight

        assertThat(fake.payoutCount.get())
                .as("at most ONE in-flight payout per org per tick")
                .isEqualTo(1);
        assertThat(payoutRuns.findByEventId(e1.getId())).hasSize(1);
        assertThat(payoutRuns.findByEventId(e2.getId()))
                .as("the second event got NO payout this tick (rolls forward)")
                .isEmpty();
    }

    // ── (b) idempotency re-run — no second payout ──────────────────────────────────
    @Test
    void idempotent_rerun_creates_no_second_payout() {
        Event e = newEndedEvent(org);
        order(e, 6_000, 600);    // net 5_400
        fake.availableMinor.set(50_000L);

        service.payOneEvent(e.getId());
        // Re-run the same event (a replayed tick). The event now has a SUBMITTED run,
        // so step-0 / candidate guards mean no second payout is created.
        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isEqualTo(1);
        assertThat(payoutRuns.findByEventId(e.getId())).hasSize(1);
    }

    // ── (d) dispute guard — FAILED transfer row skips ──────────────────────────────
    @Test
    void open_dispute_blocks_the_payout() {
        Event e = newEndedEvent(org);
        Order o = order(e, 8_000, 800);
        fake.availableMinor.set(50_000L);

        dispute(o, e, 2_000, DisputeStatus.OPEN);

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get())
                .as("an OPEN dispute freezes every payout for the org — the funds may still go back")
                .isZero();
        assertThat(payoutRuns.findByEventId(e.getId())).isEmpty();
    }

    @Test
    void settlement_rows_alone_no_longer_block_the_payout() {
        Event e = newEndedEvent(org);
        order(e, 8_000, 800);   // net 7_200
        fake.availableMinor.set(50_000L);

        // The settlements table is a READ-MODEL, not the gate. A FAILED payout row is a
        // bank-routing failure, and a FAILED transfer row is an annotation a closed dispute
        // leaves behind — neither may freeze payouts, which is what the old gate did forever.
        Settlement payoutRow = new Settlement();
        payoutRow.setOrgId(org.getId());
        payoutRow.setStripeObjectId("po_failed_old");
        payoutRow.setObjectType(SettlementObjectType.PAYOUT);
        payoutRow.setAmountMinor(1_000);
        payoutRow.setCurrency("eur");
        payoutRow.setStatus(SettlementStatus.FAILED);
        settlements.save(payoutRow);

        Settlement transferRow = new Settlement();
        transferRow.setOrgId(org.getId());
        transferRow.setStripeObjectId("tr_disputed_1");
        transferRow.setObjectType(SettlementObjectType.TRANSFER);
        transferRow.setAmountMinor(8_000);
        transferRow.setCurrency("eur");
        transferRow.setStatus(SettlementStatus.FAILED);
        settlements.save(transferRow);

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get())
                .as("with no row in the disputes registry, nothing blocks")
                .isEqualTo(1);
        assertThat(fake.lastPayoutAmount.get()).isEqualTo(7_200L);
    }

    @Test
    void closed_lost_dispute_no_longer_blocks_but_reduces_the_net() {
        Event e = newEndedEvent(org);
        Order o = order(e, 8_000, 800);   // net 7_200
        fake.availableMinor.set(50_000L);

        // A lost chargeback: the transfer row keeps its FAILED annotation, but the payout
        // must run — reduced by the face value the organizer bears.
        Settlement transferRow = new Settlement();
        transferRow.setOrgId(org.getId());
        transferRow.setStripeObjectId("tr_lost_dispute");
        transferRow.setObjectType(SettlementObjectType.TRANSFER);
        transferRow.setAmountMinor(8_000);
        transferRow.setCurrency("eur");
        transferRow.setStatus(SettlementStatus.FAILED);
        settlements.save(transferRow);

        dispute(o, e, 2_000, DisputeStatus.LOST);

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get())
                .as("a CLOSED dispute never blocks — the loss is settled by the net, not a freeze")
                .isEqualTo(1);
        assertThat(fake.lastPayoutAmount.get())
                .as("net 7_200 − 2_000 of lost face value")
                .isEqualTo(5_200L);
    }

    @Test
    void won_dispute_restores_the_net() {
        Event e = newEndedEvent(org);
        Order o = order(e, 8_000, 800);   // net 7_200
        fake.availableMinor.set(50_000L);

        dispute(o, e, 2_000, DisputeStatus.WON);

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isEqualTo(1);
        assertThat(fake.lastPayoutAmount.get())
                .as("a won dispute takes nothing off the net — the money was never lost")
                .isEqualTo(7_200L);
    }

    @Test
    void balance_insufficient_marks_failed_and_event_recandidates_next_tick() {
        Event e = newEndedEvent(org);
        order(e, 6_000, 600);   // net 5_400
        fake.availableMinor.set(50_000L);
        fake.failBalanceInsufficient = true;

        // ── tick 1: Stripe rejects the create with balance_insufficient ──
        service.payOneEvent(e.getId());

        List<PayoutRun> afterTick1 = payoutRuns.findByEventId(e.getId());
        assertThat(afterTick1).hasSize(1);
        PayoutRun failed = afterTick1.get(0);
        assertThat(failed.getStatus())
                .as("balance_insufficient → FAILED (a PLANNED row would freeze the org + event forever)")
                .isEqualTo(PayoutRunStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("balance_insufficient");
        assertThat(failed.getStripePayoutId())
                .as("no po_ is minted on a rejected create")
                .isNull();

        // The event is now re-candidate-able: neither the per-event existence guard
        // (PLANNED/SUBMITTED/PAID) nor the org-level in-flight guard (PLANNED/SUBMITTED)
        // sees a FAILED run, so the next tick is free to retry.
        assertThat(afterTick1)
                .as("FAILED run does not block the per-event candidate query")
                .noneMatch(r -> r.getStatus() == PayoutRunStatus.PLANNED
                        || r.getStatus() == PayoutRunStatus.SUBMITTED
                        || r.getStatus() == PayoutRunStatus.PAID);
        assertThat(payoutRuns.existsByStripeAccountIdAndStatusIn(org.getStripeAccountId(),
                List.of(PayoutRunStatus.PLANNED, PayoutRunStatus.SUBMITTED)))
                .as("FAILED run does not block the org-level double-pay guard")
                .isFalse();

        // ── tick 2: balance now sufficient → fresh attempt, fresh idempotency key, clean payout ──
        fake.failBalanceInsufficient = false;
        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isEqualTo(1);
        assertThat(fake.lastPayoutAmount.get()).isEqualTo(5_400L);
        List<PayoutRun> afterTick2 = payoutRuns.findByEventId(e.getId());
        assertThat(afterTick2).hasSize(2);
        PayoutRun submitted = afterTick2.stream()
                .filter(r -> r.getStatus() == PayoutRunStatus.SUBMITTED)
                .findFirst().orElseThrow();
        assertThat(submitted.getAttempt())
                .as("retry bumps attempt → a fresh idempotency key")
                .isEqualTo(2);
        assertThat(submitted.getIdempotencyKey()).isEqualTo("evt:" + e.getId() + ":attempt:2");
    }

    // ── stripe-2 — a TRANSPORT failure must NOT bump the attempt (double-pay) ──────
    @Test
    void transport_failure_parks_run_retrying_and_replays_the_same_idempotency_key() {
        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);            // net 9_000
        fake.availableMinor.set(50_000L);
        fake.failApiConnection = true;

        // ── tick 1: Stripe accepted and minted po_A for 9_000, but the read timed out.
        service.payOneEvent(e.getId());

        List<PayoutRun> afterTick1 = payoutRuns.findByEventId(e.getId());
        assertThat(afterTick1).hasSize(1);
        PayoutRun parked = afterTick1.get(0);
        assertThat(parked.getStatus())
                .as("a timeout is NOT a rejection — the outcome is unknown, so the run parks RETRYING")
                .isEqualTo(PayoutRunStatus.RETRYING);
        assertThat(parked.getAttempt()).isEqualTo(1);
        assertThat(parked.getIdempotencyKey()).isEqualTo("evt:" + e.getId() + ":attempt:1");
        assertThat(parked.getAmountMinor()).isEqualTo(9_000L);

        // ── tick 2: the event re-candidates. It must REPLAY key attempt:1, which Stripe
        // answers with the original po_ — NOT mint attempt:2 and a second 9_000 payout.
        fake.failApiConnection = false;
        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get())
                .as("exactly ONE payout request reached Stripe with a fresh key")
                .isEqualTo(1);
        assertThat(fake.lastIdempotencyKey.get())
                .as("the replay reuses attempt:1's key so Stripe returns the ORIGINAL po_ "
                        + "(a bumped attempt would be 18_000 out the door on 9_000 of net)")
                .isEqualTo("evt:" + e.getId() + ":attempt:1");
        assertThat(fake.lastPayoutAmount.get()).isEqualTo(9_000L);

        List<PayoutRun> afterTick2 = payoutRuns.findByEventId(e.getId());
        assertThat(afterTick2)
                .as("one event, one payout unit — no attempt:2 row")
                .hasSize(1);
        assertThat(afterTick2.get(0).getStatus()).isEqualTo(PayoutRunStatus.SUBMITTED);
        assertThat(afterTick2.get(0).getAttempt()).isEqualTo(1);
    }

    @Test
    void definitive_4xx_rejection_still_bumps_the_attempt() {
        Event e = newEndedEvent(org);
        order(e, 6_000, 600);   // net 5_400
        fake.availableMinor.set(50_000L);
        fake.failBalanceInsufficient = true;

        service.payOneEvent(e.getId());
        assertThat(payoutRuns.findByEventId(e.getId()).get(0).getStatus())
                .as("a 400 from Stripe means no po_ exists — a fresh attempt is safe")
                .isEqualTo(PayoutRunStatus.FAILED);

        fake.failBalanceInsufficient = false;
        service.payOneEvent(e.getId());

        assertThat(fake.lastIdempotencyKey.get()).isEqualTo("evt:" + e.getId() + ":attempt:2");
    }

    // ── stripe-4 — a SUBMITTED run must not freeze the org forever ─────────────────
    @Test
    void stale_submitted_run_is_reconciled_from_stripe_and_unblocks_the_org() {
        Event e1 = newEndedEvent(org);
        Event e2 = newEndedEvent(org);   // SAME org — blocked by e1's in-flight run
        order(e1, 5_000, 500);           // net 4_500
        order(e2, 7_000, 700);           // net 6_300
        fake.availableMinor.set(100_000L);

        service.payOneEvent(e1.getId());
        PayoutRun submitted = payoutRuns.findByEventId(e1.getId()).get(0);
        assertThat(submitted.getStatus()).isEqualTo(PayoutRunStatus.SUBMITTED);

        // No payout.* webhook ever arrives (STRIPE_WEBHOOK_SECRET_CONNECT blank, or Stripe
        // gave up retrying). Every event for the org is frozen behind this one row.
        service.payOneEvent(e2.getId());
        assertThat(payoutRuns.findByEventId(e2.getId()))
                .as("the org-level in-flight guard blocks the sibling event")
                .isEmpty();

        // The reconciliation poll reads the payout Stripe actually holds and closes the run.
        fake.retrievedStatus = "paid";
        service.reconcileSubmittedRun(submitted.getId());

        PayoutRun reconciled = payoutRuns.findById(submitted.getId()).orElseThrow();
        assertThat(reconciled.getStatus()).isEqualTo(PayoutRunStatus.PAID);
        assertThat(reconciled.getPaidAt()).isNotNull();

        // …and the org is free again, so the sibling event is paid on the next tick.
        service.payOneEvent(e2.getId());
        assertThat(fake.lastPayoutAmount.get()).isEqualTo(6_300L);
        assertThat(payoutRuns.findByEventId(e2.getId())).hasSize(1);
    }

    @Test
    void reconcile_marks_a_failed_payout_failed_so_the_event_can_retry() {
        Event e = newEndedEvent(org);
        order(e, 5_000, 500);   // net 4_500
        fake.availableMinor.set(100_000L);

        service.payOneEvent(e.getId());
        PayoutRun submitted = payoutRuns.findByEventId(e.getId()).get(0);

        fake.retrievedStatus = "failed";
        fake.retrievedFailureCode = "account_closed";
        service.reconcileSubmittedRun(submitted.getId());

        PayoutRun reconciled = payoutRuns.findById(submitted.getId()).orElseThrow();
        assertThat(reconciled.getStatus()).isEqualTo(PayoutRunStatus.FAILED);
        assertThat(reconciled.getFailureReason()).isEqualTo("account_closed");
    }

    @Test
    void reconcile_leaves_a_still_in_transit_payout_submitted() {
        Event e = newEndedEvent(org);
        order(e, 5_000, 500);
        fake.availableMinor.set(100_000L);

        service.payOneEvent(e.getId());
        PayoutRun submitted = payoutRuns.findByEventId(e.getId()).get(0);

        fake.retrievedStatus = "in_transit";
        service.reconcileSubmittedRun(submitted.getId());

        assertThat(payoutRuns.findById(submitted.getId()).orElseThrow().getStatus())
                .as("a payout genuinely still in flight stays SUBMITTED — never guess it settled")
                .isEqualTo(PayoutRunStatus.SUBMITTED);
    }

    @Test
    void inert_when_flag_off() {
        props.setPayoutScheduleManual(false);
        Event e = newEndedEvent(org);
        order(e, 5_000, 500);
        fake.availableMinor.set(50_000L);

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isZero();
        assertThat(payoutRuns.findByEventId(e.getId())).isEmpty();
    }

    // ── Phase C — refunds vs. the payout net ───────────────────────────────────────

    /**
     * The explicit ApplicationFee.Refund call is gone, but application_fee_refund_minor is
     * still persisted — it is what makes netAppFee = max(0, appFee − appFeeRefunded) collapse
     * to zero on a fully refunded event. Without that bookkeeping the event would still owe
     * the organizer −appFee and the clamp would hide it.
     */
    @Test
    void fully_refunded_event_pays_out_zero() {
        Event e = newEndedEvent(org);
        Order o = order(e, 10_000, 1_500);
        succeededRefund(o, 10_000, 1_500, false);
        fake.availableMinor.set(50_000L);

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get())
                .as("gross − refunds − (appFee − appFeeRefunded) = 0, so nothing is payable")
                .isZero();
        assertThat(payoutRuns.findByEventId(e.getId())).isEmpty();
    }

    /**
     * Withholding the payout left the fronted money in the connected balance and still called
     * the debt settled — imin was permanently short. Recovery is a real transfer reversal, and
     * only of the ORGANIZER's share: the fee share was the platform's money already.
     */
    @Test
    void platform_funded_refund_is_pulled_back_with_a_transfer_reversal() {
        Event refundedEvent = newEndedEvent(org);
        Order refundedOrder = order(refundedEvent, 4_000, 400);
        Refund fronted = succeededRefund(refundedOrder, 2_000, 200, true);

        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);          // net 9_000
        fake.availableMinor.set(50_000L);

        service.payOneEvent(e.getId());

        assertThat(fake.reversalCount.get()).isEqualTo(1);
        assertThat(fake.lastReversalAmount.get())
                .as("refund 2_000 minus the 200 fee share that was never the organizer's")
                .isEqualTo(1_800L);
        assertThat(fake.lastReversedTransfer.get()).isEqualTo("tr_test_1");
        assertThat(fake.lastReversalKey.get()).isEqualTo("refund:" + fronted.getId() + ":reversal");

        Refund reloaded = refunds.findById(fronted.getId()).orElseThrow();
        assertThat(reloaded.getRecoveredAt()).isNotNull();
        assertThat(reloaded.getRecoveryReversalId()).startsWith("trr_test_");
        assertThat(fake.lastPayoutAmount.get())
                .as("the money came back for real, so the payout is no longer withheld")
                .isEqualTo(9_000L);
    }

    @Test
    void a_reversal_the_connected_balance_cannot_cover_leaves_the_debt_open_and_still_pays_out() {
        Event refundedEvent = newEndedEvent(org);
        Order refundedOrder = order(refundedEvent, 4_000, 400);
        Refund fronted = succeededRefund(refundedOrder, 2_000, 200, true);
        fake.failReversalBalanceInsufficient = true;

        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);          // net 9_000
        fake.availableMinor.set(50_000L);

        service.payOneEvent(e.getId());

        assertThat(refunds.findById(fronted.getId()).orElseThrow().getRecoveredAt())
                .as("an unrecovered debt must stay open for the next tick")
                .isNull();
        assertThat(fake.lastPayoutAmount.get())
                .as("one unrecoverable debt must not suppress the whole payout")
                .isEqualTo(9_000L);
    }

    @Test
    void a_recovered_refund_is_never_reversed_twice() {
        Event refundedEvent = newEndedEvent(org);
        Order refundedOrder = order(refundedEvent, 4_000, 400);
        succeededRefund(refundedOrder, 2_000, 200, true);

        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);
        fake.availableMinor.set(50_000L);

        service.payOneEvent(e.getId());
        service.payOneEvent(e.getId());

        assertThat(fake.reversalCount.get())
                .as("recovered_at is the claim; a second tick must find nothing owed")
                .isEqualTo(1);
    }

    /**
     * The reversal already moved money, so its marker must not be able to roll back with the
     * payout transaction it runs inside — that rollback is an EXPECTED outcome there (the
     * payout_runs UNIQUE violation). Without an independent commit the next nightly tick would
     * reverse the same debt a second time.
     */
    @Test
    void the_recovery_marker_survives_a_rollback_of_the_payout_transaction() {
        Event refundedEvent = newEndedEvent(org);
        Order refundedOrder = order(refundedEvent, 4_000, 400);
        Refund fronted = succeededRefund(refundedOrder, 2_000, 200, true);

        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);
        fake.availableMinor.set(50_000L);
        fake.crashAfterRecovery = true;   // blows up AFTER step 0b, inside the payout tx

        assertThatThrownBy(() -> service.payOneEvent(e.getId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(fake.reversalCount.get()).isEqualTo(1);
        assertThat(payoutRuns.findByEventId(e.getId()))
                .as("the payout transaction really did roll back")
                .isEmpty();
        Refund reloaded = refunds.findById(fronted.getId()).orElseThrow();
        assertThat(reloaded.getRecoveredAt())
                .as("the marker is committed in its own transaction the moment the reversal returns")
                .isNotNull();
        assertThat(reloaded.getRecoveryReversalId()).startsWith("trr_test_");
    }

    /** A debt is still a debt for an org with nothing left to pay out. */
    @Test
    void recover_for_org_reverses_the_debt_with_no_event_being_paid() {
        Event refundedEvent = newEndedEvent(org);
        Order refundedOrder = order(refundedEvent, 4_000, 400);
        Refund fronted = succeededRefund(refundedOrder, 2_000, 200, true);

        service.recoverForOrg(org.getId());

        assertThat(fake.reversalCount.get()).isEqualTo(1);
        assertThat(fake.lastReversalAmount.get()).isEqualTo(1_800L);
        assertThat(fake.payoutCount.get())
                .as("recovery is not a payout — nothing is paid out here")
                .isZero();
        assertThat(refunds.findById(fronted.getId()).orElseThrow().getRecoveredAt()).isNotNull();
    }

    // ── the reconcile poll owes the organizer the same notice the webhook gives ────

    /**
     * This poll exists precisely for when STRIPE_WEBHOOK_SECRET_CONNECT is blank, i.e. when no
     * payout.paid webhook will ever arrive — so it, not the webhook, is what tells the organizer.
     */
    @Test
    void reconcile_publishes_payout_arrived_on_the_transition() {
        Event e = newEndedEvent(org);
        order(e, 5_000, 500);   // net 4_500
        fake.availableMinor.set(100_000L);

        service.payOneEvent(e.getId());
        PayoutRun submitted = payoutRuns.findByEventId(e.getId()).get(0);

        fake.retrievedStatus = "paid";
        service.reconcileSubmittedRun(submitted.getId());

        assertThat(published.stream(PayoutArrivedEvent.class).toList())
                .extracting(PayoutArrivedEvent::runId)
                .containsExactly(submitted.getId());
    }

    @Test
    void reconcile_publishes_nothing_for_an_already_paid_run() {
        Event e = newEndedEvent(org);
        order(e, 5_000, 500);
        fake.availableMinor.set(100_000L);

        service.payOneEvent(e.getId());
        PayoutRun submitted = payoutRuns.findByEventId(e.getId()).get(0);
        submitted.setStatus(PayoutRunStatus.PAID);
        payoutRuns.save(submitted);

        service.reconcileSubmittedRun(submitted.getId());

        assertThat(published.stream(PayoutArrivedEvent.class).count())
                .as("a second poll of a settled run must not email the organizer again")
                .isZero();
    }

    // ── P1-12 — "no bank account" is a fact about the account; a Stripe error is not ────

    /**
     * The organizer never sees a log line. Parking the payout as BLOCKED is what turns a
     * nightly WARN into something they can act on — and it must happen exactly once, however
     * many nights the sweep runs before they attach a bank account.
     */
    @Test
    void no_bank_account_parks_one_blocked_run_and_notifies_once() {
        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);            // net 9_000
        fake.availableMinor.set(50_000L);
        fake.hasBank = false;

        service.payOneEvent(e.getId());
        service.payOneEvent(e.getId());      // the next nightly tick

        assertThat(fake.payoutCount.get())
                .as("a payout with no destination is never attempted")
                .isZero();
        List<PayoutRun> runs = payoutRuns.findByEventId(e.getId());
        assertThat(runs).hasSize(1);
        PayoutRun parked = runs.get(0);
        assertThat(parked.getStatus()).isEqualTo(PayoutRunStatus.BLOCKED);
        assertThat(parked.getFailureReason()).isEqualTo(PayoutBlockReason.NO_BANK_ACCOUNT);
        assertThat(parked.getAmountMinor())
                .as("the net we could not send — no money moved")
                .isEqualTo(9_000L);
        assertThat(parked.getCurrency()).isEqualTo("eur");
        assertThat(parked.getAttempt())
                .as("attempt 0 = no Stripe attempt was ever made, so the cap is not spent on this")
                .isZero();
        assertThat(parked.getStripePayoutId()).isNull();

        assertThat(published.stream(PayoutBlockedEvent.class).toList())
                .as("one park, one organizer alert — a nightly re-notify is noise, not information")
                .extracting(PayoutBlockedEvent::runId)
                .containsExactly(parked.getId());
    }

    /**
     * A BLOCKED run only needs a human when imin gave up. "No bank account" is the one block
     * the organizer clears themselves, so it must not park the event permanently.
     */
    @Test
    void attaching_a_bank_account_unblocks_the_event_on_the_next_tick() {
        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);            // net 9_000
        fake.availableMinor.set(50_000L);
        fake.hasBank = false;

        service.payOneEvent(e.getId());
        assertThat(payoutRuns.findByEventId(e.getId())).hasSize(1);

        fake.hasBank = true;                 // the organizer added a payout bank account
        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isEqualTo(1);
        assertThat(fake.lastPayoutAmount.get()).isEqualTo(9_000L);
        PayoutRun paid = payoutRuns.findByEventId(e.getId()).stream()
                .filter(r -> r.getStatus() == PayoutRunStatus.SUBMITTED)
                .findFirst().orElseThrow();
        assertThat(paid.getAttempt())
                .as("the park took attempt 0, so the first real attempt is still 1")
                .isEqualTo(1);
    }

    @Test
    void stripe_error_on_the_bank_check_skips_the_tick_without_writing_a_row() {
        Event e = newEndedEvent(org);
        order(e, 10_000, 1_000);
        fake.availableMinor.set(50_000L);
        fake.failAccountRetrieve = true;     // we never learn whether a bank is attached

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isZero();
        assertThat(payoutRuns.findByEventId(e.getId()))
                .as("an unanswered check is not a fact about the account — never park on a guess")
                .isEmpty();
        assertThat(published.stream(PayoutBlockedEvent.class).toList())
                .as("and the organizer is not told to fix something that may not be wrong")
                .isEmpty();
    }

    // ── P1-13 — the attempt cap ───────────────────────────────────────────────────

    /**
     * Before the cap, a failing payout retried every night forever and told nobody. Three
     * attempts is the budget; the fourth night parks the run and emails the organizer instead.
     */
    @Test
    void attempt_cap_parks_the_run_blocked_and_it_never_recandidates() {
        Event e = newEndedEvent(org);
        order(e, 6_000, 600);               // net 5_400
        fake.availableMinor.set(50_000L);
        fake.failBalanceInsufficient = true;

        for (int tick = 0; tick < props.getPayoutMaxAttempts(); tick++) {
            service.payOneEvent(e.getId());
        }
        assertThat(payoutRuns.findByEventId(e.getId()))
                .as("the budget is spent on real attempts, not on the park")
                .hasSize(props.getPayoutMaxAttempts())
                .allMatch(r -> r.getStatus() == PayoutRunStatus.FAILED);

        // The tick after the budget runs out: park, do not mint attempt 4.
        service.payOneEvent(e.getId());

        List<PayoutRun> runs = payoutRuns.findByEventId(e.getId());
        assertThat(runs).hasSize(props.getPayoutMaxAttempts());
        PayoutRun parked = runs.stream()
                .filter(r -> r.getAttempt() == props.getPayoutMaxAttempts())
                .findFirst().orElseThrow();
        assertThat(parked.getStatus()).isEqualTo(PayoutRunStatus.BLOCKED);
        assertThat(parked.getFailureReason())
                .as("the park keeps the last Stripe failure, which is the only clue ops has")
                .isEqualTo("balance_insufficient");
        assertThat(published.stream(PayoutBlockedEvent.class).toList())
                .extracting(PayoutBlockedEvent::runId)
                .containsExactly(parked.getId());

        // Even with the original failure gone, a capped run needs a human — never a 4th try.
        fake.failBalanceInsufficient = false;
        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get()).isZero();
        assertThat(payoutRuns.findByEventId(e.getId())).hasSize(props.getPayoutMaxAttempts());
        assertThat(published.stream(PayoutBlockedEvent.class).count())
                .as("a parked run is announced once, not once per night")
                .isEqualTo(1);
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────

    private Organization newEligibleOrg() {
        Organization o = new Organization();
        o.setName("Payout Org");
        o.setSlug("payout-org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("payouts@test.example");
        o.setCountry("DE");
        o.setStripeAccountId("acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        o.setStripeConnectState(StripeConnectState.ACTIVE);
        o.setStripePayoutsEnabled(true);
        o.setStripePayoutScheduleManual(true);
        return orgs.save(o);
    }

    private Event newEndedEvent(Organization owner) {
        User u = new User();
        u.setOrgId(owner.getId());
        u.setEmail("creator-" + UUID.randomUUID() + "@test.example");
        u.setRole(UserRole.OWNER);
        UUID userId = users.save(u).getId();

        Event e = new Event();
        e.setOrgId(owner.getId());
        e.setName("Ended Event");
        e.setSlug("ended-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStatus(EventStatus.PAST);
        e.setCurrency("EUR");
        e.setEndsAt(Instant.now().minus(10, ChronoUnit.DAYS));
        e.setCreatedBy(userId);
        return events.save(e);
    }

    private Order order(Event e, long totalMinor, long appFeeMinor) {
        Order o = new Order();
        o.setToken("tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setEventId(e.getId());
        o.setOrgId(e.getOrgId());
        o.setEmail("buyer@test.example");
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setApplicationFeeMinor(appFeeMinor);
        o.setPaymentMethod("card");
        return orders.save(o);
    }

    private Dispute dispute(Order o, Event e, long amountMinor, DisputeStatus status) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        d.setOrgId(e.getOrgId());
        d.setEventId(e.getId());
        d.setOrderId(o.getId());
        d.setAmountMinor(amountMinor);
        d.setCurrency("eur");
        d.setStatus(status);
        return disputes.save(d);
    }

    private Refund succeededRefund(Order o, long amountMinor, long appFeeRefundMinor, boolean platformFunded) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        r.setStripeChargeId("ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        r.setAmountMinor(amountMinor);
        r.setCurrency("eur");
        r.setApplicationFeeRefundMinor(appFeeRefundMinor);
        r.setReason(RefundReason.OTHER);
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setPlatformFunded(platformFunded);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        return refunds.save(r);
    }
}
