package com.imin.iminapi.dispute;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The organizer's share of chargebacks against real queries, on H2 and Postgres 17. Orders are
 * €11.49 = €10.00 ticket + 149 booking fee, or two of them (2298 / 298).
 */
@SpringBootTest
@Import(TestRateLimitConfig.class)
abstract class DisputeWithholdingScenarios {

    @Autowired DisputeWithholding withholding;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired OrderRepository orders;
    @Autowired DisputeRepository disputes;
    @MockitoSpyBean RefundRepository refunds;

    private final List<UUID> orgIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();
    private final List<UUID> orderIds = new ArrayList<>();
    private final List<UUID> refundIds = new ArrayList<>();
    private final List<UUID> disputeIds = new ArrayList<>();

    private Instant now;
    private Organization org;
    private Event event;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        org = org();
        event = event(org);
    }

    @AfterEach
    void tearDown() {
        disputes.deleteAllById(disputeIds);
        refunds.deleteAllById(refundIds);
        orders.deleteAllById(orderIds);
        events.deleteAllById(eventIds);
        users.deleteAllById(userIds);
        orgs.deleteAllById(orgIds);
    }

    @Test
    void mixed_event_withholds_only_the_ticket_share_of_the_lost_order() {
        Order lost = order(event, 1_149, 149, false, now);
        order(event, 1_149, 149, false, now);
        order(event, 2_298, 298, false, now);
        dispute(lost, 1_149, DisputeStatus.LOST, false);

        assertThat(withholding.organizerShareMinor(event.getId())).isEqualTo(1_000L);
        assertThat(withholding.organizerShareLiveMinor(event.getId())).isEqualTo(1_000L);
        assertThat(withholding.withheldMinor(event.getId())).isEqualTo(1_149L);
    }

    @Test
    void full_charge_dispute_after_a_refund_withholds_only_what_was_not_refunded() {
        order(event, 1_149, 149, false, now);
        Order o3 = order(event, 2_298, 298, false, now);
        refund(o3, 1_149, 149, RefundStatus.SUCCEEDED);
        dispute(o3, 2_298, DisputeStatus.LOST, false);

        // gross min(2298, 2298 − 1149) = 1149; fee 149; share 1000
        assertThat(withholding.organizerShareMinor(event.getId())).isEqualTo(1_000L);
        assertThat(withholding.withheldMinor(event.getId())).isEqualTo(1_149L);
    }

    @Test
    void a_refund_that_has_not_succeeded_does_not_cap_the_dispute() {
        Order o = order(event, 1_149, 149, false, now);
        refund(o, 1_149, 149, RefundStatus.PENDING);
        dispute(o, 1_149, DisputeStatus.LOST, false);

        assertThat(withholding.organizerShareMinor(event.getId())).isEqualTo(1_000L);
        assertThat(withholding.withheldMinor(event.getId())).isEqualTo(1_149L);
    }

    @Test
    void the_payout_figure_leaves_out_test_mode_disputes() {
        Order live = order(event, 1_149, 149, false, now);
        Order test = order(event, 1_149, 149, true, now);
        dispute(test, 1_149, DisputeStatus.LOST, true);
        dispute(live, 1_149, DisputeStatus.LOST, false);

        assertThat(withholding.organizerShareLiveMinor(event.getId())).isEqualTo(1_000L);
        assertThat(withholding.organizerShareMinor(event.getId())).isEqualTo(2_000L);
    }

    @Test
    void won_and_reinstated_disputes_withhold_nothing() {
        Order o = order(event, 1_149, 149, false, now);
        dispute(o, 1_149, DisputeStatus.WON, false);
        dispute(o, 1_149, DisputeStatus.WITHDRAWN_REINSTATED, false);
        dispute(o, 500, DisputeStatus.OPEN, false);

        // only the OPEN 500: fee round(149 × 500 / 1149) = 65, share 435
        assertThat(withholding.organizerShareMinor(event.getId())).isEqualTo(435L);
        assertThat(withholding.organizerShareLiveMinor(event.getId())).isEqualTo(435L);
        assertThat(withholding.withheldMinor(event.getId())).isEqualTo(500L);
    }

    @Test
    void a_dispute_with_no_order_withholds_its_whole_amount() {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        d.setOrgId(org.getId());
        d.setEventId(event.getId());
        d.setAmountMinor(700);
        d.setCurrency("eur");
        d.setStatus(DisputeStatus.LOST);
        disputeIds.add(disputes.save(d).getId());

        assertThat(withholding.organizerShareMinor(event.getId())).isEqualTo(700L);
        assertThat(withholding.organizerShareLiveMinor(event.getId())).isEqualTo(700L);
        assertThat(withholding.withheldMinor(event.getId())).isEqualTo(700L);
    }

    @Test
    void two_disputes_on_one_order_are_split_together() {
        Order o = order(event, 2_298, 298, false, now);
        dispute(o, 1_149, DisputeStatus.OPEN, false);
        dispute(o, 1_149, DisputeStatus.LOST, false);

        assertThat(withholding.withheldMinor(event.getId())).isEqualTo(2_298L);
        assertThat(withholding.organizerShareMinor(event.getId())).isEqualTo(2_000L);
    }

    @Test
    void disputes_beyond_the_orders_remaining_gross_are_capped_per_order() {
        Order o = order(event, 1_149, 149, false, now);
        dispute(o, 1_149, DisputeStatus.OPEN, false);
        dispute(o, 1_149, DisputeStatus.LOST, false);

        // D 2298 caps at the order's 1149; share 1149 − 149 = 1000
        assertThat(withholding.withheldMinor(event.getId())).isEqualTo(1_149L);
        assertThat(withholding.organizerShareMinor(event.getId())).isEqualTo(1_000L);
    }

    @Test
    void the_window_counts_orders_from_since_up_to_but_not_including_until() {
        Instant since = now.minus(10, ChronoUnit.DAYS);
        Instant until = now.minus(1, ChronoUnit.DAYS);
        Order atSince = order(event, 1_149, 149, false, since);
        Order atUntil = order(event, 2_298, 298, false, until);
        dispute(atSince, 1_149, DisputeStatus.LOST, false);
        dispute(atUntil, 2_298, DisputeStatus.LOST, false);
        Organization other = org();
        Order elsewhere = order(event(other), 1_149, 149, false, now.minus(5, ChronoUnit.DAYS));
        dispute(elsewhere, 1_149, DisputeStatus.LOST, false);

        assertThat(withholding.organizerShareMinorByOrgWindow(org.getId(), since, until)).isEqualTo(1_000L);
    }

    @Test
    void a_page_reads_refunds_once_and_leaves_events_without_disputes_out() {
        Event quiet = event(org);
        Event other = event(org);
        order(quiet, 1_149, 149, false, now);
        Order a = order(event, 1_149, 149, false, now);
        Order b = order(other, 2_298, 298, false, now);
        refund(b, 1_149, 149, RefundStatus.SUCCEEDED);
        dispute(a, 1_149, DisputeStatus.LOST, false);
        dispute(b, 2_298, DisputeStatus.OPEN, false);
        clearInvocations(refunds);

        Map<UUID, Long> out = withholding.withheldMinorByEvent(
                List.of(event.getId(), quiet.getId(), other.getId()));

        assertThat(out).containsOnlyKeys(event.getId(), other.getId());
        assertThat(out.get(event.getId())).isEqualTo(1_149L);
        assertThat(out.get(other.getId())).isEqualTo(1_149L);
        verify(refunds, times(1)).sumSucceededAmountAndFeeByOrderIds(any());
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private Organization org() {
        Organization o = new Organization();
        o.setName("Org");
        o.setSlug("org-" + UUID.randomUUID().toString().substring(0, 8));
        o.setContactEmail("o@test.example");
        o.setCountry("FR");
        o = orgs.save(o);
        orgIds.add(o.getId());
        return o;
    }

    private Event event(Organization o) {
        User u = new User();
        u.setOrgId(o.getId());
        u.setEmail("u-" + UUID.randomUUID() + "@test.example");
        u.setRole(UserRole.OWNER);
        u = users.save(u);
        userIds.add(u.getId());

        Event e = new Event();
        e.setOrgId(o.getId());
        e.setName("E");
        e.setSlug("e-" + UUID.randomUUID().toString().substring(0, 8));
        e.setStatus(EventStatus.PAST);
        e.setCurrency("EUR");
        e.setEndsAt(now.minus(2, ChronoUnit.DAYS));
        e.setCreatedBy(u.getId());
        e = events.save(e);
        eventIds.add(e.getId());
        return e;
    }

    private Order order(Event e, long totalMinor, long feeMinor, boolean testMode, Instant createdAt) {
        Order o = new Order();
        o.setToken(UUID.randomUUID().toString().replace("-", ""));
        o.setEventId(e.getId());
        o.setOrgId(e.getOrgId());
        o.setEmail("buyer@test.example");
        o.setTotalMinor(totalMinor);
        o.setCurrency("eur");
        o.setApplicationFeeMinor(feeMinor);
        o.setPaymentMethod("card");
        o.setTestMode(testMode);
        o.setCreatedAt(createdAt);
        o = orders.save(o);
        orderIds.add(o.getId());
        return o;
    }

    private void refund(Order o, long amountMinor, long feeRefundMinor, RefundStatus status) {
        Refund r = new Refund();
        r.setOrderId(o.getId());
        r.setStripePaymentIntentId("pi_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setStripeRefundId("re_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        r.setAmountMinor(amountMinor);
        r.setCurrency("eur");
        r.setApplicationFeeRefundMinor(feeRefundMinor);
        r.setReason(RefundReason.OTHER);
        r.setStatus(status);
        r.setIdempotencyKey("idem-" + UUID.randomUUID());
        refundIds.add(refunds.save(r).getId());
    }

    /** Disputes are saved in call order, so an excluded row created first would be read first. */
    private void dispute(Order o, long amountMinor, DisputeStatus status, boolean testMode) {
        Dispute d = new Dispute();
        d.setStripeDisputeId("du_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        d.setOrgId(o.getOrgId());
        d.setEventId(o.getEventId());
        d.setOrderId(o.getId());
        d.setAmountMinor(amountMinor);
        d.setCurrency("eur");
        d.setStatus(status);
        d.setTestMode(testMode);
        disputeIds.add(disputes.save(d).getId());
    }
}
