package com.imin.iminapi.payout;

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
import com.imin.iminapi.stripe.StripeConnectState;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Stranded-funds monitor: which ended, unpaid events still hold a positive payout net; asserts on own ids. */
@IminIntegrationTest
class PayoutRetentionMonitorTest {

    @Autowired EventRepository events;
    @Autowired OrganizationRepository orgs;
    @Autowired OrderRepository orders;
    @Autowired RefundRepository refunds;
    @Autowired PayoutRunRepository payoutRuns;
    @Autowired DisputeRepository disputes;
    @Autowired PayoutRetentionMonitor monitor;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> createdOrgs = new ArrayList<>();

    @AfterEach
    void tearDown() {
        OrgRows.delete(jdbc, createdOrgs);
    }

    /** A sold event past the bound with no payout run must reach the monitor, stamped or not. */
    @ParameterizedTest(name = "stamped={0}")
    @ValueSource(booleans = {true, false})
    void a_sold_unpaid_event_past_the_bound_is_a_retention_candidate(boolean stamped) {
        Event e = endedEvent(eligibleOrg(), Instant.now().minus(100, ChronoUnit.DAYS));
        Order o = order(e, 10_000, 1_000, stamped, false);
        refund(o, 3_000, 300);

        assertThat(events.findRetentionMonitorCandidates(
                        Instant.now().minus(75, ChronoUnit.DAYS), PageRequest.of(0, 10_000)))
                .as("an event holding real net revenue must be found")
                .extracting(Event::getId)
                .contains(e.getId());
    }

    /** (10_000 − 3_000) refunded gross less (1_000 − 300) fee still kept = 6_300, at the identity rate either way. */
    @ParameterizedTest(name = "stamped={0}")
    @ValueSource(booleans = {true, false})
    void a_stranded_event_reports_its_payout_net(boolean stamped) {
        Event e = endedEvent(eligibleOrg(), Instant.now().minus(100, ChronoUnit.DAYS));
        Order o = order(e, 10_000, 1_000, stamped, false);
        refund(o, 3_000, 300);

        PayoutRetentionMonitor.StrandedEvent s = strandedById(200, e.getId());

        assertThat(s).as("the event holding 6_300 of real funds must be flagged").isNotNull();
        assertThat(s.netMinor()).isEqualTo(6_300L);
        assertThat(s.currency()).isEqualToIgnoringCase("eur");
        assertThat(s.unstampedOrders()).isEqualTo(stamped ? 0L : 1L);
        assertThat(s.approximate()).isEqualTo(!stamped);
    }

    enum NoNet { NO_ORDERS, ONLY_TEST_MODE, FULLY_REFUNDED }

    @ParameterizedTest
    @EnumSource(NoNet.class)
    void an_event_with_nothing_to_pay_is_not_flagged(NoNet kind) {
        Event e = endedEvent(eligibleOrg(), Instant.now().minus(100, ChronoUnit.DAYS));
        switch (kind) {
            case NO_ORDERS -> { }
            case ONLY_TEST_MODE -> order(e, 10_000, 1_000, true, true);
            case FULLY_REFUNDED -> refund(order(e, 10_000, 1_000, true, false), 10_000, 1_000);
        }

        assertThat(candidateIds()).as("no positive net, so not even a candidate").doesNotContain(e.getId());
        assertThat(strandedById(200, e.getId())).isNull();
    }

    @Test
    void an_event_emptied_by_a_lost_chargeback_is_not_flagged() {
        Organization org = eligibleOrg();
        Event e = endedEvent(org, Instant.now().minus(100, ChronoUnit.DAYS));
        lostDispute(order(e, 10_000, 1_000, true, false));

        assertThat(candidateIds()).as("the presentment net passes the SQL prefilter").contains(e.getId());
        assertThat(strandedById(200, e.getId())).as("the chargeback took the organizer's whole stake").isNull();
    }

    /** An older candidate with nothing owed must not end the scan before a later stranded event. */
    @Test
    void a_zero_net_page_does_not_hide_a_later_stranded_event() {
        Organization org = eligibleOrg();
        Event older = endedEvent(org, Instant.now().minus(5_000, ChronoUnit.DAYS));
        lostDispute(order(older, 10_000, 1_000, true, false));
        Event newer = endedEvent(org, Instant.now().minus(100, ChronoUnit.DAYS));
        refund(order(newer, 10_000, 1_000, true, false), 3_000, 300);

        assertThat(strandedById(1, older.getId())).isNull();
        assertThat(strandedById(1, newer.getId())).as("found on a later page").isNotNull();
    }

    @Test
    void an_event_already_paid_out_is_not_flagged() {
        Organization org = eligibleOrg();
        Event e = endedEvent(org, Instant.now().minus(100, ChronoUnit.DAYS));
        order(e, 10_000, 1_000, true, false);
        PayoutRun r = new PayoutRun();
        r.setOrgId(org.getId());
        r.setEventId(e.getId());
        r.setStripeAccountId(org.getStripeAccountId());
        r.setAmountMinor(9_000);
        r.setCurrency("eur");
        r.setStatus(PayoutRunStatus.PAID);
        r.setAttempt(1);
        r.setIdempotencyKey("evt:" + e.getId() + ":attempt:1");
        payoutRuns.save(r);

        assertThat(strandedById(200, e.getId())).as("the funds were disbursed").isNull();
    }

    /** Orders settled in two currencies have no single net to print, so none is summed. */
    @Test
    void orders_settled_in_two_currencies_are_flagged_without_a_summed_figure() {
        Event e = endedEvent(eligibleOrg(), Instant.now().minus(100, ChronoUnit.DAYS));
        order(e, 10_000, 1_000, true, false);
        Order gbp = new Order();
        gbp.setToken("tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        gbp.setEventId(e.getId());
        gbp.setOrgId(e.getOrgId());
        gbp.setEmail("buyer-" + UUID.randomUUID() + "@test.example");
        gbp.setTotalMinor(5_000);
        gbp.setCurrency("eur");
        gbp.setApplicationFeeMinor(500);
        gbp.setPaymentMethod("card");
        // Settlement columns are insert-only, so the second currency is set before the save.
        gbp.setSettlementCurrency("gbp");
        gbp.setSettlementGrossMinor(4_300L);
        gbp.setSettlementFeeMinor(430L);
        orders.save(gbp);

        PayoutRetentionMonitor.StrandedEvent s = strandedById(200, e.getId());

        assertThat(s).isNotNull();
        assertThat(s.mixedCurrencies()).isTrue();
        assertThat(s.currency()).isNull();
    }

    /** A cancelled event is held by the payout job, so it is reported as held funds, not a payout to force. */
    @ParameterizedTest
    @EnumSource(value = EventStatus.class, names = {"PAST", "CANCELLED"})
    void a_stranded_cancelled_event_is_reported_as_held_funds(EventStatus status) {
        Event e = endedEvent(eligibleOrg(), Instant.now().minus(100, ChronoUnit.DAYS));
        e.setStatus(status);
        events.save(e);
        order(e, 10_000, 1_000, true, false);

        PayoutRetentionMonitor.StrandedEvent s = strandedById(200, e.getId());

        assertThat(s).as("the event still holds 9_000 of funds").isNotNull();
        assertThat(s.netMinor()).isEqualTo(9_000L);
        assertThat(s.cancelled()).isEqualTo(status == EventStatus.CANCELLED);
    }

    private PayoutRetentionMonitor.StrandedEvent strandedById(int pageSize, UUID eventId) {
        return monitor.findStranded(Instant.now(), pageSize).stream()
                .filter(s -> s.eventId().equals(eventId)).findFirst().orElse(null);
    }

    private List<UUID> candidateIds() {
        return events.findRetentionMonitorCandidates(Instant.now().minus(75, ChronoUnit.DAYS),
                PageRequest.of(0, 10_000)).stream().map(Event::getId).toList();
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
        Organization saved = orgs.save(o);
        createdOrgs.add(saved.getId());
        return saved;
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

    private Order order(Event e, long total, long fee, boolean stamped, boolean testMode) {
        Order o = new Order();
        o.setToken("tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        o.setEventId(e.getId());
        o.setOrgId(e.getOrgId());
        o.setEmail("buyer-" + UUID.randomUUID() + "@test.example");
        o.setTotalMinor(total);
        o.setCurrency("eur");
        o.setApplicationFeeMinor(fee);
        o.setPaymentMethod("card");
        o.setTestMode(testMode);
        if (stamped) {
            // EUR settles 1:1.
            o.setSettlementCurrency("eur");
            o.setSettlementGrossMinor(total);
            o.setSettlementFeeMinor(fee);
        }
        return orders.save(o);
    }

    private Refund refund(Order o, long amount, long feeRefund) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        r.setAmountMinor(amount);
        r.setCurrency("eur");
        r.setApplicationFeeRefundMinor(feeRefund);
        r.setReason(RefundReason.OTHER);
        r.setStatus(RefundStatus.SUCCEEDED);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        return refunds.save(r);
    }

    private void lostDispute(Order o) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        d.setOrgId(o.getOrgId());
        d.setEventId(o.getEventId());
        d.setOrderId(o.getId());
        d.setAmountMinor(o.getTotalMinor());
        d.setCurrency("eur");
        d.setStatus(DisputeStatus.LOST);
        disputes.save(d);
    }
}
