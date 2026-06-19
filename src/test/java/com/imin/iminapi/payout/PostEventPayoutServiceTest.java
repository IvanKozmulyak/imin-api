package com.imin.iminapi.payout;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.refund.RefundRepository;
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

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
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
 *   <li>(d) dispute guard — a FAILED transfer row skips the event;</li>
 *   <li>(e) FEE EXCLUDED — payout amount equals net, not gross.</li>
 * </ul>
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class PostEventPayoutServiceTest {

    /** Test-controllable Stripe backend: balance to report + payouts captured. */
    static class FakeStripe {
        final AtomicReference<Long> availableMinor = new AtomicReference<>(0L);
        final AtomicInteger payoutCount = new AtomicInteger(0);
        final AtomicReference<Long> lastPayoutAmount = new AtomicReference<>(null);
        final AtomicReference<String> lastPayoutId = new AtomicReference<>(null);
        /** When set, payouts().create throws balance_insufficient (race simulation). */
        volatile boolean failBalanceInsufficient = false;
        /** When false, accounts().retrieve reports NO external bank account. */
        volatile boolean hasBank = true;

        void reset() {
            availableMinor.set(0L);
            payoutCount.set(0);
            lastPayoutAmount.set(null);
            lastPayoutId.set(null);
            failBalanceInsufficient = false;
            hasBank = true;
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
            if (path != null && path.startsWith("/v1/payouts")) {
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
            if (path != null && path.startsWith("/v1/accounts")) {
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
    @Autowired PayoutRunRepository payoutRuns;
    @Autowired UserRepository users;

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

        org = newEligibleOrg();
    }

    @AfterEach
    void tearDown() {
        props.setPayoutScheduleManual(false);
        wipe();
    }

    private void wipe() {
        payoutRuns.deleteAll();
        settlements.deleteAll();
        refunds.deleteAll();
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
        assertThat(payoutRuns.findByEventId(e.getId()).get(0).getAmountMinor()).isEqualTo(4_000L);
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
    void dispute_guard_skips_on_failed_transfer_row() {
        Event e = newEndedEvent(org);
        order(e, 8_000, 800);
        fake.availableMinor.set(50_000L);

        // An open dispute: a TRANSFER settlement row at status=failed for this org.
        Settlement transfer = new Settlement();
        transfer.setOrgId(org.getId());
        transfer.setStripeObjectId("tr_disputed_1");
        transfer.setObjectType(SettlementObjectType.TRANSFER);
        transfer.setAmountMinor(8_000);
        transfer.setCurrency("eur");
        transfer.setStatus(SettlementStatus.FAILED);
        settlements.save(transfer);

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get())
                .as("open dispute on a transfer row blocks the payout")
                .isZero();
        assertThat(payoutRuns.findByEventId(e.getId())).isEmpty();
    }

    @Test
    void failed_payout_settlement_row_is_not_a_dispute() {
        Event e = newEndedEvent(org);
        order(e, 8_000, 800);   // net 7_200
        fake.availableMinor.set(50_000L);

        // A FAILED *payout* row (bank-routing failure) must NOT block future payouts.
        Settlement payoutRow = new Settlement();
        payoutRow.setOrgId(org.getId());
        payoutRow.setStripeObjectId("po_failed_old");
        payoutRow.setObjectType(SettlementObjectType.PAYOUT);
        payoutRow.setAmountMinor(1_000);
        payoutRow.setCurrency("eur");
        payoutRow.setStatus(SettlementStatus.FAILED);
        settlements.save(payoutRow);

        service.payOneEvent(e.getId());

        assertThat(fake.payoutCount.get())
                .as("a FAILED payout row is bank-routing, not a dispute — does not block")
                .isEqualTo(1);
        assertThat(fake.lastPayoutAmount.get()).isEqualTo(7_200L);
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
        assertThat(payoutRuns.existsByEventIdAndStatusIn(e.getId(),
                List.of(PayoutRunStatus.PLANNED, PayoutRunStatus.SUBMITTED, PayoutRunStatus.PAID)))
                .as("FAILED run does not block the per-event candidate query")
                .isFalse();
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

    private void order(Event e, long totalMinor, long appFeeMinor) {
        Order o = new Order();
        o.setToken("tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setEventId(e.getId());
        o.setOrgId(e.getOrgId());
        o.setEmail("buyer@test.example");
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setApplicationFeeMinor(appFeeMinor);
        o.setPaymentMethod("card");
        orders.save(o);
    }
}
