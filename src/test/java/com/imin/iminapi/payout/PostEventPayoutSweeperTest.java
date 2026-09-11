package com.imin.iminapi.payout;

import com.imin.iminapi.config.TestRateLimitConfig;
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
import com.imin.iminapi.settlement.SettlementRepository;
import com.imin.iminapi.stripe.StripeConnectState;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.StripeClient;
import com.stripe.model.Charge;
import com.stripe.model.TransferReversal;
import com.stripe.net.ApiRequest;
import com.stripe.net.ApiResource;
import com.stripe.net.StripeResponseGetter;
import com.stripe.service.ChargeService;
import com.stripe.service.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.reflect.Type;
import java.sql.Timestamp;
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
 * Candidate-query coverage for the Track B Phase 2 sweeper: that
 * {@link EventRepository#findPayoutCandidates} applies the buffer cutoff, the
 * org-eligibility join, the per-event existence guard, and the §4.0 org-level
 * double-pay guard; and that {@link PostEventPayoutSweeper} is inert when the flag
 * is off. The per-event money move is covered separately in
 * {@link PostEventPayoutServiceTest}.
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
class PostEventPayoutSweeperTest {

    @Autowired PostEventPayoutSweeper sweeper;
    @Autowired StripeProperties props;
    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired SettlementRepository settlements;
    @Autowired PayoutRunRepository payoutRuns;
    @Autowired UserRepository users;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockitoBean StripeClient stripeClient;

    private static final String SWEEP_LOCK = "PostEventPayoutSweeper.sweep";

    /** The sweep lock is rewound here before each tick; a real acquisition must move it past. */
    private static final Instant LOCK_REWOUND_TO = Instant.parse("2020-01-01T00:00:00Z");

    private final Instant cutoff = Instant.now().minus(3, ChronoUnit.DAYS);

    /** Reversals the sweep asked Stripe for; the payout paths are not wired here on purpose. */
    private final AtomicInteger reversalCount = new AtomicInteger(0);
    private final AtomicReference<Long> lastReversalAmount = new AtomicReference<>(null);

    @BeforeEach
    void setUp() {
        wipe();
        releaseSweepLock();
        props.setPayoutScheduleManual(true);
        reversalCount.set(0);
        lastReversalAmount.set(null);
        wireRecoveryStripeCalls();
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

    @Test
    void candidate_when_ended_past_buffer_and_org_eligible() {
        Organization org = eligibleOrg();
        Event e = endedEvent(org, Instant.now().minus(10, ChronoUnit.DAYS));

        List<Event> due = events.findPayoutCandidates(cutoff, PageRequest.of(0, 50));

        assertThat(due).extracting(Event::getId).containsExactly(e.getId());
    }

    @Test
    void not_a_candidate_when_within_buffer() {
        Organization org = eligibleOrg();
        endedEvent(org, Instant.now().minus(1, ChronoUnit.DAYS));   // ended yesterday, < 3d buffer

        assertThat(events.findPayoutCandidates(cutoff, PageRequest.of(0, 50))).isEmpty();
    }

    @Test
    void not_a_candidate_when_no_endsAt() {
        Organization org = eligibleOrg();
        Event e = endedEvent(org, null);   // open-ended

        assertThat(events.findPayoutCandidates(cutoff, PageRequest.of(0, 50)))
                .extracting(Event::getId).doesNotContain(e.getId());
    }

    @Test
    void not_a_candidate_when_schedule_not_manual() {
        Organization org = eligibleOrg();
        org.setStripePayoutScheduleManual(false);   // not flipped to manual
        orgs.save(org);
        endedEvent(org, Instant.now().minus(10, ChronoUnit.DAYS));

        assertThat(events.findPayoutCandidates(cutoff, PageRequest.of(0, 50))).isEmpty();
    }

    /**
     * "Sell ⇒ payable": the transfers capability is what checkout gates on, so a
     * RESTRICTED org with outstanding requirements that can still take money is a
     * payout candidate too.
     */
    @Test
    void candidate_when_restricted_but_transfers_still_enabled() {
        Organization org = eligibleOrg();
        org.setStripeConnectState(StripeConnectState.RESTRICTED);
        orgs.save(org);
        Event e = endedEvent(org, Instant.now().minus(10, ChronoUnit.DAYS));

        assertThat(events.findPayoutCandidates(cutoff, PageRequest.of(0, 50)))
                .extracting(Event::getId).containsExactly(e.getId());
    }

    @Test
    void not_a_candidate_when_connect_state_disabled() {
        Organization org = eligibleOrg();
        org.setStripeConnectState(StripeConnectState.DISABLED);
        orgs.save(org);
        endedEvent(org, Instant.now().minus(10, ChronoUnit.DAYS));

        assertThat(events.findPayoutCandidates(cutoff, PageRequest.of(0, 50))).isEmpty();
    }

    @Test
    void not_a_candidate_when_transfers_capability_off() {
        Organization org = eligibleOrg();
        org.setStripePayoutsEnabled(false);
        orgs.save(org);
        endedEvent(org, Instant.now().minus(10, ChronoUnit.DAYS));

        assertThat(events.findPayoutCandidates(cutoff, PageRequest.of(0, 50))).isEmpty();
    }

    @Test
    void per_event_guard_excludes_event_with_inflight_run() {
        Organization org = eligibleOrg();
        Event e = endedEvent(org, Instant.now().minus(10, ChronoUnit.DAYS));
        seedRun(org, e, PayoutRunStatus.SUBMITTED);

        assertThat(events.findPayoutCandidates(cutoff, PageRequest.of(0, 50))).isEmpty();
    }

    @Test
    void org_level_double_pay_guard_excludes_sibling_event_when_acct_inflight() {
        Organization org = eligibleOrg();
        Event paid = endedEvent(org, Instant.now().minus(11, ChronoUnit.DAYS));
        Event sibling = endedEvent(org, Instant.now().minus(10, ChronoUnit.DAYS));
        // An in-flight run for `paid` claims the org's single in-flight slot.
        seedRun(org, paid, PayoutRunStatus.PLANNED);

        // Neither the already-claimed event nor its sibling are candidates this tick.
        List<Event> due = events.findPayoutCandidates(cutoff, PageRequest.of(0, 50));
        assertThat(due.stream().map(Event::getId).toList())
                .as("§4.0: at most one in-flight payout per org per tick")
                .doesNotContain(paid.getId(), sibling.getId());
    }

    @Test
    void failed_run_does_not_block_re_candidacy() {
        Organization org = eligibleOrg();
        Event e = endedEvent(org, Instant.now().minus(10, ChronoUnit.DAYS));
        seedRun(org, e, PayoutRunStatus.FAILED);   // FAILED is not PLANNED/SUBMITTED/PAID

        assertThat(events.findPayoutCandidates(cutoff, PageRequest.of(0, 50)))
                .extracting(Event::getId).containsExactly(e.getId());
    }

    /**
     * The retention monitor is the only warning that an org is sitting on funds Stripe will
     * take back. Its org join has to match {@code findPayoutCandidates}: a RESTRICTED org is
     * payable, so an unpaid RESTRICTED org past the bound is exactly what must be alerted on —
     * it used to be filtered out by an {@code = ACTIVE} predicate and warned about nobody.
     */
    @Test
    void retention_monitor_includes_a_restricted_org_like_the_payout_candidates() {
        Organization org = eligibleOrg();
        org.setStripeConnectState(StripeConnectState.RESTRICTED);
        orgs.save(org);
        Event e = endedEvent(org, Instant.now().minus(100, ChronoUnit.DAYS));
        e.setRevenueMinor(10_000);
        events.save(e);

        Instant retentionCutoff = Instant.now().minus(75, ChronoUnit.DAYS);

        assertThat(events.findRetentionMonitorCandidates(retentionCutoff, PageRequest.of(0, 50)))
                .extracting(Event::getId).containsExactly(e.getId());
    }

    @Test
    void retention_monitor_excludes_a_disabled_org() {
        Organization org = eligibleOrg();
        org.setStripeConnectState(StripeConnectState.DISABLED);
        orgs.save(org);
        Event e = endedEvent(org, Instant.now().minus(100, ChronoUnit.DAYS));
        e.setRevenueMinor(10_000);
        events.save(e);

        assertThat(events.findRetentionMonitorCandidates(
                Instant.now().minus(75, ChronoUnit.DAYS), PageRequest.of(0, 50)))
                .as("DISABLED is terminal — nothing will ever be paid out, and the payout "
                        + "candidate query excludes it too")
                .isEmpty();
    }

    @Test
    void sweeper_inert_when_flag_off() {
        props.setPayoutScheduleManual(false);
        Organization org = eligibleOrg();
        endedEvent(org, Instant.now().minus(10, ChronoUnit.DAYS));

        sweeper.sweep();   // must not touch Stripe at all

        // The tick really ran: ShedLock granted and stamped the lock. Without this, a tick
        // skipped by a lock someone else still holds would satisfy the assertions below too.
        assertThat(sweepLockStamped()).as("the sweep tick was granted its ShedLock").isTrue();
        // ...and it stopped at the kill-switch: no run created, no transfer reversed.
        assertThat(payoutRuns.findAll()).isEmpty();
        assertThat(reversalCount.get()).isZero();
    }

    /**
     * Recovery used to ride along inside {@code payOneEvent}, so it only ran for an org that
     * still had a payout CANDIDATE. An org whose events have all paid out has none — and kept
     * money imin fronted for a refund forever.
     */
    @Test
    void sweep_recovers_a_platform_funded_refund_for_an_org_with_no_candidate_event() {
        Organization org = eligibleOrg();
        Event e = endedEvent(org, Instant.now().minus(10, ChronoUnit.DAYS));
        seedRun(org, e, PayoutRunStatus.PAID);          // already disbursed → not a candidate
        Refund fronted = platformFundedRefund(orderOn(e));

        assertThat(events.findPayoutCandidates(cutoff, PageRequest.of(0, 50)))
                .as("the org has nothing left to pay out")
                .isEmpty();

        sweeper.sweep();

        assertThat(reversalCount.get()).isEqualTo(1);
        assertThat(lastReversalAmount.get())
                .as("the organizer's share only: refund 2_000 minus the 200 fee share")
                .isEqualTo(1_800L);
        assertThat(refunds.findById(fronted.getId()).orElseThrow().getRecoveredAt()).isNotNull();
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

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

    private Event endedEvent(Organization owner, Instant endsAt) {
        User u = new User();
        u.setOrgId(owner.getId());
        u.setEmail("c-" + UUID.randomUUID() + "@test.example");
        u.setRole(UserRole.OWNER);
        UUID userId = users.save(u).getId();

        Event e = new Event();
        e.setOrgId(owner.getId());
        e.setName("E");
        e.setSlug("e-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStatus(EventStatus.PAST);
        e.setCurrency("EUR");
        e.setEndsAt(endsAt);
        e.setCreatedBy(userId);
        return events.save(e);
    }

    /**
     * {@code lockAtLeastFor = "PT1M"} would hold the sweep lock for the rest of this shared
     * context and silently skip every later tick. Expired, not deleted: ShedLock remembers the
     * row exists and only ever UPDATEs it.
     */
    private void releaseSweepLock() {
        Timestamp rewound = Timestamp.from(LOCK_REWOUND_TO);
        jdbc.update("update shedlock set lock_until = ?, locked_at = ?", rewound, rewound);
    }

    /** True once ShedLock has granted and stamped the sweep lock, i.e. the tick was not skipped. */
    private boolean sweepLockStamped() {
        Long stamped = jdbc.queryForObject(
                "select count(*) from shedlock where name = ? and locked_at > ?",
                Long.class, SWEEP_LOCK, Timestamp.from(LOCK_REWOUND_TO));
        return stamped != null && stamped == 1L;
    }

    private Order orderOn(Event e) {
        Order o = new Order();
        o.setToken("tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setEventId(e.getId());
        o.setOrgId(e.getOrgId());
        o.setEmail("buyer@test.example");
        o.setTotalMinor(4_000);
        o.setCurrency("eur");
        o.setApplicationFeeMinor(400);
        o.setPaymentMethod("card");
        return orders.save(o);
    }

    private Refund platformFundedRefund(Order o) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        r.setStripeChargeId("ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        r.setAmountMinor(2_000);
        r.setCurrency("eur");
        r.setApplicationFeeRefundMinor(200);
        r.setReason(RefundReason.OTHER);
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setPlatformFunded(true);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        return refunds.save(r);
    }

    /**
     * Real {@code ChargeService}/{@code TransferService} over a mocked response getter (both
     * accessors on {@link StripeClient} are final), answering the two calls recovery makes.
     */
    private void wireRecoveryStripeCalls() {
        StripeResponseGetter rg = mock(StripeResponseGetter.class);
        try {
            when(rg.request(any(ApiRequest.class), any(Type.class))).thenAnswer(inv -> {
                ApiRequest req = inv.getArgument(0);
                String path = req.getPath();
                if (path != null && path.startsWith("/v1/charges/")) {
                    return ApiResource.GSON.fromJson(
                            "{ \"object\": \"charge\", \"id\": \"ch_1\", \"transfer\": \"tr_test_1\" }",
                            Charge.class);
                }
                if (path != null && path.startsWith("/v1/transfers/")) {
                    Object amt = req.getParams() == null ? null : req.getParams().get("amount");
                    lastReversalAmount.set(amt == null ? 0L : ((Number) amt).longValue());
                    return ApiResource.GSON.fromJson(
                            "{ \"object\": \"transfer_reversal\", \"id\": \"trr_test_%d\", \"amount\": %d }"
                                    .formatted(reversalCount.incrementAndGet(), lastReversalAmount.get()),
                            TransferReversal.class);
                }
                throw new IllegalStateException("unexpected Stripe path in test: " + path);
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(stripeClient.charges()).thenReturn(new ChargeService(rg));
        when(stripeClient.transfers()).thenReturn(new TransferService(rg));
    }

    private void seedRun(Organization org, Event e, PayoutRunStatus status) {
        PayoutRun r = new PayoutRun();
        r.setOrgId(org.getId());
        r.setEventId(e.getId());
        r.setStripeAccountId(org.getStripeAccountId());
        r.setAmountMinor(1_000);
        r.setCurrency("eur");
        r.setStatus(status);
        r.setAttempt(1);
        r.setIdempotencyKey("evt:" + e.getId() + ":attempt:1");
        payoutRuns.save(r);
    }
}
