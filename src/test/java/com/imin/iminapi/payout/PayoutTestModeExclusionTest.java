package com.imin.iminapi.payout;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.dispute.Dispute;
import com.imin.iminapi.dispute.DisputeRepository;
import com.imin.iminapi.dispute.DisputeStatus;
import com.imin.iminapi.model.CheckoutAttribution;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Order;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.refund.Refund;
import com.imin.iminapi.refund.RefundReason;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.refund.RefundStatus;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.service.event.FreeCheckoutService;
import com.imin.iminapi.stripe.StripeConnectState;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.StripeClient;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.reflect.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test-era money must never leave a live balance. Production ran on {@code sk_test_} from
 * day one and those orders are KEPT at cutover (they hold the only record of who owns a
 * ticket), so without {@code orders.test_mode} a test-era event would re-enter the payout
 * sweep under a live key and its fake gross would be disbursed from the organizer's real
 * connected balance.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class PayoutTestModeExclusionTest {

    @Autowired PostEventPayoutService service;
    @Autowired FreeCheckoutService freeCheckout;
    @Autowired StripeProperties props;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired OrderRepository orders;
    @Autowired TicketTierRepository tiers;
    @Autowired TicketRepository tickets;
    @Autowired RefundRepository refunds;
    @Autowired PayoutRunRepository payoutRuns;
    @Autowired DisputeRepository disputes;
    @Autowired UserRepository users;

    @MockitoBean StripeClient stripeClient;

    private final AtomicReference<Long> availableMinor = new AtomicReference<>(500_000L);
    private final java.util.concurrent.atomic.AtomicBoolean bankAttached =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    private final AtomicInteger payoutCount = new AtomicInteger(0);
    private final AtomicReference<Long> lastPayoutAmount = new AtomicReference<>(null);

    private String originalSecretKey;
    private Organization org;

    @BeforeEach
    void setUp() {
        wipe();
        // StripeProperties is a shared singleton: these swaps are safe only while the suite runs
        // test classes sequentially (Surefire's default), and tearDown restores the original.
        originalSecretKey = props.getSecretKey();
        // The payout path only ever runs under a live key in production; pin that here so a
        // run this test creates is stamped live like its hand-built orders.
        props.setSecretKey("sk_live_dummy");
        props.setPayoutScheduleManual(true);
        payoutCount.set(0);
        lastPayoutAmount.set(null);
        bankAttached.set(true);
        wireStripe();
        org = eligibleOrg();
    }

    @AfterEach
    void tearDown() {
        props.setPayoutScheduleManual(false);
        props.setSecretKey(originalSecretKey);
        wipe();
    }

    @Test
    void netSumsExcludeTestModeOrders() {
        Event e = endedEvent();
        order(e, 10_000, 1_000, false);
        order(e, 90_000, 9_000, true);
        refund(order(e, 4_000, 400, true), 4_000, 400);

        assertThat(orders.sumLiveTotalMinorByEventId(e.getId())).isEqualTo(10_000L);
        assertThat(orders.sumLiveApplicationFeeMinorByEventId(e.getId())).isEqualTo(1_000L);
        assertThat(refunds.sumSucceededLiveRefundMinorByEventId(e.getId()))
                .as("a refund of a test-mode order is netted off nothing")
                .isZero();
        assertThat(refunds.sumSucceededLiveRefundApplicationFeeMinorByEventId(e.getId())).isZero();
        // The unfiltered sums are untouched — the organizer's own revenue readouts still
        // show the full history.
        assertThat(orders.sumTotalMinorByEventId(e.getId())).isEqualTo(104_000L);
    }

    @Test
    void eventWithOnlyTestModeOrdersIsNotAPayoutCandidate() {
        Event testEra = endedEvent();
        order(testEra, 50_000, 5_000, true);
        Event mixed = endedEvent();
        order(mixed, 10_000, 1_000, true);
        order(mixed, 20_000, 2_000, false);

        assertThat(events.findPayoutCandidates(Instant.now().minus(3, ChronoUnit.DAYS),
                PageRequest.of(0, 50)))
                .extracting(Event::getId)
                .containsExactly(mixed.getId());
    }

    @Test
    void eventWithNoOrdersAtAllIsStillAPayoutCandidate() {
        Event e = endedEvent();

        assertThat(events.findPayoutCandidates(Instant.now().minus(3, ChronoUnit.DAYS),
                PageRequest.of(0, 50)))
                .as("the exclusion is about test money, not about being unsold")
                .extracting(Event::getId)
                .containsExactly(e.getId());
    }

    @Test
    void eventWithOnlyTestModeOrdersIsNotARetentionCandidate() {
        Event testEra = endedEvent();
        testEra.setEndsAt(Instant.now().minus(100, ChronoUnit.DAYS));
        testEra.setRevenueMinor(50_000);
        events.save(testEra);
        order(testEra, 50_000, 5_000, true);

        assertThat(events.findRetentionMonitorCandidates(
                Instant.now().minus(75, ChronoUnit.DAYS), PageRequest.of(0, 50)))
                .as("no real funds are stranded, so there is nothing to warn about")
                .isEmpty();
    }

    @Test
    void paysOnlyTheLiveNetWhenAnEventMixesLiveAndTestOrders() {
        Event e = endedEvent();
        order(e, 10_000, 1_500, false);      // live: net 8_500
        order(e, 90_000, 9_000, true);       // test-era: contributes nothing

        service.payOneEvent(e.getId());

        assertThat(payoutCount.get()).isEqualTo(1);
        assertThat(lastPayoutAmount.get())
                .as("only the live order's net leaves the balance")
                .isEqualTo(8_500L);
        assertThat(payoutRuns.findByEventId(e.getId()).get(0).getAmountMinor()).isEqualTo(8_500L);
    }

    @Test
    void eventWithOnlyTestModeOrdersPaysNothing() {
        Event e = endedEvent();
        order(e, 90_000, 9_000, true);

        service.payOneEvent(e.getId());

        assertThat(payoutCount.get()).isZero();
        assertThat(payoutRuns.findByEventId(e.getId())).isEmpty();
    }

    @Test
    void netIgnoresATestEraDispute() {
        Event e = endedEvent();
        order(e, 10_000, 1_500, false);          // live: net 8_500
        dispute(e, 5_000, true);                 // test-era chargeback: clawed back nothing real

        service.payOneEvent(e.getId());

        assertThat(lastPayoutAmount.get())
                .as("a test-era dispute withholds nothing from a live net")
                .isEqualTo(8_500L);
    }

    @Test
    void netStillWithholdsALiveDispute() {
        Event e = endedEvent();
        order(e, 10_000, 1_500, false);          // live: net 8_500
        dispute(e, 5_000, false);

        service.payOneEvent(e.getId());

        assertThat(lastPayoutAmount.get())
                .as("a live lost dispute still comes off the top")
                .isEqualTo(3_500L);
    }

    @Test
    void alreadyTriggeredIgnoresATestEraPayoutRun() {
        Event e = endedEvent();
        order(e, 10_000, 1_500, false);          // live: net 8_500
        paidRun(e, 4_000, true);                 // test-era payout: no live balance ever moved

        service.payOneEvent(e.getId());

        assertThat(lastPayoutAmount.get())
                .as("the organizer is owed the whole live net; a test payout paid them nothing")
                .isEqualTo(8_500L);
    }

    @Test
    void alreadyTriggeredStillSubtractsALivePayoutRun() {
        Event e = endedEvent();
        order(e, 10_000, 1_500, false);          // live: net 8_500
        paidRun(e, 4_000, false);

        service.payOneEvent(e.getId());

        assertThat(lastPayoutAmount.get())
                .as("net 8_500 − already triggered 4_000")
                .isEqualTo(4_500L);
    }

    @Test
    void newPayoutRunsCarryTheRunningKeyMode() {
        Event live = endedEvent();
        order(live, 10_000, 1_500, false);
        service.payOneEvent(live.getId());
        PayoutRun liveRun = payoutRuns.findByEventId(live.getId()).get(0);
        assertThat(liveRun.isTestMode()).as("created under sk_live_dummy").isFalse();
        // Settle it: only one payout per org may be in flight, and the next event shares the org.
        liveRun.setStatus(PayoutRunStatus.PAID);
        payoutRuns.save(liveRun);

        // A run planned under a test key is test-era money even though the event's orders are not.
        props.setSecretKey("sk_test_dummy");
        Event underTestKey = endedEvent();
        order(underTestKey, 10_000, 1_500, false);
        service.payOneEvent(underTestKey.getId());
        assertThat(payoutRuns.findByEventId(underTestKey.getId()).get(0).isTestMode()).isTrue();
    }

    @Test
    void newFreeOrdersCarryTheRunningKeyMode() {
        Event e = endedEvent();
        TicketTier tier = freeTier(e);

        props.setSecretKey("sk_test_dummy");
        Order underTestKey = freeCheckout.issueFreeOrder(
                e, tier, 1, "test-key@test.example", null, false, false,
                CheckoutAttribution.NONE, null);

        props.setSecretKey("sk_live_dummy");
        Order underLiveKey = freeCheckout.issueFreeOrder(
                e, tier, 1, "live-key@test.example", null, false, false,
                CheckoutAttribution.NONE, null);

        assertThat(orders.findById(underTestKey.getId()).orElseThrow().isTestMode()).isTrue();
        assertThat(orders.findById(underLiveKey.getId()).orElseThrow().isTestMode()).isFalse();
    }

    /**
     * The park writes a run like any other, so it carries the same V130 stamp — a test-era
     * park must not be read as a live one by the guard below.
     */
    @Test
    void aParkedNoBankRunCarriesTheRunningKeyMode() {
        bankAttached.set(false);
        Event underLiveKey = endedEvent();
        order(underLiveKey, 10_000, 1_500, false);

        service.payOneEvent(underLiveKey.getId());

        PayoutRun parked = payoutRuns.findByEventId(underLiveKey.getId()).get(0);
        assertThat(parked.getStatus()).isEqualTo(PayoutRunStatus.BLOCKED);
        assertThat(parked.getFailureReason()).isEqualTo(PayoutBlockReason.NO_BANK_ACCOUNT);
        assertThat(parked.isTestMode()).as("parked under sk_live_dummy").isFalse();

        props.setSecretKey("sk_test_dummy");
        Event underTestKey = endedEvent();
        order(underTestKey, 10_000, 1_500, false);

        service.payOneEvent(underTestKey.getId());

        assertThat(payoutRuns.findByEventId(underTestKey.getId()).get(0).isTestMode()).isTrue();
    }

    /**
     * The cutover parks every non-terminal run {@code blocked}, which permanently excludes its
     * event from the sweep. On an event that mixes test-era orders with live sales that would
     * withhold the organizer's real money forever, so the guard reads live runs only.
     */
    @Test
    void aTestEraParkedRunDoesNotBlockTheLivePayout() {
        Event e = endedEvent();
        order(e, 10_000, 1_500, false);          // live: net 8_500
        parkedRun(e, true);                      // parked by the cutover script

        service.payOneEvent(e.getId());

        assertThat(payoutCount.get()).isEqualTo(1);
        assertThat(lastPayoutAmount.get()).isEqualTo(8_500L);
    }

    @Test
    void aLiveParkedRunStillBlocksTheEvent() {
        Event e = endedEvent();
        order(e, 10_000, 1_500, false);
        parkedRun(e, false);

        service.payOneEvent(e.getId());

        assertThat(payoutCount.get())
                .as("a live run parked for a human is never retried automatically")
                .isZero();
        assertThat(payoutRuns.findByEventId(e.getId())).hasSize(1);
    }

    // ── plumbing ───────────────────────────────────────────────────────────────

    /** Balance, external-account check and Payout.create — the three calls payOneEvent makes. */
    private void wireStripe() {
        StripeResponseGetter rg = mock(StripeResponseGetter.class);
        try {
            when(rg.request(any(ApiRequest.class), any(Type.class)))
                    .thenAnswer((InvocationOnMock inv) -> handle(inv.getArgument(0)));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        when(stripeClient.balance()).thenReturn(new BalanceService(rg));
        when(stripeClient.payouts()).thenReturn(new PayoutService(rg));
        when(stripeClient.accounts()).thenReturn(new com.stripe.service.AccountService(rg));
    }

    @SuppressWarnings("unchecked")
    private <T extends StripeObject> T handle(ApiRequest req) {
        String path = req.getPath();
        if (path != null && path.startsWith("/v1/balance")) {
            return (T) ApiResource.GSON.fromJson(
                    "{ \"object\": \"balance\", \"available\": [ { \"amount\": "
                            + availableMinor.get() + ", \"currency\": \"eur\" } ], \"pending\": [] }",
                    Balance.class);
        }
        if (path != null && path.startsWith("/v1/payouts")) {
            Object amt = req.getParams() == null ? null : req.getParams().get("amount");
            long amount = amt == null ? 0L : ((Number) amt).longValue();
            lastPayoutAmount.set(amount);
            String poId = "po_test_" + payoutCount.incrementAndGet();
            return (T) ApiResource.GSON.fromJson(
                    "{ \"object\": \"payout\", \"id\": \"" + poId + "\", \"amount\": " + amount
                            + ", \"currency\": \"eur\", \"status\": \"pending\" }",
                    Payout.class);
        }
        if (path != null && path.startsWith("/v1/accounts")) {
            String externals = bankAttached.get()
                    ? "[ { \"object\": \"bank_account\", \"id\": \"ba_test\", "
                            + "\"last4\": \"4242\", \"currency\": \"eur\" } ]"
                    : "[]";
            return (T) ApiResource.GSON.fromJson(
                    "{ \"object\": \"account\", \"id\": \"acct_test\", \"external_accounts\": "
                            + "{ \"object\": \"list\", \"data\": " + externals + " } }",
                    com.stripe.model.Account.class);
        }
        throw new IllegalStateException("unexpected Stripe path in test: " + path);
    }

    private void wipe() {
        disputes.deleteAll();
        refunds.deleteAll();
        payoutRuns.deleteAll();
        tickets.deleteAll();
        orders.deleteAll();
        tiers.deleteAll();
        events.deleteAll();
        users.deleteAll();
        orgs.deleteAll();
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private Organization eligibleOrg() {
        Organization o = new Organization();
        o.setName("Org");
        o.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("o@test.example");
        o.setCountry("DE");
        o.setStripeAccountId("acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        o.setStripeConnectState(StripeConnectState.ACTIVE);
        o.setStripePayoutsEnabled(true);
        o.setStripePayoutScheduleManual(true);
        return orgs.save(o);
    }

    private Event endedEvent() {
        User u = new User();
        u.setOrgId(org.getId());
        u.setEmail("u-" + UUID.randomUUID() + "@test.example");
        u.setRole(UserRole.OWNER);
        UUID userId = users.save(u).getId();

        Event e = new Event();
        e.setOrgId(org.getId());
        e.setName("E");
        e.setSlug("e-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStatus(EventStatus.PAST);
        e.setCurrency("EUR");
        e.setEndsAt(Instant.now().minus(10, ChronoUnit.DAYS));
        e.setCreatedBy(userId);
        return events.save(e);
    }

    private TicketTier freeTier(Event e) {
        TicketTier t = new TicketTier();
        t.setEventId(e.getId());
        t.setName("Free");
        t.setPriceMinor(0);
        t.setQuantity(100);
        return tiers.save(t);
    }

    private Order order(Event e, long totalMinor, long appFeeMinor, boolean testMode) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(e.getId());
        o.setOrgId(e.getOrgId());
        o.setEmail("buyer@test.example");
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setApplicationFeeMinor(appFeeMinor);
        o.setPaymentMethod("card");
        o.setTestMode(testMode);
        return orders.save(o);
    }

    /** A LOST dispute on the event — the status the net withholds. */
    private Dispute dispute(Event e, long amountMinor, boolean testMode) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        d.setOrgId(e.getOrgId());
        d.setEventId(e.getId());
        d.setAmountMinor(amountMinor);
        d.setCurrency("eur");
        d.setStatus(DisputeStatus.LOST);
        d.setTestMode(testMode);
        return disputes.save(d);
    }

    /** A settled run, i.e. an amount that counts as already triggered for the event. */
    private PayoutRun paidRun(Event e, long amountMinor, boolean testMode) {
        PayoutRun r = new PayoutRun();
        r.setOrgId(e.getOrgId());
        r.setEventId(e.getId());
        r.setStripeAccountId(org.getStripeAccountId());
        r.setAmountMinor(amountMinor);
        r.setCurrency("eur");
        r.setStatus(PayoutRunStatus.PAID);
        r.setAttempt(1);
        r.setIdempotencyKey("evt:" + e.getId() + ":attempt:1");
        r.setTestMode(testMode);
        return payoutRuns.save(r);
    }

    /** A run parked BLOCKED for a human — what the cutover script leaves behind. */
    private PayoutRun parkedRun(Event e, boolean testMode) {
        PayoutRun r = new PayoutRun();
        r.setOrgId(e.getOrgId());
        r.setEventId(e.getId());
        r.setStripeAccountId(org.getStripeAccountId());
        r.setAmountMinor(4_000);
        r.setCurrency("eur");
        r.setStatus(PayoutRunStatus.BLOCKED);
        r.setFailureReason("TEST_MODE_CUTOVER");
        r.setAttempt(1);
        r.setIdempotencyKey("evt:" + e.getId() + ":attempt:1");
        r.setTestMode(testMode);
        return payoutRuns.save(r);
    }

    private Refund refund(Order o, long amountMinor, long feeRefundMinor) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setAmountMinor(amountMinor);
        r.setCurrency("eur");
        r.setApplicationFeeRefundMinor(feeRefundMinor);
        r.setReason(RefundReason.OTHER);
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        return refunds.save(r);
    }
}
