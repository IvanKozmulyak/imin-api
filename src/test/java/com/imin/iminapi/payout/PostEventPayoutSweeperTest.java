package com.imin.iminapi.payout;

import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.refund.RefundRepository;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.settlement.SettlementRepository;
import com.imin.iminapi.stripe.StripeConnectState;
import com.imin.iminapi.stripe.StripeProperties;
import com.stripe.StripeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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

    @MockitoBean StripeClient stripeClient;

    private final Instant cutoff = Instant.now().minus(3, ChronoUnit.DAYS);

    @BeforeEach
    void setUp() {
        wipe();
        props.setPayoutScheduleManual(true);
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

    @Test
    void not_a_candidate_when_connect_state_not_active() {
        Organization org = eligibleOrg();
        org.setStripeConnectState(StripeConnectState.RESTRICTED);
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

    @Test
    void sweeper_inert_when_flag_off() {
        props.setPayoutScheduleManual(false);
        Organization org = eligibleOrg();
        endedEvent(org, Instant.now().minus(10, ChronoUnit.DAYS));

        sweeper.sweep();   // must not touch Stripe at all

        // No runs created; the mocked StripeClient was never invoked (balance()/payouts() null).
        assertThat(payoutRuns.findAll()).isEmpty();
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
